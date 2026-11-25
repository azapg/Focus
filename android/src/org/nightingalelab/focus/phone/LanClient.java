package org.nightingalelab.focus.phone;

import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/**
 * Pinned-TLS HTTP/1.1 client speaking the exact Focus device protocol (/v1/hello, /v2/pair/*,
 * /v1/snapshot, /v1/command). Rejects any non-private address and pins the observed SHA-256 cert.
 */
public final class LanClient {
  public static final class CommandException extends IOException {
    final int status;

    CommandException(int status, String message) {
      super(message);
      this.status = status;
    }

    boolean judgeUnsupported() {
      return status == 409 && ("Unsupported laptop control".equals(getMessage())
          || "Enable the pause judge on the laptop first".equals(getMessage()));
    }
  }

  static final class UnreachableException extends IOException {
    UnreachableException(Throwable cause) { super("Laptop unreachable", cause); }
  }

  static final class BeforeSendException extends IOException {
    BeforeSendException(IOException cause) { super(cause); }
  }

  private static final class AddressFailure extends IOException {
    final String address;
    AddressFailure(String address, Exception cause) {
      super(cause);
      this.address = address;
    }
  }

  interface AddressProbe<T> {
    T check(String address, int timeoutMs) throws Exception;
  }

  // Race only read-only identity probes. A command is sent once, on the winning address.
  static <T> T findAddress(Collection<String> known, Supplier<List<String>> discovered,
      AddressProbe<T> probe, long deadline) throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(6);
    CompletionService<T> completed = new ExecutorCompletionService<>(workers);
    Set<String> scheduled = new HashSet<>();
    Exception last = null;
    try {
      while (SystemClock.elapsedRealtime() < deadline) {
        List<String> candidates = new ArrayList<>(known);
        candidates.addAll(discovered.get());
        for (String address : candidates) {
          if (address == null || address.isEmpty()) continue;
          if (scheduled.size() >= known.size() + 32 && !known.contains(address)) continue;
          if (!scheduled.add(address)) continue;
          completed.submit(() -> {
            int timeout = (int) Math.min(1450, deadline - SystemClock.elapsedRealtime());
            if (timeout <= 0) throw new SocketTimeoutException();
            try {
              return probe.check(address, timeout);
            } catch (Exception failure) {
              throw new AddressFailure(address, failure);
            }
          });
        }
        Future<T> result = completed.poll(Math.min(60,
            Math.max(0, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
        if (result == null) continue;
        try {
          return result.get();
        } catch (ExecutionException failure) {
          if (failure.getCause() instanceof AddressFailure) {
            AddressFailure error = (AddressFailure) failure.getCause();
            if (known.contains(error.address) && (last == null || !(last instanceof SSLHandshakeException))) {
              last = (Exception) error.getCause();
            }
          }
        }
      }
      throw new UnreachableException(last);
    } finally {
      workers.shutdownNow();
    }
  }

  public static final class Response {
    public final JSONObject json;
    public final String fingerprint;

    public Response(JSONObject json, String fingerprint) {
      this.json = json;
      this.fingerprint = fingerprint;
    }
  }

  public static InetSocketAddress parseAddress(String address) {
    if (address == null || address.trim().isEmpty())
      throw new IllegalArgumentException("Enter an address like 192.168.1.50:47832");
    String s = address.trim();
    String host;
    int port;
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      if (close < 0 || close + 2 >= s.length() || s.charAt(close + 1) != ':')
        throw new IllegalArgumentException("Invalid IPv6 address");
      host = s.substring(1, close);
      port = Integer.parseInt(s.substring(close + 2));
    } else {
      int colon = s.lastIndexOf(':');
      if (colon <= 0) {
        host = s;
        port = 47832;
      } else {
        host = s.substring(0, colon);
        port = Integer.parseInt(s.substring(colon + 1));
      }
    }
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
    try {
      InetAddress addr = InetAddress.getByName(host);
      boolean allowed = addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress();
      if (!allowed && addr instanceof Inet6Address) {
        byte b0 = addr.getAddress()[0];
        allowed = (b0 & 0xfe) == 0xfc; // fc00::/7 unique local
      }
      if (!allowed) throw new IllegalArgumentException("Only local network addresses are allowed");
      return new InetSocketAddress(addr, port);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Unknown local address");
    }
  }

  public static Response request(
      String address, String expectedFingerprint, String bearerToken, String path, JSONObject body)
      throws Exception {
    return request(address, expectedFingerprint, bearerToken, path, body, 4000, 6000);
  }

  static Response request(String address, String expectedFingerprint, String bearerToken,
      String path, JSONObject body, int connectMs, int readMs) throws Exception {
    return requestUntil(address, expectedFingerprint, bearerToken, path, body, connectMs, readMs, Long.MAX_VALUE);
  }

  static Response requestUntil(String address, String expectedFingerprint, String bearerToken,
      String path, JSONObject body, int connectMs, int readMs, long deadline) throws Exception {
    if (bearerToken != null && !bearerToken.isEmpty()
        && (expectedFingerprint == null || expectedFingerprint.isEmpty())) {
      throw new SSLHandshakeException("Missing pinned device identity");
    }
    InetSocketAddress target = parseAddress(address);
    final String[] observed = new String[1];
    X509TrustManager pinManager = new X509TrustManager() {
      @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
      @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
        try {
          String fp = PairingCode.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
          observed[0] = fp;
          if (expectedFingerprint != null && !expectedFingerprint.isEmpty() && !expectedFingerprint.equalsIgnoreCase(fp)) {
            throw new CertificateException("Device certificate fingerprint mismatch");
          }
        } catch (NoSuchAlgorithmException e) {
          throw new CertificateException(e);
        }
      }
    };
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, new TrustManager[] {pinManager}, new SecureRandom());

    boolean ask = body != null && "judge_pause".equals(body.optString("action"));
    boolean mayHaveSent = false;
    try (Socket raw = new Socket()) {
      Metric.DEVICE_REQUEST.count(1);
      raw.connect(target, timeoutBefore(deadline, connectMs));
      raw.setSoTimeout(timeoutBefore(deadline, ask ? connectMs : readMs));
      try (SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket(raw, target.getHostString(), target.getPort(), true)) {
        socket.startHandshake();
        socket.setSoTimeout(timeoutBefore(deadline, readMs));
        if (ask && deadline != Long.MAX_VALUE) {
          body.put("answer_budget_ms", timeoutBefore(deadline, readMs));
        }
        byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        String method = body == null ? "GET" : "POST";
        StringBuilder req = new StringBuilder();
        req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(target.getHostString()).append(":").append(target.getPort()).append("\r\n");
        req.append("Connection: close\r\n");
        if (bearerToken != null && !bearerToken.isEmpty()) {
          req.append("Authorization: Bearer ").append(bearerToken).append("\r\n");
        }
        if (body != null) {
          req.append("Content-Type: application/json\r\n");
          req.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = socket.getOutputStream();
        mayHaveSent = true;
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        if (payload.length > 0) out.write(payload);
        out.flush();
        Metric.DEVICE_TX.count(payload.length);

        InputStream in = new BufferedInputStream(new FilterInputStream(socket.getInputStream()) {
          @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            socket.setSoTimeout(timeoutBefore(deadline, readMs));
            return super.read(buffer, offset, length);
          }
        });
        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        int tail = 0;
        while (tail != 0x0d0a0d0a) {
          if (headBytes.size() > 16384) throw new IOException("Response headers too large");
          int b = in.read();
          if (b < 0) throw new EOFException("Connection closed while reading headers");
          headBytes.write(b);
          tail = (tail << 8) | b;
        }
        String[] headLines = new String(headBytes.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        String[] statusParts = headLines[0].split(" ", 3);
        int status = statusParts.length >= 2 ? Integer.parseInt(statusParts[1]) : 500;
        int contentLength = -1;
        for (int i = 1; i < headLines.length; i++) {
          int colon = headLines[i].indexOf(':');
          if (colon > 0 && headLines[i].substring(0, colon).trim().equalsIgnoreCase("content-length")) {
            contentLength = Integer.parseInt(headLines[i].substring(colon + 1).trim());
          }
        }
        if (contentLength > 8 * 1024 * 1024) throw new IOException("Response too large");
        ByteArrayOutputStream respBytes = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int remaining = contentLength;
        while (remaining != 0) {
          int want = remaining < 0 ? buf.length : Math.min(buf.length, remaining);
          int n = in.read(buf, 0, want);
          if (n < 0) break;
          respBytes.write(buf, 0, n);
          if (respBytes.size() > 8 * 1024 * 1024) throw new IOException("Response too large");
          if (remaining > 0) remaining -= n;
        }
        if (remaining > 0) throw new EOFException("Incomplete device response");
        Metric.DEVICE_RX.count(respBytes.size());
        String text = new String(respBytes.toByteArray(), StandardCharsets.UTF_8);
        JSONObject json = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status < 200 || status >= 300) {
          throw new CommandException(status, json.optString("error", "Device returned HTTP " + status));
        }
        return new Response(json, observed[0]);
      }
    } catch (IOException error) {
      if (ask && !mayHaveSent && !(error instanceof SSLHandshakeException && error.getCause() instanceof CertificateException)) {
        throw new BeforeSendException(error);
      }
      throw error;
    }
  }

  private static int timeoutBefore(long deadline, int limit) throws SocketTimeoutException {
    long remaining = deadline - SystemClock.elapsedRealtime();
    if (remaining <= 0) throw new SocketTimeoutException();
    return (int) Math.min(remaining, limit);
  }
}

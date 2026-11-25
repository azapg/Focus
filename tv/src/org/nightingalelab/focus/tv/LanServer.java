package org.nightingalelab.focus.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/** One bounded HTTP/1.1 request per TLS connection. No browser access, files or redirects. */
public final class LanServer implements Closeable {
  public interface Handler {
    String handle(String method, String path, Map<String, String> headers, String body)
        throws Exception;
  }

  public static final class ApiError extends Exception {
    public final int status;
    public ApiError(int status, String message) { super(message); this.status = status; }
  }

  private final SSLServerSocket listener;
  private final Handler handler;
  private final java.util.function.Consumer<Exception> failure;
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
      3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(6));
  private volatile boolean closed;
  private final Set<SSLSocket> sockets = ConcurrentHashMap.newKeySet();
  public boolean isClosed() { return closed; }

  public LanServer(SSLContext tls, int port, Handler handler) throws IOException {
    this(tls, port, handler, e -> {});
  }

  public LanServer(SSLContext tls, int port, Handler handler, java.util.function.Consumer<Exception> failure) throws IOException {
    this.handler = handler;
    this.failure = failure;
    listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress(port), 8);
    List<String> protocols = new ArrayList<>();
    for (String p : listener.getSupportedProtocols())
      if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) protocols.add(p);
    listener.setEnabledProtocols(protocols.toArray(new String[0]));
  }

  public int port() { return listener.getLocalPort(); }

  public void run() {
    while (!closed) {
      try {
        SSLSocket socket = (SSLSocket) listener.accept();
        socket.setSoTimeout(5000);
        sockets.add(socket);
        try { workers.execute(() -> serve(socket)); }
        catch (RejectedExecutionException e) { sockets.remove(socket); socket.close(); }
      } catch (IOException e) {
        if (!closed) close();
      }
    }
  }

  private static String readHeaders(InputStream in) throws IOException, ApiError {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int tail = 0;
    while (tail != 0x0d0a0d0a) {
      if (bytes.size() >= 8192) throw new ApiError(431, "Headers too large");
      int b = in.read();
      if (b < 0) throw new EOFException();
      if (b > 127 || b == 0) throw new ApiError(400, "Invalid header");
      bytes.write(b);
      tail = (tail << 8) | b;
    }
    return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
  }

  private void serve(SSLSocket socket) {
    try (SSLSocket connection = socket) {
      connection.startHandshake();
      int status = 200;
      String response;
      try {
        InputStream in = new BufferedInputStream(connection.getInputStream());
        String[] lines = readHeaders(in).split("\r\n");
        Metric.DEVICE_INBOUND.count(1);
        String[] start = lines[0].split(" ");
        if (start.length != 3 || !start[2].equals("HTTP/1.1"))
          throw new ApiError(400, "Invalid request");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
          int colon = lines[i].indexOf(':');
          if (colon <= 0) throw new ApiError(400, "Invalid header");
          String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
          if (!key.matches("[a-z0-9-]+") || headers.containsKey(key))
            throw new ApiError(400, "Duplicate or invalid header");
          headers.put(key, lines[i].substring(colon + 1).trim());
        }
        if (headers.containsKey("origin") || headers.containsKey("transfer-encoding"))
          throw new ApiError(403, "Native Focus clients only");
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException e) { throw new ApiError(400, "Invalid length"); }
        int maxLen = start[1].equals("/v1/habits") ? 512 * 1024 : 16384;
        if (length < 0 || length > maxLen) throw new ApiError(413, "Request too large");
        if (!start[0].equals("POST") && (!start[0].equals("GET") || length != 0))
          throw new ApiError(405, "Unsupported method");
        if (start[0].equals("POST") && !headers.getOrDefault("content-type", "").split(";")[0].equalsIgnoreCase("application/json"))
          throw new ApiError(415, "Use JSON");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
          int n = in.read(body, offset, length - offset);
          if (n < 0) throw new EOFException();
          offset += n;
        }
        Metric.DEVICE_INBOUND_RX.count(body.length);
        response = handler.handle(start[0], start[1], headers, new String(body, StandardCharsets.UTF_8));
        if (response.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
          throw new ApiError(507, "TV history is too large to synchronize");
      } catch (ApiError e) {
        status = e.status;
        response = "{\"error\":\"" + e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"}";
      } catch (Exception e) {
        status = 500;
        response = "{\"error\":\"TV request failed. Refresh before retrying.\"}";
      }
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      OutputStream out = connection.getOutputStream();
      out.write(("HTTP/1.1 " + status + " Focus\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.write(bytes);
      out.flush();
      Metric.DEVICE_INBOUND_TX.count(bytes.length);
    } catch (IOException ignored) {
      failure.accept(ignored);
      // A vanished client must never stop tracking, enforcement or the accept loop.
    } finally { sockets.remove(socket); }
  }

  @Override public void close() {
    closed = true;
    try { listener.close(); } catch (IOException ignored) {}
    workers.shutdownNow();
    for (SSLSocket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
    sockets.clear();
  }
}

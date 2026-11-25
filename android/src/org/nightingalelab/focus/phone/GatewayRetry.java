package org.nightingalelab.focus.phone;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

final class GatewayRetry {
  enum Kind {
    TIMEOUT, TRANSPORT, HTTP, ENVELOPE, FINISH_REASON, REFUSAL, NOT_TEXT, PARSE,
    MINUTES_MISMATCH, COUNTER_OFFER_MISMATCH, CANCELLED;

    boolean invalid() {
      switch (this) {
        case ENVELOPE:
        case FINISH_REASON:
        case NOT_TEXT:
        case PARSE:
        case MINUTES_MISMATCH:
        case COUNTER_OFFER_MISMATCH:
          return true;
        default:
          return false;
      }
    }

    boolean retryable(int status) {
      if (this == HTTP) return status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599;
      return this == TIMEOUT || this == TRANSPORT || invalid();
    }
  }

  static final class Rejected extends IOException {
    final Kind kind;
    final long retryAfterMs;
    final int status;

    Rejected(Kind kind) { this(kind, 0, 0); }
    Rejected(Kind kind, int status, long retryAfterMs) {
      this.kind = kind;
      this.status = status;
      this.retryAfterMs = retryAfterMs;
    }
  }

  interface Call<T> {
    T complete(Attempt attempt, long deadline) throws Exception;
  }

  interface Report {
    void record(Attempt attempt, Kind failure, long durationMs);
  }

  static final class Attempt {
    final int number;
    final long started;
    final long deadline;
    volatile int status;
    private HttpURLConnection connection;
    private boolean cancelled;
    private boolean reported;

    Attempt(int number, long started, long deadline) {
      this.number = number;
      this.started = started;
      this.deadline = deadline;
    }

    synchronized void attach(HttpURLConnection connection) throws InterruptedException {
      if (cancelled) throw new InterruptedException();
      this.connection = connection;
    }

    synchronized void detach() { connection = null; }

    synchronized void cancel() {
      cancelled = true;
      HttpURLConnection active = connection;
      // Disconnect can wait for a read on some implementations. Keep it off the Ask worker.
      if (active != null) daemon(active::disconnect).start();
    }
  }

  private static final class Result<T> {
    final T answer;
    final Rejected error;
    final long completedAt;

    Result(T answer, Rejected error, long completedAt) {
      this.answer = answer;
      this.error = error;
      this.completedAt = completedAt;
    }
  }

  private static Thread daemon(Runnable task) {
    Thread thread = new Thread(task, "focus-gateway-completion");
    thread.setDaemon(true);
    return thread;
  }

  static <T> T run(long deadline, LongSupplier clock, Call<T> call, Report report) throws Rejected, InterruptedException {
    return run(deadline, clock, call, report, AskPolicy.ATTEMPT_MS, AskPolicy.HEDGE_MS);
  }

  static <T> T run(long deadline, LongSupplier clock, Call<T> call, Report report,
      int attemptMs, int hedgeMs) throws Rejected, InterruptedException {
    ExecutorService workers = Executors.newCachedThreadPool(GatewayRetry::daemon);
    CompletionService<Result<T>> completed = new ExecutorCompletionService<>(workers);
    Map<Future<Result<T>>, Attempt> pending = new LinkedHashMap<>();
    long hedgeAt = clock.getAsLong() + hedgeMs;
    long retryAt = 0;
    int started = 0;
    int invalid = 0;
    Rejected last = new Rejected(Kind.TIMEOUT);
    Future<Result<T>> ready = null;
    try {
      while (clock.getAsLong() < deadline) {
        long now = clock.getAsLong();
        if (ready == null) ready = completed.poll();
        if (ready != null) {
          Future<Result<T>> finished = ready;
          ready = null;
          Attempt attempt = pending.remove(finished);
          if (attempt == null) continue;
          Result<T> result;
          try {
            result = finished.get();
          } catch (ExecutionException unexpected) {
            result = new Result<>(null, new Rejected(Kind.PARSE), clock.getAsLong());
          }
          Rejected error = result.completedAt >= attempt.deadline
              ? new Rejected(Kind.TIMEOUT) : result.error;
          if (error == null) {
            report(attempt, null, result.completedAt, report);
            return result.answer;
          }
          report(attempt, error.kind, Math.min(attempt.deadline, result.completedAt), report);
          last = error;
          if (!error.kind.retryable(attempt.status) || error.kind.invalid() && ++invalid >= 2) throw error;
          retryAt = Math.max(retryAt, now + Math.max(error.retryAfterMs, backoff()));
          continue;
        }

        Iterator<Map.Entry<Future<Result<T>>, Attempt>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
          Map.Entry<Future<Result<T>>, Attempt> entry = entries.next();
          Attempt attempt = entry.getValue();
          if (now < attempt.deadline || entry.getKey().isDone()) continue;
          cancel(entry.getKey(), attempt, Kind.TIMEOUT, now, report);
          entries.remove();
          last = new Rejected(Kind.TIMEOUT);
          retryAt = Math.max(retryAt, now + backoff());
        }

        long launchAt = pending.isEmpty() ? retryAt : Math.max(hedgeAt, retryAt);
        if (started >= AskPolicy.MAX_ATTEMPTS || started > 1 && !pending.isEmpty()
            || started != 0 && Math.max(now, launchAt) + AskPolicy.MIN_RETRY_WINDOW_MS > deadline) {
          launchAt = Long.MAX_VALUE;
        }
        if (launchAt <= now) {
          Attempt attempt = new Attempt(++started, now, Math.min(deadline, now + attemptMs));
          pending.put(completed.submit(() -> {
            try {
              return new Result<>(call.complete(attempt, attempt.deadline), null, clock.getAsLong());
            } catch (Rejected error) {
              return new Result<>(null, error, clock.getAsLong());
            } catch (SocketTimeoutException error) {
              return new Result<>(null, new Rejected(Kind.TIMEOUT), clock.getAsLong());
            } catch (IOException error) {
              return new Result<>(null, new Rejected(attempt.status >= 200 && attempt.status < 300
                  ? Kind.ENVELOPE : Kind.TRANSPORT), clock.getAsLong());
            } catch (Exception error) {
              return new Result<>(null, new Rejected(Kind.PARSE), clock.getAsLong());
            }
          }), attempt);
          if (started > 1) hedgeAt = Long.MAX_VALUE;
          continue;
        }
        if (pending.isEmpty() && launchAt == Long.MAX_VALUE) break;
        long wake = Math.min(deadline, launchAt);
        for (Attempt attempt : pending.values()) wake = Math.min(wake, attempt.deadline);
        ready = completed.poll(Math.max(0, wake - now), TimeUnit.MILLISECONDS);
      }
      throw clock.getAsLong() >= deadline && !pending.isEmpty() ? new Rejected(Kind.TIMEOUT) : last;
    } finally {
      long ended = clock.getAsLong();
      for (Map.Entry<Future<Result<T>>, Attempt> entry : pending.entrySet()) {
        cancel(entry.getKey(), entry.getValue(), ended >= deadline ? Kind.TIMEOUT : Kind.CANCELLED, ended, report);
      }
      workers.shutdownNow();
    }
  }

  private static int backoff() {
    return AskPolicy.BACKOFF_MS + ThreadLocalRandom.current().nextInt(AskPolicy.JITTER_MS + 1);
  }

  private static void cancel(Future<?> future, Attempt attempt, Kind kind, long now, Report report) {
    future.cancel(true);
    attempt.cancel();
    report(attempt, kind, now, report);
  }

  private static void report(Attempt attempt, Kind failure, long now, Report report) {
    if (attempt.reported) return;
    attempt.reported = true;
    report.record(attempt, failure, now - attempt.started);
  }

  private static HttpURLConnection open(URL url, int readMs, String method) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
    connection.setConnectTimeout(Math.min(AskPolicy.CONNECT_MS, readMs));
    connection.setReadTimeout(readMs);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    return connection;
  }

  static void warm(URL url) throws IOException {
    HttpURLConnection connection = open(url, 1000, "HEAD");
    try {
      int status = connection.getResponseCode();
      InputStream body = status < 400 ? connection.getInputStream() : connection.getErrorStream();
      if (body != null) body.close();
    } catch (IOException error) {
      connection.disconnect();
      throw error;
    }
  }

  static String post(URL url, String key, byte[] bytes, Attempt attempt, int timeoutMs, int maxBytes)
      throws IOException, InterruptedException {
    HttpURLConnection connection = open(url, timeoutMs, "POST");
    attempt.attach(connection);
    boolean consumed = false;
    try {
      connection.setRequestProperty("Authorization", "Bearer " + key);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
      int status = connection.getResponseCode();
      attempt.status = status;
      if (status < 200 || status >= 300) throw new Rejected(Kind.HTTP, status, retryAfter(connection.getHeaderField("Retry-After")));
      try (InputStream in = connection.getInputStream()) {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1;) {
          if (response.size() + n > maxBytes) throw new Rejected(Kind.ENVELOPE);
          response.write(buffer, 0, n);
        }
        if (connection.getContentLength() > response.size() && connection.getContentEncoding() == null) {
          throw new Rejected(Kind.ENVELOPE);
        }
        consumed = true;
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.toByteArray())).toString();
      }
    } finally {
      if (!consumed) connection.disconnect();
      attempt.detach();
    }
  }

  static long retryAfter(String value) {
    if (value == null) return 0;
    long cap = AskPolicy.PHONE_OVERALL_MS + 1L;
    String header = value.trim();
    if (header.matches("[0-9]+")) {
      try {
        return Math.min(cap, Math.min(cap, Long.parseLong(header)) * 1000);
      } catch (NumberFormatException overflow) {
        return cap;
      }
    }
    try {
      return Math.min(cap, Math.max(0, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
          .toInstant().toEpochMilli() - System.currentTimeMillis()));
    } catch (DateTimeParseException invalid) {
      return 0;
    }
  }
}

package org.nightingalelab.focus.phone;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.nightingalelab.focus.phone.GatewayRetry.Kind.*;

public final class GatewayRetryTest {
  private static final class Reply {
    final int status;
    final String body;
    final long delayMs;
    final String retryAfter;

    Reply(int status, String body, long delayMs, String retryAfter) {
      this.status = status;
      this.body = body;
      this.delayMs = delayMs;
      this.retryAfter = retryAfter;
    }
  }

  private static final class Gateway implements AutoCloseable {
    final HttpServer server;
    final URL url;
    final AtomicInteger count = new AtomicInteger();
    final Map<Integer, GatewayRetry.Kind> failures = new TreeMap<>();
    long elapsed;

    Gateway(Reply... replies) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
      }));
      server.createContext("/completion", exchange -> {
        int index = count.getAndIncrement();
        try {
          exchange.getRequestBody().readAllBytes();
          if (index >= replies.length) {
            exchange.sendResponseHeaders(400, -1);
            return;
          }
          Reply reply = replies[index];
          byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
          if (reply.retryAfter != null) exchange.getResponseHeaders().set("Retry-After", reply.retryAfter);
          exchange.sendResponseHeaders(reply.status, body.length);
          Thread.sleep(reply.delayMs);
          exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
      });
      server.start();
      url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/completion");
    }

    String ask(int budget, int timeout, int hedge) throws Exception {
      long started = now();
      try {
        return GatewayRetry.run(started + budget, GatewayRetryTest::now, (attempt, deadline) -> {
          String body = GatewayRetry.post(url, "test-key", "{}".getBytes(StandardCharsets.UTF_8), attempt,
              (int) Math.max(1, deadline - now()), 65536);
          if ("invalid".equals(body)) throw new GatewayRetry.Rejected(ENVELOPE);
          if ("refusal".equals(body)) throw new GatewayRetry.Rejected(REFUSAL);
          return body;
        }, (attempt, failure, duration) -> failures.put(attempt.number, failure), timeout, hedge);
      } finally {
        elapsed = now() - started;
      }
    }

    @Override public void close() { server.stop(0); }
  }

  private static long now() { return System.nanoTime() / 1_000_000; }
  private static Reply ok(long wait) { return new Reply(200, "valid", wait, null); }
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void failed(Gateway gateway, GatewayRetry.Kind kind, int budget, int timeout, int hedge)
      throws Exception {
    try {
      gateway.ask(budget, timeout, hedge);
      throw new AssertionError("Expected " + kind);
    } catch (GatewayRetry.Rejected rejected) {
      check(rejected.kind == kind, "Expected " + kind + " but saw " + rejected.kind);
    }
  }

  public static void main(String[] args) throws Exception {
    for (Reply failure : Arrays.asList(
        new Reply(503, "error", 0, null), new Reply(429, "error", 0, "0"),
        new Reply(200, "invalid", 0, null))) {
      try (Gateway gateway = new Gateway(failure, ok(0))) {
        try {
          check("valid".equals(gateway.ask(2300, 1600, 1000)), "Retry did not recover");
        } catch (GatewayRetry.Rejected error) {
          throw new AssertionError("Initial HTTP " + failure.status + " ended with " + error.kind
              + " after " + gateway.count + " requests and " + gateway.failures, error);
        }
        check(gateway.count.get() == 2 && gateway.failures.size() == 2, "Expected exactly two attempts");
      }
    }
    try (Gateway gateway = new Gateway(ok(900), ok(0))) {
      check("valid".equals(gateway.ask(1800, 250, 1000)), "Timeout retry did not recover");
      check(gateway.failures.get(1) == TIMEOUT && gateway.count.get() == 2, "Expected a timeout");
    }
    try (Gateway gateway = new Gateway(ok(900), ok(0))) {
      check("valid".equals(gateway.ask(1800, 1500, 160)), "Hedge did not recover");
      check(gateway.failures.get(1) == CANCELLED && gateway.elapsed < 850, "Slow request must lose");
    }
    try (Gateway gateway = new Gateway(new Reply(429, "error", 0, "1"), ok(0))) {
      check("valid".equals(gateway.ask(2300, 1200, 1000)), "Retry-After should fit");
      check(gateway.elapsed >= 1000, "Retry-After was ignored");
    }
    try (Gateway gateway = new Gateway(new Reply(429, "error", 0, "90"))) {
      failed(gateway, HTTP, 1200, 1000, 800);
      check(gateway.count.get() == 1, "Retry-After must fit the deadline");
    }
    for (int status : new int[] {400, 401, 403, 404}) {
      try (Gateway gateway = new Gateway(new Reply(status, "error", 0, null))) {
        failed(gateway, HTTP, 1500, 900, 800);
        check(gateway.count.get() == 1, "Must not retry " + status);
      }
    }
    try (Gateway gateway = new Gateway(new Reply(200, "refusal", 0, null))) {
      failed(gateway, REFUSAL, 1500, 900, 800);
      check(gateway.count.get() == 1, "Must not retry an explicit refusal");
    }
    try (Gateway gateway = new Gateway(ok(1000), ok(1000))) {
      failed(gateway, TIMEOUT, 500, 1500, 140);
      check(gateway.count.get() == 2 && gateway.elapsed < 900, "Overall deadline must bound both requests");
    }
    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
    try {
      GatewayRetry.run(540, clock::get, (attempt, deadline) -> {
        clock.set(deadline + 1);
        return "late";
      }, (attempt, failure, duration) -> check(failure == TIMEOUT, "A late sample must time out"), 300, 450);
      throw new AssertionError("Accepted a sample completed after its deadline");
    } catch (GatewayRetry.Rejected rejected) {
      check(rejected.kind == TIMEOUT, "Expected late sample to time out");
    }
    check(AskPolicy.ATTEMPT_MS < AskPolicy.LAPTOP_OVERALL_MS
        && AskPolicy.LAPTOP_OVERALL_MS < AskPolicy.PHONE_READ_MS
        && AskPolicy.PHONE_READ_MS < AskPolicy.PHONE_OVERALL_MS, "Inconsistent budgets");
    System.out.println("Gateway retry tests passed");
  }
}

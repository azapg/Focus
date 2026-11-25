package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.json.*;

import static org.nightingalelab.focus.phone.FocusEventCodes.*;

public final class PhoneAskChecks {
  private PhoneAskChecks() {}

  public static void verify(Context context, JSONObject report) throws Exception {
    verifyEventSyncResponseLimit();
    DeviceHub hub = DeviceHub.get(context);
    List<DeviceHub.PairedDevice> previous = hub.list();
    String laptop = "test-ask-laptop";
    JSONObject pushed = new JSONObject()
        .put("device_id", laptop).put("kind", "laptop").put("now", System.currentTimeMillis())
        .put("snapshot_revision", 1).put("dns", new JSONObject().put("enabled", true));
    JSONObject reciprocal = new JSONObject().put("address", "127.0.0.1:1")
        .put("fingerprint", "0".repeat(64)).put("token", "0".repeat(64));
    try {
      hub.seedPreviewDataForTest(Collections.emptyList());
      hub.ingestSyncedPeer(pushed, reciprocal, null, "phone");
      DeviceHub.PairedDevice peer = hub.findById(laptop);
      peer.online = true; // Simulate a previously working connection going down.
      AtomicBoolean gatewayCalled = new AtomicBoolean();
      PhonePauseJudge judge = new PhonePauseJudge(context, (model, request) -> {
        gatewayCalled.set(true);
        JSONArray actions = request.getJSONObject("response_format").getJSONObject("json_schema")
            .getJSONObject("schema").getJSONObject("properties").getJSONObject("action").getJSONArray("enum");
        check(!actions.toString().contains("allow_services") && !actions.toString().contains("peek"),
            "Gateway schema offered an unavailable action");
        return new JSONObject().put("action", "allow_services").put("services", new JSONArray().put("discord"))
            .put("minutes", 3).put("message", "Check one message.").put("counter_offer", JSONObject.NULL).toString();
      });
      String correlation = FocusEvents.newId();
      PhonePauseJudge.Decision reply = judge.ask("Check one Discord message", null, null, correlation);
      check(gatewayCalled.get() && reply.is("fallback") && GATEWAY_INVALID_REPLY.equals(reply.cause),
          "Unexpected scoped reply must fail closed after a LAN outage");
      String updatedAddress = "192.168.1.4:47833";
      reciprocal.put("address", updatedAddress);
      hub.ingestSyncedPeer(new JSONObject(pushed.toString()).put("snapshot_revision", 2), reciprocal, null, "phone");
      check(!peer.online && peer.snapshot != null, "Pushed snapshots must not make laptop controls online");
      check(hub.isAddressPaired(updatedAddress), "The laptop's new address must not require another pairing");
      await(() -> Arrays.stream(FocusEvents.export(context).split("\n")).anyMatch(
          line -> line.contains(correlation) && line.contains("\"decision_action\":\"fallback\"")), 3000);
      String events = FocusEvents.export(context);
      check(events.contains(correlation) && events.contains(LAN_UNREACHABLE)
          && events.contains(GATEWAY_INVALID_REPLY) && events.contains("\"decision_action\":\"fallback\"")
          && !events.contains("Discord message"), "Missing or unsafe Ask events");
      CountDownLatch laptopStarted = new CountDownLatch(1);
      CountDownLatch releaseLaptop = new CountDownLatch(1);
      CountDownLatch laptopFinished = new CountDownLatch(1);
      PhonePauseJudge late = new PhonePauseJudge(context, (model, request) ->
          "{\"action\":\"deny\",\"minutes\":0,\"services\":[],"
              + "\"message\":\"Direct Gateway answer.\",\"counter_offer\":null}",
          new PhonePauseJudge.LaptopWire() {
            @Override public DeviceHub.LaptopRoute resolve(DeviceHub hub, List<DeviceHub.PairedDevice> candidates,
                long deadline, String correlation) {
              return new DeviceHub.LaptopRoute(peer, peer.fingerprint, peer.token, peer.address, 0, deadline);
            }

            @Override public PhonePauseJudge.Decision complete(DeviceHub.LaptopRoute route, String text,
                PausePolicy.CheckIn checkIn, int cap, String correlation, long deadline) {
              laptopStarted.countDown();
              long until = SystemClock.elapsedRealtime() + 5000;
              while (releaseLaptop.getCount() > 0 && SystemClock.elapsedRealtime() < until) {
                try { releaseLaptop.await(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) {} // A laptop can finish after the caller gave up.
              }
              laptopFinished.countDown();
              return new PhonePauseJudge.Decision("late-laptop-ticket", "full_pause",
                  Collections.emptyList(), 3, "Late laptop answer.", "", "", peer);
            }
          });
      String lateId = FocusEvents.newId();
      PhonePauseJudge.Decision winner;
      try {
        long started = SystemClock.elapsedRealtime();
        winner = late.askUntil("Check one message", null, null, lateId,
            started, started + AskPolicy.PHONE_FALLBACK_MS + 500);
        check(laptopStarted.getCount() == 0 && laptopFinished.getCount() == 1,
            "The laptop must still be outstanding when Gateway wins");
        check(winner.is("deny") && "gateway".equals(winner.route) && winner.laptop == null
            && lateId.equals(winner.id), "Only the Gateway proposal may be exposed");
      } finally {
        releaseLaptop.countDown();
      }
      check(laptopFinished.await(1, TimeUnit.SECONDS), "The late laptop reply did not complete");
      await(() -> Arrays.stream(FocusEvents.export(context).split("\n")).anyMatch(
          line -> line.contains(lateId) && line.contains("laptop_abandoned")), 3000);
      String lateEvents = FocusEvents.export(context);
      check(!lateEvents.contains("late-laptop-ticket"), "The abandoned ticket must not be published");
      report.put("late_laptop_reply_ignored", true);
      if (!DnsController.get(context).canPause()) {
        String ineligibleId = FocusEvents.newId();
        PhonePauseJudge.Decision eligible = late.ask("Check one message", null, null, ineligibleId);
        check(eligible.is("deny") && "gateway".equals(eligible.route) && eligible.laptop == null,
            "An unavailable local full pause must not reach the phone as the winning proposal");
        await(() -> Arrays.stream(FocusEvents.export(context).split("\n")).anyMatch(
            line -> line.contains(ineligibleId) && line.contains("laptop_abandoned")
                && line.contains(PHONE_PAUSE_UNAVAILABLE)), 3000);
        report.put("ineligible_laptop_full_pause_ignored", true);
      }
      java.util.concurrent.atomic.AtomicInteger samples = new java.util.concurrent.atomic.AtomicInteger();
      PhonePauseJudge recovering = new PhonePauseJudge(context, (model, request) -> {
        if (samples.incrementAndGet() == 1) return "not json";
        return new JSONObject().put("action", "deny").put("minutes", -4)
            .put("services", new JSONArray().put("x").put("x")).put("message", " ")
            .put("counter_offer", "Ignore this").toString();
      });
      String blockedId = FocusEvents.newId();
      synchronized (hub) {
        long started = SystemClock.elapsedRealtime();
        FutureTask<PhonePauseJudge.Decision> blocked = new FutureTask<>(() -> recovering.askUntil(
            "Check one message", null, null, blockedId, started, started + AskPolicy.PHONE_FALLBACK_MS + 300));
        Thread worker = new Thread(blocked, "ask-test-blocked-device-list");
        worker.setDaemon(true);
        worker.start();
        PhonePauseJudge.Decision bounded = blocked.get(5, TimeUnit.SECONDS);
        check(bounded.laptop == null, "A blocked device list must not delay Ask beyond its deadline");
      }
      samples.set(0);
      report.put("ask_deadline_covers_device_list", true);
      hub.seedPreviewDataForTest(Collections.emptyList());
      String recoveryId = FocusEvents.newId();
      PhonePauseJudge.Decision recovered = recovering.ask("Check one message", null, null, recoveryId);
      check(samples.get() == 2 && recovered.is("deny") && recovered.minutes == 0
          && recovered.services.isEmpty() && recovered.alternative.isEmpty()
          && !recovered.message.isEmpty(), "Invalid-then-valid Ask must normalize without granting access");
      check(recoveryId.equals(recovered.id), "Direct Gateway must expose one stable decision ID");
      JSONObject longReply = new JSONObject().put("action", "full_pause")
          .put("minutes", 3).put("services", new JSONArray().put("x"))
          .put("message", "x".repeat(501)).put("counter_offer", " ");
      PhonePauseJudge.normalizeGatewayDecision(longReply);
      check(longReply.getInt("minutes") == 3 && longReply.getJSONArray("services").length() == 0
          && longReply.getString("message").length() == 500 && longReply.isNull("counter_offer"),
          "Normalization must preserve a positive duration and discard extraneous details");
      report.put("invalid_gateway_retry_and_normalization", true);
      report.put("offline_laptop_push_and_invalid_gateway", true);
    } finally {
      hub.seedPreviewDataForTest(previous);
    }

    String stale = "192.168.1.3:47833";
    String fresh = "192.168.1.4:47833";
    long discoveryAvailable = SystemClock.elapsedRealtime() + 100;
    String recovered = LanClient.findAddress(Collections.singletonList(stale),
        () -> SystemClock.elapsedRealtime() >= discoveryAvailable ? Collections.singletonList(fresh) : Collections.emptyList(),
        (address, timeout) -> {
          if (address.equals(stale)) {
            SystemClock.sleep(Math.min(timeout, 400));
            throw new SocketTimeoutException();
          }
          return address;
        }, discoveryAvailable + 800);
    check(fresh.equals(recovered), "A stale saved address should yield to discovered address before the deadline");
    report.put("stale_laptop_address_recovers", true);

    Exception badPin = new LanClient.UnreachableException(new SSLHandshakeException("Test certificate"));
    Map<String, String> mismatches = Collections.singletonMap(stale, laptop);
    check(LAPTOP_UNREACHABLE.equals(PhonePauseJudge.laptopCause(DeviceHub.laptopResolutionFailure(
        badPin, Collections.emptyMap(), (address, id) -> true))), "A stale address alone cannot identify the laptop");
    check(LAPTOP_UNREACHABLE.equals(PhonePauseJudge.laptopCause(DeviceHub.laptopResolutionFailure(
        badPin, mismatches, (address, id) -> false))), "A saved address alone cannot require pairing again");
    check(LAPTOP_TLS_MISMATCH.equals(PhonePauseJudge.laptopCause(DeviceHub.laptopResolutionFailure(
        badPin, mismatches, (address, id) -> stale.equals(address) && laptop.equals(id)))),
        "A newly discovered matching laptop with a different certificate should require pairing again");
    report.put("repair_requires_matching_discovery", true);

    expectScope(LAPTOP_UNREACHABLE, false, "aaaaaa", "aaaaaa", true, true, false, 3);
    expectScope(SCOPE_KEY_MISSING, true, "", "aaaaaa", true, true, false, 3);
    expectScope(SCOPE_PROFILE_MISMATCH, true, "aaaaaa", "bbbbbb", true, true, false, 3);
    expectScope(DNS_DISABLED, true, "aaaaaa", "aaaaaa", false, false, false, 3);
    expectScope(DNS_PAUSED, true, "aaaaaa", "aaaaaa", true, false, true, 3);
    expectScope(PRIVATE_DNS_INACTIVE, true, "aaaaaa", "aaaaaa", true, false, false, 3);
    expectScope(CAP_EXCEEDED, true, "aaaaaa", "aaaaaa", true, true, false, 6);
    expectScope(OK, true, "aaaaaa", "aaaaaa", true, true, false, 3);
    Set<String> messages = new HashSet<>();
    for (String cause : Arrays.asList(LAPTOP_UNREACHABLE, LAPTOP_TIMEOUT, LAPTOP_TLS_MISMATCH, LAPTOP_UNAUTHORIZED,
        LAPTOP_JUDGE_DISABLED, LAPTOP_UNSUPPORTED, LAPTOP_REJECTED, LAPTOP_INVALID_REPLY, NO_LAPTOP_ANSWER,
        SCOPE_KEY_MISSING, SCOPE_PROFILE_MISMATCH, DNS_DISABLED, DNS_PAUSED, PRIVATE_DNS_INACTIVE, CAP_EXCEEDED,
        DECISION_EXPIRED, APPLY_TIMEOUT, GATEWAY_NO_KEY, GATEWAY_KEYSTORE, GATEWAY_HTTP_4XX, GATEWAY_HTTP_5XX,
        GATEWAY_TIMEOUT, GATEWAY_REFUSED, GATEWAY_INVALID_REPLY)) {
      check(messages.add(PhonePauseJudge.causeMessage(cause)), "Duplicate message for " + cause);
    }
    report.put("ask_cause_messages", true);

    check(DnsController.privateDnsActive(true, false, true, () -> true), "VPN should use matching underlying DNS");
    check(!DnsController.privateDnsActive(true, false, true, () -> false), "VPN without protected underlying DNS");
    check(!DnsController.privateDnsActive(true, false, false, () -> true), "Non-VPN must check its own DNS");
    check(!DnsController.privateDnsActive(false, false, true, () -> true), "Underlying DNS cannot excuse tampering");
    check(DnsController.privateDnsActive(true, true, false, () -> false), "Direct DNS still active");
    report.put("vpn_underlying_private_dns", true);
  }

  private static void verifyEventSyncResponseLimit() throws JSONException {
    JSONObject row = new JSONObject().put("v", 1);
    JSONArray page = new JSONArray().put(row);
    String snapshot = "{\"padding\":\"\"}";
    String extension = ",\"focus_events\":" + page;
    int limit = LanServer.MAX_RESPONSE_BYTES;
    int padding = limit - snapshot.length();
    String full = "{\"padding\":\"" + "x".repeat(padding) + "\"}";
    check(full.equals(LanController.appendEvents(full, page)), "A full sync response must omit events");

    String fits = "{\"padding\":\"" + "x".repeat(padding - extension.length()) + "\"}";
    String response = LanController.appendEvents(fits, new JSONArray().put(row).put(row));
    check(response.length() == limit && new JSONObject(response).getJSONArray("focus_events").length() == 1,
        "Only rows that fit in the existing response limit may be included");
  }

  private static void expectScope(String cause, boolean laptop, String profile, String phoneProfile,
      boolean enabled, boolean configured, boolean paused, int minutes) {
    String actual = PhonePauseJudge.scopeCause(laptop, profile, false, phoneProfile, enabled, configured, paused, minutes, 5);
    check(cause.equals(actual), "Expected " + cause + " but got " + actual);
  }

  public static void verifyScopedTimer(Instrumentation instrumentation, Context context, JSONObject report) throws Exception {
    DeviceHub hub = DeviceHub.get(context);
    List<DeviceHub.PairedDevice> previous = hub.list();
    String id = "test-scope-laptop";
    DeviceHub.PairedDevice laptop = new DeviceHub.PairedDevice(id, "laptop", "Test laptop", "127.0.0.1:1",
        "0".repeat(64), "0".repeat(64));
    NotificationManager nm = context.getSystemService(NotificationManager.class);
    long laptopNow = System.currentTimeMillis() + 9 * 60_000L;
    long until = laptopNow + 240_000; // Model a laptop clock rollback after a three-minute grant.
    JSONObject grant = new JSONObject().put("id", "test-grant").put("duration_ms", 180_000);
    JSONObject sample = new JSONObject().put("now", laptopNow).put("snapshot_revision", 2)
        .put("dns", new JSONObject().put("scoped_until", until).put("scoped_grant", grant)
            .put("scoped_services", new JSONArray().put("x").put("github")));
    try {
      DeviceHub.PairedDevice tv = new DeviceHub.PairedDevice("test-paused-tv", "tv", "Test TV", "", "", "");
      tv.snapshot = new JSONObject().put("dns", new JSONObject().put("pause_until", laptopNow + 300_000));
      tv.lastSeen = System.currentTimeMillis();
      tv.online = true;
      hub.seedPreviewDataForTest(Arrays.asList(tv, laptop));
      hub.ingestSyncedPeer(new JSONObject(sample.toString()).put("device_id", id)
          .put("snapshot_revision", 3), null, null, "phone");
      check(hub.activeScopedAccess() == null, "A push by itself has no direct clock sample");
      hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", sample),
          SystemClock.elapsedRealtime() - 500);
      await(() -> timer(nm, "X, GitHub open") != null, 5000);
      Notification scoped = checkTimer(nm, "X, GitHub open", "End");
      check(tv.online && tv.dnsPausedNow(System.currentTimeMillis()),
          "A remote TV pause must not suppress a successful scoped Start notification");
      ScopedAccessWindow access = hub.activeScopedAccess();
      check(access != null && access.remaining() > 170_000 && access.remaining() <= 179_500
          && Math.abs(scoped.when - System.currentTimeMillis() - access.remaining()) < 1_000,
          "The countdown must stay within the approved duration, including after clock rollback");
      int boot = Store.get(context).boot();
      JSONObject saved = access.save(boot);
      check(boot >= 0 && ScopedAccessWindow.restore(id, saved, boot).deadline == access.deadline,
          "Process restart should keep the original monotonic deadline");
      check(ScopedAccessWindow.restore(id, saved, boot + 1) == null,
          "Reboot should wait for a fresh laptop sample");

      instrumentation.getUiAutomation().executeShellCommand("cmd statusbar expand-notifications").close();
      try {
        SystemClock.sleep(600);
        screenshot(instrumentation, context, "ask-scoped-notification.png");
      } finally {
        instrumentation.getUiAutomation().executeShellCommand("cmd statusbar collapse").close();
      }
      long failedEnds = failedEnds(context);
      scoped.actions[0].actionIntent.send();
      await(() -> failedEnds(context) > failedEnds, 8000);
      check(hub.activeScopedAccess() != null && timer(nm, "X, GitHub open") != null,
          "An unreachable laptop should leave the countdown running");

      verifyScopedEnd(context, hub, laptop, sample, nm);

      hub.ingestSyncedPeer(new JSONObject().put("device_id", id).put("now", laptopNow)
          .put("snapshot_revision", 1).put("dns", new JSONObject()), null, null, "phone");
      check(hub.activeScopedAccess() != null, "A snapshot taken before the apply reply must not clear the timer");
      hub.ingestSyncedPeer(new JSONObject(sample.toString()).put("device_id", id)
          .put("snapshot_revision", 4), null, null, "phone");
      check(hub.activeScopedAccess().deadline == access.deadline,
          "A snapshot with the same unresolved grant should retain its original deadline");
      JSONObject shorter = new JSONObject(sample.toString()).put("now", until - 1000);
      ScopedAccessWindow expiredDuringRefresh = ScopedAccessWindow.sample(id, shorter,
          SystemClock.elapsedRealtime() - 1500, access);
      check(!FocusNotifications.updateScopedAccess(context, expiredDuringRefresh, false),
          "Notification work must return an expired shortened deadline to the hub");
      hub.ingestSyncedPeer(new JSONObject(sample.toString()).put("device_id", id)
          .put("snapshot_revision", 5).put("now", until + 1000), null, null, "phone");
      await(() -> timer(nm, "X, GitHub open") == null, 5000);
      JSONObject rollback = new JSONObject(sample.toString()).put("snapshot_revision", 6);
      hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", rollback), SystemClock.elapsedRealtime());
      check(hub.activeScopedAccess() == null, "A laptop clock correction must not restart the same grant");
      hub.ingestSyncedPeer(new JSONObject().put("device_id", id).put("now", laptopNow + 1000)
          .put("snapshot_revision", 7).put("dns", new JSONObject()), null, null, "phone");
      await(() -> timer(nm, "X, GitHub open") == null, 5000);
      check(hub.activeScopedAccess() == null, "A newer snapshot should clear the timer when the laptop ends access");
      hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", sample), SystemClock.elapsedRealtime());
      check(hub.activeScopedAccess() == null, "An older apply reply must not revive an ended grant");

      grant.put("id", "test-grant-2");
      sample.put("snapshot_revision", 8).put("now", until - 2500);
      synchronized (hub) {
        hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", sample), SystemClock.elapsedRealtime());
        await(() -> timer(nm, "X, GitHub open") != null, 2000);
        File temporary = new File(context.getFilesDir(), "paired_devices.json.tmp");
        check(temporary.mkdir(), "Block the first expiry write");
        try {
          await(() -> timer(nm, "X, GitHub open") == null, 5000);
          hub.refreshScopedNotification(false);
        } finally {
          check(temporary.delete(), "Restore the temporary write path");
        }
        hub.refreshScopedNotification(false);
      }
      File devices = new File(context.getFilesDir(), "paired_devices.json");
      JSONArray persisted = new JSONArray(new String(Files.readAllBytes(devices.toPath()), StandardCharsets.UTF_8));
      ScopedAccessWindow offline = ScopedAccessWindow.restore(id, persisted.getJSONObject(0).optJSONObject("scoped_access"), boot + 1);
      check(offline != null && "test-grant-2".equals(offline.grantId) && offline.remaining() == 0,
          "The hub must retry saving an offline expiry after a failed write");

      grant.put("id", "test-grant-3");
      sample.put("snapshot_revision", 9);
      JSONObject expired = new JSONObject(sample.toString()).put("device_id", id)
          .put("snapshot_revision", 10).put("now", until + 1000);
      hub.ingestSyncedPeer(expired, null, null, "phone");
      hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", sample), SystemClock.elapsedRealtime());
      check(hub.activeScopedAccess() == null, "A newer push that reports expiry must beat the first Start reply");
      ScopedAccessWindow expiredWindow = ScopedAccessWindow.sample(id, expired, SystemClock.elapsedRealtime(), null);
      ScopedAccessWindow restored = ScopedAccessWindow.restore(id, expiredWindow.save(boot), boot + 1);
      ScopedAccessWindow afterReboot = ScopedAccessWindow.sample(id, sample, SystemClock.elapsedRealtime(), restored);
      check(restored != null && afterReboot.remaining() == 0,
          "Reboot must retain a known expiry when a later laptop sample has rolled back");
      report.put("scoped_timer_immediate_skew_stale_snapshot_end_and_offline_expiry", true);
    } finally {
      hub.seedPreviewDataForTest(previous);
      hub.refreshScopedNotification(false);
    }
  }

  private static void verifyScopedEnd(Context context, DeviceHub hub, DeviceHub.PairedDevice first,
      JSONObject sample, NotificationManager nm) throws Exception {
    String id = "test-second-laptop";
    String token = "1".repeat(64);
    LanController controller = LanController.get(context);
    Method tls = LanController.class.getDeclaredMethod("tls");
    tls.setAccessible(true);
    JSONObject apply = new JSONObject(sample.toString()).put("device_id", id);
    apply.getJSONObject("dns").getJSONObject("scoped_grant").put("id", "second-grant");
    apply.getJSONObject("dns").put("scoped_services", new JSONArray().put("x"));
    AtomicReference<JSONObject> latest = new AtomicReference<>(new JSONObject(apply.toString()).put("snapshot_revision", 3));
    Queue<String> endCommands = new ConcurrentLinkedQueue<>();
    try (LanServer server = new LanServer(context, (SSLContext) tls.invoke(controller), 0,
        (method, path, headers, body, outcome) -> {
      if ("/v1/hello".equals(path)) return new JSONObject().put("device_id", id).toString();
      if (!("Bearer " + token).equals(headers.get("authorization"))) throw new LanServer.ApiError(401, "Unpaired");
      outcome.authenticated = true;
      if ("/v1/snapshot".equals(path)) return latest.get().toString();
      JSONObject command = new JSONObject(body);
      JSONObject snapshot = latest.get();
      String requested = command.getString("grant_id");
      endCommands.add(requested);
      if (!"/v1/command".equals(path) || !"end_scoped_access".equals(command.optString("action"))
          || !"replacement".equals(requested)) throw new LanServer.ApiError(409, "Grant has changed");
      return new JSONObject().put("device_id", id).put("now", snapshot.getLong("now"))
          .put("snapshot_revision", 5).put("dns", new JSONObject()).toString();
    }, error -> {})) {
      new Thread(server::run, "test-scoped-timer-laptop").start();
      String address = "127.0.0.1:" + server.port();
      String pin = LanClient.request(address, null, null, "/v1/hello", null).fingerprint;
      DeviceHub.PairedDevice second = new DeviceHub.PairedDevice(id, "laptop", "Second laptop", address, pin, token);
      hub.seedPreviewDataForTest(Collections.singletonList(second));
      check(hub.refreshOne(id), "The direct snapshot should arrive before the Start reply");
      ScopedAccessWindow confirmed = hub.activeScopedAccess();
      check(confirmed != null && id.equals(confirmed.laptopId), "The direct snapshot should establish the grant");
      hub.seedPreviewDataForTest(Arrays.asList(first, second));
      check(first.id.equals(hub.activeScopedAccess().laptopId), "The first phone grant should still have priority before Start");
      hub.scopedAccessApplied(second, new JSONObject().put("scoped_timer", apply), SystemClock.elapsedRealtime());
      Notification old = checkTimer(nm, "X open", "End");
      check(hub.activeScopedAccess().deadline == confirmed.deadline,
          "An older Start reply should select the newer snapshot without extending the deadline");

      JSONObject replacement = new JSONObject(latest.get().toString()).put("snapshot_revision", 4);
      replacement.getJSONObject("dns").getJSONObject("scoped_grant").put("id", "replacement");
      replacement.getJSONObject("dns").put("scoped_services", new JSONArray().put("github"));
      latest.set(replacement);
      check(hub.refreshOne(id), "The replacement snapshot should reach the phone");
      hub.scopedAccessApplied(second, new JSONObject().put("scoped_timer", replacement), SystemClock.elapsedRealtime());
      Notification current = checkTimer(nm, "GitHub open", "End");
      AtomicBoolean finished = new AtomicBoolean();
      old.actions[0].actionIntent.send(0, (intent, sent, code, data, extras) -> finished.set(true), null);
      await(finished::get, 5000);
      check(endCommands.isEmpty(), "A stale End must not send a command for the replacement");
      current.actions[0].actionIntent.send();
      await(() -> timer(nm, "X, GitHub open") != null, 5000);
      check(endCommands.size() == 1 && "replacement".equals(endCommands.peek()) && second.snapshot.optLong("snapshot_revision") == 5,
          "End should close the replacement from the command response");
    }
  }

  private static long failedEnds(Context context) {
    return Store.get(context).recentDiagnostics(100).stream()
        .filter(line -> line.endsWith(" scoped_timer_end_failed")).count();
  }

  public static void verifyOfflinePause(Instrumentation instrumentation, Context context, JSONObject report) throws Exception {
    DnsController dns = DnsController.get(context);
    DeviceHub hub = DeviceHub.get(context);
    List<DeviceHub.PairedDevice> previous = hub.list();
    Activity activity = instrumentation.startActivitySync(
        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Constructor<JudgeSheetDialog> constructor = JudgeSheetDialog.class
        .getDeclaredConstructor(Activity.class, DeviceHub.PairedDevice.class, Runnable.class);
    constructor.setAccessible(true);
    Method show = JudgeSheetDialog.class.getDeclaredMethod("show");
    show.setAccessible(true);
    AtomicReference<JudgeSheetDialog> created = new AtomicReference<>();
    instrumentation.runOnMainSync(() -> {
      try { created.set(constructor.newInstance(activity, null, (Runnable) () -> {})); }
      catch (Exception error) { throw new AssertionError(error); }
    });
    JudgeSheetDialog sheet = created.get();
    Dialog dialog = field(sheet, "dialog", Dialog.class);
    hub.seedPreviewDataForTest(Collections.emptyList());
    try {
      dns.connect("aaaaaa");
      check(dns.canPause(), "The emulator needs secure settings and exact alarm permissions");
      check(dns.configured() && dns.activePrivateDnsNetwork() == null,
          "The synthetic profile must be configured but unvalidated, which blocks Peek");
      PhonePauseJudge approved = new PhonePauseJudge(context, (model, request) ->
          "{\"action\":\"full_pause\",\"services\":[],\"minutes\":20,"
              + "\"message\":\"Read the message.\",\"counter_offer\":null}");
      PhonePauseJudge.Decision decision = approved.ask("Check one Discord message", null, null, FocusEvents.newId());
      check(decision.is("full_pause") && decision.minutes == 5 && dns.deadline() == 0,
          "Gateway proposal must keep the five-minute cap and await approval");
      instrumentation.runOnMainSync(() -> {
        try { show.invoke(sheet); } catch (Exception error) { throw new AssertionError(error); }
      });
      instrumentation.waitForIdleSync();
      screenshot(instrumentation, context, "ask-before-answer.png");
      Button fullPause = field(sheet, "secondary", Button.class);
      instrumentation.runOnMainSync(() -> {
        check(fullPause.isShown() && fullPause.isEnabled(), "Manual pause must be reachable before Ask");
        fullPause.performClick();
      });
      await(() -> selectFiveMinutes(instrumentation), 5000);
      EditText reason = field(sheet, "request", EditText.class);
      EditText plan = field(sheet, "afterPlan", EditText.class);
      Button hold = field(sheet, "primary", Button.class);
      instrumentation.runOnMainSync(() -> {
        reason.setText("Check a club message");
        plan.setText("Return to the essay");
        check(!hold.isEnabled(), "Manual pause must wait");
      });
      await(() -> hold.isEnabled(), 22_000);
      screenshot(instrumentation, context, "ask-manual-hold.png");
      instrumentation.runOnMainSync(() -> {
        plan.setText("");
        check(!hold.isEnabled(), "Manual pause must require a plan");
        plan.setText("Return to the essay");
        touch(hold, MotionEvent.ACTION_DOWN);
        touch(hold, MotionEvent.ACTION_UP);
      });
      SystemClock.sleep(3200);
      check(dns.deadline() == 0, "Releasing early must not apply the pause");
      instrumentation.runOnMainSync(() -> touch(hold, MotionEvent.ACTION_DOWN));
      await(() -> dns.deadline() > 0, 5000);
      check(dns.remainingMs() > 4 * 60_000 && dns.remainingMs() <= 5 * 60_000,
          "Manual pause should use the chosen five minutes without laptop or Gateway");
      NotificationManager nm = context.getSystemService(NotificationManager.class);
      Notification phonePause = checkTimer(nm, "Phone DNS off", "Turn on now");
      DeviceHub.PairedDevice laptop = new DeviceHub.PairedDevice("test-overlapping-laptop", "laptop",
          "Test laptop", "", "", "");
      hub.seedPreviewDataForTest(Collections.singletonList(laptop));
      long laptopNow = System.currentTimeMillis();
      JSONObject scopedTimer = new JSONObject().put("now", laptopNow).put("snapshot_revision", 1)
          .put("dns", new JSONObject().put("scoped_until", laptopNow + 60_000)
              .put("scoped_grant", new JSONObject().put("id", "overlapping-grant").put("duration_ms", 60_000))
              .put("scoped_services", new JSONArray().put("x")));
      hub.scopedAccessApplied(laptop, new JSONObject().put("scoped_timer", scopedTimer), SystemClock.elapsedRealtime());
      checkTimer(nm, "X open", "End");
      dns.refreshPauseNotification(true);
      checkTimer(nm, "Phone DNS off", "Turn on now");
      checkTimer(nm, "X open", "End");
      phonePause.actions[0].actionIntent.send();
      await(() -> dns.configured() && timer(nm, "Phone DNS off") == null, 5000);
      checkTimer(nm, "X open", "End");
      hub.forget(laptop.id);
      await(() -> timer(nm, "X open") == null, 5000);
      report.put("scoped_and_phone_pause_notifications_coexist", true);
      await(() -> FocusEvents.export(context).contains("\"manual_override\":true"), 5000);
      report.put("manual_pause_offline_with_wait_plan_and_hold", true);
      report.put("manual_ask_pause_timer", true);

      instrumentation.runOnMainSync(() -> {
        try {
          JudgeSheetDialog normal = constructor.newInstance(activity, null, (Runnable) () -> {});
          Field judgeField = JudgeSheetDialog.class.getDeclaredField("judge");
          judgeField.setAccessible(true);
          judgeField.set(normal, approved);
          created.set(normal);
          show.invoke(normal);
          field(normal, "request", EditText.class).setText("Check another club message");
          field(normal, "ask", Button.class).performClick();
        } catch (Exception error) {
          throw new AssertionError(error);
        }
      });
      Button approvedHold = field(created.get(), "primary", Button.class);
      instrumentation.runOnMainSync(() -> {
        try { field(created.get(), "afterPlan", EditText.class).setText("Return to the essay"); }
        catch (Exception error) { throw new AssertionError(error); }
      });
      await(() -> approvedHold.isShown() && approvedHold.isEnabled(), 22_000);
      instrumentation.runOnMainSync(() -> touch(approvedHold, MotionEvent.ACTION_DOWN));
      await(() -> dns.deadline() > 0, 5000);
      checkTimer(nm, "Phone DNS off", "Turn on now");
      report.put("approved_ask_full_pause_timer", true);
    } finally {
      if (dns.deadline() > 0) dns.resume();
      if (dns.enabled()) dns.disconnect();
      hub.seedPreviewDataForTest(previous);
      hub.refreshScopedNotification(false);
      instrumentation.runOnMainSync(() -> { dialog.dismiss(); activity.finish(); });
    }
  }

  private static Notification checkTimer(NotificationManager nm, String title, String action) throws Exception {
    await(() -> timer(nm, title) != null, 5000);
    Notification notification = timer(nm, title);
    check(notification != null && notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        && notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0 && notification.getTimeoutAfter() > 0
        && notification.actions.length == 1 && action.contentEquals(notification.actions[0].title),
        "Expected a countdown with one " + action + " action");
    if (Build.VERSION.SDK_INT >= 36) {
      check(notification.extras.getBoolean("android.requestPromotedOngoing")
          && Notification.ProgressStyle.class.getName().equals(notification.extras.getString(Notification.EXTRA_TEMPLATE)),
          "Android 16 timer should request a native progress Live Update");
    }
    return notification;
  }

  private static Notification timer(NotificationManager nm, String title) {
    return Arrays.stream(nm.getActiveNotifications()).map(active -> active.getNotification())
        .filter(n -> title.equals(n.extras.getString(Notification.EXTRA_TITLE)))
        .findFirst().orElse(null);
  }

  private static void touch(Button hold, int action) {
    long now = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(now, now, action, 3, 3, 0);
    hold.dispatchTouchEvent(event);
    event.recycle();
  }

  private static boolean selectFiveMinutes(Instrumentation instrumentation) {
    AccessibilityNodeInfo window = instrumentation.getUiAutomation().getRootInActiveWindow();
    if (window == null) return false;
    for (AccessibilityNodeInfo match : window.findAccessibilityNodeInfosByText("5 min")) {
      if (match.getText() != null && "5 min".contentEquals(match.getText()) && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
    }
    return false;
  }

  private static void screenshot(Instrumentation instrumentation, Context context, String name) throws IOException {
    Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
    if (image == null) return;
    try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }

  private static <T> T field(JudgeSheetDialog sheet, String name, Class<T> type) throws Exception {
    Field field = JudgeSheetDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(sheet));
  }

  private static void await(BooleanSupplier ready, long timeout) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < until) {
      if (ready.getAsBoolean()) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Timed out waiting for Ask sheet");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

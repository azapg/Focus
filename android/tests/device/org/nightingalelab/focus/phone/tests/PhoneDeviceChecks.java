package org.nightingalelab.focus.phone.tests;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.nightingalelab.focus.phone.*;

/**
 * Real on-emulator instrumentation checks for Focus for Android.
 * Modes:
 * - default ("verify"): runs real TLS Keystore API checks, Private DNS instant snap-back check,
 *   browser domain tracking check, policy ratchet check, and widget bitmap rendering.
 * - "seed_showcase": seeds fictional activity and an update for screenshots in a fresh emulator.
 * - "failure_events": checks oversized update logging, diagnostic privacy and the share row.
 */
public final class PhoneDeviceChecks extends Instrumentation {
  private String mode = "verify";

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    if (arguments != null && arguments.getString("mode") != null) {
      mode = arguments.getString("mode");
    }
    start();
  }

  @Override
  public void onStart() {
    Bundle out = new Bundle();
    JSONObject report = new JSONObject();
    boolean ok = false;
    try {
      Context target = getTargetContext();
      if ("seed_showcase".equals(mode) || "seed_store_showcase".equals(mode)) {
        seedShowcase(target, report);
        if ("seed_store_showcase".equals(mode)) {
          long now = System.currentTimeMillis();
          int day = org.nightingalelab.focus.habits.Habits.day(now);
          org.nightingalelab.focus.habits.HabitStore.get(target).updateWallet(wallet -> {
            wallet.start(day - 1);
            return wallet.earn("fixture:store", "fixture", 65, "", now, day);
          });
        }
        ok = true;
      } else if ("capture_habit_nudge".equals(mode)) {
        captureHabitNudge(target, report);
        ok = true;
      } else if ("verify_ask".equals(mode)) {
        PhoneAskChecks.verify(target, report);
        PhoneAskChecks.verifyScopedTimer(this, target, report);
        PhoneAskChecks.verifyOfflinePause(this, target, report);
        ok = true;
      } else if ("failure_events".equals(mode)) {
        verifyFailureEvents(target, report);
        ok = true;
      } else {
        runVerify(target, report);
        ok = true;
      }
    } catch (Throwable t) {
      try {
        report.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
      } catch (JSONException ignored) {}
    }
    out.putString("result", report.toString());
    finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
  }

  private void verifyFailureEvents(Context c, JSONObject report) throws Exception {
    long size = 256L * 1024 * 1024 + 1;
    UpdateManifest oversized = new UpdateManifest(1, "nightly", "android", c.getPackageName(),
        10, "test", "abcdef2", "abcdef2", "Private test title", "", "focus-phone.apk",
        size, "0".repeat(64));
    Method install = PhoneUpdater.class.getDeclaredMethod("runDownloadAndInstall",
        UpdateManifest.class, String.class, String.class);
    install.setAccessible(true);
    install.invoke(PhoneUpdater.get(c), oversized, "https://example.test/private", "private-token");
    String privateId = FocusEvents.newId();
    for (String field : new String[] {"request", "reason", "url", "hostname", "title", "app_name",
        "key", "token", "fingerprint"}) {
      FocusEvents.record(c, "ask", "gateway", "failed", "gateway_invalid_reply", 0,
          new JSONObject().put("correlation_id", privateId).put(field, "private-value"));
    }
    android.net.Uri uri = android.net.Uri.parse("content://" + c.getPackageName()
        + ".events/focus-events.jsonl");
    String export;
    try (InputStream stream = c.getContentResolver().openInputStream(uri)) {
      export = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    if (export.contains(privateId) || export.contains("private-value") || export.contains("private-token")
        || export.contains("example.test") || export.contains("Private test title")) {
      throw new AssertionError("Unexpected text in diagnostic export");
    }
    boolean oversizedRecorded = false;
    for (String line : export.split("\n")) {
      if (line.isEmpty()) continue;
      JSONObject row = new JSONObject(line);
      JSONObject fields = row.getJSONObject("fields");
      if ("apk_too_large".equals(row.getString("cause")) && fields.getLong("expected_bytes") == size) {
        oversizedRecorded = fields.getLong("actual_bytes") == 0 && fields.getLong("limit_bytes") < size
            && "abcdef2".equals(fields.getString("to_build"));
      }
    }
    if (!oversizedRecorded) throw new AssertionError("Over-limit download was not recorded");

    Activity activity = startActivitySync(new Intent(c, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    runOnMainSync(() -> SettingsDialogs.showPhoneSheet(activity, () -> {}));
    waitForIdleSync();
    UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    saveNudgeScreenshot(c, automation, "share-diagnostics.png");
    report.put("oversized_download_and_private_fields", true);
  }

  private void captureHabitNudge(Context c, JSONObject report) throws Exception {
    UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SystemClock.sleep(1_000);
    Method post = FocusNotifications.class.getDeclaredMethod("sendHabitNudge", Context.class, String.class);
    post.setAccessible(true);
    post.invoke(null, c, "3 habits left today");
    NotificationManager notifications = c.getSystemService(NotificationManager.class);
    NotificationChannel channel = notifications.getNotificationChannel("focus_habit_nudges");
    report.put("quiet_high_importance", channel != null && channel.getSound() == null
        && !channel.shouldVibrate() && channel.getImportance() == NotificationManager.IMPORTANCE_HIGH);
    if (!report.getBoolean("quiet_high_importance")) throw new AssertionError("Expected a quiet heads-up channel");
    SystemClock.sleep(1_200);
    saveNudgeScreenshot(c, automation, "habit-nudge-heads-up.png");
    try (ParcelFileDescriptor result = automation.executeShellCommand("cmd statusbar expand-notifications");
         InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
      byte[] bytes = new byte[256];
      while (output.read(bytes) != -1) {}
    }
    SystemClock.sleep(900);
    saveNudgeScreenshot(c, automation, "habit-nudge-shade.png");
  }

  private void saveNudgeScreenshot(Context c, UiAutomation automation, String name) throws Exception {
    Bitmap bitmap = automation.takeScreenshot();
    if (bitmap == null) throw new AssertionError("Could not capture " + name);
    try (FileOutputStream file = new FileOutputStream(new File(c.getExternalFilesDir(null), name))) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)) throw new IOException("Could not save " + name);
    } finally {
      bitmap.recycle();
    }
  }

  private void runVerify(Context c, JSONObject report) throws Exception {
    Store store = Store.get(c);
    DnsController dns = DnsController.get(c);
    LanController lan = LanController.get(c);

    // 1. Real Android Keystore TLS server + /v1/hello + approval-v2 + snapshot
    lan.enable();
    lan.ensure();
    Thread.sleep(500);
    String ep = "127.0.0.1:" + LanController.PORT;
    LanClient.Response hello = LanClient.request(ep, null, null, "/v1/hello", null);
    report.put("tls_hello_kind", hello.json.optString("kind"));
    if (!"phone".equals(hello.json.optString("kind"))) throw new AssertionError("Expected kind=phone");

    // Unpaired snapshot must fail 401
    boolean deniedUnpaired = false;
    try {
      LanClient.request(ep, hello.fingerprint, null, "/v1/snapshot", null);
    } catch (IOException expected) {
      deniedUnpaired = true;
    }
    report.put("unpaired_snapshot_denied", deniedUnpaired);
    if (!deniedUnpaired) throw new AssertionError("Unpaired snapshot should be denied");

    // Full approval-v2 commit-reveal handshake against loopback phone TLS server
    String nonce = PairingCode.token();
    LanClient.Response req = LanClient.request(ep, hello.fingerprint, null, "/v2/pair/request",
        new JSONObject().put("commitment", PairingCode.hash(nonce)).put("manager_name", "Test Laptop"));
    String reqId = req.json.getString("request_id");
    String pollSecret = req.json.getString("poll_secret");
    String serverNonce = req.json.getString("server_nonce");
    LanClient.Response rev = LanClient.request(ep, hello.fingerprint, null, "/v2/pair/reveal",
        new JSONObject().put("request_id", reqId).put("poll_secret", pollSecret).put("client_nonce", nonce));
    String expectedCode = ApprovalPairing.comparison(hello.fingerprint, nonce, serverNonce);
    boolean codeMatch = expectedCode.equals(rev.json.optString("code"));
    report.put("approval_v2_code_match", codeMatch);
    if (!codeMatch) throw new AssertionError("Approval code mismatch");

    lan.approve(reqId);
    LanClient.Response status = LanClient.request(ep, hello.fingerprint, null, "/v2/pair/status",
        new JSONObject().put("request_id", reqId).put("poll_secret", pollSecret));
    String token = status.json.getString("token");
    LanClient.Response snap = LanClient.request(ep, hello.fingerprint, token, "/v1/snapshot", null);
    boolean snapOk = "personal".equals(snap.json.optString("scope")) && snap.json.has("spans") && snap.json.has("dns");
    report.put("authenticated_snapshot_ok", snapOk);
    if (!snapOk) throw new AssertionError("Snapshot missing expected fields");
    verifyRelayClockCorrection(c, report);
    verifyPauseProjectionAfterFailedRefresh(c, report);
    verifyPauseLimitRelayFreshness(c, report);
    PhoneAskChecks.verify(c, report);

    // 2. Private DNS Option 1 instant snap-back check (restores original DNS afterward!)
    boolean dnsPerm = dns.permitted();
    report.put("dns_permitted", dnsPerm);
    if (dnsPerm) {
      String origMode = Settings.Global.getString(c.getContentResolver(), DnsController.MODE);
      String origHost = Settings.Global.getString(c.getContentResolver(), DnsController.HOST);
      try {
        dns.connect("f0c057");
        MonitorService.ensure(c);
        Thread.sleep(1000);
        // Simulate user flipping Private DNS to "off" in Android Settings
        Settings.Global.putString(c.getContentResolver(), DnsController.MODE, "off");
        boolean snappedBack = false;
        for (int i = 0; i < 20; i++) {
          Thread.sleep(150);
          if ("hostname".equals(Settings.Global.getString(c.getContentResolver(), DnsController.MODE))
              && "f0c057.dns.nextdns.io".equals(Settings.Global.getString(c.getContentResolver(), DnsController.HOST))) {
            snappedBack = true;
            break;
          }
        }
        report.put("private_dns_instant_snapback", snappedBack);
        if (!snappedBack) throw new AssertionError("Private DNS did not snap back after tampering");
        verifyDnsPauseTimer(c, dns, report);
      } finally {
        try {
          dns.disconnect();
        } finally {
          Settings.Global.putString(c.getContentResolver(), DnsController.MODE, origMode);
          Settings.Global.putString(c.getContentResolver(), DnsController.HOST, origHost);
        }
      }
    }

    // 3. Policy ratchet on Android SharedPreferences
    PausePolicy.UpdateOutcome loosen = store.updatePolicyInt("wait_seconds", 10);
    boolean ratchetQueued = !loosen.appliedNow && store.policyConfig().waitSeconds == 15
        && store.pendingPolicyChanges().containsKey("wait_seconds");
    store.cancelPendingPolicy("wait_seconds");
    report.put("policy_ratchet_ok", ratchetQueued);
    if (!ratchetQueued) throw new AssertionError("Policy ratchet failed");

    // 4. Widget bitmaps render cleanly without crash
    Bitmap barBmp = WidgetUpdater.renderSplitBarBitmap(252 * 60_000L, 98 * 60_000L);
    Bitmap ringBmp = WidgetUpdater.renderRingBitmap(72);
    report.put("widget_bitmaps_ok", barBmp.getWidth() > 0 && ringBmp.getWidth() > 0);

    // 5. Built-in updater verifies installed APK's real package name, versionCode, SHA-256, and signing certificate
    File installedApk = new File(c.getApplicationInfo().sourceDir);
    String installedSha = UpdateManifest.sha256Hex(installedApk);
    UpdateManifest selfManifest = new UpdateManifest(
        1, "nightly", "android", c.getPackageName(),
        PhoneUpdater.get(c).installedVersionCode(),
        BuildInfo.VERSION_NAME, BuildInfo.BUILD_ID, BuildInfo.COMMIT_SHA,
        "verify self APK", BuildInfo.BUILT_AT,
        "focus-phone.apk", installedApk.length(), installedSha);
    PhoneUpdater.verifyApkForInstall(c, installedApk, selfManifest);
    report.put("updater_apk_signature_verify_ok", true);

    // Cleanup test manager token
    lan.disconnectAll();
    lan.enable();
  }

  private void verifyRelayClockCorrection(Context c, JSONObject report) throws Exception {
    DeviceHub hub = DeviceHub.get(c);
    List<DeviceHub.PairedDevice> before = hub.list();
    try {
      JSONObject laptop = new JSONObject().put("device_id", "test-relay").put("now", 20).put("snapshot_revision", 20);
      JSONObject tv = new JSONObject().put("device_id", "test-relayed-tv").put("now", 14);
      // A cache written before relay-source tracking contains no source ID.
      DeviceHub.PairedDevice legacy = new DeviceHub.PairedDevice("test-relayed-tv", "tv", "TV", "", "", "");
      legacy.relayed = true;
      legacy.snapshot = tv;
      List<DeviceHub.PairedDevice> seeded = new ArrayList<>(before);
      seeded.add(legacy);
      hub.seedPreviewDataForTest(seeded);
      hub.ingestSyncedPeer(laptop, null, null, "phone");
      JSONObject latestLaptop = new JSONObject(laptop.toString()).put("now", 18).put("snapshot_revision", 22);
      hub.ingestSyncedPeer(latestLaptop, null, null, "phone");
      JSONObject laterLaptop = new JSONObject(laptop.toString()).put("now", 19).put("snapshot_revision", 21);
      JSONObject correctedTv = new JSONObject(tv.toString()).put("now", 13);
      hub.ingestSyncedPeer(laterLaptop, null, new JSONArray().put(correctedTv), "phone");
      hub.ingestSyncedPeer(laptop, null, new JSONArray().put(tv), "phone");
      JSONObject delayedLegacy = new JSONObject().put("device_id", "test-relay").put("now", 99);
      hub.ingestSyncedPeer(delayedLegacy, null, new JSONArray().put(tv), "phone");
      if (hub.findById("test-relayed-tv").snapshot.optLong("now") != 13) {
        throw new AssertionError("A delayed relay must not overwrite the clock-corrected snapshot");
      }
      if (hub.findById("test-relay").snapshot.optLong("snapshot_revision") != 22) {
        throw new AssertionError("A relayed update must not overwrite the newer laptop snapshot");
      }
      JSONObject oldLaptop = new JSONObject().put("device_id", "test-old-relay").put("now", 20);
      JSONObject oldTv = new JSONObject().put("device_id", "test-old-tv").put("now", 14);
      hub.ingestSyncedPeer(oldLaptop, null, new JSONArray().put(oldTv), "phone");
      hub.ingestSyncedPeer(new JSONObject(oldLaptop.toString()).put("now", 22), null, null, "phone");
      hub.ingestSyncedPeer(new JSONObject(oldLaptop.toString()).put("now", 21), null,
          new JSONArray().put(new JSONObject(oldTv.toString()).put("now", 13)), "phone");
      hub.ingestSyncedPeer(oldLaptop, null, new JSONArray().put(oldTv), "phone");
      if (hub.findById("test-old-tv").snapshot.optLong("now") != 13) {
        throw new AssertionError("An older laptop must still deliver updates after a TV clock correction");
      }
      if (hub.findById("test-old-relay").snapshot.optLong("now") != 22) {
        throw new AssertionError("An older relay delivery must not replace a newer laptop snapshot");
      }

      DeviceHub.PairedDevice relay = hub.findById("test-relay");
      JSONObject downgraded = new JSONObject().put("device_id", "test-relay").put("now", 22);
      JSONObject downgradePush = new JSONObject(downgraded.toString()).put("now", 21);
      JSONObject downgradeTv = new JSONObject(tv.toString()).put("now", 12);
      Method adopt = DeviceHub.class.getDeclaredMethod(
          "adoptSnapshot", DeviceHub.PairedDevice.class, JSONObject.class, JSONObject.class);
      adopt.setAccessible(true);
      if (!Boolean.TRUE.equals(adopt.invoke(null, relay, downgraded, relay.snapshot))) {
        throw new AssertionError("An uncontested direct response must confirm downgrade");
      }
      hub.ingestSyncedPeer(downgradePush, null, new JSONArray().put(downgradeTv), "phone");
      hub.ingestSyncedPeer(laterLaptop, null, new JSONArray().put(tv), "phone");
      hub.ingestSyncedPeer(latestLaptop, null, new JSONArray().put(tv), "phone");
      if (relay.snapshot != downgraded || hub.findById("test-relayed-tv").snapshot.optLong("now") != 12) {
        throw new AssertionError("A downgrade must admit new legacy relay data and reject in-flight revisions");
      }
      JSONObject upgraded = new JSONObject(laptop.toString()).put("now", 23).put("snapshot_revision", 23);
      hub.ingestSyncedPeer(upgraded, null, new JSONArray().put(tv), "phone");
      if (relay.snapshot.optLong("snapshot_revision") != 23
          || hub.findById("test-relayed-tv").snapshot.optLong("now") != 14) {
        throw new AssertionError("A revision above the previous range must resume ordered updates");
      }
      report.put("relay_clock_correction_and_stale_update", true);
    } finally {
      hub.seedPreviewDataForTest(before);
    }
  }

  private void verifyPauseProjectionAfterFailedRefresh(Context c, JSONObject report) throws Exception {
    DeviceHub hub = DeviceHub.get(c);
    List<DeviceHub.PairedDevice> before = hub.list();
    String peer = "test-offline-tv";
    long sampledAt = System.currentTimeMillis();
    long started = sampledAt - 54 * 60_000 - 59_000;
    JSONObject reciprocal = new JSONObject().put("address", "127.0.0.1:1")
        .put("fingerprint", "0".repeat(64)).put("token", "0".repeat(64));
    JSONObject snapshot = new JSONObject().put("device_id", peer).put("kind", "tv")
        .put("now", sampledAt).put("snapshot_revision", 1)
        .put("dns", new JSONObject().put("pause_until", sampledAt + 60_000))
        .put("pauses", new JSONArray().put(new JSONObject().put("t", started)
            .put("end", sampledAt).put("kind", "dns").put("end_kind", JSONObject.NULL)));
    Method combined = DeviceHub.class.getDeclaredMethod("combinedPauses", long.class, long.class, boolean.class);
    combined.setAccessible(true);
    try {
      hub.ingestSyncedPeer(snapshot, reciprocal, null, "phone");
      long submitAt = sampledAt + 2000;
      if (remotePauseEnd(combined, hub, peer, started, submitAt) != submitAt) {
        throw new AssertionError("A confirmed live pause should count through submit time");
      }
      if (hub.refreshOne(peer) || !hub.findById(peer).online) {
        throw new AssertionError("Expected a failed refresh inside the online grace period");
      }
      if (remotePauseEnd(combined, hub, peer, started, submitAt) != sampledAt) {
        throw new AssertionError("A failed refresh must use only the recorded pause end");
      }
      hub.ingestSyncedPeer(new JSONObject(snapshot.toString()).put("snapshot_revision", 2),
          reciprocal, null, "phone");
      if (remotePauseEnd(combined, hub, peer, started, submitAt) != submitAt) {
        throw new AssertionError("A fresh update should resume live projection");
      }
      report.put("pause_projection_stops_after_failed_refresh", true);
    } finally {
      hub.seedPreviewDataForTest(before);
    }
  }

  @SuppressWarnings("unchecked")
  private long remotePauseEnd(Method combined, DeviceHub hub, String peer, long from, long to) throws Exception {
    List<InsightsEngine.PauseRecord> pauses =
        (List<InsightsEngine.PauseRecord>) combined.invoke(hub, from, to, true);
    return pauses.stream().filter(pause -> pause.deviceId.equals(peer)).findFirst().orElseThrow().ended;
  }

  private void verifyPauseLimitRelayFreshness(Context c, JSONObject report) throws Exception {
    DeviceHub hub = DeviceHub.get(c);
    List<DeviceHub.PairedDevice> before = hub.list();
    DeviceHub.PairedDevice laptop = new DeviceHub.PairedDevice("test-laptop", "laptop", "Laptop", "", "", "");
    DeviceHub.PairedDevice tv = new DeviceHub.PairedDevice("test-tv", "tv", "TV", "", "", "");
    Method adoptDirect = DeviceHub.class.getDeclaredMethod("adoptDirectSnapshot", DeviceHub.PairedDevice.class,
        JSONObject.class, JSONObject.class, long.class);
    adoptDirect.setAccessible(true);
    Method relay = DeviceHub.class.getDeclaredMethod("relayAllowance", DeviceHub.PairedDevice.class, long.class, long.class);
    relay.setAccessible(true);
    long now = System.currentTimeMillis();
    long earlyRequest = SystemClock.elapsedRealtimeNanos();
    JSONObject tvSnapshot = new JSONObject().put("pause_allowance_generation", "new-pairing").put("now", now);
    JSONObject oldLaptopSnapshot = new JSONObject().put("device_id", laptop.id).put("kind", "laptop").put("pause_daily_base", 90)
        .put("snapshot_revision", 1).put("now", now + 60_000);
    try {
      hub.seedPreviewDataForTest(Arrays.asList(laptop, tv));
      adoptDirect.invoke(null, tv, tvSnapshot, null, SystemClock.elapsedRealtimeNanos());
      adoptDirect.invoke(null, laptop, oldLaptopSnapshot, null, earlyRequest);
      JSONObject cached = ((JSONObject) relay.invoke(hub, tv, now - 60_000, now)).getJSONObject(laptop.id);
      if (cached.has("generation")) {
        throw new AssertionError("An older request must not certify a cached limit, even with a fast clock");
      }
      JSONObject freshLaptopSnapshot = new JSONObject(oldLaptopSnapshot.toString())
          .put("snapshot_revision", 2).put("now", now - 60_000);
      adoptDirect.invoke(null, laptop, freshLaptopSnapshot, oldLaptopSnapshot, SystemClock.elapsedRealtimeNanos());
      JSONObject fresh = ((JSONObject) relay.invoke(hub, tv, now - 60_000, now)).getJSONObject(laptop.id);
      if (!"new-pairing".equals(fresh.optString("generation"))) {
        throw new AssertionError("A newly requested limit must relay even when the source clock is slow");
      }
      JSONObject pushed = new JSONObject(freshLaptopSnapshot.toString())
          .put("snapshot_revision", 3).put("pause_daily_base", 75);
      hub.ingestSyncedPeer(pushed, null, null, "phone");
      JSONObject updated = ((JSONObject) relay.invoke(hub, tv, now - 60_000, now)).getJSONObject(laptop.id);
      if (!"new-pairing".equals(updated.optString("generation")) || updated.optInt("base") != 75) {
        throw new AssertionError("A newer laptop revision must retain the direct observation");
      }
      JSONObject replacement = new JSONObject(tvSnapshot.toString()).put("pause_allowance_generation", "another-pairing");
      adoptDirect.invoke(null, tv, replacement, tvSnapshot, SystemClock.elapsedRealtimeNanos());
      JSONObject revoked = ((JSONObject) relay.invoke(hub, tv, now - 60_000, now)).getJSONObject(laptop.id);
      if (revoked.has("generation")) throw new AssertionError("A second revocation must invalidate the prior sample");
      long requestedAt = SystemClock.elapsedRealtimeNanos();
      JSONObject response = new JSONObject(pushed.toString()).put("snapshot_revision", 4);
      JSONObject newer = new JSONObject(response.toString()).put("snapshot_revision", 5).put("pause_daily_base", 30);
      hub.ingestSyncedPeer(newer, null, null, "phone");
      adoptDirect.invoke(null, laptop, response, pushed, requestedAt);
      JSONObject afterRace = ((JSONObject) relay.invoke(hub, tv, now - 60_000, now)).getJSONObject(laptop.id);
      if (!"another-pairing".equals(afterRace.optString("generation")) || afterRace.optInt("base") != 30) {
        throw new AssertionError("A direct reply must confirm a newer revision received during the request");
      }
      report.put("relayed_limits_follow_pairing_without_wall_clocks", true);
    } finally {
      hub.seedPreviewDataForTest(before);
    }
  }

  private void verifyDnsPauseTimer(Context c, DnsController dns, JSONObject report) throws Exception {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    await(() -> pauseTimer(nm) == null, "Timer visible before a phone pause");
    dns.pause(60_000, "Test the phone timer", "");
    await(() -> pauseTimer(nm) != null, "Phone pause did not post a timer");
    Notification timer = pauseTimer(nm);
    long remaining = timer.when - System.currentTimeMillis();
    if (!timer.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        || !timer.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        || Math.abs(remaining - dns.remainingMs()) > 1_000
        || (timer.flags & Notification.FLAG_ONGOING_EVENT) == 0) {
      throw new AssertionError("Timer should count down to the phone's DNS deadline");
    }
    if (Build.VERSION.SDK_INT >= 36) {
      if (!timer.extras.getBoolean("android.requestPromotedOngoing")
          || !Notification.ProgressStyle.class.getName().equals(
              timer.extras.getString(Notification.EXTRA_TEMPLATE))
          || timer.extras.getBoolean(Notification.EXTRA_COLORIZED)) {
        throw new AssertionError("Android 16 timer should be a native progress Live Update");
      }
      report.put("dns_live_update_eligible", timer.hasPromotableCharacteristics());
      report.put("dns_live_update_allowed", nm.canPostPromotedNotifications());
      report.put("dns_live_update_promoted", (timer.flags & Notification.FLAG_PROMOTED_ONGOING) != 0);
    }
    PendingIntent turnOn = timer.actions[0].actionIntent;
    turnOn.send();
    await(() -> dns.configured() && pauseTimer(nm) == null,
        "Turn on now should restore DNS and dismiss the timer");
    report.put("dns_pause_timer_turn_on_now", true);

    dns.pause(5_000, "Test automatic expiry", "");
    await(() -> pauseTimer(nm) != null, "Second phone pause did not post a timer");
    long secondDeadline = dns.deadline();
    CountDownLatch delivered = new CountDownLatch(1);
    turnOn.send(0, (pending, intent, code, data, extras) -> delivered.countDown(), null);
    if (!delivered.await(3, TimeUnit.SECONDS)) {
      throw new AssertionError("Previous notification action was not delivered");
    }
    if (dns.deadline() != secondDeadline || dns.configured()) {
      throw new AssertionError("A previous notification action ended the new pause");
    }
    await(() -> dns.configured() && pauseTimer(nm) == null,
        "Expiry should restore DNS and dismiss the timer");
    report.put("dns_pause_timer_expiry", true);
  }

  private static Notification pauseTimer(NotificationManager nm) {
    for (android.service.notification.StatusBarNotification active : nm.getActiveNotifications()) {
      Notification notification = active.getNotification();
      if ("focus_dns_pause".equals(notification.getChannelId())
          || "focus_dns_live".equals(notification.getChannelId())) {
        return notification;
      }
    }
    return null;
  }

  private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
    long deadline = SystemClock.elapsedRealtime() + 15_000;
    while (!condition.getAsBoolean()) {
      if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(failure);
      Thread.sleep(100);
    }
  }

  private void seedShowcase(Context c, JSONObject report) throws Exception {
    if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) {
      throw new AssertionError("Emulator only");
    }
    Store store = Store.get(c);
    SQLiteDatabase db = store.getWritableDatabase();
    long now = System.currentTimeMillis();
    long day = 86_400_000L, minute = 60_000L;
    long today = InsightsEngine.dayStart(now, TimeZone.getDefault());
    long morning = today + 9 * 60 * minute;
    insertSpan(db, morning, morning + 27 * minute, "com.android.chrome", "catalog.example");
    insertSpan(db, morning + 32 * minute, morning + 49 * minute, "com.google.android.apps.photos", "");
    insertSpan(db, morning + 54 * minute, morning + 65 * minute, "com.android.chrome", "discord.com");
    insertPause(db, store, "seed-swap", morning + 54 * minute, 11 * minute, 15,
        "Find the seed swap time on Discord");
    insertSpan(db, morning - 2 * day, morning - 2 * day + 34 * minute, "com.android.chrome", "catalog.example");
    insertSpan(db, today - 3 * day - 8 * minute, today - 3 * day + 9 * minute, "com.android.chrome", "maps.example");
    insertSpan(db, morning - 5 * day, morning - 5 * day + 43 * minute, "com.google.android.apps.photos", "");

    JSONArray laptopSpans = new JSONArray();
    addSpan(laptopSpans, morning, morning + 39 * minute, "VS Code", "VS Code");
    addSpan(laptopSpans, morning - day, morning - day + 45 * minute, "VS Code", "VS Code");
    DeviceHub.PairedDevice laptop = new DeviceHub.PairedDevice(
        "seed-catalog-laptop", "laptop", "Juniper Laptop",
        "192.0.2.10:47832", "a".repeat(64), "b".repeat(64));
    laptop.online = false;
    laptop.lastSeen = morning;
    laptop.snapshot = new JSONObject().put("kind", "laptop").put("now", morning + 70 * minute)
        .put("spans", laptopSpans).put("pauses", new JSONArray());
    DeviceHub.get(c).seedPreviewDataForTest(Collections.singletonList(laptop));

    PhoneUpdater.get(c).setEnabled(false);
    PhoneUpdater.get(c).seedPreviewUpdateForTest(new UpdateManifest(
        1, "nightly", "android", c.getPackageName(),
        PhoneUpdater.get(c).installedVersionCode() + 100L,
        "0.2.0-nightly.demo", "222222b", "222222b000000000000000000000000000000000",
        "fix: retain the selected activity tab", "2026-02-12T05:30:00Z",
        "focus-phone.apk", 4_404_019L, "0".repeat(64),
        Collections.singletonList(new String[] {"Activity tabs", "Return to the selected activity tab."}),
        ""));
    report.put("seeded", true);
  }

  private void insertPause(SQLiteDatabase db, Store store, String id, long started, long durMs, int minutes, String reason) {
    ContentValues pv = new ContentValues();
    pv.put("id", id);
    pv.put("pkg", DnsController.KEY);
    pv.put("started", started);
    pv.put("deadline_wall", started + minutes * 60_000L);
    pv.put("deadline_elapsed", 0);
    pv.put("boot", store.boot());
    pv.put("reason", reason);
    pv.put("after_plan", "");
    pv.put("ended", started + durMs);
    pv.put("end_kind", "dns_resumed");
    db.insert("pauses", null, pv);
  }

  private void insertSpan(SQLiteDatabase db, long s, long e, String pkg, String dom) {
    ContentValues v = new ContentValues();
    v.put("s", s);
    v.put("e", e);
    v.put("pkg", pkg);
    v.put("domain", dom);
    db.insert("spans", null, v);
  }

  private void addSpan(JSONArray arr, long s, long e, String app, String label) throws JSONException {
    arr.put(new JSONObject().put("s", s).put("e", e).put("app", app).put("label", label));
  }

}

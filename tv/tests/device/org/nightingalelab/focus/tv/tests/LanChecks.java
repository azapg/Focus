package org.nightingalelab.focus.tv.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.tv.*;

/** Disposable-emulator API checks. Does not write DNS settings or launch media apps. */
public final class LanChecks extends Instrumentation {
  private static final String FIXTURE = "org.nightingalelab.focus.tv.fixture";
  private final JSONObject result = new JSONObject();
  private String pin, token;
  private int status;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void check(boolean ok, String label) throws Exception {
    if (!ok) throw new AssertionError(label);
    result.put(label, true);
  }
  private JSONObject request(String path, JSONObject body, String auth, boolean origin) throws Exception {
    final String expected = pin;
    if (expected == null && !path.equals("/v1/hello")) throw new AssertionError("Pin required");
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(null, new TrustManager[] {new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      public void checkClientTrusted(X509Certificate[] c, String a) {}
      public void checkServerTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException {
        try {
          String found = PairingCode.hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()));
          if (expected != null && !expected.equals(found)) throw new java.security.cert.CertificateException("Pin mismatch");
          pin = found;
        } catch (GeneralSecurityException e) { throw new java.security.cert.CertificateException(e); }
      }
    }}, new SecureRandom());
    try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", LanController.PORT)) {
      socket.setSoTimeout(8000); socket.startHandshake();
      byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
      String headers = (body == null ? "GET" : "POST") + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\n";
      if (auth != null) headers += "Authorization: Bearer " + auth + "\r\n";
      if (origin) headers += "Origin: https://example.invalid\r\n";
      OutputStream out = socket.getOutputStream();
      out.write((headers + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(data); out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream head = new ByteArrayOutputStream(); int tail = 0;
      while (tail != 0x0d0a0d0a) { int b = in.read(); if (b < 0 || head.size() > 8192) throw new IOException("Incomplete response"); head.write(b); tail = tail << 8 | b; }
      String[] lines = new String(head.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
      status = Integer.parseInt(lines[0].split(" ")[1]); int length = 0;
      for (String line : lines) if (line.startsWith("Content-Length: ")) length = Integer.parseInt(line.substring(16).trim());
      byte[] response = new byte[length]; int read = 0;
      while (read < length) { int n = in.read(response, read, length - read); if (n < 0) throw new IOException("Incomplete body"); read += n; }
      return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
  }
  private JSONObject command(JSONObject value) throws Exception { return request("/v1/command", value, token, false); }
  @Override public void onStart() {
    int code = Activity.RESULT_CANCELED;
    Context c = getTargetContext(); LanController lan = LanController.get(c); Store store = Store.get(c);
    Rules prior = null;
    String habitId = null;
    SharedPreferences prefs = c.getSharedPreferences("devices", 0);
    boolean enabled = prefs.getBoolean("enabled", false);
    Set<String> managers = new HashSet<>(prefs.getStringSet("managers", Collections.emptySet()));
    try {
      if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) throw new AssertionError("Emulator only");
      // Instrumentation restarts the target process; rebind only our emulator's service.
      UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      for (String shell : new String[] {
          "settings delete secure enabled_accessibility_services",
          "settings put secure accessibility_enabled 0",
          "settings put secure enabled_accessibility_services org.nightingalelab.focus.tv/.RestrictionService",
          "settings put secure accessibility_enabled 1"}) {
        try (ParcelFileDescriptor fd = automation.executeShellCommand(shell);
             InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
          byte[] buffer = new byte[512]; while (output.read(buffer) != -1) {}
        }
        // Let AccessibilityManager observe removal before restoring the component.
        // Otherwise Android can coalesce the writes and keep its crashed-service entry.
        SystemClock.sleep(1000);
      }
      long bound = SystemClock.elapsedRealtime() + 10000;
      while (!RestrictionService.connected && SystemClock.elapsedRealtime() < bound) SystemClock.sleep(100);
      check(RestrictionService.connected, "accessibility_bound");
      check(AppCatalog.allowed(c, FIXTURE), "fixture_in_catalog");
      prior = store.rule(FIXTURE);
      String pairingCode = lan.beginPairing();
      JSONObject hello = request("/v1/hello", null, null, false);
      check(status == 200 && hello.getInt("version") == 1, "android_keystore_tls");
      check(lan.shortFingerprint().replace(" ", "").equalsIgnoreCase(pin.substring(0,16)), "identity_matches_tv_display");
      request("/v1/snapshot", null, null, false); check(status == 401, "unpaired_history_denied");
      request("/v1/command", new JSONObject().put("action", "dns_resume"), null, false); check(status == 401, "unpaired_command_denied");
      long discoveryDeadline=SystemClock.elapsedRealtime()+10000;
      while (!lan.discovering() && SystemClock.elapsedRealtime()<discoveryDeadline) SystemClock.sleep(100);
      check(lan.discovering() && hello.optString("pairing").equals("approval-v2"), "nearby_service_registered");
      String nonce=PairingCode.token();
      JSONObject approval=request("/v2/pair/request",new JSONObject().put("commitment",PairingCode.hash(nonce)).put("manager_name","Test laptop"),null,false);
      check(status==200 && !approval.has("token"),"approval_start_has_no_credential");
      JSONObject credentials=new JSONObject().put("request_id",approval.getString("request_id")).put("poll_secret",approval.getString("poll_secret"));
      JSONObject reveal=new JSONObject(credentials.toString()).put("client_nonce",nonce);
      JSONObject waiting=request("/v2/pair/reveal",reveal,null,false);
      String comparison=ApprovalPairing.comparison(pin,nonce,approval.getString("server_nonce"));
      check(status==200 && waiting.getString("code").equals(comparison) && waiting.getString("status").equals("waiting"),"approval_matching_number");
      check(!waiting.has("token"),"approval_waiting_has_no_credential");
      request("/v2/pair/status",new JSONObject(credentials.toString()).put("poll_secret",PairingCode.token()),null,false);
      check(status==400,"approval_wrong_poll_secret_denied");
      request("/v1/snapshot",null,null,false);check(status==401,"approval_pending_history_denied");
      android.view.accessibility.AccessibilityNodeInfo allow=null;
      long promptDeadline=SystemClock.elapsedRealtime()+10000;
      while (allow==null && SystemClock.elapsedRealtime()<promptDeadline) {
        android.view.accessibility.AccessibilityNodeInfo root=automation.getRootInActiveWindow();
        if(root!=null && c.getPackageName().contentEquals(root.getPackageName())) {
          java.util.List<android.view.accessibility.AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText("Allow");
          for(android.view.accessibility.AccessibilityNodeInfo node:found) if("Allow".contentEquals(node.getText()) && node.isClickable()) allow=node;
        }
        if(allow==null) SystemClock.sleep(150);
      }
      check(allow!=null,"native_tv_approval_prompt");
      automation.waitForIdle(300,5000);
      android.graphics.Rect target=new android.graphics.Rect(); allow.getBoundsInScreen(target);
      check(!target.isEmpty(),"local_tv_allow_target");
      long touch=SystemClock.uptimeMillis();
      for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(touch,SystemClock.uptimeMillis(),action,target.centerX(),target.centerY(),0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); sendPointerSync(event); event.recycle();
      }
      SystemClock.sleep(400);
      JSONObject approved=request("/v2/pair/status",credentials,null,false);
      check(status==200 && approved.getString("status").equals("approved") && approved.getString("token").matches("[a-f0-9]{64}"),"local_approval_issues_credential");
      request("/v1/snapshot",null,approved.getString("token"),false);check(status==200,"approved_credential_reads_snapshot");
      request("/v2/pair/cancel",credentials,null,false);
      request("/v1/snapshot",null,approved.getString("token"),false);check(status==401,"cancel_unclaimed_approval_revokes_credential");
      request("/v1/pair", new JSONObject().put("code", "wrong"), null, false); check(status == 403, "wrong_code_denied");
      JSONObject pair = request("/v1/pair", new JSONObject().put("code", pairingCode), null, false);
      token = pair.getString("token"); check(status == 200 && token.matches("[a-f0-9]{64}"), "pairing_credential_issued");
      check(prefs.getStringSet("managers", Collections.emptySet()).contains(PairingCode.hash(token)), "only_token_hash_persisted");
      request("/v1/pair", new JSONObject().put("code", pairingCode), null, false); check(status == 403, "pairing_code_single_use");
      JSONObject snap = request("/v1/snapshot", null, token, false);
      check(status == 200 && snap.getString("scope").equals("household") && snap.getJSONObject("permissions").getBoolean("usage"), "authenticated_snapshot");
      request("/v1/snapshot", null, token, true); check(status == 403, "browser_origin_denied");
      JSONObject rule = new JSONObject().put("action", "set_rule").put("package", FIXTURE).put("blocked", true);
      command(rule); check(status == 200 && store.rule(FIXTURE).blocked, "remote_block_persisted");
      command(new JSONObject().put("action", "set_rule").put("package", FIXTURE).put("daily_limit_ms", 60000).put("scheduled", true).put("from_minute", 1320).put("to_minute", 420));
      Rules r = store.rule(FIXTURE);
      check(status == 200 && r.blocked && r.dailyLimitMs == 60000 && r.scheduled && r.fromMinute == 1320 && r.toMinute == 420, "partial_rule_update_preserves_block");
      command(new JSONObject().put("action", "set_rule").put("package", FIXTURE).put("from_minute", 4_294_967_296L));
      check(status == 409 && store.rule(FIXTURE).fromMinute == 1320, "overflow_rule_denied");
      command(new JSONObject().put("action", "set_rule").put("package", c.getPackageName()).put("blocked", true)); check(status == 409, "protected_app_denied");
      command(new JSONObject().put("action", "app_pause").put("package", DnsController.KEY).put("minutes", 1).put("reason", "fixture")); check(status == 409 && store.pauseDeadline(DnsController.KEY) == 0, "dns_key_cannot_be_used_as_app_exception");
      command(new JSONObject().put("action", "app_pause").put("package", FIXTURE).put("minutes", 1).put("reason", " ")); check(status == 409, "empty_reason_denied");
      command(new JSONObject().put("action", "app_pause").put("package", FIXTURE).put("minutes", 1.5).put("reason", "fixture")); check(status == 409, "fractional_duration_denied");
      command(new JSONObject().put("action", "app_pause").put("package", FIXTURE).put("minutes", 1).put("reason", "LAN fixture"));
      check(status == 200 && store.pauseDeadline(FIXTURE) > SystemClock.elapsedRealtime(), "remote_app_exception");
      command(new JSONObject().put("action", "app_resume").put("package", FIXTURE)); check(status == 200 && store.pauseDeadline(FIXTURE) == 0, "remote_exception_end");
      // Exercise audit expiry without changing Android DNS settings.
      store.pause(DnsController.KEY, 1000, "LAN DNS audit fixture");
      android.content.ContentValues expired = new android.content.ContentValues();
      expired.put("deadline_elapsed", 0);
      store.getWritableDatabase().update("pauses", expired, "pkg=? AND ended IS NULL", new String[] {DnsController.KEY});
      store.expirePauses();
      try (android.database.Cursor row = store.getReadableDatabase().rawQuery(
          "SELECT ended FROM pauses WHERE pkg=? AND reason=?", new String[] {DnsController.KEY, "LAN DNS audit fixture"})) {
        check(row.moveToFirst() && row.isNull(0), "dns_off_audit_waits_for_confirmed_restore");
      }
      store.endPause(DnsController.KEY, "test_cleanup");
      store.getWritableDatabase().delete("pauses", "pkg=? AND reason=?", new String[] {DnsController.KEY, "LAN DNS audit fixture"});
      check(!DnsController.get(c).permitted(), "dns_settings_permission_absent");
      command(new JSONObject().put("action", "dns_connect").put("profile", "a1b2c3")); check(status == 409 && !DnsController.get(c).enabled(), "dns_permission_denial_is_honest");
      HabitStore habits = HabitStore.get(c);
      String previousGeneration = habits.allowanceGeneration();
      long now = System.currentTimeMillis();
      int day = Habits.day(now);
      long effectiveAt = now + 60_000;
      JSONObject limit = new JSONObject().put("minutes", 60)
          .put("pending", new JSONObject().put("value", 90).put("from", effectiveAt));
      JSONObject laptop = new JSONObject().put("kind", "laptop").put("now", now)
          .put("limit", limit).put("direct", true).put("day", day).put("off", 0).put("revision", 9);
      request("/v1/habits", new JSONObject().put("allowance", new JSONObject().put("test-laptop", laptop)), token, false);
      check(status == 200 && habits.dailyBase(effectiveAt) == 90, "tv_caches_manager_pause_schedule");
      lan.disconnectAll();
      check(!lan.enabled() && lan.pairedCount() == 0, "revoke_all_disables_listener");
      check(habits.dailyBase(effectiveAt) == 60 && !c.getSharedPreferences("habit_allowance", 0).contains("limit"),
          "revoke_all_clears_cached_pause_schedule");
      String phoneCode = lan.beginPairing();
      request("/v1/snapshot", null, token, false); check(status == 401, "revoked_token_denied_after_restart");
      JSONObject phonePair = request("/v1/pair", new JSONObject().put("code", phoneCode), null, false);
      check(status == 200, "phone_pairs_after_revoke_all");
      String newToken = phonePair.getString("token");
      String generation = request("/v1/snapshot", null, newToken, false).getString("pause_allowance_generation");
      check(!generation.isEmpty() && !generation.equals(previousGeneration)
          && generation.equals(c.getSharedPreferences("habit_allowance", 0).getString("generation", "")),
          "tv_saves_new_pause_allowance_generation");
      JSONObject phone = new JSONObject().put("kind", "phone").put("now", now + 1)
          .put("base", 30).put("direct", true);
      JSONObject cachedLaptop = new JSONObject(laptop.toString()).put("direct", false)
          .put("generation", previousGeneration).put("now", now + 60_000);
      JSONObject relayed = new JSONObject().put("test-phone", phone).put("test-laptop", cachedLaptop);
      request("/v1/habits", new JSONObject().put("allowance", relayed), newToken, false);
      check(status == 200 && habits.dailyBase(effectiveAt) == 30, "revoked_laptop_limit_cannot_return_through_new_manager");
      cachedLaptop.put("generation", generation).put("now", now - 60_000);
      cachedLaptop.put("revision", 10);
      request("/v1/habits", new JSONObject().put("allowance", relayed), newToken, false);
      check(status == 200 && habits.dailyBase(effectiveAt) == 90, "fresh_relay_can_share_laptop_limit_after_revoke");
      JSONObject tightened = new JSONObject(cachedLaptop.toString())
          .put("revision", 11).put("now", now - 120_000).put("off", 5 * 60_000)
          .put("limit", new JSONObject().put("minutes", 30));
      relayed.put("test-laptop", tightened);
      request("/v1/habits", new JSONObject().put("allowance", relayed), newToken, false);
      check(status == 200 && habits.dailyBase(effectiveAt) == 30, "tv_accepts_new_limit_after_laptop_clock_correction");
      check(habits.peerDnsMs(day) == 5 * 60_000, "tv_accepts_new_usage_after_laptop_clock_correction");
      relayed.put("test-laptop", cachedLaptop);
      request("/v1/habits", new JSONObject().put("allowance", relayed), newToken, false);
      check(habits.dailyBase(effectiveAt) == 30 && habits.peerDnsMs(day) == 5 * 60_000,
          "delayed_laptop_sample_does_not_restore_old_limit_or_usage");
      java.lang.reflect.Constructor<HabitStore> constructor = HabitStore.class.getDeclaredConstructor(Context.class);
      constructor.setAccessible(true);
      HabitStore reloaded = constructor.newInstance(c);
      check(reloaded.remainingDnsMs(day, effectiveAt, 0) == 25 * 60_000,
          "tv_reloads_spent_pause_time");
      reloaded.exchange(new JSONObject().put("allowance", relayed));
      check(reloaded.remainingDnsMs(day, effectiveAt, 0) == 25 * 60_000,
          "tv_reloads_usage_revision_and_rejects_stale_sample");
      check(reloaded.remainingDnsMs(day + 1, effectiveAt + 86_400_000, 0) == 30 * 60_000,
          "yesterday_usage_does_not_reduce_tomorrow_allowance");

      String fixtureHabit = "TV pause allowance fixture";
      habits.save(null, fixtureHabit, 1, 0, -1, 5, false);
      habitId = habits.read().status(now).stream().filter(row -> fixtureHabit.equals(row.habit.name))
          .findFirst().orElseThrow().habit.id;
      JSONObject current = request("/v1/snapshot", null, newToken, false);
      JSONArray choices = current.getJSONObject("dns").getJSONArray("pause_choices");
      check(status == 200 && choices.toString().equals("[1,5,10]"), "tv_advertises_its_current_dns_pause_choices");
      JSONObject denied = request("/v1/command", new JSONObject().put("action", "dns_pause")
          .put("minutes", 30).put("reason", "TV allowance fixture"), newToken, false);
      check(status == 409 && denied.toString().contains("remaining allowance"),
          "remote_tv_pause_refused_when_minutes_exceed_allowance");
      habits.save(habitId, fixtureHabit, 1, 0, -1, 5, true);
      habitId = null;
      check(habits.read().status(now).isEmpty(), "tv_last_habit_removed");
      current = request("/v1/snapshot", null, newToken, false);
      choices = current.getJSONObject("dns").getJSONArray("pause_choices");
      check(status == 200 && choices.toString().equals("[1,5,10]"),
          "tv_keeps_configured_pause_choices_with_no_habits");
      denied = request("/v1/command", new JSONObject().put("action", "dns_pause")
          .put("minutes", 30).put("reason", "TV allowance fixture"), newToken, false);
      check(status == 409 && denied.toString().contains("remaining allowance"),
          "remote_tv_pause_still_refused_after_last_habit_is_deleted");
      habits.revokePauseLimit();
      code = Activity.RESULT_OK;
    } catch (Throwable e) {
      android.util.Log.w("FocusTV", "lan_check_failed", e);
      try { result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (JSONException ignored) {}
    } finally {
      try {
        if (habitId != null) HabitStore.get(c).save(habitId, "TV pause allowance fixture", 1, 0, -1, 5, true);
        if (prior != null) store.saveRule(FIXTURE, prior);
        store.endPause(FIXTURE, "test_cleanup");
        store.getWritableDatabase().delete("pauses", "pkg=? AND reason=?", new String[] {FIXTURE, "LAN fixture"});
        store.getWritableDatabase().delete("pauses", "pkg=? AND reason=?", new String[] {DnsController.KEY, "LAN DNS audit fixture"});
        lan.stopServer();
        prefs.edit().putBoolean("enabled", enabled).putStringSet("managers", managers).commit();
        if (enabled) lan.ensure();
        result.put("cleanup_complete", true);
      } catch (Throwable e) { code = Activity.RESULT_CANCELED; }
      Bundle report = new Bundle(); report.putString("result", result.toString()); finish(code, report);
    }
  }
}

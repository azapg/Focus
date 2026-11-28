package org.nightingalelab.focus.tv.tests;

import android.app.*;
import android.content.*;
import android.database.*;
import android.os.*;
import android.view.accessibility.*;
import java.util.*;
import org.json.*;
import org.nightingalelab.focus.tv.*;

/** Installed temporarily; signed instrumentation has no exported production command channel. */
public final class DeviceChecks extends Instrumentation {
  private static final String FIXTURE = "org.nightingalelab.focus.tv.fixture",
      YOUTUBE = "com.google.android.youtube.tv";
  private Bundle args;
  private Store store;
  private final JSONObject result = new JSONObject();
  private final Map<String, Rules> restore = new HashMap<>();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    this.args = args;
    start();
  }

  interface Condition {
    boolean get();
  }

  private void waitFor(Condition condition, String message, long max) {
    long end = SystemClock.elapsedRealtime() + max;
    while (SystemClock.elapsedRealtime() < end) {
      if (condition.get()) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError(message);
  }

  private void check(boolean value, String label) throws Exception {
    if (!value) throw new AssertionError(label);
    result.put(label, true);
  }

  private void checkpoint(String name) {
    Bundle b = new Bundle();
    b.putString("stage", name);
    sendStatus(1, b);
    SystemClock.sleep(2500);
  }

  private void open(String pkg) {
    Intent i = getTargetContext().getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
    if (i == null) throw new AssertionError("Missing app " + pkg);
    getTargetContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  private boolean blocked(String pkg) {
    return BlockActivity.visible && pkg.equals(BlockActivity.presentedPackage);
  }

  private void rule(String pkg, Rules r) {
    if (!restore.containsKey(pkg)) {
      Rules prior = store.rule(pkg);
      restore.put(pkg, prior);
      try {
        android.content.SharedPreferences prefs =
            getTargetContext().getSharedPreferences("device-test-rollback", 0);
        JSONObject backup = new JSONObject(prefs.getString("rules", "{}"));
        backup.put(
            pkg,
            new JSONObject()
                .put("blocked", prior.blocked)
                .put("limit", prior.dailyLimitMs)
                .put("scheduled", prior.scheduled)
                .put("from", prior.fromMinute)
                .put("to", prior.toMinute));
        if (!prefs.edit().putString("rules", backup.toString()).commit())
          throw new AssertionError("Rollback snapshot failed");
      } catch (JSONException e) {
        throw new AssertionError(e);
      }
    }
    store.saveRule(pkg, r);
  }

  private void rollback() throws Exception {
    android.content.SharedPreferences prefs =
        getTargetContext().getSharedPreferences("device-test-rollback", 0);
    JSONObject backup = new JSONObject(prefs.getString("rules", "{}"));
    java.util.Iterator<String> keys = backup.keys();
    while (keys.hasNext()) {
      String pkg = keys.next();
      JSONObject v = backup.getJSONObject(pkg);
      Rules r = new Rules();
      r.blocked = v.getBoolean("blocked");
      r.dailyLimitMs = v.getLong("limit");
      r.scheduled = v.getBoolean("scheduled");
      r.fromMinute = v.getInt("from");
      r.toMinute = v.getInt("to");
      store.saveRule(pkg, r);
    }
    prefs.edit().remove("rules").commit();
  }

  private AccessibilityNodeInfo ownRoot() {
    AccessibilityNodeInfo root =
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .getRootInActiveWindow();
    if (root == null || !getTargetContext().getPackageName().contentEquals(root.getPackageName()))
      throw new AssertionError("Expected Focus window");
    return root;
  }

  private void click(String label) {
    AccessibilityNodeInfo root = ownRoot();
    for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
      if (!label.contentEquals(n.getText())) continue;
      while (n != null && !n.isClickable()) n = n.getParent();
      if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
    }
    throw new AssertionError("Missing Focus control " + label);
  }

  private AccessibilityNodeInfo editable(AccessibilityNodeInfo n) {
    if (n.isEditable()) return n;
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo child = n.getChild(i);
      if (child != null) {
        AccessibilityNodeInfo found = editable(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void seedScreenshot() {
    if (!screenshotEmulator()) {
      throw new AssertionError("Emulator only");
    }
    long day = 86_400_000L, minute = 60_000L;
    long today = Store.dayStart(System.currentTimeMillis());
    seedSpan(today + 9 * 60 * minute, 37, "com.google.android.youtube.tv");
    seedSpan(today + 11 * 60 * minute, 24, "com.netflix.ninja");
    seedSpan(today - day + 12 * 60 * minute, 21, "com.google.android.youtube.tv");
    seedSpan(today - 3 * day + 12 * 60 * minute, 46, "com.netflix.ninja");
    seedSpan(today - 5 * day + 10 * 60 * minute, 18, "com.google.android.youtube.tv");
  }

  private void seedStoreScreenshot() {
    if (!screenshotEmulator()) {
      throw new AssertionError("Emulator only");
    }
    Rules blocked = new Rules();
    blocked.blocked = true;
    store.saveRule(FIXTURE, blocked);
  }

  private static boolean screenshotEmulator() {
    String model = Build.MODEL.toLowerCase(Locale.ROOT);
    String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
    return Build.FINGERPRINT.contains("generic") || model.contains("sdk")
        || hardware.equals("ranchu") || hardware.equals("goldfish");
  }

  private void seedSpan(long start, int minutes, String pkg) {
    ContentValues span = new ContentValues();
    span.put("s", start);
    span.put("e", start + minutes * 60_000L);
    span.put("pkg", pkg);
    store.getWritableDatabase().insertOrThrow("spans", null, span);
  }

  @Override
  public void onStart() {
    super.onStart();
    boolean keepRestart = false;
    boolean screenshot = "seed-screenshot".equals(args.getString("phase"));
    try {
      store = Store.get(getTargetContext());
      if (screenshot) {
        if ("store".equals(args.getString("flow"))) {
          AppCatalog.invalidate();
          seedStoreScreenshot();
        } else {
          seedScreenshot();
        }
        result.put("passed", true);
        return;
      }
      AppCatalog.invalidate();
      if ("cleanup".equals(args.getString("phase"))) {
        rollback();
        result.put("passed", true);
        return;
      }
      check(store.usageAllowed(), "usage_access");
      check(AppCatalog.allowed(getTargetContext(), FIXTURE), "fixture_is_restrictable");
      check(
          !AppCatalog.allowed(getTargetContext(), getTargetContext().getPackageName()),
          "focus_protected");
      check(
          !AppCatalog.allowed(getTargetContext(), "com.android.tv.settings"), "settings_protected");
      if ("restart-check".equals(args.getString("phase"))) {
        check(store.pauseDeadline(FIXTURE) > 0, "pause_survived_process_restart");
        check(store.usedToday(FIXTURE) > 0, "history_survived_process_restart");
        checkpoint("restart_verified");
      } else {
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        checkpoint("access_rebind");
        MonitorService.ensure(getTargetContext());
        waitFor(() -> RestrictionService.connected, "Accessibility service did not connect", 15000);
        check(true, "accessibility_connected");
        waitFor(
            () -> Store.heartbeat > 0 && SystemClock.elapsedRealtime() - Store.heartbeat < 5000,
            "Usage collector did not become ready",
            15000);
        check(true, "usage_collector_ready");
        Rules allow = new Rules();
        rule(FIXTURE, allow);
        open(FIXTURE);
        waitFor(() -> store.usedToday(FIXTURE) > 1500, "Fixture usage not recorded", 12000);
        check(true, "foreground_usage_persisted");
        long sample = store.usedToday(FIXTURE);
        result.put("synthetic_usage_ms", sample);
        check(sample < 30000, "usage_duration_not_inflated");
        long base = Store.dayStart(System.currentTimeMillis()) - 14 * 86400000L;
        android.content.ContentValues clipped = new android.content.ContentValues();
        clipped.put("s", base + 1000);
        clipped.put("e", base + 4000);
        clipped.put("pkg", FIXTURE);
        store.getWritableDatabase().insertOrThrow("spans", null, clipped);
        clipped.put("s", base + 5000);
        clipped.put("e", base + 7000);
        store.getWritableDatabase().insertOrThrow("spans", null, clipped);
        check(store.usage(base + 2000, base + 6000).get(FIXTURE) == 3000L, "sqlite_range_clipping");
        Rules blocked = new Rules();
        blocked.blocked = true;
        rule(FIXTURE, blocked);
        waitFor(() -> blocked(FIXTURE), "Fixture not blocked", 8000);
        check(true, "fixture_blocked");
        checkpoint("fixture_blocked");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(1000);
        check(editable(ownRoot()) != null, "remote_select_opens_pause_form");
        click("Allow for 5 minutes");
        SystemClock.sleep(500);
        check(store.pauseDeadline(FIXTURE) == 0, "empty_reason_rejected");
        AccessibilityNodeInfo input = editable(ownRoot());
        if (input == null) throw new AssertionError("Reason field missing");
        Bundle text = new Bundle();
        text.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "Synthetic TV installation test");
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
          throw new AssertionError("Reason input failed");
        click("Allow for 5 minutes");
        waitFor(() -> store.pauseDeadline(FIXTURE) > 0, "UI pause not saved", 5000);
        check(
            store.pauseDeadline(FIXTURE) - SystemClock.elapsedRealtime() > 290000,
            "duration_selected_by_ui");
        waitFor(
            () -> FIXTURE.equals(RestrictionService.timerPackage), "Corner countdown absent", 8000);
        check(true, "corner_countdown_with_fixture_foreground");
        checkpoint("countdown");
        store.pause(FIXTURE, 5000, "Synthetic expiry test");
        open(FIXTURE);
        waitFor(() -> blocked(FIXTURE), "Expiry did not reblock", 12000);
        check(store.pauseDeadline(FIXTURE) == 0, "deadline_expired");
        check(RestrictionService.timerPackage.isEmpty(), "countdown_removed_on_expiry");
        check(true, "automatic_reblock_with_focus_backgrounded");
        checkpoint("expired");
        Rules daily = new Rules();
        daily.dailyLimitMs = 1000;
        rule(FIXTURE, daily);
        open(FIXTURE);
        waitFor(() -> blocked(FIXTURE), "Daily limit not enforced", 8000);
        check(true, "daily_limit_enforced");
        Rules schedule = new Rules();
        schedule.scheduled = true;
        schedule.fromMinute = (Store.minute() + 1439) % 1440;
        schedule.toMinute = (Store.minute() + 2) % 1440;
        rule(FIXTURE, schedule);
        open(FIXTURE);
        waitFor(() -> blocked(FIXTURE), "Schedule not enforced", 8000);
        check(true, "schedule_enforced");
        rule(FIXTURE, blocked);
        if (AppCatalog.allowed(getTargetContext(), YOUTUBE)) {
          rule(YOUTUBE, blocked);
          open(YOUTUBE);
          waitFor(() -> blocked(YOUTUBE), "YouTube not blocked", 10000);
          check(true, "real_youtube_launch_blocked");
          checkpoint("youtube_blocked");
          click("Home");
          waitFor(() -> !BlockActivity.visible, "Home recovery failed", 5000);
          check(true, "home_recovery");
        } else result.put("real_youtube_launch_blocked", "not installed");
        checkpoint("access_disable");
        waitFor(() -> !RestrictionService.connected, "Accessibility disable not reflected", 8000);
        open(FIXTURE);
        SystemClock.sleep(1800);
        check(!BlockActivity.visible, "disabled_access_does_not_claim_enforcement");
        checkpoint("access_rebind");
        waitFor(() -> RestrictionService.connected, "Accessibility reconnect failed", 15000);
        waitFor(() -> blocked(FIXTURE), "Re-enabled restriction did not recover", 8000);
        check(true, "accessibility_reenable_recovers");
        rule(FIXTURE, allow);
        store.pause(FIXTURE, 120000, "Synthetic restart test");
        keepRestart = true;
        getTargetContext()
            .startActivity(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(1800);
        checkpoint("usage");
      }
      result.put("passed", true);
    } catch (Throwable e) {
      try {
        result.put("passed", false);
        result.put("failure", e.toString());
      } catch (Exception ignored) {
      }
    } finally {
      if (store != null && !screenshot) {
        try {
          rollback();
          result.put("rules_restored", true);
        } catch (Exception cleanup) {
          try {
            result.put("passed", false);
            result.put("cleanup_failure", cleanup.toString());
          } catch (Exception ignored) {
          }
        }
        if (!keepRestart) {
          store.endPause(FIXTURE, "test_cleanup");
          store.getWritableDatabase().delete("spans", "pkg=?", new String[] {FIXTURE});
          store.getWritableDatabase().delete("pauses", "pkg=?", new String[] {FIXTURE});
        }
        getTargetContext()
            .startActivity(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      }
      Bundle out = new Bundle();
      out.putString("result", result.toString());
      finish(result.optBoolean("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
  }
}

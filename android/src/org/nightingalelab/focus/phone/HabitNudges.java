package org.nightingalelab.focus.phone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.*;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;

/** Observes phone focus locally. The cooldown and feed setting are specific to this phone. */
final class HabitNudges {
  static final String LOCK_CHANGED = "org.nightingalelab.focus.phone.HABIT_FEED_LOCK_CHANGED";
  private static final long[] NO_TIMES = {};
  private static volatile long lockUntil;
  private final Context context;
  private final SharedPreferences settings;
  private final FocusStateDetector detector;
  private final HabitNudgePolicy policy = new HabitNudgePolicy();
  private final Deque<FocusStateDetector.Span> observations = new ArrayDeque<>();
  private long lastTick;
  private boolean unfocused;
  private String notice;

  HabitNudges(Context context) {
    this.context = context;
    settings = preferences(context);
    policy.lastSent = settings.getLong("last_sent", 0);
    FocusStateDetector.Config config = new FocusStateDetector.Config();
    config.standaloneConsumingChurn = true;
    detector = new FocusStateDetector(config);
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("habit_nudges", Context.MODE_PRIVATE);
  }

  static boolean lockEnabled(Context context) {
    return preferences(context).getBoolean("feed_lock", false);
  }

  static void setLockEnabled(Context context, boolean enabled) {
    preferences(context).edit().putBoolean("feed_lock", enabled).apply();
    if (!enabled) setLocked(context, false);
  }

  static boolean locked(Context context) {
    return android.os.SystemClock.elapsedRealtime() < lockUntil && lockEnabled(context);
  }

  static void setLocked(Context context, boolean locked) {
    long now = android.os.SystemClock.elapsedRealtime();
    boolean wasLocked = now < lockUntil;
    lockUntil = locked ? now + 15_000 : 0;
    if (wasLocked != locked) {
      context.sendBroadcast(new Intent(LOCK_CHANGED).setPackage(context.getPackageName()));
    }
  }

  void tick() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastTick;
    if (elapsed >= 0 && elapsed < FocusStateDetector.TICK_MS) return;
    String foreground = Store.foreground;
    boolean interrupted = elapsed < 0 || elapsed > 2 * FocusStateDetector.TICK_MS;
    if (interrupted || foreground.isEmpty()) observations.clear();
    long from = interrupted ? now - 1 : lastTick;
    lastTick = now;
    Store activity = Store.get(context);
    if (!foreground.isEmpty()) {
      HabitNudgePolicy.appendSpans(observations, activity.localSpans(from, now), activity.userRules(),
          foreground, FocusAccessibilityService.onFeed(foreground), now);
    }
    while (!observations.isEmpty() && observations.peek().end <= now - 180_000) observations.remove();
    FocusStateDetector.Signals signals = detector.measure(now, new ArrayList<>(observations), NO_TIMES, null, NO_TIMES);
    FocusStateDetector.Transition transition = detector.step(now, signals, !signals.currentConsuming);
    if (transition != null) unfocused = transition.to == FocusStateDetector.State.UNFOCUSED;
    boolean entered = transition != null && unfocused;
    applyPolicy(now, entered, signals.currentConsuming && FocusAccessibilityService.canCoverFeed(foreground));
  }

  void habitsChanged() {
    applyPolicy(System.currentTimeMillis(), false, false);
  }

  private void applyPolicy(long now, boolean entered, boolean consuming) {
    if (!entered && !policy.active() && notice == null) return;

    long previous = policy.lastSent;
    List<Habits.Status> rows = HabitStore.get(context).read().status(now);
    Set<String> completed = new HashSet<>();
    int due = 0;
    for (Habits.Status row : rows) {
      if (row.due) due++;
      if (row.completion != null) completed.add(row.completion.id);
    }
    String next = policy.step(now, Habits.day(now), entered, unfocused, consuming,
        lockEnabled(context) && FocusAccessibilityService.connected, due, completed);
    if (policy.lastSent != previous) settings.edit().putLong("last_sent", policy.lastSent).apply();
    setLocked(context, HabitNudgePolicy.LOCKED.equals(next));
    if (!Objects.equals(notice, next)) {
      if (next == null) FocusNotifications.clearHabitNudge(context);
      // Do not post again if a sync changes the due count during the same gentle reminder.
      else if (notice == null || HabitNudgePolicy.LOCKED.equals(next)) FocusNotifications.sendHabitNudge(context, next);
      notice = next;
    }
  }

}

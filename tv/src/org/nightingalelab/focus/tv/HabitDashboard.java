package org.nightingalelab.focus.tv;

import android.app.Activity;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.nightingalelab.focus.habits.*;
import static org.nightingalelab.focus.habits.HabitViews.*;

final class HabitDashboard {
  private final Activity activity;
  private final HabitStore habits;
  private final Store activityStore;
  private final long now;

  HabitDashboard(Activity activity, long now) {
    this.activity = activity;
    this.habits = HabitStore.get(activity);
    this.activityStore = Store.get(activity);
    this.now = now;
  }

  View view(FrameLayout host, Runnable refresh) {
    Habits ledger = habits.read();
    List<Habits.Status> rows = ledger.status(now);
    int day = Habits.day(now);
    boolean coins = ledger.wallet.active(day);
    LinearLayout screen = column(activity);
    LinearLayout summary = row(activity);
    long due = rows.stream().filter(s -> s.due).count();
    long overdue = rows.stream().filter(s -> s.due && (s.overdue || s.grace)).count();
    long done = rows.stream().filter(s -> s.completion != null).count();
    summary.addView(text(activity, "Habits", 16, WHITE), new LinearLayout.LayoutParams(0, -2, 1));
    String reward = coins ? ledger.wallet.balance() + " coins" : "+" + ledger.credit(day) + "m earned";
    summary.addView(text(activity, allowance() + " left · " + reward, 15, GREY));
    screen.addView(summary);
    Ui.gap(screen, 10);
    LinearLayout columns = row(activity);
    columns.setGravity(Gravity.TOP);
    LinearLayout list = card();
    LinearLayout usage = card();
    String statusText = overdue > 0 ? overdue + " overdue" : due > 0 ? due + " due" : done + "/" + rows.size();
    list.addView(text(activity, statusText, 14, overdue > 0 ? RED : due > 0 ? ORANGE : GREY));
    for (Habits.Status row : rows) {
      if (!row.due) continue;
      Ui.gap(list, 6);
      Hold hold = new Hold(activity, row, 17, coins, () -> complete(row, host, refresh), null);
      hold.setId(MainActivity.controlId("habit_" + row.habit.id));
      list.addView(hold);
    }
    if (due == 0) {
      Ui.gap(list, 8);
      list.addView(text(activity, rows.isEmpty() ? "No habits" : "All habits done today.", 16, GREY));
    }
    usage.addView(text(activity, "TV today", 14, GREY));
    Map<String, Long> totals = activityStore.usage(Store.dayStart(now), now);
    List<Map.Entry<String, Long>> apps = new ArrayList<>(totals.entrySet());
    apps.sort(Map.Entry.<String, Long>comparingByValue().reversed());
    for (Map.Entry<String, Long> app : apps.subList(0, Math.min(5, apps.size()))) {
      Ui.gap(usage, 8);
      LinearLayout line = row(activity);
      line.addView(text(activity, AppCatalog.label(activity, app.getKey()), 17, WHITE),
          new LinearLayout.LayoutParams(0, -2, 1));
      line.addView(text(activity, minutes(app.getValue()), 16, GREY));
      usage.addView(line);
    }
    ScrollView scroller = new ScrollView(activity);
    scroller.addView(list);
    columns.addView(scroller, new LinearLayout.LayoutParams(0, -2, 1.15f));
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, .85f);
    right.leftMargin = dp(activity, 14);
    columns.addView(usage, right);
    screen.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
    return screen;
  }

  private String allowance() {
    return minutes(DnsController.remainingPauseMs(activity, now));
  }

  private LinearLayout card() {
    LinearLayout card = column(activity);
    card.setBackground(shape(activity, 0xFF060607, BORDER, 13));
    card.setPadding(dp(activity, 13), dp(activity, 11), dp(activity, 13), dp(activity, 13));
    return card;
  }

  private void complete(Habits.Status row, FrameLayout host, Runnable refresh) {
    activityStore.collect();
    long checkedAt = System.currentTimeMillis();
    long from = Math.max(checkedAt - 32 * 60_000L, activityStore.habitObservedFrom());
    Habits.Screen local = null;
    if (activityStore.usageAllowed() && Store.heartbeat != 0 && SystemClock.elapsedRealtime() - Store.heartbeat < 30_000) {
      try {
        JSONObject history = activityStore.historyRange(from, checkedAt);
        local = new Habits.Screen(from, checkedAt, HabitStore.spans(history.optJSONArray("spans"), checkedAt));
      } catch (JSONException ignored) {}
    }
    int day = Habits.day(checkedAt);
    int prior = habits.read().credit(day);
    Habits.Event completed = habits.complete(row.habit.id, checkedAt, Habits.away(checkedAt, local, new ArrayList<>(habits.screens().values())));
    refresh.run();
    if (completed != null) {
      int earned = completed.walletReward ? completed.reward : Math.max(0, habits.read().credit(day) - prior);
      undo(host, row.habit.name, rewardLabel(earned, completed.walletReward), 18, () -> { habits.undo(completed.id); refresh.run(); });
    }
  }

  private static String minutes(long ms) {
    long m = ms / 60_000L;
    return m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
  }
}

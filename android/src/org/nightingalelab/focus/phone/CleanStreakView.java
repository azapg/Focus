package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Today header and history for the profile-wide clean streak. */
final class CleanStreakView {
  private CleanStreakView() {}

  static View headerButton(Activity activity, FocusStats.CleanStreak streak) {
    LinearLayout button = Theme.row(activity);
    button.setMinimumHeight(Theme.dp(activity, 48));
    button.setMinimumWidth(Theme.dp(activity, 48));
    button.setPadding(Theme.dp(activity, 8), 0, Theme.dp(activity, 8), 0);
    button.addView(new Theme.IconView(activity, Theme.IconView.Kind.FLAME, Theme.SUB, 17));
    Theme.gap(button, 5);
    button.addView(Theme.text(activity, Integer.toString(streak.count), 13, Theme.FG, true));
    String description = "Clean streak, " + streak.count + (streak.count == 1 ? " day" : " days");
    if (!streak.days.isEmpty()) {
      FocusStats.CleanStreak.Status latest = streak.days.values().iterator().next();
      if (latest != FocusStats.CleanStreak.Status.CLEAN) description += ", " + label(latest);
    }
    button.setContentDescription(description);
    button.setFocusable(true);
    button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(view, info);
        info.setClassName(Button.class.getName());
      }
    });
    button.setOnClickListener(view -> showHistory(activity, streak));
    return button;
  }

  private static void showHistory(Activity activity, FocusStats.CleanStreak streak) {
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity, streak.count + (streak.count == 1 ? " clean day" : " clean days"), 24, Theme.FG, true));
    body.addView(Theme.text(activity, streak.freezeReady ? "1 freeze ready" : "No freeze ready", 13, Theme.SUB, false));
    Theme.gap(body, 12);
    body.addView(Theme.text(activity, "All devices", 12, Theme.SUB, true));
    DateTimeFormatter dates = DateTimeFormatter.ofPattern("EEE, MMM d");
    for (Map.Entry<LocalDate, FocusStats.CleanStreak.Status> entry : streak.days.entrySet()) {
      FocusStats.CleanStreak.Status status = entry.getValue();
      LinearLayout row = Theme.row(activity);
      row.setMinimumHeight(Theme.dp(activity, 36));
      row.addView(Theme.text(activity, entry.getKey().format(dates), 13, Theme.FG, true),
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
      row.addView(Theme.text(activity, label(status), 12, status == FocusStats.CleanStreak.Status.DRIFT
          ? Theme.WARN : Theme.SUB, true));
      body.addView(row);
      if (status == FocusStats.CleanStreak.Status.PENDING) {
        body.addView(Theme.text(activity, "Waiting for activity to sync.", 12, Theme.SUB, false));
      } else if (status == FocusStats.CleanStreak.Status.FROZEN) {
        body.addView(Theme.text(activity, "Freeze used. Your streak held.", 12, Theme.SUB, false));
      }
    }
    if (streak.days.isEmpty()) {
      body.addView(Theme.text(activity, "No days recorded yet.", 13, Theme.SUB, false));
    }
    SettingsDialogs.sheetDialog(activity, "Clean streak", body);
  }

  private static String label(FocusStats.CleanStreak.Status status) {
    switch (status) {
      case CLEAN: return "Clean";
      case PENDING: return "Pending";
      case FROZEN: return "Frozen";
      case DRIFT: return "Drift";
      case UNVERIFIED: return "Unverified";
      default: throw new AssertionError(status);
    }
  }
}

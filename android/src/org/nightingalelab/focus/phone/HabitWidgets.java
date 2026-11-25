package org.nightingalelab.focus.phone;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;
import java.util.*;
import org.nightingalelab.focus.habits.*;
import static org.nightingalelab.focus.habits.HabitViews.*;

public final class HabitWidgets {
  public static class Provider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
      update(context, manager, System.currentTimeMillis());
    }
  }

  public static final class ListWidget extends Provider {}
  public static final class NextWidget extends Provider {}
  public static final class AllowanceWidget extends Provider {}

  static void update(Context context, AppWidgetManager manager, long now) {
    int[] list = ids(context, manager, ListWidget.class);
    int[] next = ids(context, manager, NextWidget.class);
    int[] allowance = ids(context, manager, AllowanceWidget.class);
    if (list.length + next.length + allowance.length == 0) return;
    Habits habits = HabitStore.get(context).read();
    List<Habits.Status> rows = habits.status(now);
    List<Habits.Status> due = new ArrayList<>();
    int overdue = 0;
    int done = 0;
    for (Habits.Status row : rows) {
      if (row.due) {
        due.add(row);
        if (row.overdue || row.grace) overdue++;
      }
      if (row.completion != null) done++;
    }
    int credit = habits.credit(Habits.day(now));
    if (list.length > 0) {
      RemoteViews views = base(context, R.layout.widget_habits, "Habits");
      String earned = " · +" + credit + "m";
      if (due.isEmpty()) label(views, R.id.hab_widget_meta, done + "/" + rows.size() + earned, GREY);
      else attention(views, R.id.hab_widget_meta, overdue > 0 ? overdue + " overdue" : due.size() + " due", earned, overdue > 0 ? RED : ORANGE);
      views.removeAllViews(R.id.hab_widget_rows);
      for (int i = 0; i < Math.min(3, due.size()); i++) {
        Habits.Status row = due.get(i);
        RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.widget_habit_row);
        label(item, R.id.hab_widget_name, row.habit.name, WHITE);
        attention(item, R.id.hab_widget_caption, row.dueLabel(),
            " · " + row.habit.streakLabel(row.streak) + " · +" + row.habit.reward + "m",
            row.overdue || row.grace ? RED : ORANGE);
        item.setOnClickPendingIntent(R.id.hab_widget_row, intent(context, row.habit.id));
        views.addView(R.id.hab_widget_rows, item);
      }
      views.setViewVisibility(R.id.hab_widget_empty, due.isEmpty() ? View.VISIBLE : View.GONE);
      views.setTextViewText(R.id.hab_widget_empty, rows.isEmpty() ? "No habits" : "All habits done today.");
      manager.updateAppWidget(list, views);
    }
    if (next.length > 0) {
      RemoteViews views = base(context, R.layout.widget_habit_compact, "Next");
      Habits.Status row = due.isEmpty() ? null : due.get(0);
      label(views, R.id.hab_widget_meta, row == null ? "Done" : row.habit.streakLabel(row.streak), GREY);
      label(views, R.id.hab_widget_name, row == null ? (rows.isEmpty() ? "No habits" : "All done today") : row.habit.name, WHITE);
      if (row == null) label(views, R.id.hab_widget_caption, "", GREY);
      else attention(views, R.id.hab_widget_caption, row.dueLabel(), " · +" + row.habit.reward + "m", row.overdue || row.grace ? RED : ORANGE);
      views.setOnClickPendingIntent(R.id.hab_widget_root, intent(context, row == null ? null : row.habit.id));
      manager.updateAppWidget(next, views);
    }
    if (allowance.length > 0) {
      RemoteViews views = base(context, R.layout.widget_habit_compact, "Allowance");
      int base = Store.get(context).policyConfig().dailyLimitMinutes;
      int day = Habits.day(now);
      int extra = habits.wallet.extraPauseMinutes(day);
      long limitMs = (base + (long) extra) * 60_000L;
      long offMs = HabitScreen.offToday(context, now);
      long left = limitMs - offMs;
      int valueColor = left <= 0 ? RED : limitMs > 0 && offMs * 4 >= limitMs * 3 ? ORANGE : WHITE;
      String headline = left < 0 ? PausePolicy.formatMinutes((int) ((-left + 59_999L) / 60_000L)) + " over"
          : PausePolicy.formatMinutes((int) (left / 60_000L));
      label(views, R.id.hab_widget_meta, "Today", GREY);
      label(views, R.id.hab_widget_name, headline, valueColor);
      views.setTextViewTextSize(R.id.hab_widget_name, android.util.TypedValue.COMPLEX_UNIT_SP, 23);
      label(views, R.id.hab_widget_caption, base + "m + " + extra + (habits.wallet.active(day) ? "m bought" : "m earned"), GREY);
      manager.updateAppWidget(allowance, views);
    }
  }

  private static int[] ids(Context context, AppWidgetManager manager, Class<?> type) {
    return manager.getAppWidgetIds(new ComponentName(context, type));
  }

  private static RemoteViews base(Context context, int layout, String title) {
    RemoteViews views = new RemoteViews(context.getPackageName(), layout);
    label(views, R.id.hab_widget_title, title, GREY);
    views.setOnClickPendingIntent(R.id.hab_widget_root, intent(context, null));
    return views;
  }

  private static void label(RemoteViews views, int id, String text, int color) {
    views.setTextViewText(id, text);
    views.setTextColor(id, color);
  }

  private static void attention(RemoteViews views, int id, String due, String rest, int color) {
    SpannableString text = new SpannableString(due + rest);
    text.setSpan(new ForegroundColorSpan(color), 0, due.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    views.setTextViewText(id, text);
    views.setTextColor(id, GREY);
  }

  private static PendingIntent intent(Context context, String habit) {
    Intent intent = new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .setData(Uri.parse("focus-habits://widget/" + (habit == null ? "today" : Uri.encode(habit))))
        .putExtra("page", "habits");
    if (habit != null) intent.putExtra("habit", habit);
    return PendingIntent.getActivity(context, 340, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
}

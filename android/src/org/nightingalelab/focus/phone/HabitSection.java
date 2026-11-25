package org.nightingalelab.focus.phone;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import org.nightingalelab.focus.habits.*;
import static org.nightingalelab.focus.habits.HabitViews.*;

final class HabitSection {
  private final MainActivity activity;
  private final HabitStore store;
  private final FrameLayout overlay;
  private final Runnable rerender;
  private boolean showDone = true;
  private String expanded;

  HabitSection(MainActivity activity, FrameLayout overlay, Runnable rerender) {
    this.activity = activity;
    this.overlay = overlay;
    this.rerender = rerender;
    store = HabitStore.get(activity);
  }

  View overview() {
    long now = System.currentTimeMillis();
    Habits ledger = store.read();
    List<Habits.Status> statuses = ledger.status(now);
    LinearLayout box = card(activity);
    LinearLayout head = row(activity);
    long done = statuses.stream().filter(s -> s.completion != null).count();
    long due = statuses.stream().filter(s -> s.due).count();
    long overdue = statuses.stream().filter(s -> s.due && (s.overdue || s.grace)).count();
    head.addView(text(activity, "Habits", 13, GREY));
    String summary = due == 0 ? done + "/" + statuses.size() : overdue > 0 ? overdue + " overdue" : due + " due";
    TextView count = text(activity, "  " + summary, 13,
        due == 0 ? WHITE : overdue > 0 ? RED : ORANGE);
    head.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(action(activity, "New", () -> editor(null)));
    box.addView(head);
    for (Habits.Status status : statuses) {
      if (!status.due) continue;
      Hold hold = new Hold(activity, status, 14, () -> complete(status, overlay, false, rerender), () -> toggle(status));
      addSpaced(box, hold);
      if (status.habit.id.equals(expanded)) addSpaced(box, week(status, now));
    }
    if (due == 0 && !statuses.isEmpty()) addSpaced(box, text(activity, "All habits done today.", 13, GREY));
    if (done > 0) {
      box.addView(action(activity, "Done · " + done, () -> { showDone = !showDone; rerender.run(); }));
      if (showDone) for (Habits.Status status : statuses) {
        if (status.completion == null) continue;
        LinearLayout line = row(activity);
        line.setPadding(dp(activity, 3), dp(activity, 8), dp(activity, 3), dp(activity, 8));
        line.addView(text(activity, status.habit.name, 13, GREY), new LinearLayout.LayoutParams(0, -2, 1));
        Habits.Event event = status.completion;
        String away = event.away < 0 ? "" : "Away " + event.away + "m    ";
        TextView detail = text(activity, away + "+" + event.reward + "m", 12, MUTED);
        if (event.away >= 0) detail.setContentDescription("Away from all screens " + event.away + " min, plus " + event.reward + " minutes");
        line.addView(detail);
        line.setOnClickListener(v -> toggle(status));
        box.addView(line);
        if (status.habit.id.equals(expanded)) addSpaced(box, week(status, now));
      }
    }
    return box;
  }

  private void toggle(Habits.Status status) {
    expanded = status.habit.id.equals(expanded) ? null : status.habit.id;
    rerender.run();
  }

  private View week(Habits.Status status, long now) {
    LinearLayout strip = row(activity);
    LocalDate date = LocalDate.ofEpochDay(Habits.day(now) - 6);
    for (String state : status.week) {
      LinearLayout cell = column(activity);
      cell.setGravity(Gravity.CENTER);
      cell.addView(text(activity, date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()), 10, MUTED));
      View point = new View(activity);
      GradientDrawable dot = shape(activity, "done".equals(state) ? 0xFFD4D4D8 : CARD, BORDER, 6);
      if ("due".equals(state)) dot.setStroke(dp(activity, 1), ORANGE);
      else if ("miss".equals(state)) dot.setStroke(dp(activity, 1), RED_STROKE);
      point.setBackground(dot);
      cell.addView(point, new LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 8)));
      cell.setContentDescription(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + state);
      strip.addView(cell, new LinearLayout.LayoutParams(0, dp(activity, 42), 1));
      date = date.plusDays(1);
    }
    return strip;
  }

  View settings() {
    long now = System.currentTimeMillis();
    Habits ledger = store.read();
    LinearLayout box = card(activity);
    LinearLayout head = row(activity);
    head.addView(text(activity, "Habits", 14, WHITE), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(action(activity, "New habit", () -> editor(null)));
    box.addView(head);
    for (Habits.Status status : ledger.status(now)) {
      Habits.Habit habit = status.habit;
      box.addView(action(activity, habit.name + " · " + habit.cadence() + " · +" + habit.reward + "m", () -> editor(habit)));
    }
    int today = Habits.day(now);
    int current = ledger.capOn(today);
    int pending = ledger.capOn(today + 1);
    LinearLayout cap = row(activity);
    String label = "Daily credit cap";
    if (pending != current) label += "\n+" + pending + "m tomorrow";
    cap.addView(text(activity, label, 12, GREY), new LinearLayout.LayoutParams(0, -2, 1));
    for (int value : new int[] {30, 45, 60}) {
      TextView option = action(activity, "+" + value + "m", () -> {
        store.setCap(value);
        sync();
        rerender.run();
      });
      if (current == value) option.setBackground(shape(activity, 0xFF222225, BORDER, 6));
      cap.addView(option);
    }
    addSpaced(box, cap);
    LinearLayout feedLock = row(activity);
    boolean lock = HabitNudges.lockEnabled(activity);
    feedLock.addView(text(activity, "Lock feeds after a nudge", 12, GREY),
        new LinearLayout.LayoutParams(0, -2, 1));
    feedLock.addView(action(activity, lock ? "On" : "Off", () -> {
      HabitNudges.setLockEnabled(activity, !lock);
      rerender.run();
    }));
    addSpaced(box, feedLock);
    return box;
  }

  private void editor(Habits.Habit habit) {
    android.app.Dialog dialog = new android.app.Dialog(activity);
    LinearLayout box = card(activity);
    int initialDays = habit == null ? 0 : habit.weekdays;
    int initialInterval = habit == null ? 1 : habit.interval;
    EditText name = field("Habit name", habit == null ? "" : habit.name, false);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
    box.addView(name);
    Spinner cadence = choices("Daily", "Every N days", "Weekly");
    cadence.setSelection(initialDays > 0 ? 2 : initialInterval > 1 ? 1 : 0);
    rowField(box, "Cadence", cadence);
    EditText interval = field("Interval", String.valueOf(Math.max(2, initialInterval)), true);
    View intervalRow = rowField(box, "Interval", interval);
    LinearLayout days = row(activity);
    String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    final int[] selected = {initialDays};
    for (int i = 0; i < labels.length; i++) {
      int bit = 1 << i;
      TextView day = action(activity, labels[i].substring(0, 1), () -> {});
      day.setContentDescription(labels[i]);
      day.setGravity(Gravity.CENTER);
      day.setBackground(shape(activity, (selected[0] & bit) == 0 ? CARD : 0xFF34343A, BORDER, 6));
      day.setOnClickListener(v -> {
        selected[0] ^= bit;
        v.setBackground(shape(activity, (selected[0] & bit) == 0 ? CARD : 0xFF34343A, BORDER, 6));
      });
      days.addView(day, new LinearLayout.LayoutParams(0, -2, 1));
    }
    box.addView(days);
    cadence.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onNothingSelected(AdapterView<?> parent) {}
      public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        intervalRow.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
        days.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
      }
    });
    int initialTime = habit == null ? -1 : habit.time;
    final int[] time = {initialTime};
    TextView clock = action(activity, timeLabel(initialTime), () -> {});
    clock.setOnClickListener(v -> new android.app.TimePickerDialog(activity, (picker, h, m) -> {
      time[0] = h * 60 + m;
      clock.setText(timeLabel(time[0]));
    }, time[0] < 0 ? 18 : time[0] / 60, time[0] < 0 ? 0 : time[0] % 60, true).show());
    LinearLayout timeControls = row(activity);
    timeControls.addView(clock);
    timeControls.addView(action(activity, "Anytime", () -> {
      time[0] = -1;
      clock.setText(timeLabel(time[0]));
    }));
    rowField(box, "Time", timeControls);
    Spinner rewards = choices("+5m", "+10m", "+15m");
    rewards.setSelection(habit == null ? 1 : Math.max(0, Math.min(2, habit.reward / 5 - 1)));
    rowField(box, "Credit", rewards);
    LinearLayout footer = row(activity);
    footer.setGravity(Gravity.END);
    if (habit != null) footer.addView(action(activity, "Remove", () -> {
      store.save(habit.id, habit.name, habit.interval, habit.weekdays, habit.time, habit.reward, true);
      dialog.dismiss();
      sync();
      rerender.run();
    }));
    footer.addView(action(activity, habit == null ? "Add" : "Save", () -> {
      if (name.getText().toString().trim().isEmpty()) { name.setError("Enter a habit name"); return; }
      int step = 1;
      int mask = cadence.getSelectedItemPosition() == 2 ? selected[0] : 0;
      if (cadence.getSelectedItemPosition() == 2 && mask == 0) {
        Toast.makeText(activity, "Select at least one day", Toast.LENGTH_SHORT).show();
        return;
      }
      if (cadence.getSelectedItemPosition() == 1) {
        try { step = Integer.parseInt(interval.getText().toString()); }
        catch (NumberFormatException e) { step = 0; }
        if (step < 2 || step > 365) { interval.setError("Enter 2–365 days"); return; }
      }
      store.save(habit == null ? null : habit.id, name.getText().toString(),
          step, mask, time[0], (rewards.getSelectedItemPosition() + 1) * 5, false);
      dialog.dismiss();
      sync();
      rerender.run();
    }));
    box.addView(footer);
    dismissOnSwipe(box, dialog::dismiss);
    dialog.setContentView(box);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 28),
          dp(activity, 380)), -2);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
  }

  private EditText field(String label, String value, boolean number) {
    EditText field = new EditText(activity);
    field.setTextSize(14);
    field.setHint(label);
    field.setHintTextColor(MUTED);
    field.setTextColor(WHITE);
    field.setSingleLine(true);
    if (number) field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setText(value);
    return field;
  }

  private static String timeLabel(int value) {
    return value < 0 ? "Set time" : String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
  }

  private Spinner choices(String... values) {
    Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    return spinner;
  }

  private View rowField(LinearLayout parent, String label, View value) {
    LinearLayout row = row(activity);
    row.addView(text(activity, label, 12, GREY), new LinearLayout.LayoutParams(dp(activity, 65), -2));
    row.addView(value, new LinearLayout.LayoutParams(-1, -2));
    parent.addView(row);
    return row;
  }

  void complete(Habits.Status status, FrameLayout host, boolean mini, Runnable refresh) {
    long now = System.currentTimeMillis();
    Store.get(activity).collect();
    int day = Habits.day(now);
    int prior = store.read().credit(day);
    Habits.Event completion = store.complete(status.habit.id, now, HabitScreen.away(activity, now));
    int earned = store.read().credit(day) - prior;
    refresh.run();
    if (completion != null) {
      undo(host, status.habit.name, Math.max(0, earned), mini ? 16 : 78, () -> {
        store.undo(completion.id);
        refresh.run();
        sync();
      });
      sync();
    }
  }

  private void sync() {
    WidgetUpdater.updateAll(activity);
    new Thread(() -> DeviceHub.get(activity).syncAll(), "focus-habits-sync").start();
  }

  static LinearLayout card(Context context) {
    LinearLayout box = column(context);
    box.setBackground(shape(context, 0xFF060607, BORDER, 13));
    box.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 10));
    return box;
  }

  static void addSpaced(LinearLayout parent, View view) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(parent.getContext(), 6);
    parent.addView(view, p);
  }
}

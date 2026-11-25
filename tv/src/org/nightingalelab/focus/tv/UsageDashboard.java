package org.nightingalelab.focus.tv;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class UsageDashboard {
  private static final int CARD = 0xff060607;
  private static final int AMBER = 0xffd99a44;
  private static final int ROWS = 3;
  private static final int ROW_HEIGHT = 37;
  private static final int PLOT_HEIGHT = 68;

  private enum Category {
    VIDEOS("Videos", 0xffbcbcc2), MUSIC("Music", 0xff686870),
    GAMES("Games", 0xff89939e), OTHER("Other", 0xff47474e);

    final String label;
    final int color;
    Category(String label, int color) {
      this.label = label;
      this.color = color;
    }
  }

  private static final class Range {
    final long from, to;
    final String label;

    Range(long from, long to, String label) {
      this.from = from;
      this.to = to;
      this.label = label;
    }

    long overlap(long start, long end) {
      return Math.max(0, Math.min(to, end) - Math.max(from, start));
    }
  }

  private static final class Pause {
    final String pkg, reason;
    final long start, end;
    final boolean dns, active;

    Pause(JSONObject object) {
      pkg = object.optString("package");
      reason = object.optString("reason");
      start = object.optLong("t");
      end = object.optLong("end");
      dns = DnsController.KEY.equals(pkg);
      active = object.isNull("end_kind");
    }
  }

  private final MainActivity activity;
  private final boolean week;
  private final int selectedSlot, selectedCategory;
  private final IntConsumer onSlot, onCategory;
  private final Map<String, AppCatalog.App> catalog = new LinkedHashMap<>();
  private final Map<String, Category> categories = new HashMap<>();
  private final Map<String, Long> usage = new HashMap<>();
  private final List<Pause> pauses = new ArrayList<>();
  private final long[] used, dnsUsed;
  private final Range overall, displayed;
  private final Range[] slots;

  UsageDashboard(MainActivity activity, boolean week, int slot, int category, IntConsumer onSlot,
      IntConsumer onCategory) throws JSONException {
    this.activity = activity;
    this.week = week;
    this.selectedSlot = slot;
    this.selectedCategory = category;
    this.onSlot = onSlot;
    this.onCategory = onCategory;
    long now = System.currentTimeMillis();
    Calendar start = Calendar.getInstance();
    start.setTimeInMillis(Store.dayStart(now));
    Calendar first = (Calendar) start.clone();
    if (week) first.add(Calendar.DATE, -6);

    SimpleDateFormat date = new SimpleDateFormat("MMM d", Locale.getDefault());
    String label = week ? date.format(first.getTime()) + " – " + date.format(start.getTime())
        : new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(start.getTime());
    overall = new Range(first.getTimeInMillis(), now, label);
    slots = new Range[week ? 7 : 12];
    used = new long[slots.length];
    dnsUsed = new long[slots.length];
    SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.getDefault());
    Calendar end = (Calendar) start.clone();
    end.add(Calendar.DATE, 1);
    for (int i = 0; i < slots.length; i++) {
      Calendar a = (Calendar) first.clone();
      Calendar b = (Calendar) first.clone();
      if (week) {
        a.add(Calendar.DATE, i);
        b.add(Calendar.DATE, i + 1);
      } else {
        a.set(Calendar.HOUR_OF_DAY, i * 2);
        if (i == slots.length - 1) b = end;
        else b.set(Calendar.HOUR_OF_DAY, (i + 1) * 2);
      }
      String name = week ? weekday.format(a.getTime()) : clock(i * 2) + "–" + clock((i + 1) * 2);
      slots[i] = new Range(a.getTimeInMillis(), b.getTimeInMillis(), name);
    }
    displayed = slot < 0 ? overall : slots[slot];
    for (AppCatalog.App app : AppCatalog.apps(activity)) catalog.put(app.pkg, app);

    JSONObject history = Store.get(activity).historyRange(overall.from, overall.to);
    JSONArray pauseData = history.getJSONArray("pauses");
    for (int i = pauseData.length() - 1; i >= 0; i--) pauses.add(new Pause(pauseData.getJSONObject(i)));
    List<Pause> dns = new ArrayList<>();
    for (Pause pause : pauses) if (pause.dns) dns.add(pause);
    JSONArray spans = history.getJSONArray("spans");
    for (int i = 0; i < spans.length(); i++) {
      JSONObject span = spans.getJSONObject(i);
      long from = span.getLong("s"), to = span.getLong("e");
      long duration = displayed.overlap(from, to);
      if (duration > 0) usage.merge(span.getString("app"), duration, Long::sum);
      for (int j = 0; j < slots.length; j++) {
        Range range = slots[j];
        long inSlot = range.overlap(from, to);
        if (inSlot == 0) continue;
        used[j] += inSlot;
        for (Pause pause : dns) {
          dnsUsed[j] += range.overlap(Math.max(from, pause.start), Math.min(to, pause.end));
        }
      }
    }
  }

  private static String clock(int hour) {
    return String.format(Locale.ROOT, "%02d:00", hour);
  }

  private Category category(String pkg) {
    return categories.computeIfAbsent(pkg, key -> {
      if (key.equals("com.google.android.youtube.tvmusic") || key.equals("com.spotify.tv.android"))
        return Category.MUSIC;
      if (key.startsWith("com.google.android.youtube.") || key.equals("com.netflix.ninja")
          || key.startsWith("tv.twitch.") || key.startsWith("com.amazon.amazonvideo."))
        return Category.VIDEOS;
      try {
        int value = activity.getPackageManager().getApplicationInfo(key, 0).category;
        if (value == ApplicationInfo.CATEGORY_AUDIO) return Category.MUSIC;
        if (value == ApplicationInfo.CATEGORY_VIDEO) return Category.VIDEOS;
        if (value == ApplicationInfo.CATEGORY_GAME) return Category.GAMES;
      } catch (PackageManager.NameNotFoundException ignored) {
        // Older usage survives uninstalling the app.
      }
      return Category.OTHER;
    });
  }

  View view() {
    LinearLayout content = Ui.column(activity);
    content.addView(Ui.text(activity, displayed.label, 13, Ui.MUTED));
    long total = 0, dns = 0;
    for (long duration : usage.values()) total += duration;
    for (Pause p : pauses) if (p.dns) dns += displayed.overlap(p.start, p.end);
    LinearLayout heading = Ui.row(activity);
    heading.addView(Ui.text(activity, Ui.time(total), 40, Ui.FG));
    Ui.gap(heading, 9);
    heading.addView(Ui.text(activity, "on this TV", 17, Ui.MUTED),
        new LinearLayout.LayoutParams(0, -2, 1));
    if (dns > 0) heading.addView(Ui.text(activity, Ui.time(dns) + " DNS off", 14, AMBER));
    content.addView(heading);
    if (total > 0) breakdown(content);
    Ui.gap(content, 10);
    content.addView(chart());
    Ui.gap(content, 10);
    LinearLayout lists = Ui.row(activity);
    lists.setGravity(Gravity.TOP);
    LinearLayout apps = appsPanel(), pausePanel = pausesPanel();
    int height = Ui.dp(activity, 46 + ROWS * ROW_HEIGHT);
    lists.addView(apps, new LinearLayout.LayoutParams(0, height, 1));
    Ui.gap(lists, 12);
    lists.addView(pausePanel, new LinearLayout.LayoutParams(0, height, 1));
    content.addView(lists);
    ScrollView scroll = new ScrollView(activity);
    scroll.setClipToPadding(false);
    scroll.addView(content);
    return scroll;
  }

  private void breakdown(LinearLayout content) {
    Map<Category, Long> duration = new EnumMap<>(Category.class);
    usage.forEach((pkg, time) -> duration.merge(category(pkg), time, Long::sum));
    List<Category> order = new ArrayList<>(duration.keySet());
    order.sort(Comparator.comparingLong((Category c) -> duration.get(c)).reversed());
    LinearLayout strip = Ui.row(activity), legend = Ui.row(activity);
    for (Category c : order) {
      if (strip.getChildCount() > 0) {
        Ui.gap(strip, 2);
        Ui.gap(legend, 10);
      }
      View segment = new View(activity);
      segment.setBackground(Ui.shape(c.color, 0, 2, activity));
      segment.setAlpha(selectedCategory < 0 || selectedCategory == c.ordinal() ? 1f : .35f);
      strip.addView(segment, new LinearLayout.LayoutParams(0, -1, duration.get(c).floatValue()));
      TextView item = control(c.label + "  " + Ui.time(duration.get(c)), 13, Ui.MUTED,
          "category_" + c.ordinal(), () -> onCategory.accept(c.ordinal()));
      android.graphics.drawable.Drawable swatch = Ui.shape(c.color, 0, 2, activity);
      int size = Ui.dp(activity, 7);
      swatch.setBounds(0, 0, size, size);
      item.setCompoundDrawablesRelative(swatch, null, null, null);
      item.setCompoundDrawablePadding(Ui.dp(activity, 5));
      item.setSelected(selectedCategory == c.ordinal());
      legend.addView(item);
    }
    Ui.gap(content, 4);
    content.addView(strip, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 8)));
    Ui.gap(content, 3);
    content.addView(legend);
  }

  private LinearLayout panel(String title, TextView action) {
    LinearLayout panel = Ui.column(activity);
    panel.setBackground(Ui.shape(CARD, Ui.BORDER, 13, activity));
    int pad = Ui.dp(activity, 12);
    panel.setPadding(pad, Ui.dp(activity, 8), pad, Ui.dp(activity, 8));
    LinearLayout header = Ui.row(activity);
    header.addView(Ui.text(activity, title, 14, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) header.addView(action);
    panel.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 30)));
    return panel;
  }

  private LinearLayout chart() {
    LinearLayout panel = panel(week ? "Viewing by day" : "Viewing by time of day", null);
    LinearLayout header = (LinearLayout) panel.getChildAt(0);
    boolean hasDns = Arrays.stream(dnsUsed).anyMatch(value -> value > 0);
    if (hasDns) header.addView(Ui.text(activity, "•  Viewing with DNS off", 12, AMBER));
    long step = (week ? 180 : 60) * 60_000L;
    long greatest = Arrays.stream(used).max().orElse(0);
    long max = Math.max(step, ((greatest + step - 1) / step) * step);
    LinearLayout plot = Ui.row(activity);
    plot.setGravity(Gravity.TOP);
    FrameLayout scale = new FrameLayout(activity);
    long[] ticks = { max, max / 2, 0 };
    int[] position = { Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM };
    for (int i = 0; i < ticks.length; i++) {
      TextView tick = Ui.text(activity, i == 2 ? "0" : Ui.time(ticks[i]).replace(" 0m", ""), 10, Ui.MUTED);
      scale.addView(tick, new FrameLayout.LayoutParams(-2, -2, Gravity.END | position[i]));
    }
    plot.addView(scale, new LinearLayout.LayoutParams(Ui.dp(activity, 35), Ui.dp(activity, PLOT_HEIGHT)));
    Ui.gap(plot, 6);
    for (int i = 0; i < slots.length; i++) {
      final int index = i;
      LinearLayout cell = Ui.column(activity);
      cell.setGravity(Gravity.CENTER_HORIZONTAL);
      cell.setId(MainActivity.controlId("chart_" + i));
      cell.setFocusable(true);
      cell.setSelected(selectedSlot == i);
      cell.setBackground(controlBackground());
      cell.setOnClickListener(v -> onSlot.accept(index));
      Ui.buttonSemantics(cell);
      cell.setContentDescription(slots[i].label + ", " + Ui.time(used[i]) + " viewing"
          + (dnsUsed[i] > 0 ? ", " + Ui.time(dnsUsed[i]) + " with DNS off" : ""));
      View bar = new Bar(used[i], dnsUsed[i], max, selectedSlot == i);
      cell.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(activity, PLOT_HEIGHT)));
      TextView caption = Ui.text(activity, week ? slots[i].label : (i % 2 == 0 ? clock(i * 2).substring(0, 2) : ""), 11, Ui.MUTED);
      caption.setGravity(Gravity.CENTER);
      caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      cell.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 20)));
      plot.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    panel.addView(plot);
    if (selectedSlot >= 0) {
      long off = dnsUsed[selectedSlot];
      LinearLayout footer = Ui.row(activity);
      String summary = displayed.label + "  ·  " + Ui.time(used[selectedSlot]) + " viewing";
      if (off > 0) summary += "  ·  " + Ui.time(off) + " with DNS off";
      footer.addView(Ui.text(activity, summary, 12, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
      footer.addView(control("Clear", 13, Ui.FG, "clear_slot", () -> onSlot.accept(-1)));
      panel.addView(footer);
    } else {
      panel.addView(Ui.text(activity, week ? "Select a day to see its apps and pauses"
          : "Select a time to see its apps and pauses", 12, Ui.MUTED));
    }
    return panel;
  }

  private final class Bar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long time, off, max;
    private final boolean selected;

    Bar(long time, long off, long max, boolean selected) {
      super(activity);
      this.time = time;
      this.off = off;
      this.max = max;
      this.selected = selected;
    }

    @Override protected void onDraw(Canvas canvas) {
      int bottom = getHeight() - Ui.dp(activity, 2);
      int cap = Ui.dp(activity, 4);
      paint.setColor(Ui.BORDER);
      for (int i = 0; i < 3; i++) {
        float y = cap + (bottom - cap) * i / 2f;
        canvas.drawLine(0, y, getWidth(), y, paint);
      }
      float left = getWidth() * .18f, right = getWidth() - left;
      float top = bottom - Math.max(2, (float) (bottom - cap) * time / max);
      paint.setColor(selected ? 0xffd4d4d8 : 0xff66666e);
      canvas.drawRoundRect(left, top, right, bottom, 3, 3, paint);
      if (off > 0) {
        paint.setColor(AMBER);
        canvas.drawRect(left, top, right, Math.min(bottom, top + (float) (bottom - cap) * off / max), paint);
      }
    }
  }

  private LinearLayout appsPanel() {
    String title = selectedCategory < 0 ? "Apps" : "Apps · " + Category.values()[selectedCategory].label;
    LinearLayout panel = panel(title, control("All apps ›", 13, Ui.MUTED, "all_apps", activity::openRestrictions));
    List<String> ordered = new ArrayList<>(usage.keySet());
    ordered.removeIf(pkg -> selectedCategory >= 0 && category(pkg).ordinal() != selectedCategory);
    ordered.sort(Comparator.<String>comparingLong(usage::get).reversed().thenComparing(pkg -> pkg));
    if (ordered.isEmpty()) panel.addView(Ui.text(activity, "No app activity in this period", 13, Ui.MUTED));
    for (int i = 0; i < Math.min(ROWS, ordered.size()); i++) {
      String pkg = ordered.get(i);
      AppCatalog.App app = catalog.get(pkg);
      String label = app == null ? AppCatalog.label(activity, pkg) : app.label;
      String status = Ui.time(usage.get(pkg));
      if (app != null && Store.get(activity).rule(pkg).blocked) status += "  ·  Blocked";
      LinearLayout row = twoLineRow(label, "", status, Ui.MUTED, "dashboard_" + pkg, app == null
          ? null : () -> activity.appDetails(pkg, usage.get(pkg), displayed.label));
      if (app != null) {
        ImageView icon = new ImageView(activity);
        try {
          icon.setImageDrawable(activity.getPackageManager().getApplicationIcon(pkg));
          LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(Ui.dp(activity, 22), Ui.dp(activity, 22));
          size.setMarginEnd(Ui.dp(activity, 8));
          row.addView(icon, 0, size);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      }
      panel.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(activity, ROW_HEIGHT)));
    }
    return panel;
  }

  private List<Pause> displayedPauses() {
    List<Pause> result = new ArrayList<>();
    for (Pause pause : pauses) if (displayed.overlap(pause.start, pause.end) > 0) result.add(pause);
    return result;
  }

  private LinearLayout pausesPanel() {
    List<Pause> items = displayedPauses();
    TextView all = items.size() <= ROWS ? null
        : control("View all ›", 13, Ui.MUTED, "all_pauses", () -> openPauses(items));
    LinearLayout panel = panel("Pauses", all);
    if (items.isEmpty()) panel.addView(Ui.text(activity, "No pauses in this period", 13, Ui.MUTED));
    for (int i = 0; i < Math.min(ROWS, items.size()); i++) {
      panel.addView(pauseRow(items.get(i)), new LinearLayout.LayoutParams(-1, Ui.dp(activity, ROW_HEIGHT)));
    }
    return panel;
  }

  private LinearLayout pauseRow(Pause pause) {
    String title = pause.reason.isEmpty() ? pauseType(pause) : pause.reason;
    return twoLineRow(title, pauseDate(pause) + "  ·  " + pauseType(pause),
        pause.active ? "Active" : Ui.time(pause.end - pause.start), pause.dns ? AMBER : Ui.MUTED,
        "pause_" + pause.pkg + pause.start, () -> openPause(pause));
  }

  private String pauseDate(Pause pause) {
    return new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date(pause.start));
  }

  private String pauseType(Pause pause) {
    return pause.dns ? "DNS off" : AppCatalog.label(activity, pause.pkg) + " allowed";
  }

  private void openPauses(List<Pause> items) {
    LinearLayout list = Ui.column(activity), body = Ui.column(activity);
    for (Pause item : items) list.addView(pauseRow(item), new LinearLayout.LayoutParams(-1, Ui.dp(activity, 46)));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(list);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 220)));
    Ui.dialog(activity, "Pauses · " + displayed.label, body).show();
  }

  private void openPause(Pause pause) {
    LinearLayout body = Ui.column(activity);
    body.addView(Ui.text(activity, pauseType(pause) + "  ·  " + pauseDate(pause), 15, Ui.MUTED));
    Ui.gap(body, 8);
    if (!pause.reason.isEmpty()) body.addView(Ui.text(activity, pause.reason, 17, Ui.FG));
    body.addView(Ui.text(activity, pause.active ? "Active" : Ui.time(pause.end - pause.start), 15, Ui.MUTED));
    Ui.dialog(activity, "Pause details", body).show();
  }

  private LinearLayout twoLineRow(String title, String subtitle, String value, int valueColor, String id, Runnable action) {
    LinearLayout row = Ui.row(activity), labels = Ui.column(activity);
    row.setPadding(Ui.dp(activity, 6), 0, Ui.dp(activity, 6), 0);
    TextView name = Ui.text(activity, title, 15, Ui.FG);
    name.setSingleLine();
    name.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(name);
    if (!subtitle.isEmpty()) {
      TextView info = Ui.text(activity, subtitle, 11, Ui.MUTED);
      info.setSingleLine();
      info.setEllipsize(TextUtils.TruncateAt.END);
      labels.addView(info);
    }
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(row, 6);
    row.addView(Ui.text(activity, value, 13, valueColor));
    if (action != null) {
      row.setId(MainActivity.controlId(id));
      row.setFocusable(true);
      row.setBackground(controlBackground());
      row.setOnClickListener(v -> action.run());
      Ui.buttonSemantics(row);
      row.setContentDescription(title + (subtitle.isEmpty() ? "" : ", " + subtitle) + ", " + value);
      row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    return row;
  }

  private TextView control(String label, int size, int color, String id, Runnable action) {
    TextView v = Ui.text(activity, label, size, color);
    v.setId(MainActivity.controlId(id));
    v.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 4), Ui.dp(activity, 6), Ui.dp(activity, 4));
    v.setFocusable(true);
    v.setBackground(controlBackground());
    v.setOnClickListener(view -> action.run());
    Ui.buttonSemantics(v);
    return v;
  }

  private StateListDrawable controlBackground() {
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, Ui.shape(0xff232327, Ui.FG, 6, activity));
    bg.addState(new int[] {android.R.attr.state_selected}, Ui.shape(0xff202024, 0, 6, activity));
    bg.addState(new int[] {}, Ui.shape(0x00000000, 0, 6, activity));
    return bg;
  }
}

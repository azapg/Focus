package org.nightingalelab.focus.phone;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.widget.RemoteViews;
import java.util.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/** Renders and refreshes the home-screen widgets: A (score), B (sentence), C (week), E (ring), F (now). */
public final class WidgetUpdater {
  public static void updateAll(Context c) {
    Metric.WIDGET_REFRESH.count(1);
    try {
      AppWidgetManager mgr = AppWidgetManager.getInstance(c);
      if (mgr == null) return;
      long now = System.currentTimeMillis();
      HabitWidgets.update(c, mgr, now);
      TimeZone tz = TimeZone.getDefault();
      long weekFrom = InsightsEngine.dayStart(now, tz) - 6L * 86_400_000L;
      List<InsightsEngine.SpanRecord> spans = DeviceHub.get(c).combinedSpans(weekFrom, now);
      List<InsightsEngine.PauseRecord> pauses = DeviceHub.get(c).combinedPauses(weekFrom, now);
      List<ActivityClassifier.UserRule> rules = Store.get(c).userRules();
      InsightsEngine.TodaySummary today = InsightsEngine.computeToday(spans, pauses, rules, now, tz);
      InsightsEngine.WeekSummary week = InsightsEngine.computeWeek(spans, pauses, rules, now, tz);

      String activeStatus = activeStatusShort(c, now);
      updateA(c, mgr, today, activeStatus);
      updateB(c, mgr, today);
      updateC(c, mgr, today, week);
      updateE(c, mgr, today);
      updateIntention(c);
    } catch (RuntimeException ignored) {}
  }

  /** Widget F: the current intention, with Done, or Set when nothing is running. */
  static void updateIntention(Context c) {
    try {
      AppWidgetManager mgr = AppWidgetManager.getInstance(c);
      if (mgr == null) return;
      int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetFProvider.class));
      if (ids == null || ids.length == 0) return;
      Intention current = IntentionStore.get(c).current();
      long now = System.currentTimeMillis();
      RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_f);
      Intent setIntent = new Intent(c, MainActivity.class).putExtra("page", "today").putExtra("open_intention", true)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent set = PendingIntent.getActivity(c, 320, setIntent,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      if (current == null) {
        rv.setTextViewText(R.id.wf_title, "What are you on?");
        rv.setTextColor(R.id.wf_title, Theme.MUTED);
        rv.setTextViewText(R.id.wf_status, "");
        rv.setImageViewBitmap(R.id.wf_bar, renderProgressBitmap(0f, false));
        rv.setTextViewText(R.id.wf_btn, "Set");
        rv.setOnClickPendingIntent(R.id.wf_btn, set);
        rv.setOnClickPendingIntent(R.id.widget_root, set);
      } else {
        Intention.Drift drift = IntentionMonitor.drift;
        boolean orange = drift.showing();
        rv.setTextViewText(R.id.wf_title, current.label);
        rv.setTextColor(R.id.wf_title, Theme.FG);
        rv.setTextViewText(R.id.wf_status, orange ? drift.text()
            : current.timeUp(now) ? "Time's up" : Intention.left(current.remainingMs(now)));
        rv.setTextColor(R.id.wf_status, orange ? Theme.WARN : Theme.MUTED);
        rv.setImageViewBitmap(R.id.wf_bar, renderProgressBitmap(current.progress(now), orange));
        rv.setTextViewText(R.id.wf_btn, "Done");
        Intent done = new Intent(c, IntentionMonitor.Actions.class).setAction(IntentionMonitor.ACTION)
            .putExtra(IntentionMonitor.EXTRA_DO, "done");
        rv.setOnClickPendingIntent(R.id.wf_btn, PendingIntent.getBroadcast(c, 321, done,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(c, 322, false));
      }
      mgr.updateAppWidget(ids, rv);
      Metric.WIDGET_UPDATE.count(ids.length);
    } catch (RuntimeException ignored) {}
  }

  static Bitmap renderProgressBitmap(float progress, boolean orange) {
    int w = 480, h = 12;
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(0xFF1C1C20);
    canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, paint);
    if (progress > 0f) {
      paint.setColor(orange ? Theme.WARN : 0xFFD4D4D8);
      canvas.drawRoundRect(new RectF(0, 0, Math.max(h, w * Math.min(1f, progress)), h), h / 2f, h / 2f, paint);
    }
    return bmp;
  }

  private static String activeStatusShort(Context c, long now) {
    DnsController dns = DnsController.get(c);
    if (dns.deadline() > 0) return "Phone paused · " + InsightsEngine.formatCountdown(dns.remainingMs());
    for (DeviceHub.PairedDevice d : DeviceHub.get(c).list()) {
      if (d.dnsPausedNow(now)) {
        String label = "tv".equals(d.kind) ? "TV" : d.name;
        return label + " paused · " + InsightsEngine.formatCountdown(d.dnsPauseUntil() - now);
      }
    }
    return dns.enabled() ? "Protected" : "Focus";
  }

  private static PendingIntent openAppIntent(Context c, int reqCode, boolean openPause) {
    Intent i = new Intent(c, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (openPause) i.putExtra("open_pause_hint", true);
    return PendingIntent.getActivity(c, reqCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private static void updateA(Context c, AppWidgetManager mgr, InsightsEngine.TodaySummary today, String status) {
    int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetAProvider.class));
    if (ids == null || ids.length == 0) return;
    RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_a);
    rv.setTextViewText(R.id.wa_pct, today.creatingPct + "%");
    rv.setTextViewText(R.id.wa_time, InsightsEngine.formatHm(today.creatingMs));
    rv.setImageViewBitmap(R.id.wa_bar, renderSplitBarBitmap(today.creatingMs, today.consumingMs));
    rv.setTextViewText(R.id.wa_status, status);
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(c, 310, false));
    rv.setOnClickPendingIntent(R.id.wa_pause_btn, openAppIntent(c, 311, true));
    mgr.updateAppWidget(ids, rv);
    Metric.WIDGET_UPDATE.count(ids.length);
  }

  private static void updateB(Context c, AppWidgetManager mgr, InsightsEngine.TodaySummary today) {
    int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetBProvider.class));
    if (ids == null || ids.length == 0) return;
    RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_b);
    String line1 = today.creatingMs + today.consumingMs >= 60_000L
        ? "You built for " + InsightsEngine.formatHm(today.creatingMs) + "."
        : today.heroLine1;
    String line2 = today.creatingMs + today.consumingMs >= 60_000L
        ? "Scrolled " + InsightsEngine.formatHm(today.consumingMs) + "."
        : "All devices combined.";
    rv.setTextViewText(R.id.wb_line1, line1);
    rv.setTextViewText(R.id.wb_line2, line2);
    rv.setTextViewText(R.id.wb_sub, today.widgetSubtitle);
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(c, 312, false));
    mgr.updateAppWidget(ids, rv);
    Metric.WIDGET_UPDATE.count(ids.length);
  }

  private static void updateC(Context c, AppWidgetManager mgr, InsightsEngine.TodaySummary today, InsightsEngine.WeekSummary week) {
    int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetCProvider.class));
    if (ids == null || ids.length == 0) return;
    RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_c);
    rv.setTextViewText(R.id.wc_summary, today.creatingPct + "% today · " + week.weekCreatingPct + "% avg");
    rv.setImageViewBitmap(R.id.wc_chart, renderWeekBitmap(week.days));
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(c, 313, false));
    mgr.updateAppWidget(ids, rv);
    Metric.WIDGET_UPDATE.count(ids.length);
  }

  private static void updateE(Context c, AppWidgetManager mgr, InsightsEngine.TodaySummary today) {
    int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetEProvider.class));
    if (ids == null || ids.length == 0) return;
    RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_e);
    rv.setImageViewBitmap(R.id.we_ring, renderRingBitmap(today.creatingPct));
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(c, 314, false));
    mgr.updateAppWidget(ids, rv);
    Metric.WIDGET_UPDATE.count(ids.length);
  }

  public static Bitmap renderSplitBarBitmap(long creating, long consuming) {
    int w = 480, h = 24;
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Theme.TRACK);
    canvas.drawRoundRect(0, 0, w, h, 12, 12, p);
    long sum = creating + consuming;
    if (sum > 0) {
      float gap = (creating > 0 && consuming > 0) ? 6f : 0f;
      float avail = w - gap;
      float cw = avail * creating / (float) sum;
      if (cw > 1) {
        p.setColor(Theme.CREATE);
        canvas.drawRoundRect(0, 0, cw, h, 9, 9, p);
      }
      if (avail - cw > 1) {
        p.setColor(Theme.CONSUME);
        canvas.drawRoundRect(cw + gap, 0, w, h, 9, 9, p);
      }
    }
    return bmp;
  }

  public static Bitmap renderWeekBitmap(List<InsightsEngine.DayBar> days) {
    int w = 640, h = 200;
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    if (days == null || days.isEmpty()) return bmp;

    Paint createP = new Paint(Paint.ANTI_ALIAS_FLAG);
    createP.setColor(Theme.CREATE);
    Paint consumeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    consumeP.setColor(Theme.CONSUME);
    Paint pctP = new Paint(Paint.ANTI_ALIAS_FLAG);
    pctP.setColor(Theme.MUTED);
    pctP.setTextSize(22f);
    pctP.setTextAlign(Paint.Align.CENTER);
    Paint dayP = new Paint(Paint.ANTI_ALIAS_FLAG);
    dayP.setTextSize(22f);
    dayP.setTextAlign(Paint.Align.CENTER);

    int count = days.size();
    float gap = 16f;
    float colW = (w - gap * (count - 1)) / count;
    float topPad = 36f, botPad = 36f;
    float area = h - topPad - botPad;
    long maxTotal = 1;
    for (InsightsEngine.DayBar d : days) maxTotal = Math.max(maxTotal, d.creatingMs + d.consumingMs);

    for (int i = 0; i < count; i++) {
      InsightsEngine.DayBar d = days.get(i);
      float x = i * (colW + gap);
      float cx = x + colW / 2f;
      float base = h - botPad;
      float cH = d.creatingMs > 0 ? Math.max(8f, area * d.creatingMs / (float) maxTotal) : 0;
      float kH = d.consumingMs > 0 ? Math.max(8f, area * d.consumingMs / (float) maxTotal) : 0;
      float topY = base;
      if (cH > 0) {
        canvas.drawRoundRect(x, base - cH, x + colW, base, 7, 7, createP);
        topY = base - cH;
      }
      if (kH > 0) {
        float kBot = cH > 0 ? topY - 6f : base;
        canvas.drawRoundRect(x, kBot - kH, x + colW, kBot, 7, 7, consumeP);
        topY = kBot - kH;
      }
      String pct = (d.creatingMs + d.consumingMs) > 0 ? d.creatingPct + "%" : "—";
      canvas.drawText(pct, cx, Math.max(22f, topY - 10f), pctP);
      dayP.setColor(d.isToday ? Theme.FG : Theme.DIM);
      canvas.drawText(d.dayLabel, cx, h - 8f, dayP);
    }
    return bmp;
  }

  public static Bitmap renderRingBitmap(int pct) {
    int size = 280;
    Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    float pad = 26f;
    RectF oval = new RectF(pad, pad, size - pad, size - pad);

    Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    track.setStyle(Paint.Style.STROKE);
    track.setStrokeWidth(22f);
    track.setColor(Theme.HOLD_FILL);
    canvas.drawArc(oval, 0, 360, false, track);

    Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    arc.setStyle(Paint.Style.STROKE);
    arc.setStrokeWidth(22f);
    arc.setStrokeCap(Paint.Cap.ROUND);
    arc.setColor(Theme.FG);
    float sweep = Math.max(0, Math.min(100, pct)) * 3.6f;
    if (sweep > 0) canvas.drawArc(oval, -90f, sweep, false, arc);

    Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    num.setColor(Theme.FG);
    num.setTextSize(56f);
    num.setTextAlign(Paint.Align.CENTER);
    num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    canvas.drawText(pct + "%", size / 2f, size / 2f + 8f, num);

    Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
    lbl.setColor(Theme.MUTED);
    lbl.setTextSize(26f);
    lbl.setTextAlign(Paint.Align.CENTER);
    canvas.drawText("creating", size / 2f, size / 2f + 46f, lbl);
    return bmp;
  }
}

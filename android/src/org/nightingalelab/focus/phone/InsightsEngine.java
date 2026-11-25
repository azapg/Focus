package org.nightingalelab.focus.phone;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Computes combined all-device Today and 7-day Pattern metrics, heatmaps,
 * pause honesty checks, and natural prose summaries matching the Focus Android design.
 */
public final class InsightsEngine {
  public static final String[] BAND_LABELS = {"6-9", "9-12", "12-15", "15-18", "18-21", "21-24"};

  public static final class SpanRecord {
    public final long s;
    public final long e;
    public final String app;
    public final String label;
    public final String domain;
    public final String deviceKind; // "phone", "tv", "laptop"
    public final String deviceName;
    public final String deviceId;

    public SpanRecord(long s, long e, String app, String label, String domain, String deviceKind, String deviceName) {
      this(s, e, app, label, domain, deviceKind, deviceName, null);
    }

    public SpanRecord(long s, long e, String app, String label, String domain, String deviceKind, String deviceName, String deviceId) {
      this.s = s;
      this.e = e;
      this.app = app;
      this.label = label;
      this.domain = domain;
      this.deviceKind = deviceKind;
      this.deviceName = deviceName;
      this.deviceId = deviceId;
    }
  }

  public static final class PauseRecord {
    public final String deviceId;
    public final String deviceKind;
    public final String deviceName;
    public final long started;
    public final long ended; // wall ms, or now if still active
    public final boolean active;
    public final int minutes;
    public final String reason;
    public final String afterPlan;
    public final PausePolicy.CheckIn checkIn;
    public final int rating;
    public final String ratingNote;

    public PauseRecord(String deviceId, String deviceKind, String deviceName, long started, long ended,
        boolean active, int minutes, String reason, String afterPlan) {
      this(deviceId, deviceKind, deviceName, started, ended, active, minutes, reason, afterPlan, null, 0, "");
    }

    public PauseRecord(String deviceId, String deviceKind, String deviceName, long started, long ended,
        boolean active, int minutes, String reason, String afterPlan, PausePolicy.CheckIn checkIn, int rating, String ratingNote) {
      this.deviceId = deviceId;
      this.deviceKind = deviceKind;
      this.deviceName = deviceName;
      this.started = started;
      this.ended = ended;
      this.active = active;
      this.minutes = minutes;
      this.reason = reason == null ? "" : reason;
      this.afterPlan = afterPlan == null ? "" : afterPlan;
      this.checkIn = checkIn;
      this.rating = !active && PausePolicy.validRating(rating) ? rating : 0;
      this.ratingNote = this.rating == 0 ? "" : PausePolicy.ratingNote(ratingNote);
    }
  }

  public static final class AppRow {
    public final String name;
    public final String deviceLabel;
    public final String group;
    public final String matchType;
    public final String matchValue;
    public final long ms;

    public AppRow(String name, String deviceLabel, String group, String matchType, String matchValue, long ms) {
      this.name = name;
      this.deviceLabel = deviceLabel;
      this.group = group;
      this.matchType = matchType;
      this.matchValue = matchValue;
      this.ms = ms;
    }
  }

  public static final class PauseRow {
    public final String timeLabel;
    public final String deviceDuration;
    public final String reason;
    public final String outcome;
    public final boolean drift;
    public final String afterPlan;

    public PauseRow(String timeLabel, String deviceDuration, String reason, String outcome, boolean drift, String afterPlan) {
      this.timeLabel = timeLabel;
      this.deviceDuration = deviceDuration;
      this.reason = reason;
      this.outcome = outcome;
      this.drift = drift;
      this.afterPlan = afterPlan;
    }
  }

  public static final class DayBar {
    public final String dayLabel;
    public final String initial;
    public final long creatingMs;
    public final long consumingMs;
    public final int creatingPct;
    public final boolean isToday;

    public DayBar(String dayLabel, String initial, long creatingMs, long consumingMs, int creatingPct, boolean isToday) {
      this.dayLabel = dayLabel;
      this.initial = initial;
      this.creatingMs = creatingMs;
      this.consumingMs = consumingMs;
      this.creatingPct = creatingPct;
      this.isToday = isToday;
    }
  }

  public static final class TodaySummary {
    public long creatingMs;
    public long consumingMs;
    public long learningMs;
    public int creatingPct;
    public int yesterdayPct = -1;
    public String heroLine1;
    public String heroLine2;
    public String explainPlain;
    public String widgetSubtitle;
    public List<AppRow> creatingApps = new ArrayList<>();
    public List<AppRow> consumingApps = new ArrayList<>();
    public List<AppRow> uncategorized = new ArrayList<>();
    public List<PauseRow> pauses = new ArrayList<>();
    public int totalPausesToday;
    public String pauseRatingSummary = "";
  }

  public static final class WeekSummary {
    public int weekCreatingPct;
    public String heroLine1;
    public String heroLine2;
    public String explainText;
    public String leakText;
    public List<DayBar> days = new ArrayList<>();
    public int[][] heatLevels = new int[6][7]; // [band 0..5][day 0..6], level 0..4
    public int weekPauses;
    public int weekDriftPauses;
  }

  public static String formatHm(long ms) {
    long totalMin = Math.max(0, ms / 60_000L);
    long h = totalMin / 60;
    long m = totalMin % 60;
    if (h > 0) return h + "h " + String.format(Locale.ROOT, "%02dm", m);
    if (totalMin == 0 && ms >= 1000) return "<1m";
    return m + "m";
  }

  public static String formatCountdown(long remainingMs) {
    long sec = Math.max(0, (remainingMs + 999) / 1000);
    long h = sec / 3600;
    long m = (sec % 3600) / 60;
    long s = sec % 60;
    if (h > 0) return h + ":" + String.format(Locale.ROOT, "%02d:%02d", m, s);
    return m + ":" + String.format(Locale.ROOT, "%02d", s);
  }

  public static long dayStart(long nowMs, TimeZone tz) {
    Calendar cal = Calendar.getInstance(tz);
    cal.setTimeInMillis(nowMs);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  private static long overlap(long a, long b, long x, long y) {
    return Math.max(0L, Math.min(b, y) - Math.max(a, x));
  }

  private static String deviceShortLabel(String kind, String name) {
    if ("tv".equals(kind)) return "TV";
    if ("laptop".equals(kind)) return "Laptop";
    if ("phone".equals(kind)) return "Phone";
    return name == null || name.isEmpty() ? "Device" : name;
  }

  public static TodaySummary computeToday(
      List<SpanRecord> spans,
      List<PauseRecord> pauses,
      List<ActivityClassifier.UserRule> rules,
      long nowMs,
      TimeZone tz) {
    TodaySummary out = new TodaySummary();
    long todayStart = dayStart(nowMs, tz);
    long yestStart = todayStart - 86_400_000L;

    Map<String, AppRow> creatingMap = new LinkedHashMap<>();
    Map<String, AppRow> consumingMap = new LinkedHashMap<>();
    Map<String, AppRow> unknownMap = new LinkedHashMap<>();

    long yestCreating = 0;
    long yestConsuming = 0;

    // Longest continuous creating block (gap <= 3 min merges)
    String longestApp = "";
    long longestMs = 0;
    String curBlockApp = "";
    long curBlockEnd = 0;
    long curBlockMs = 0;

    // Track top consuming app + time of day for prose explanation
    Map<String, Long> consumeByApp = new HashMap<>();
    Map<String, Long> consumeMorningByApp = new HashMap<>();
    Map<String, Long> consumeNightByApp = new HashMap<>();

    List<SpanRecord> sorted = new ArrayList<>(spans);
    sorted.sort(Comparator.comparingLong(a -> a.s));

    Calendar cal = Calendar.getInstance(tz);
    for (SpanRecord sp : sorted) {
      if (sp.e <= sp.s) continue;
      ActivityClassifier.Result c = ActivityClassifier.classify(sp.app, sp.label, sp.domain, rules);
      if (c.noise) continue;

      long dy = overlap(sp.s, sp.e, yestStart, todayStart);
      if (dy > 0) {
        if (ActivityClassifier.GROUP_CREATING.equals(c.group)) yestCreating += dy;
        else if (ActivityClassifier.GROUP_CONSUMING.equals(c.group)) yestConsuming += dy;
      }

      long dt = overlap(sp.s, sp.e, todayStart, nowMs);
      if (dt <= 0) continue;

      String devLabel = deviceShortLabel(sp.deviceKind, sp.deviceName);
      String key = c.app + "::" + devLabel;
      if (ActivityClassifier.GROUP_CREATING.equals(c.group)) {
        out.creatingMs += dt;
        AppRow prev = creatingMap.get(key);
        creatingMap.put(key, new AppRow(c.app, devLabel, c.group, c.matchType, c.matchValue, (prev == null ? 0 : prev.ms) + dt));

        long segStart = Math.max(sp.s, todayStart);
        long segEnd = Math.min(sp.e, nowMs);
        if (c.app.equals(curBlockApp) && segStart - curBlockEnd <= 180_000L) {
          curBlockMs += dt;
          curBlockEnd = Math.max(curBlockEnd, segEnd);
        } else {
          curBlockApp = c.app;
          curBlockMs = dt;
          curBlockEnd = segEnd;
        }
        if (curBlockMs > longestMs) {
          longestMs = curBlockMs;
          longestApp = curBlockApp;
        }
      } else if (ActivityClassifier.GROUP_CONSUMING.equals(c.group)) {
        out.consumingMs += dt;
        AppRow prev = consumingMap.get(key);
        consumingMap.put(key, new AppRow(c.app, devLabel, c.group, c.matchType, c.matchValue, (prev == null ? 0 : prev.ms) + dt));
        consumeByApp.put(c.app, consumeByApp.getOrDefault(c.app, 0L) + dt);

        cal.setTimeInMillis(Math.max(sp.s, todayStart));
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour < 9) consumeMorningByApp.put(c.app, consumeMorningByApp.getOrDefault(c.app, 0L) + dt);
        else if (hour >= 21) consumeNightByApp.put(c.app, consumeNightByApp.getOrDefault(c.app, 0L) + dt);
      } else if (ActivityClassifier.GROUP_LEARN.equals(c.group)) {
        out.learningMs += dt;
      } else if (ActivityClassifier.GROUP_UNKNOWN.equals(c.group)) {
        String uKey = c.matchType + ":" + c.matchValue;
        AppRow prev = unknownMap.get(uKey);
        unknownMap.put(uKey, new AppRow(c.app, devLabel, c.group, c.matchType, c.matchValue, (prev == null ? 0 : prev.ms) + dt));
      }
    }

    out.creatingApps.addAll(creatingMap.values());
    out.creatingApps.sort((a, b) -> Long.compare(b.ms, a.ms));
    out.consumingApps.addAll(consumingMap.values());
    out.consumingApps.sort((a, b) -> Long.compare(b.ms, a.ms));
    out.uncategorized.addAll(unknownMap.values());
    out.uncategorized.sort((a, b) -> Long.compare(b.ms, a.ms));

    long denom = out.creatingMs + out.consumingMs;
    out.creatingPct = denom > 0 ? (int) Math.round(out.creatingMs * 100.0 / denom) : 0;
    long yDenom = yestCreating + yestConsuming;
    if (yDenom >= 60_000L) {
      out.yesterdayPct = (int) Math.round(yestCreating * 100.0 / yDenom);
    }

    // Build prose hero + explanation
    if (denom < 60_000L && out.learningMs > 0) {
      out.heroLine1 = "Learning today · " + formatHm(out.learningMs);
      out.heroLine2 = "";
      out.explainPlain = out.heroLine1;
      out.widgetSubtitle = "";
    } else if (denom < 60_000L) {
      out.heroLine1 = "Nothing recorded yet.";
      out.heroLine2 = "Your day shows up here.";
      out.explainPlain = "Keep Focus running on your phone and paired devices to see Creating vs Consuming.";
      out.widgetSubtitle = "No activity recorded yet today.";
    } else {
      out.heroLine1 = "You built for " + formatHm(out.creatingMs) + ".";
      out.heroLine2 = "Scrolled and watched " + formatHm(out.consumingMs) + ".";
      StringBuilder ex = new StringBuilder();
      ex.append("That's ").append(out.creatingPct).append("% creating");
      if (out.yesterdayPct >= 0) {
        if (out.creatingPct > out.yesterdayPct) {
          ex.append(", up from ").append(out.yesterdayPct).append("% yesterday.");
        } else if (out.creatingPct < out.yesterdayPct) {
          ex.append(", down from ").append(out.yesterdayPct).append("% yesterday.");
        } else {
          ex.append(", matching yesterday.");
        }
      } else {
        ex.append(" today.");
      }

      String topConsume = "";
      long topConsumeMs = 0;
      for (Map.Entry<String, Long> e : consumeByApp.entrySet()) {
        if (e.getValue() > topConsumeMs) {
          topConsumeMs = e.getValue();
          topConsume = e.getKey();
        }
      }
      if (!topConsume.isEmpty() && topConsumeMs >= 60_000L) {
        String timeHint = "";
        if (consumeMorningByApp.getOrDefault(topConsume, 0L) * 2 >= topConsumeMs) timeHint = " before 9am";
        else if (consumeNightByApp.getOrDefault(topConsume, 0L) * 2 >= topConsumeMs) timeHint = " after 21:00";
        ex.append(" Most of the scrolling was ").append(topConsume).append(timeHint).append(".");
      }
      if (!longestApp.isEmpty() && longestMs >= 10 * 60_000L) {
        ex.append(" Your longest focus was ").append(formatHm(longestMs)).append(" in ").append(longestApp).append(".");
      }
      out.explainPlain = ex.toString();

      StringBuilder wSub = new StringBuilder();
      if (out.yesterdayPct >= 0) {
        wSub.append(out.creatingPct >= out.yesterdayPct ? "Up from " : "Down from ")
            .append(out.yesterdayPct).append("% yesterday.");
      } else {
        wSub.append(out.creatingPct).append("% creating today.");
      }
      if (!topConsume.isEmpty() && topConsumeMs >= 60_000L) {
        wSub.append(" ").append(topConsume).append(" took most of it.");
      }
      out.widgetSubtitle = wSub.toString();
    }

    // Pauses today across all devices
    SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT);
    hhmm.setTimeZone(tz);
    List<PauseRecord> todayPauses = new ArrayList<>();
    for (PauseRecord p : pauses) {
      if (p.started >= todayStart && p.started <= nowMs) todayPauses.add(p);
    }
    todayPauses.sort(Comparator.comparingLong(a -> a.started));
    out.totalPausesToday = todayPauses.size();

    int rated = 0;
    int totalScore = 0;
    for (PauseRecord p : todayPauses) {
      PauseHonesty h = evaluatePauseHonesty(p, sorted, rules, nowMs);
      String devDur = deviceShortLabel(p.deviceKind, p.deviceName) + " · " + PausePolicy.formatDurationChip(p.minutes);
      out.pauses.add(new PauseRow(hhmm.format(new Date(p.started)), devDur, p.reason, h.summary, h.drift, p.afterPlan));
      if (p.rating > 0 && p.ended <= nowMs) {
        rated++;
        totalScore += p.rating;
      }
    }
    if (rated > 0) out.pauseRatingSummary = String.format(Locale.ROOT,
        "%d rated %s averaged %.1f/5 today.", rated, rated == 1 ? "pause" : "pauses", totalScore / (double) rated);

    return out;
  }

  public static final class PauseHonesty {
    public final String summary;
    public final boolean drift;

    public PauseHonesty(String summary, boolean drift) {
      this.summary = summary;
      this.drift = drift;
    }
  }

  public static PauseHonesty evaluatePauseHonesty(
      PauseRecord p, List<SpanRecord> spans, List<ActivityClassifier.UserRule> rules, long nowMs) {
    long end = p.active ? nowMs : Math.min(p.ended, nowMs);
    Map<String, Long> byApp = new HashMap<>();
    Map<String, String> groupByApp = new HashMap<>();
    for (SpanRecord sp : spans) {
      if (!sp.deviceKind.equals(p.deviceKind)) continue;
      long d = overlap(sp.s, sp.e, p.started, end);
      if (d <= 0) continue;
      ActivityClassifier.Result c = ActivityClassifier.classify(sp.app, sp.label, sp.domain, rules);
      if (c.noise) continue;
      byApp.put(c.app, byApp.getOrDefault(c.app, 0L) + d);
      groupByApp.put(c.app, c.group);
    }
    String topApp = "";
    long topMs = 0;
    boolean drift = false;
    String driftApp = "";
    long driftMs = 0;
    for (Map.Entry<String, Long> e : byApp.entrySet()) {
      if (e.getValue() < 15_000L) continue;
      if (e.getValue() > topMs) {
        topMs = e.getValue();
        topApp = e.getKey();
      }
      boolean matched = ActivityClassifier.reasonMentions(p.reason, e.getKey());
      String grp = groupByApp.get(e.getKey());
      boolean isWork = ActivityClassifier.GROUP_CREATING.equals(grp) || ActivityClassifier.GROUP_OTHER.equals(grp)
          || ActivityClassifier.GROUP_LEARN.equals(grp);
      if (!matched && !isWork && e.getValue() > driftMs) {
        drift = true;
        driftMs = e.getValue();
        driftApp = e.getKey();
      }
    }
    String prefix = p.active ? "so far: " : "did: ";
    if (drift) return new PauseHonesty(prefix + driftApp + " " + formatHm(driftMs), true);
    if (!topApp.isEmpty()) return new PauseHonesty(prefix + topApp + " " + formatHm(topMs), false);
    if (p.active) return new PauseHonesty("just started", false);
    if (p.afterPlan != null && !p.afterPlan.isEmpty()) return new PauseHonesty("then: " + p.afterPlan, false);
    return new PauseHonesty("nothing distracting", false);
  }

  public static WeekSummary computeWeek(
      List<SpanRecord> spans,
      List<PauseRecord> pauses,
      List<ActivityClassifier.UserRule> rules,
      long nowMs,
      TimeZone tz) {
    WeekSummary out = new WeekSummary();
    long todayStart = dayStart(nowMs, tz);
    long weekStart = todayStart - 6L * 86_400_000L;

    long[] dayCreating = new long[7];
    long[] dayConsuming = new long[7];
    long[][] heatMs = new long[6][7];
    long[] bandTotals = new long[6];

    Calendar cal = Calendar.getInstance(tz);
    for (SpanRecord sp : spans) {
      if (sp.e <= weekStart || sp.s >= nowMs || sp.e <= sp.s) continue;
      ActivityClassifier.Result c = ActivityClassifier.classify(sp.app, sp.label, sp.domain, rules);
      if (c.noise) continue;

      long cur = Math.max(sp.s, weekStart);
      long end = Math.min(sp.e, nowMs);
      while (cur < end) {
        int dayIdx = (int) ((cur - weekStart) / 86_400_000L);
        if (dayIdx < 0 || dayIdx >= 7) break;
        long nextDay = weekStart + (dayIdx + 1L) * 86_400_000L;
        long chunkEnd = Math.min(end, nextDay);
        long dt = chunkEnd - cur;
        if (ActivityClassifier.GROUP_CREATING.equals(c.group)) {
          dayCreating[dayIdx] += dt;
        } else if (ActivityClassifier.GROUP_CONSUMING.equals(c.group)) {
          dayConsuming[dayIdx] += dt;
          cal.setTimeInMillis(cur);
          int hour = cal.get(Calendar.HOUR_OF_DAY);
          if (hour >= 6) {
            int band = Math.min(5, (hour - 6) / 3);
            heatMs[band][dayIdx] += dt;
            bandTotals[band] += dt;
          }
        }
        cur = chunkEnd;
      }
    }

    SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.US);
    dayFmt.setTimeZone(tz);
    long totalC = 0, totalK = 0;
    long weekdayC = 0, weekdayK = 0, weekendC = 0, weekendK = 0;

    for (int i = 0; i < 7; i++) {
      long dStart = weekStart + i * 86_400_000L;
      cal.setTimeInMillis(dStart);
      String lbl = dayFmt.format(new Date(dStart));
      int dow = cal.get(Calendar.DAY_OF_WEEK);
      boolean isWeekend = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
      long c = dayCreating[i];
      long k = dayConsuming[i];
      totalC += c;
      totalK += k;
      if (isWeekend) {
        weekendC += c;
        weekendK += k;
      } else {
        weekdayC += c;
        weekdayK += k;
      }
      int pct = (c + k) > 0 ? (int) Math.round(c * 100.0 / (c + k)) : 0;
      out.days.add(new DayBar(lbl, lbl.substring(0, 1), c, k, pct, i == 6));
    }

    long totalDenom = totalC + totalK;
    out.weekCreatingPct = totalDenom > 0 ? (int) Math.round(totalC * 100.0 / totalDenom) : 0;

    // Heatmap scaling (0..4)
    long maxCell = 1;
    for (int b = 0; b < 6; b++) {
      for (int d = 0; d < 7; d++) {
        if (heatMs[b][d] > maxCell) maxCell = heatMs[b][d];
      }
    }
    for (int b = 0; b < 6; b++) {
      for (int d = 0; d < 7; d++) {
        long v = heatMs[b][d];
        if (v < 60_000L) out.heatLevels[b][d] = 0;
        else {
          double ratio = (double) v / maxCell;
          out.heatLevels[b][d] = ratio > 0.75 ? 4 : ratio > 0.50 ? 3 : ratio > 0.25 ? 2 : 1;
        }
      }
    }

    // Weekly pauses honesty
    for (PauseRecord p : pauses) {
      if (p.started < weekStart || p.started > nowMs) continue;
      out.weekPauses++;
      if (evaluatePauseHonesty(p, spans, rules, nowMs).drift) out.weekDriftPauses++;
    }

    // Headlines & prose
    if (totalDenom < 60_000L) {
      out.heroLine1 = "Patterns take a few days.";
      out.heroLine2 = "Keep Focus running.";
      out.explainText = "As your phone, TV, and laptop log activity, your 7-day breakdown and consuming heatmap fill in here.";
      out.leakText = "No pauses recorded in the last 7 days.";
      return out;
    }

    int wdPct = (weekdayC + weekdayK) > 0 ? (int) Math.round(weekdayC * 100.0 / (weekdayC + weekdayK)) : -1;
    int wePct = (weekendC + weekendK) > 0 ? (int) Math.round(weekendC * 100.0 / (weekendC + weekendK)) : -1;
    if (wdPct >= 55 && wePct >= 0 && wePct < 45) {
      out.heroLine1 = "Weekdays you build.";
      out.heroLine2 = "Weekends you watch.";
    } else if (out.weekCreatingPct >= 65) {
      out.heroLine1 = "Strong week creating.";
      out.heroLine2 = out.weekCreatingPct + "% across all devices.";
    } else if (out.weekCreatingPct < 45) {
      out.heroLine1 = "Consuming won this week.";
      out.heroLine2 = "Only " + out.weekCreatingPct + "% creating.";
    } else {
      out.heroLine1 = "Split week overall.";
      out.heroLine2 = out.weekCreatingPct + "% creating, " + (100 - out.weekCreatingPct) + "% consuming.";
    }

    StringBuilder ex = new StringBuilder();
    ex.append("You were ").append(out.weekCreatingPct).append("% creating this week.");
    if (wePct >= 0 && wePct < 50 && wdPct >= 50) {
      ex.append(" Saturday and Sunday dropped to ").append(wePct).append("%.");
    }
    out.explainText = ex.toString();

    int peakBand = 5;
    long peakBandMs = 0;
    long sumBands = 0;
    for (int b = 0; b < 6; b++) {
      sumBands += bandTotals[b];
      if (bandTotals[b] > peakBandMs) {
        peakBandMs = bandTotals[b];
        peakBand = b;
      }
    }
    StringBuilder lk = new StringBuilder();
    if (sumBands > 0 && peakBandMs > 0) {
      int share = (int) Math.round(peakBandMs * 100.0 / sumBands);
      String bandWord = peakBand == 5 ? "Nights are the leak" : peakBand == 0 ? "Mornings are the leak" : "Peak drift";
      lk.append(bandWord).append(": ").append(share).append("% of your consuming happens ")
          .append(BAND_LABELS[peakBand].replace("-", ":00–")).append(":00.");
    }
    if (out.weekPauses > 0) {
      if (lk.length() > 0) lk.append(" ");
      lk.append(out.weekDriftPauses).append(" of ").append(out.weekPauses)
          .append(" pauses this week didn't match their reason.");
    }
    out.leakText = lk.toString();

    return out;
  }
}

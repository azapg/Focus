package org.nightingalelab.focus.phone;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Everything the Focus main screen shows, computed from combined spans + pauses of all devices:
 * screen time, building vs drifting, automatic categories, per-app rows (devices, website, DNS-off
 * minutes), the 7-day bars, and pause rows with what was opened during each pause.
 *
 * Pure Java (no android.*) so PhoneCoreTest can run it on the desktop JVM.
 */
public final class FocusStats {
  public static final long MIN = 60_000L;
  public static final long DAY = 86_400_000L;
  /** A pause that ended within this window stays pinned at the top of the Pauses card. */
  public static final long FRESH_MS = 10 * MIN;
  /** An unmentioned drifting app counts as "drifted from the reason" after this long. */
  public static final long DRIFT_MIN_MS = 2 * MIN;

  public static final class Category {
    public final ActivityClassifier.Fine fine;
    public long ms;

    Category(ActivityClassifier.Fine fine) {
      this.fine = fine;
    }
  }

  public static final class AppStat {
    public final String name;
    public long ms;
    public long offMs;
    long webMs;
    final Map<String, Long> catMs = new HashMap<>();
    final Map<String, Long> deviceMs = new HashMap<>();
    final Map<String, Long> pkgMs = new HashMap<>();
    public ActivityClassifier.Fine category;
    /** Devices sorted by time, e.g. ["Laptop", "Phone"]. */
    public List<String> devices = new ArrayList<>();
    /** True when (nearly) all of the time was seen as a website. */
    public boolean web;
    /** Installed phone package with the most time, for the real app icon (may be null). */
    public String iconPackage;

    AppStat(String name) {
      this.name = name;
    }

    public boolean drifting() {
      return category != null && ActivityClassifier.FG_DRIFT.equals(category.group);
    }

    /** Time shows in amber when at least half of it happened with DNS off. */
    public boolean mostlyDnsOff() {
      return offMs >= MIN && offMs * 2 >= ms;
    }
  }

  public static final class DidApp {
    public final String name;
    public final long ms;
    public final boolean drifting;

    DidApp(String name, long ms, boolean drifting) {
      this.name = name;
      this.ms = ms;
      this.drifting = drifting;
    }
  }

  public static final class PauseStat {
    public String deviceId;
    public String deviceKind;
    public String deviceLabel;
    public long started;
    public long effectiveEnd;
    public boolean live;
    public boolean fresh;
    public int pickedMinutes;
    public long offMs;
    public String reason;
    public PausePolicy.CheckIn checkIn;
    public int rating;
    public String ratingNote;
    public String endingDomain;
    public String timeLabel;
    public String dayTimeLabel;
    public boolean drift;
    public List<DidApp> did = new ArrayList<>();

    /** Stable key for remembering which row is open. */
    public String key() {
      return deviceId + "|" + started;
    }
  }

  public static final class DayStat {
    public String label;
    public long dayStart;
    private long dayEnd;
    public boolean today;
    public long totalMs;
    public long offMs;
  }

  public static final class View {
    public long from;
    public long to;
    public boolean singleDay;
    public int nDays;
    public long totalMs;
    public long buildMs;
    public long driftMs;
    /** DNS-off in range across ALL devices (the limit is shared, so the ring ignores the device filter). */
    public long offAllMs;
    public List<Category> categories = new ArrayList<>();
    public List<AppStat> apps = new ArrayList<>();
    /** Pauses in range (device filter applied), newest first. */
    public List<PauseStat> pauses = new ArrayList<>();
    public String ratingSummary;
    public Map<Integer, Map<PausePolicy.CheckIn, Integer>> checkInsByHour = new TreeMap<>();
    public List<DayStat> week = new ArrayList<>();
    /** Devices with screen time in the last 7 days, e.g. ["Phone", "Laptop"]. */
    public Set<String> devicesWithData = new LinkedHashSet<>();

    /** Show device badges only when more than one device reports data. */
    public boolean multiDevice() {
      return devicesWithData.size() > 1;
    }

    /** Biggest 3 pauses with a running or just-ended pause pinned first. */
    public List<PauseStat> topPauses(int n) {
      List<PauseStat> fresh = new ArrayList<>();
      List<PauseStat> rest = new ArrayList<>();
      for (PauseStat p : pauses) (p.fresh ? fresh : rest).add(p);
      rest.sort((a, b) -> Long.compare(b.offMs, a.offMs));
      List<PauseStat> out = new ArrayList<>(fresh);
      out.addAll(rest);
      return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }
  }

  public static String deviceLabel(String kind, String name) {
    if ("tv".equals(kind)) return "TV";
    if ("laptop".equals(kind)) return "Laptop";
    if ("phone".equals(kind)) return "Phone";
    return name == null || name.isEmpty() ? "Device" : name;
  }

  /** "3h 47m", "48m", "<1m", "0m" (same rules as the design). */
  public static String fmt(long ms) {
    if (ms <= 0) return "0m";
    long r = Math.round(ms / (double) MIN);
    if (r < 1) return "<1m";
    long h = r / 60, m = r % 60;
    return h > 0 ? h + "h " + String.format(Locale.ROOT, "%02dm", m) : m + "m";
  }

  /** Compact week bar label: "45m", "6h". */
  public static String fmtBar(long ms) {
    long r = Math.round(ms / (double) MIN);
    return r < 60 ? r + "m" : Math.round(r / 60.0) + "h";
  }

  /** "4:59" countdown. */
  public static String fmtClock(long remainingMs) {
    long sec = Math.max(0, (remainingMs + 999) / 1000);
    return (sec / 60) + ":" + String.format(Locale.ROOT, "%02d", sec % 60);
  }

  private static long overlap(long a, long b, long x, long y) {
    return Math.max(0L, Math.min(b, y) - Math.max(a, x));
  }

  private static long effectiveEnd(InsightsEngine.PauseRecord p, long now) {
    return Math.max(p.started, Math.min(p.active ? now : p.ended, now));
  }

  static long localDayStart(long now, TimeZone tz, int offset) {
    Calendar day = Calendar.getInstance(tz);
    day.setTimeInMillis(now);
    day.add(Calendar.DAY_OF_YEAR, offset);
    return InsightsEngine.dayStart(day.getTimeInMillis(), tz);
  }

  private static String devKey(String kind, String name) {
    return kind + "|" + name;
  }

  /** DNS-off used today across every device: what the shared daily limit counts. */
  public static long dnsOffToday(List<InsightsEngine.PauseRecord> pauses, long now, TimeZone tz) {
    long todayStart = InsightsEngine.dayStart(now, tz);
    long sum = 0;
    for (InsightsEngine.PauseRecord p : pauses) {
      sum += overlap(p.started, effectiveEnd(p, now), todayStart, now);
    }
    return sum;
  }

  /**
   * @param week        false = today, true = last 7 days
   * @param selectedDay -1, or 0..6 to narrow to one day of the week (6 = today)
   * @param scope       null for all devices, or a device label ("Phone", "Laptop", "TV")
   */
  public static View compute(
      List<InsightsEngine.SpanRecord> spans,
      List<InsightsEngine.PauseRecord> pauses,
      List<ActivityClassifier.UserRule> rules,
      long now,
      TimeZone tz,
      boolean week,
      int selectedDay,
      String scope) {
    View v = new View();
    long[] boundaries = new long[8];
    for (int i = 0; i < boundaries.length; i++) boundaries[i] = localDayStart(now, tz, i - 6);
    long todayStart = boundaries[6];
    long weekStart = boundaries[0];
    if (selectedDay >= 0 && selectedDay <= 6) {
      v.from = boundaries[selectedDay];
      v.to = Math.min(now, boundaries[selectedDay + 1]);
      v.singleDay = true;
    } else if (week) {
      v.from = weekStart;
      v.to = now;
      v.singleDay = false;
    } else {
      v.from = todayStart;
      v.to = now;
      v.singleDay = true;
    }
    v.nDays = v.singleDay ? 1 : 7;

    // Merged DNS-off intervals per device, to count app minutes used with DNS off.
    Map<String, List<long[]>> offByDevice = new HashMap<>();
    for (InsightsEngine.PauseRecord p : pauses) {
      long end = effectiveEnd(p, now);
      if (end <= p.started) continue;
      offByDevice.computeIfAbsent(devKey(p.deviceKind, p.deviceName), k -> new ArrayList<>())
          .add(new long[] {p.started, end});
    }
    for (List<long[]> list : offByDevice.values()) merge(list);

    for (int i = 0; i < 7; i++) {
      DayStat d = new DayStat();
      d.dayStart = boundaries[i];
      d.dayEnd = boundaries[i + 1];
      d.today = i == 6;
      SimpleDateFormat df = new SimpleDateFormat("EEE", Locale.US);
      df.setTimeZone(tz);
      d.label = df.format(new Date(d.dayStart + (d.dayEnd - d.dayStart) / 2));
      v.week.add(d);
    }

    Map<String, Category> cats = new LinkedHashMap<>();
    Map<String, AppStat> apps = new HashMap<>();
    List<ClassifiedSpan> classified = new ArrayList<>();

    for (InsightsEngine.SpanRecord sp : spans) {
      if (sp.e <= sp.s) continue;
      ActivityClassifier.Result c = ActivityClassifier.classify(sp.app, sp.label, sp.domain, rules);
      if (c.noise) continue;
      String dev = deviceLabel(sp.deviceKind, sp.deviceName);
      ActivityClassifier.Fine f = ActivityClassifier.fineCategory(c, sp.deviceKind);
      ClassifiedSpan cs = new ClassifiedSpan(sp, c, f, dev);
      classified.add(cs);
      if (overlap(sp.s, sp.e, weekStart, now) > 0) v.devicesWithData.add(dev);
      if (scope != null && !scope.equals(dev)) continue;

      // Week bars (device filter applies)
      for (DayStat d : v.week) {
        d.totalMs += overlap(sp.s, sp.e, d.dayStart, Math.min(now, d.dayEnd));
      }

      long a = Math.max(sp.s, v.from);
      long b = Math.min(sp.e, v.to);
      if (b <= a) continue;
      long dt = b - a;
      long off = Math.min(dt, overlapAll(offByDevice.get(devKey(sp.deviceKind, sp.deviceName)), a, b));

      v.totalMs += dt;
      if (ActivityClassifier.FG_CREATE.equals(f.group)) v.buildMs += dt;
      if (ActivityClassifier.FG_DRIFT.equals(f.group)) v.driftMs += dt;
      cats.computeIfAbsent(f.id, k -> new Category(f)).ms += dt;

      AppStat app = apps.computeIfAbsent(c.app, AppStat::new);
      app.ms += dt;
      app.offMs += off;
      app.catMs.merge(f.id, dt, Long::sum);
      app.deviceMs.merge(dev, dt, Long::sum);
      if ("host".equals(c.matchType)) app.webMs += dt;
      if ("phone".equals(sp.deviceKind) && "package".equals(c.matchType) && sp.app != null && sp.app.contains(".")) {
        app.pkgMs.merge(sp.app, dt, Long::sum);
      }
    }

    for (Category c : cats.values()) if (c.ms >= 30_000L) v.categories.add(c);
    v.categories.sort((x, y) -> Long.compare(y.ms, x.ms));

    for (AppStat a : apps.values()) {
      if (a.ms < 24_000L) continue;
      a.category = ActivityClassifier.fineById(topKey(a.catMs));
      List<Map.Entry<String, Long>> devs = new ArrayList<>(a.deviceMs.entrySet());
      devs.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
      for (Map.Entry<String, Long> e : devs) a.devices.add(e.getKey());
      a.web = a.webMs * 10 >= a.ms * 9;
      a.iconPackage = topKey(a.pkgMs);
      v.apps.add(a);
    }
    v.apps.sort((x, y) -> {
      int byMs = Long.compare(y.ms, x.ms);
      return byMs != 0 ? byMs : x.name.compareToIgnoreCase(y.name);
    });

    // Pauses
    SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT);
    hhmm.setTimeZone(tz);
    SimpleDateFormat dayHhmm = new SimpleDateFormat("EEE HH:mm", Locale.US);
    dayHhmm.setTimeZone(tz);
    long pauseEndExclusive = selectedDay >= 0 && selectedDay < 6 ? v.to : now + 1;
    for (InsightsEngine.PauseRecord p : pauses) {
      long end = effectiveEnd(p, now);
      long actual = end - p.started;
      String dev = deviceLabel(p.deviceKind, p.deviceName);
      boolean scoped = scope == null || scope.equals(dev);
      for (DayStat d : v.week) {
        if (scoped && p.started >= d.dayStart && p.started < d.dayEnd) d.offMs += actual;
      }
      if (p.started < v.from || p.started >= pauseEndExclusive) continue;
      v.offAllMs += actual;
      if (!scoped) continue;

      PauseStat ps = new PauseStat();
      ps.deviceId = p.deviceId;
      ps.deviceKind = p.deviceKind;
      ps.deviceLabel = dev;
      ps.started = p.started;
      ps.effectiveEnd = end;
      ps.live = p.active && end >= now - 1000;
      ps.fresh = ps.live || (now - end >= 0 && now - end <= FRESH_MS);
      ps.pickedMinutes = p.minutes;
      ps.offMs = actual;
      ps.reason = p.reason;
      ps.checkIn = p.checkIn;
      ps.rating = p.ended <= now ? p.rating : 0;
      ps.ratingNote = ps.rating == 0 ? "" : p.ratingNote;
      ps.endingDomain = ps.rating == 0 ? "" : endingDomain(p, spans);
      ps.timeLabel = ps.live ? "Now" : hhmm.format(new Date(p.started));
      ps.dayTimeLabel = ps.live ? "Now" : dayHhmm.format(new Date(p.started));
      fillDid(ps, p, classified);
      v.pauses.add(ps);
      if (p.checkIn != null) {
        Calendar start = Calendar.getInstance(tz);
        start.setTimeInMillis(p.started);
        v.checkInsByHour.computeIfAbsent(start.get(Calendar.HOUR_OF_DAY), h -> new EnumMap<>(PausePolicy.CheckIn.class))
            .merge(p.checkIn, 1, Integer::sum);
      }
    }
    v.pauses.sort((x, y) -> Long.compare(y.started, x.started));
    v.ratingSummary = ratingSummary(v.pauses);
    return v;
  }

  private static String endingDomain(InsightsEngine.PauseRecord pause, List<InsightsEngine.SpanRecord> spans) {
    InsightsEngine.SpanRecord last = null;
    long lastEnd = 0;
    for (InsightsEngine.SpanRecord span : spans) {
      boolean sameDevice = span.deviceId != null
          ? span.deviceId.equals(pause.deviceId)
          : Objects.equals(span.deviceKind, pause.deviceKind) && Objects.equals(span.deviceName, pause.deviceName);
      if (!sameDevice || "FocusDNS".equals(span.label) || "org.nightingalelab.focus.phone".equals(span.app)
          || overlap(span.s, span.e, pause.started, pause.ended) <= 0) continue;
      long end = Math.min(span.e, pause.ended);
      if (last == null || end > lastEnd || (end == lastEnd && span.s > last.s)) {
        last = span;
        lastEnd = end;
      }
    }
    String domain = last == null || last.domain == null ? "" : last.domain.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    return domain.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") ? domain : "";
  }

  private static String ratingSummary(List<PauseStat> pauses) {
    List<PauseStat> rated = new ArrayList<>();
    for (PauseStat pause : pauses) if (pause.rating > 0) rated.add(pause);
    rated.sort((a, b) -> Long.compare(b.effectiveEnd, a.effectiveEnd));
    String domain = "";
    for (PauseStat pause : rated) {
      if (pause.endingDomain.isEmpty()) continue;
      domain = pause.endingDomain;
      break;
    }
    int count = 0;
    int total = 0;
    for (PauseStat pause : rated) {
      if (!domain.isEmpty() && !domain.equals(pause.endingDomain)) continue;
      total += pause.rating;
      if (++count == 8) break;
    }
    return count == 0 ? "" : String.format(Locale.ROOT, "Last %d rated %s%s · %.1f/5 average",
        count, count == 1 ? "pause" : "pauses", domain.isEmpty() ? "" : " ending on " + domain,
        total / (double) count);
  }

  /** What was opened on the paused device during the pause, and whether it drifted from the reason. */
  private static void fillDid(PauseStat ps, InsightsEngine.PauseRecord p, List<ClassifiedSpan> spans) {
    Map<String, Long> byApp = new HashMap<>();
    Map<String, ClassifiedSpan> sample = new HashMap<>();
    for (ClassifiedSpan cs : spans) {
      if (!cs.span.deviceKind.equals(p.deviceKind)) continue;
      if (!Objects.equals(cs.span.deviceName, p.deviceName)) continue;
      long d = overlap(cs.span.s, cs.span.e, ps.started, ps.effectiveEnd);
      if (d <= 0) continue;
      byApp.merge(cs.result.app, d, Long::sum);
      sample.putIfAbsent(cs.result.app, cs);
    }
    List<Map.Entry<String, Long>> list = new ArrayList<>(byApp.entrySet());
    list.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
    for (Map.Entry<String, Long> e : list) {
      ClassifiedSpan cs = sample.get(e.getKey());
      // Drift = a recognized consuming app the reason didn't mention (by name or activity), for 2m+.
      boolean consuming = ActivityClassifier.GROUP_CONSUMING.equals(cs.result.group);
      boolean mentioned = ActivityClassifier.reasonMentions(p.reason, e.getKey())
          || ActivityClassifier.reasonMentionsCategory(p.reason, cs.result.category);
      if (e.getValue() >= 30_000L) {
        ps.did.add(new DidApp(e.getKey(), e.getValue(), !mentioned && ActivityClassifier.FG_DRIFT.equals(cs.fine.group)));
      }
      if (consuming && !mentioned && e.getValue() >= DRIFT_MIN_MS) ps.drift = true;
    }
  }

  private static final class ClassifiedSpan {
    final InsightsEngine.SpanRecord span;
    final ActivityClassifier.Result result;
    final ActivityClassifier.Fine fine;
    final String device;

    ClassifiedSpan(InsightsEngine.SpanRecord span, ActivityClassifier.Result result, ActivityClassifier.Fine fine, String device) {
      this.span = span;
      this.result = result;
      this.fine = fine;
      this.device = device;
    }
  }

  private static String topKey(Map<String, Long> m) {
    String best = null;
    long bestMs = -1;
    for (Map.Entry<String, Long> e : m.entrySet()) {
      if (e.getValue() > bestMs) {
        bestMs = e.getValue();
        best = e.getKey();
      }
    }
    return best;
  }

  private static void merge(List<long[]> list) {
    list.sort((x, y) -> Long.compare(x[0], y[0]));
    List<long[]> out = new ArrayList<>();
    for (long[] iv : list) {
      if (!out.isEmpty() && iv[0] <= out.get(out.size() - 1)[1]) {
        long[] last = out.get(out.size() - 1);
        last[1] = Math.max(last[1], iv[1]);
      } else {
        out.add(new long[] {iv[0], iv[1]});
      }
    }
    list.clear();
    list.addAll(out);
  }

  private static long overlapAll(List<long[]> intervals, long a, long b) {
    if (intervals == null) return 0;
    long sum = 0;
    for (long[] iv : intervals) sum += overlap(a, b, iv[0], iv[1]);
    return sum;
  }

  /** "→ YouTube 13m, T3 Code 11m" parts for a pause row (the caller colors drifting apps). */
  public static String didText(DidApp d) {
    return d.name + " " + fmt(d.ms);
  }

  /** "Asked for 30m · DNS off 30m · drifted from the reason". */
  public static String pauseDetail(PauseStat p) {
    return "Asked for " + PausePolicy.formatDurationChip(Math.max(1, p.pickedMinutes))
        + " · DNS off " + fmt(p.offMs) + (p.live ? " so far" : "")
        + (p.drift ? " · drifted from the reason" : "");
  }

  /** Ring subtitle: "Limit 1h 00m · 13m left" (one day) or "28m/day · limit 1h 00m/day" (week). */
  public static String ringSubtitle(View v, int limitMinutes, boolean scoped) {
    long limitMs = limitMinutes * MIN;
    String lim = PausePolicy.formatMinutes(limitMinutes);
    String tail = scoped ? " · all devices" : "";
    if (v.nDays > 1) {
      return fmt(v.offAllMs / v.nDays) + "/day · limit " + lim + "/day" + tail;
    }
    long left = limitMs - v.offAllMs;
    String state = left < 0 ? fmt(-left) + " over" : left < 30_000L ? "limit reached" : fmt(left) + " left";
    return "Limit " + lim + " · " + state + tail;
  }

  /** Fraction of the (per-day) limit used, 0..1, for the ring. */
  public static float ringFraction(View v, int limitMinutes) {
    double perDay = v.offAllMs / (double) Math.max(1, v.nDays);
    return (float) Math.min(1.0, perDay / Math.max(1.0, limitMinutes * (double) MIN));
  }
}

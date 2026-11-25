package org.nightingalelab.focus.phone;

import java.util.*;

/**
 * Shared pause escalation rules across all devices plus the strictness ratchet:
 * tightening a rule starts immediately; loosening a rule waits until tomorrow (midnight local).
 */
public final class PausePolicy {
  public static final long RATING_WINDOW_MS = 10 * 60_000L;
  public static final int RATING_NOTE_LIMIT = 200;

  public enum CheckIn {
    TIRED("tired", "Tired"), BORED("bored", "Bored"), STUCK("stuck", "Stuck"),
    ANXIOUS("anxious", "Anxious"), SPECIFIC("specific", "Need something specific");

    public final String value;
    public final String label;

    CheckIn(String value, String label) {
      this.value = value;
      this.label = label;
    }

    public static CheckIn parse(String value) {
      for (CheckIn feeling : values()) if (feeling.value.equals(value)) return feeling;
      return null;
    }
  }

  public static boolean ratingOpen(long ended, long now) {
    return ended > 0 && now >= ended && now - ended < RATING_WINDOW_MS;
  }

  public static boolean validRating(int score) {
    return score >= 1 && score <= 5;
  }

  public static String ratingNote(String note) {
    if (note == null) return "";
    String clean = note.trim();
    return clean.substring(0, clean.offsetByCodePoints(0,
        Math.min(RATING_NOTE_LIMIT, clean.codePointCount(0, clean.length()))));
  }
  public static final int[] SHORT_DURATIONS = {1, 5, 10, 30};
  public static final int[] TV_DURATIONS = {30, 60, 120, 300};

  public static final class Config {
    public int waitThreshold = 3;      // Pause N triggers wait (default: 3rd pause)
    public int planThreshold = 5;      // Pause N triggers wait + "what's next" plan (default: 5th pause)
    public int waitSeconds = 15;       // Breathing wait duration in seconds
    public int maxPhoneMinutes = 30;
    public int maxLaptopMinutes = 30;
    public int maxTvMinutes = 300;     // 5h
    public int dailyLimitMinutes = 60; // Soft DNS-off limit per day, shared by all devices
    public boolean guardPrivateDns = true;
    public boolean notifyHonesty = true;
    public boolean notifyEvening = true;

    public Config copy() {
      Config c = new Config();
      c.waitThreshold = waitThreshold;
      c.planThreshold = planThreshold;
      c.waitSeconds = waitSeconds;
      c.maxPhoneMinutes = maxPhoneMinutes;
      c.maxLaptopMinutes = maxLaptopMinutes;
      c.maxTvMinutes = maxTvMinutes;
      c.dailyLimitMinutes = dailyLimitMinutes;
      c.guardPrivateDns = guardPrivateDns;
      c.notifyHonesty = notifyHonesty;
      c.notifyEvening = notifyEvening;
      return c;
    }
  }

  public static final class PendingChange {
    public final String key;
    public final String label;
    public final String fromText;
    public final String toText;
    public final int intValue;
    public final boolean boolValue;
    public final long effectiveAt;

    public PendingChange(String key, String label, String fromText, String toText, int intValue, boolean boolValue, long effectiveAt) {
      this.key = key;
      this.label = label;
      this.fromText = fromText;
      this.toText = toText;
      this.intValue = intValue;
      this.boolValue = boolValue;
      this.effectiveAt = effectiveAt;
    }
  }

  public static final class UpdateOutcome {
    public final boolean appliedNow;
    public final PendingChange pending;

    public UpdateOutcome(boolean appliedNow, PendingChange pending) {
      this.appliedNow = appliedNow;
      this.pending = pending;
    }
  }

  /** Returns escalation level for the next pause (1-indexed count across all devices today). */
  public static int level(Config cfg, int nextPauseNumber) {
    if (nextPauseNumber >= cfg.planThreshold) return 3;
    if (nextPauseNumber >= cfg.waitThreshold) return 2;
    return 1;
  }

  /** Hold-to-pause duration in milliseconds: 2s normally, 3s once escalated (level 2+). */
  public static long holdDurationMs(Config cfg, int nextPauseNumber) {
    return level(cfg, nextPauseNumber) >= 2 ? 3000L : 2000L;
  }

  public static int waitSecondsFor(Config cfg, int nextPauseNumber) {
    return level(cfg, nextPauseNumber) >= 2 ? Math.max(0, cfg.waitSeconds) : 0;
  }

  public static boolean requiresAfterPlan(Config cfg, int nextPauseNumber) {
    return level(cfg, nextPauseNumber) >= 3;
  }

  public static int[] allowedDurations(Config cfg, String deviceKind) {
    int[] base = "tv".equals(deviceKind) ? TV_DURATIONS : SHORT_DURATIONS;
    int max = "tv".equals(deviceKind) ? cfg.maxTvMinutes
        : "laptop".equals(deviceKind) ? cfg.maxLaptopMinutes
        : cfg.maxPhoneMinutes;
    List<Integer> out = new ArrayList<>();
    for (int m : base) {
      if (m <= max) out.add(m);
    }
    if (out.isEmpty()) out.add(base[0]);
    int[] arr = new int[out.size()];
    for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
    return arr;
  }

  /** Shortest reason the pause sheet accepts (matches Store.pause). */
  public static final int MIN_REASON_CHARS = 3;

  public static boolean reasonOk(String reason) {
    return reason != null && reason.trim().length() >= MIN_REASON_CHARS;
  }

  private static final java.util.regex.Pattern BARE_LINK = java.util.regex.Pattern.compile(
      "(?i)^[\\s\\p{Z}\\uFEFF]*(https?://[^\\s\\p{Z}\\uFEFF]+)[\\s\\p{Z}\\uFEFF]*$");

  public static String peekLink(String text) {
    java.util.regex.Matcher match = BARE_LINK.matcher(text);
    return match.matches() ? match.group(1) : null;
  }

  /** One duration chip in the pause sheet. */
  public static final class Choice {
    public final int minutes;
    public final boolean allowed;

    public Choice(int minutes, boolean allowed) {
      this.minutes = minutes;
      this.allowed = allowed;
    }
  }

  public static int maxPauseMinutes(Config cfg, String deviceKind) {
    return "tv".equals(deviceKind) ? cfg.maxTvMinutes
        : "laptop".equals(deviceKind) ? cfg.maxLaptopMinutes
        : cfg.maxPhoneMinutes;
  }

  /** Extrapolate a recently observed live pause only to its advertised end. */
  public static long projectedDnsEnd(long recordedEnd, long sampledAt, long pauseUntil, long now) {
    if (sampledAt <= 0 || sampledAt > now || now - sampledAt >= 75_000L || pauseUntil <= sampledAt) {
      return recordedEnd;
    }
    return Math.max(recordedEnd, Math.min(now, pauseUntil));
  }

  /** Minutes of DNS-off left today under the shared soft limit (never negative). */
  public static long minutesLeftToday(Config cfg, long dnsOffUsedTodayMs) {
    long left = cfg.dailyLimitMinutes * 60_000L - Math.max(0, dnsOffUsedTodayMs);
    return Math.max(0, left / 60_000L);
  }

  /**
   * Pause sheet durations: 1/5/10/30m, hidden above the device's max pause, and disabled when longer
   * than what's left of today's shared DNS-off limit. 1 minute is always allowed.
   */
  public static List<Choice> pauseChoices(Config cfg, String deviceKind, long dnsOffUsedTodayMs) {
    int max = maxPauseMinutes(cfg, deviceKind);
    long left = minutesLeftToday(cfg, dnsOffUsedTodayMs);
    List<Choice> out = new ArrayList<>();
    for (int m : SHORT_DURATIONS) {
      if (m > max && m != 1) continue;
      out.add(new Choice(m, m == 1 || m <= left));
    }
    return out;
  }

  /** Default chip: 5m when it's allowed, otherwise 1m. */
  public static int defaultChoice(List<Choice> choices) {
    for (Choice c : choices) if (c.minutes == 5 && c.allowed) return 5;
    return 1;
  }

  /** Sheet subtitle, e.g. "6th pause today · 15s wait · 13m left of your limit". */
  public static String sheetNote(Config cfg, int nextPauseNumber, long dnsOffUsedTodayMs) {
    int wait = waitSecondsFor(cfg, nextPauseNumber);
    long left = minutesLeftToday(cfg, dnsOffUsedTodayMs);
    String limit = left < 1 ? "limit reached · 1m available" : formatMinutes(left) + " left of your limit";
    return ordinal(nextPauseNumber) + " pause today · " + (wait > 0 ? wait + "s wait" : "no wait") + " · " + limit;
  }

  /** "45m", "1h 00m", "2h 30m". */
  public static String formatMinutes(long minutes) {
    long h = minutes / 60, m = minutes % 60;
    return h > 0 ? h + "h " + String.format(Locale.ROOT, "%02dm", m) : m + "m";
  }

  public static String ordinal(int n) {
    if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
    switch (n % 10) {
      case 1: return n + "st";
      case 2: return n + "nd";
      case 3: return n + "rd";
      default: return n + "th";
    }
  }

  public static String formatDurationChip(int minutes) {
    return minutes < 60 ? minutes + "m" : (minutes / 60) + "h";
  }

  public static String costSummary(Config cfg, int nextPauseNumber) {
    int lvl = level(cfg, nextPauseNumber);
    if (lvl == 1) return ordinal(nextPauseNumber) + " pause today · just a reason";
    if (lvl == 2) return ordinal(nextPauseNumber) + " pause today · " + cfg.waitSeconds + "s wait";
    return ordinal(nextPauseNumber) + " pause today · " + cfg.waitSeconds + "s wait + plan";
  }

  public static long nextMidnightMs(long nowMs, TimeZone tz) {
    Calendar cal = Calendar.getInstance(tz);
    cal.setTimeInMillis(nowMs);
    cal.add(Calendar.DAY_OF_YEAR, 1);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  /**
   * Evaluates a proposed rule change under the strictness ratchet:
   * - Tightening applies immediately and clears any queued loosening for that key.
   * - Loosening is scheduled for tomorrow midnight local time.
   */
  public static UpdateOutcome proposeInt(
      Config cfg, Map<String, PendingChange> pending, String key, int newValue, long nowMs, TimeZone tz) {
    int current;
    boolean tightening;
    String label;
    String fromText;
    String toText;
    switch (key) {
      case "wait_seconds":
        newValue = Math.max(5, Math.min(120, newValue));
        current = cfg.waitSeconds;
        tightening = newValue >= current;
        label = "Wait";
        fromText = current + "s";
        toText = newValue + "s";
        break;
      case "wait_threshold":
        newValue = Math.max(1, Math.min(10, newValue));
        current = cfg.waitThreshold;
        tightening = newValue <= current; // lower threshold = stricter
        label = "Wait starts at";
        fromText = ordinal(current) + " pause";
        toText = ordinal(newValue) + " pause";
        break;
      case "plan_threshold":
        newValue = Math.max(cfg.waitThreshold, Math.min(15, newValue));
        current = cfg.planThreshold;
        tightening = newValue <= current;
        label = "Plan starts at";
        fromText = ordinal(current) + " pause";
        toText = ordinal(newValue) + " pause";
        break;
      case "max_phone_minutes":
        current = cfg.maxPhoneMinutes;
        tightening = newValue <= current;
        label = "Phone longest pause";
        fromText = formatDurationChip(current);
        toText = formatDurationChip(newValue);
        break;
      case "max_laptop_minutes":
        current = cfg.maxLaptopMinutes;
        tightening = newValue <= current;
        label = "Laptop longest pause";
        fromText = formatDurationChip(current);
        toText = formatDurationChip(newValue);
        break;
      case "max_tv_minutes":
        current = cfg.maxTvMinutes;
        tightening = newValue <= current;
        label = "TV longest pause";
        fromText = formatDurationChip(current);
        toText = formatDurationChip(newValue);
        break;
      case "daily_limit_minutes":
        newValue = Math.max(5, Math.min(600, newValue));
        current = cfg.dailyLimitMinutes;
        tightening = newValue <= current; // a lower limit is stricter
        label = "Daily DNS-off limit";
        fromText = formatMinutes(current);
        toText = formatMinutes(newValue);
        break;
      default:
        throw new IllegalArgumentException("Unknown policy key: " + key);
    }
    if (newValue == current) {
      pending.remove(key);
      return new UpdateOutcome(true, null);
    }
    if (tightening) {
      applyInt(cfg, key, newValue);
      pending.remove(key);
      return new UpdateOutcome(true, null);
    }
    PendingChange change = new PendingChange(
        key, label, fromText, toText, newValue, false, nextMidnightMs(nowMs, tz));
    pending.put(key, change);
    return new UpdateOutcome(false, change);
  }

  public static UpdateOutcome proposeGuardPrivateDns(
      Config cfg, Map<String, PendingChange> pending, boolean enabled, long nowMs, TimeZone tz) {
    if (enabled == cfg.guardPrivateDns) {
      pending.remove("guard_private_dns");
      return new UpdateOutcome(true, null);
    }
    if (enabled) {
      cfg.guardPrivateDns = true;
      pending.remove("guard_private_dns");
      return new UpdateOutcome(true, null);
    }
    PendingChange change = new PendingChange(
        "guard_private_dns", "Private DNS guard", "On", "Off", 0, false, nextMidnightMs(nowMs, tz));
    pending.put("guard_private_dns", change);
    return new UpdateOutcome(false, change);
  }

  public static boolean applyDuePending(Config cfg, Map<String, PendingChange> pending, long nowMs) {
    boolean changed = false;
    Iterator<Map.Entry<String, PendingChange>> it = pending.entrySet().iterator();
    while (it.hasNext()) {
      PendingChange p = it.next().getValue();
      if (nowMs >= p.effectiveAt) {
        if ("guard_private_dns".equals(p.key)) cfg.guardPrivateDns = p.boolValue;
        else applyInt(cfg, p.key, p.intValue);
        it.remove();
        changed = true;
      }
    }
    return changed;
  }

  private static void applyInt(Config cfg, String key, int value) {
    switch (key) {
      case "wait_seconds": cfg.waitSeconds = value; break;
      case "wait_threshold":
        cfg.waitThreshold = value;
        if (cfg.planThreshold < value) cfg.planThreshold = value;
        break;
      case "plan_threshold": cfg.planThreshold = Math.max(cfg.waitThreshold, value); break;
      case "max_phone_minutes": cfg.maxPhoneMinutes = value; break;
      case "max_laptop_minutes": cfg.maxLaptopMinutes = value; break;
      case "max_tv_minutes": cfg.maxTvMinutes = value; break;
      case "daily_limit_minutes": cfg.dailyLimitMinutes = value; break;
    }
  }
}

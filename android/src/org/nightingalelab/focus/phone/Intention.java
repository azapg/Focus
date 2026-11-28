package org.nightingalelab.focus.phone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One intention ("Cleaning house, 45 min, done when the trash is out"), shared with the laptop.
 * Pure Java: the JSON shape, the offline sentence parser and the drift rules live here so they
 * can be unit tested without Android.
 */
final class Intention {
  static final long MINUTE = 60_000L;
  static final int DEFAULT_MINUTES = 60;
  static final int MIN_MINUTES = 1;
  static final int MAX_MINUTES = 240;
  static final int EXTEND_MINUTES = 10;
  static final int MAX_LABEL = 80;
  static final int MAX_DONE_WHEN = 140;
  static final int MAX_RAW = 300;

  final String id;
  final long start;
  final long until;
  final int plannedMinutes;
  final String label;
  final String doneWhen;
  final String raw;
  final String parseSource;
  final int extensions;

  Intention(String id, long start, long until, int plannedMinutes, String label, String doneWhen,
      String raw, String parseSource, int extensions) {
    this.id = id;
    this.start = start;
    this.until = until;
    this.plannedMinutes = plannedMinutes;
    this.label = label;
    this.doneWhen = doneWhen == null ? "" : doneWhen;
    this.raw = raw == null ? "" : raw;
    this.parseSource = parseSource == null || parseSource.isEmpty() ? "local" : parseSource;
    this.extensions = extensions;
  }

  static Intention started(String id, long at, Parsed parsed) {
    return new Intention(id, at, at + parsed.minutes * MINUTE, parsed.minutes, parsed.label,
        parsed.doneWhen, parsed.raw, parsed.source, 0);
  }


  /** Same rule as the laptop: add minutes from whichever is later, now or the planned end. */
  Intention extended(long at, int minutes) {
    long next = Math.max(at, until) + clamp(minutes, 1, 60) * MINUTE;
    return new Intention(id, start, next, plannedMinutes, label, doneWhen, raw, parseSource, extensions + 1);
  }

  boolean timeUp(long now) { return now >= until; }

  long remainingMs(long now) { return Math.max(0, until - now); }

  float progress(long now) {
    if (until <= start) return 1f;
    return Math.max(0f, Math.min(1f, (now - start) / (float) (until - start)));
  }

  boolean sameAs(Intention other) {
    return other != null && id.equals(other.id) && until == other.until && label.equals(other.label)
        && doneWhen.equals(other.doneWhen);
  }

  /** "31m left" or "1h 05m left". */
  static String left(long ms) {
    long minutes = (ms + MINUTE - 1) / MINUTE;
    if (minutes < 60) return minutes + "m left";
    return (minutes / 60) + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "m left";
  }

  static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

  private static final Pattern IS_DONE = Pattern.compile("(?i)^((?:the\\s+)?\\S+(?:\\s+\\S+){0,2})\\s+is\\s+(.+)$");
  private static final Pattern ARE_DONE = Pattern.compile("(?i)^((?:the\\s+)?\\S+(?:\\s+\\S+){0,2})\\s+are\\s+(.+)$");

  /** "the trash is out" becomes "Is the trash out?". Same wording as doneWhenQuestion in ui/stats.js. */
  String question() {
    String text = doneWhen.trim().replaceAll("[.!,;]+$", "");
    if (text.isEmpty()) return "Done?";
    if (text.endsWith("?")) return capitalize(text);
    Matcher is = IS_DONE.matcher(text);
    if (is.matches()) return "Is " + is.group(1) + " " + is.group(2) + "?";
    Matcher are = ARE_DONE.matcher(text);
    if (are.matches()) return "Are " + are.group(1) + " " + are.group(2) + "?";
    return capitalize(text) + "?";
  }

  // ---------------------------------------------------------------------------------------------
  // Offline parser. The laptop reads sentences with the AI Gateway; this covers the phone when the
  // laptop is asleep. It mirrors parse_local in app/src/intentions.rs for the common forms.
  // ---------------------------------------------------------------------------------------------

  static final class Parsed {
    final String label;
    final int minutes;
    final String doneWhen;
    final String raw;
    final String source;

    Parsed(String label, int minutes, String doneWhen, String raw, String source) {
      this.label = label;
      this.minutes = clamp(minutes, MIN_MINUTES, MAX_MINUTES);
      this.doneWhen = doneWhen == null ? "" : doneWhen;
      this.raw = raw;
      this.source = source;
    }

  }

  private static final Pattern DONE = Pattern.compile(
      "(?i)(?:^|[\\s,;:\\-])(done (?:when|once|after|if) |done:)");
  private static final Pattern UNTIL = Pattern.compile(
      "(?i)(?<![a-z0-9])(?:until|till|til|by)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?![a-z0-9])");
  private static final Pattern NAMED = Pattern.compile(
      "(?i)(?<![a-z0-9])(?:for\\s+)?(half an hour|an hour)(?![a-z0-9])");
  private static final Pattern NUMBER = Pattern.compile(
      "(?i)(?<![a-z0-9.])(?:for\\s+)?(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m)"
          + "(?:\\s*(?:and\\s+)?(\\d{1,2})\\s*(?:minutes?|mins?|m))?(?![a-z0-9])");

  static String cleanText(String raw, int max) {
    String text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    return text.length() > max ? text.substring(0, max).trim() : text;
  }

  /** Parses one sentence. `localMinutes` is minutes since local midnight, for "until 11:45". */
  static Parsed parseLocal(String rawText, int localMinutes) {
    String raw = cleanText(rawText, MAX_RAW);
    String head = raw;
    String doneWhen = "";
    Matcher done = DONE.matcher(raw);
    if (done.find()) {
      head = raw.substring(0, done.start(1));
      doneWhen = cleanText(raw.substring(done.end(1)).replaceAll("[.,;\\s]+$", ""), MAX_DONE_WHEN);
    }
    Integer minutes = null;
    Matcher until = UNTIL.matcher(head);
    while (minutes == null && until.find()) {
      Integer diff = untilMinutes(until, localMinutes);
      if (diff != null) {
        minutes = diff;
        head = remove(head, until.start(), until.end());
      }
    }
    if (minutes == null) {
      Matcher named = NAMED.matcher(head);
      if (named.find()) {
        minutes = named.group(1).toLowerCase(Locale.ROOT).startsWith("half") ? 30 : 60;
        head = remove(head, named.start(), named.end());
      }
    }
    if (minutes == null) {
      Matcher number = NUMBER.matcher(head);
      if (number.find()) {
        double value = Double.parseDouble(number.group(1));
        boolean hours = number.group(2).toLowerCase(Locale.ROOT).startsWith("h");
        double total = hours ? value * 60 : value;
        if (hours && number.group(3) != null) total += Integer.parseInt(number.group(3));
        minutes = (int) Math.round(total);
        head = remove(head, number.start(), number.end());
      }
    }
    String label = cleanLabel(head);
    if (label.isEmpty()) label = capitalize(cleanText(raw, MAX_LABEL));
    return new Parsed(label, minutes == null ? DEFAULT_MINUTES : minutes, doneWhen, raw, "local");
  }

  private static Integer untilMinutes(Matcher m, int localMinutes) {
    int hour = Integer.parseInt(m.group(1));
    int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    String meridiem = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
    if (minute >= 60) return null;
    // A bare "by 3" is too ambiguous, like on the laptop.
    if (meridiem.isEmpty() && m.group(2) == null) return null;
    int target;
    if (!meridiem.isEmpty()) {
      if (hour < 1 || hour > 12) return null;
      target = (hour % 12 + ("pm".equals(meridiem) ? 12 : 0)) * 60 + minute;
    } else {
      if (hour > 23) return null;
      target = hour * 60 + minute;
      // "until 3:30" in the afternoon means 15:30.
      if (target <= localMinutes && hour < 12 && target + 720 > localMinutes) target += 720;
    }
    if (target <= localMinutes) target += 1440;
    int diff = target - localMinutes;
    return diff > 0 && diff <= MAX_MINUTES ? diff : null;
  }

  private static String remove(String text, int from, int to) {
    String left = text.substring(0, from).trim();
    String right = text.substring(to).trim();
    if (left.isEmpty()) return right;
    if (right.isEmpty()) return left;
    return left + " " + right;
  }

  private static String cleanLabel(String text) {
    List<String> words = new ArrayList<>();
    for (String part : text.trim().split("\\s+")) {
      String word = part.replaceAll("^[,;]+|[,;]+$", "");
      if (!word.isEmpty()) words.add(word);
    }
    String joined = String.join(" ", words).replaceAll("^[\\s,;:\\-—·]+|[\\s,;:\\-—·]+$", "");
    return capitalize(cleanText(joined, MAX_LABEL));
  }

  static String capitalize(String text) {
    if (text.isEmpty() || !Character.isLowerCase(text.charAt(0))) return text;
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Drift: time in something that doesn't fit while an intention runs.
  // ---------------------------------------------------------------------------------------------

  /** Nudge once after this much unbroken drift. */
  static final long NUDGE_AFTER_MS = 3 * MINUTE;
  /** At most one nudge per intention in this window, so it never repeats while you stay. */
  static final long NUDGE_COOLDOWN_MS = 15 * MINUTE;
  /** Shorter runs are a glance, not drift. */
  static final long DRIFT_SHOW_MS = MINUTE;
  /** Keep showing orange this long after leaving, so opening Focus still shows what happened. */
  static final long DRIFT_LINGER_MS = 2 * MINUTE;
  /** Gaps up to this long (a notification shade, a lock) don't break a run. */
  static final long DRIFT_GAP_MS = 15_000L;

  /** One stretch of phone use. `drift` is decided by the caller from the app's category. */
  static final class Use {
    final long s;
    final long e;
    final String app;
    final boolean drift;

    Use(long s, long e, String app, boolean drift) {
      this.s = s;
      this.e = e;
      this.app = app;
      this.drift = drift;
    }
  }

  static final class Drift {
    static final Drift NONE = new Drift("", 0, false);
    final String app;
    final long ms;
    final boolean current;

    Drift(String app, long ms, boolean current) {
      this.app = app;
      this.ms = ms;
      this.current = current;
    }

    boolean showing() { return ms >= DRIFT_SHOW_MS; }

    /** "Block Puzzle 6m off-plan". */
    String text() { return app + " " + Math.max(1, ms / MINUTE) + "m off-plan"; }
  }

  /**
   * Finds the latest run of drifting use since the intention started. A run is current when its
   * last stretch reaches `now`; an ended run still shows for {@link #DRIFT_LINGER_MS}.
   */
  static Drift drift(List<Use> uses, long since, long now) {
    long runStart = -1;
    long runEnd = -1;
    String app = "";
    long lastStart = -1;
    long lastEnd = -1;
    String lastApp = "";
    for (Use use : uses) {
      long s = Math.max(use.s, since);
      long e = Math.min(use.e, now);
      if (e <= s) continue;
      // A few seconds elsewhere (the launcher, the shade) doesn't end a run.
      boolean breaksRun = !use.drift && e - s > DRIFT_GAP_MS;
      boolean gapTooLong = runEnd >= 0 && s - runEnd > DRIFT_GAP_MS;
      if (runStart >= 0 && (breaksRun || gapTooLong)) {
        lastStart = runStart;
        lastEnd = runEnd;
        lastApp = app;
        runStart = runEnd = -1;
      }
      if (!use.drift) continue;
      if (runStart < 0) runStart = s;
      if (!use.app.isEmpty()) app = use.app;
      runEnd = Math.max(runEnd, e);
    }
    if (runStart >= 0 && now - runEnd <= DRIFT_GAP_MS) return new Drift(app, runEnd - runStart, true);
    if (runStart >= 0) {
      lastStart = runStart;
      lastEnd = runEnd;
      lastApp = app;
    }
    if (lastStart >= 0 && now - lastEnd <= DRIFT_LINGER_MS) return new Drift(lastApp, lastEnd - lastStart, false);
    return Drift.NONE;
  }

  /** True when a heads-up is due: a current run past 3 minutes, and no nudge in the last 15. */
  static boolean nudgeDue(Drift drift, long lastNudgeAt, long now) {
    return drift.current && drift.ms >= NUDGE_AFTER_MS && now - lastNudgeAt >= NUDGE_COOLDOWN_MS;
  }
}

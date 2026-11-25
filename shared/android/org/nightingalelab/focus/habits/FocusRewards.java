package org.nightingalelab.focus.habits;

import java.util.*;

/**
 * Pure single-app focus projection and native finalized-day coin earning adapter.
 *
 * Mirrors Rust {@code app/src/habits/focus_rewards.rs}. Only the single profile finalization
 * authority invokes {@link #awardFinalizedDay} inside {@link HabitStore#updateWallet}.
 */
public final class FocusRewards {
  public static final String FOCUS_KIND = "focus";
  public static final long FOCUS_GLANCE_MS = 45_000L;
  public static final long FOCUS_AWAY_MS = 2L * 60_000L;
  public static final int TRIAL_COINS_PER_MILESTONE = 5;
  public static final long TRIAL_MILESTONE_MS = 30L * 60_000L;
  public static final int TRIAL_MAX_MILESTONES = 4;

  public enum Category {
    CODING(25L * 60_000L, false),
    BUSINESS(25L * 60_000L, false),
    WRITING(25L * 60_000L, false),
    DESIGN(25L * 60_000L, false),
    READING(15L * 60_000L, false),
    LEARNING(15L * 60_000L, false),
    WASTE(0L, true),
    OTHER(0L, false);

    public final long minRunMs;
    public final boolean waste;

    Category(long minRunMs, boolean waste) {
      this.minRunMs = minRunMs;
      this.waste = waste;
    }
  }

  public static final class Span {
    public final long startMs;
    public final long endMs;
    public final String app;
    public final Category category;

    public Span(long startMs, long endMs, String app, Category category) {
      this.startMs = startMs;
      this.endMs = endMs;
      this.app = app == null ? "" : app;
      this.category = category == null ? Category.OTHER : category;
    }
  }

  public static final class DeviceStream {
    public final List<Span> spans;
    public final List<long[]> pauses;

    public DeviceStream(List<Span> spans, List<long[]> pauses) {
      this.spans = spans == null ? Collections.emptyList() : spans;
      this.pauses = pauses == null ? Collections.emptyList() : pauses;
    }
  }

  public static final class Rate {
    public static final Rate TRIAL_DEFAULT =
        new Rate(TRIAL_COINS_PER_MILESTONE, TRIAL_MILESTONE_MS, TRIAL_MAX_MILESTONES);

    public final int coinsPerMilestone;
    public final long milestoneMs;
    public final int maxMilestones;

    public Rate(int coinsPerMilestone, long milestoneMs, int maxMilestones) {
      this.coinsPerMilestone = coinsPerMilestone;
      this.milestoneMs = milestoneMs;
      this.maxMilestones = maxMilestones;
    }

    public boolean valid() {
      return coinsPerMilestone > 0
          && coinsPerMilestone <= 10_000
          && milestoneMs > 0
          && maxMilestones > 0
          && maxMilestones <= 48;
    }
  }

  public static final class FinalizedDay {
    public final String date;
    public final int day;
    public final long dayStartMs;
    public final long dayEndMs;
    public final boolean complete;
    public final long unionFocusMs;
    public final long atMs;

    public FinalizedDay(
        String date,
        int day,
        long dayStartMs,
        long dayEndMs,
        boolean complete,
        long unionFocusMs,
        long atMs) {
      this.date = date;
      this.day = day;
      this.dayStartMs = dayStartMs;
      this.dayEndMs = dayEndMs;
      this.complete = complete;
      this.unionFocusMs = Math.max(0L, unionFocusMs);
      this.atMs = atMs;
    }
  }

  public enum Status {
    AWARDED,
    ALREADY_AWARDED,
    NO_MILESTONES,
    WALLET_DORMANT,
    NOT_FINALIZED
  }

  public static final class AwardOutcome {
    public final Status status;
    public final int milestonesAwarded;
    public final int alreadyPresent;
    public final int coinsAwarded;

    public AwardOutcome(Status status, int milestonesAwarded, int alreadyPresent, int coinsAwarded) {
      this.status = status;
      this.milestonesAwarded = milestonesAwarded;
      this.alreadyPresent = alreadyPresent;
      this.coinsAwarded = coinsAwarded;
    }
  }

  private FocusRewards() {}

  private static List<long[]> mergeIntervals(List<long[]> raw) {
    List<long[]> valid = new ArrayList<>();
    for (long[] iv : raw) {
      if (iv != null && iv.length >= 2 && iv[1] > iv[0]) valid.add(new long[] {iv[0], iv[1]});
    }
    valid.sort((a, b) -> {
      int c = Long.compare(a[0], b[0]);
      return c != 0 ? c : Long.compare(a[1], b[1]);
    });
    List<long[]> merged = new ArrayList<>();
    for (long[] iv : valid) {
      if (!merged.isEmpty() && iv[0] <= merged.get(merged.size() - 1)[1]) {
        long[] last = merged.get(merged.size() - 1);
        last[1] = Math.max(last[1], iv[1]);
      } else {
        merged.add(iv);
      }
    }
    return merged;
  }

  private static List<long[]> subtractIntervals(long a, long b, List<long[]> cuts) {
    List<long[]> out = new ArrayList<>();
    if (b <= a) return out;
    out.add(new long[] {a, b});
    for (long[] cut : cuts) {
      long cx = cut[0], cy = cut[1];
      if (cy <= a) continue;
      if (cx >= b) break;
      List<long[]> next = new ArrayList<>();
      for (long[] iv : out) {
        long x = iv[0], y = iv[1];
        if (cy <= x || cx >= y) {
          next.add(iv);
        } else {
          if (cx > x) next.add(new long[] {x, cx});
          if (cy < y) next.add(new long[] {cy, y});
        }
      }
      out = next;
      if (out.isEmpty()) break;
    }
    return out;
  }

  /** Union pre-verified qualifying intervals across devices within [dayStartMs, dayEndMs), minus pauses. */
  public static long unionVerifiedIntervalsMs(
      List<long[]> intervals, List<long[]> pauses, long dayStartMs, long dayEndMs) {
    if (dayEndMs <= dayStartMs || intervals == null || intervals.isEmpty()) return 0L;
    List<long[]> clippedPauses = new ArrayList<>();
    if (pauses != null) {
      for (long[] p : pauses) {
        if (p == null || p.length < 2) continue;
        long a = Math.max(p[0], dayStartMs), b = Math.min(p[1], dayEndMs);
        if (b > a) clippedPauses.add(new long[] {a, b});
      }
    }
    List<long[]> mergedPauses = mergeIntervals(clippedPauses);
    List<long[]> kept = new ArrayList<>();
    for (long[] iv : intervals) {
      if (iv == null || iv.length < 2) continue;
      long a = Math.max(iv[0], dayStartMs), b = Math.min(iv[1], dayEndMs);
      if (b > a) kept.addAll(subtractIntervals(a, b, mergedPauses));
    }
    long sum = 0L;
    for (long[] iv : mergeIntervals(kept)) sum += iv[1] - iv[0];
    return sum;
  }

  private static final class Event {
    final long s;
    final long e;
    final String app;
    final long minMs;
    final boolean waste;
    final boolean isBreak;

    Event(long s, long e, String app, long minMs, boolean waste, boolean isBreak) {
      this.s = s;
      this.e = e;
      this.app = app;
      this.minMs = minMs;
      this.waste = waste;
      this.isBreak = isBreak;
    }
  }

  private static final class OpenRun {
    final String app;
    final long minMs;
    long end;
    long ms;
    final List<long[]> intervals = new ArrayList<>();

    OpenRun(Event ev) {
      this.app = ev.app;
      this.minMs = ev.minMs;
      this.end = ev.e;
      this.ms = ev.e - ev.s;
      this.intervals.add(new long[] {ev.s, ev.e});
    }
  }

  /** Pure single-app focus projection across devices within [dayStartMs, dayEndMs). */
  public static long unionFocusMs(List<DeviceStream> streams, long dayStartMs, long dayEndMs) {
    if (dayEndMs <= dayStartMs || streams == null || streams.isEmpty()) return 0L;
    List<long[]> allQualified = new ArrayList<>();
    for (DeviceStream stream : streams) {
      List<long[]> clippedPauses = new ArrayList<>();
      for (long[] p : stream.pauses) {
        if (p == null || p.length < 2) continue;
        long a = Math.max(p[0], dayStartMs), b = Math.min(p[1], dayEndMs);
        if (b > a) clippedPauses.add(new long[] {a, b});
      }
      List<long[]> offs = mergeIntervals(clippedPauses);
      List<Event> events = new ArrayList<>();
      for (long[] p : offs) events.add(new Event(p[0], p[1], "", 0L, false, true));
      for (Span sp : stream.spans) {
        long a = Math.max(sp.startMs, dayStartMs), b = Math.min(sp.endMs, dayEndMs);
        if (b <= a) continue;
        for (long[] piece : subtractIntervals(a, b, offs)) {
          events.add(new Event(piece[0], piece[1], sp.app, sp.category.minRunMs, sp.category.waste, false));
        }
      }
      events.sort((x, y) -> {
        int c = Long.compare(x.s, y.s);
        return c != 0 ? c : Boolean.compare(y.isBreak, x.isBreak);
      });
      final OpenRun[] cur = new OpenRun[1];
      final List<Event> pending = new ArrayList<>();
      final class Runner {
        void emit(OpenRun r) {
          if (r != null && r.ms >= r.minMs) allQualified.addAll(r.intervals);
        }

        void process(Event ev) {
          if (ev.isBreak || ev.waste) {
            emit(cur[0]);
            cur[0] = null;
            pending.clear();
            return;
          }
          if (cur[0] == null) {
            cur[0] = ev.minMs > 0 ? new OpenRun(ev) : null;
            pending.clear();
            return;
          }
          long gap = ev.s - cur[0].end;
          if (ev.app.equals(cur[0].app) && ev.minMs > 0) {
            boolean ok = pending.isEmpty() ? gap <= FOCUS_AWAY_MS : gap <= FOCUS_GLANCE_MS;
            if (ok) {
              long addS = Math.max(ev.s, cur[0].end);
              if (ev.e > addS) {
                cur[0].ms += ev.e - addS;
                cur[0].end = ev.e;
                cur[0].intervals.add(new long[] {addS, ev.e});
              }
              pending.clear();
              return;
            }
            List<Event> saved = new ArrayList<>(pending);
            emit(cur[0]);
            cur[0] = null;
            pending.clear();
            for (Event p : saved) process(p);
            process(ev);
            return;
          }
          if (ev.e - cur[0].end <= FOCUS_GLANCE_MS && gap <= FOCUS_GLANCE_MS) {
            pending.add(ev);
            return;
          }
          List<Event> saved = new ArrayList<>(pending);
          emit(cur[0]);
          cur[0] = null;
          pending.clear();
          for (Event p : saved) process(p);
          process(ev);
        }
      }
      Runner runner = new Runner();
      for (Event ev : events) runner.process(ev);
      runner.emit(cur[0]);
    }
    long sum = 0L;
    for (long[] iv : mergeIntervals(allQualified)) sum += iv[1] - iv[0];
    return sum;
  }

  private static int gregorianEpochDay(int y, int m, int d) {
    // Howard Hinnant's civil-to-days algorithm (exact proleptic Gregorian days since 1970-01-01).
    int yr = m <= 2 ? y - 1 : y;
    int era = (yr >= 0 ? yr : yr - 399) / 400;
    int yoe = yr - era * 400;
    int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146_097 + doe - 719_468;
  }

  private static boolean validCalendarDate(int y, int m, int d) {
    if (m < 1 || m > 12 || d < 1) return false;
    boolean leap = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0);
    int[] maxDays = {31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    return d <= maxDays[m - 1];
  }

  private static void validateDateMatchesDay(String date, int day) {
    if (date == null || date.length() != 10 || date.charAt(4) != '-' || date.charAt(7) != '-') {
      throw new IllegalArgumentException("Focus award date must be YYYY-MM-DD");
    }
    int y, m, d;
    try {
      y = Integer.parseInt(date.substring(0, 4));
      m = Integer.parseInt(date.substring(5, 7));
      d = Integer.parseInt(date.substring(8, 10));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Focus award date must be YYYY-MM-DD");
    }
    if (!validCalendarDate(y, m, d)) {
      throw new IllegalArgumentException("Invalid focus award calendar date: " + date);
    }
    int expected = gregorianEpochDay(y, m, d);
    if (expected != day) {
      throw new IllegalArgumentException(
          "Focus award date " + date + " does not match epoch day " + day + " (expected " + expected + ")");
    }
  }

  /**
   * Atomically mint finalized daily focus milestones (focus:YYYY-MM-DD:1..N) into the wallet.
   * Caller MUST invoke this inside the profile authority's {@link HabitStore#updateWallet} transaction.
   */
  public static AwardOutcome awardFinalizedDay(Wallet wallet, FinalizedDay input, Rate rate) {
    if (wallet == null || input == null || rate == null || !rate.valid()) {
      throw new IllegalArgumentException("Invalid focus reward arguments");
    }
    if (input.dayEndMs <= input.dayStartMs) {
      throw new IllegalArgumentException("Invalid finalized focus day bounds");
    }
    validateDateMatchesDay(input.date, input.day);

    if (!input.complete) {
      return new AwardOutcome(Status.NOT_FINALIZED, 0, 0, 0);
    }
    if (!wallet.active(input.day)) {
      return new AwardOutcome(Status.WALLET_DORMANT, 0, 0, 0);
    }
    int milestones = (int) Math.min(rate.maxMilestones, input.unionFocusMs / rate.milestoneMs);
    if (milestones <= 0) {
      return new AwardOutcome(Status.NO_MILESTONES, 0, 0, 0);
    }

    String ref = FOCUS_KIND + ":" + input.date;
    int awarded = 0;
    int already = 0;
    for (int n = 1; n <= milestones; n++) {
      String key = FOCUS_KIND + ":" + input.date + ":" + n;
      if (wallet.earn(key, FOCUS_KIND, rate.coinsPerMilestone, ref, input.atMs, input.day)) {
        awarded++;
      } else {
        already++;
      }
    }
    Status status = awarded > 0 ? Status.AWARDED : Status.ALREADY_AWARDED;
    return new AwardOutcome(status, awarded, already, awarded * rate.coinsPerMilestone);
  }

  public static long coinsForDay(Wallet wallet, int day) {
    return coinsInRange(wallet, day, day);
  }

  public static long coinsInRange(Wallet wallet, int fromDay, int toDay) {
    if (wallet == null || toDay < fromDay) return 0L;
    long sum = 0L;
    for (Wallet.Entry e : wallet.entries(FOCUS_KIND, fromDay, toDay)) {
      sum += e.amount;
    }
    return sum;
  }
}

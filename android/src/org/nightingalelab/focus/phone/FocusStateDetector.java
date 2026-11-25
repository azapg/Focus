package org.nightingalelab.focus.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure detector for ordered foreground spans and timestamped signals.
 *  Keep thresholds and transition rules aligned with app/src/focus_state.rs. */
public final class FocusStateDetector {
  public static final long TICK_MS = 5_000;
  private static final long ACTIVE_GAP_MS = 15_000;
  private static final long MAX_CONSUMING_GAP_MS = 15_000;

  public enum State { FOCUSED, DRIFTING, UNFOCUSED }
  public enum Kind { CREATING, CONSUMING, BROWSING, FEED, NEUTRAL, IGNORED }

  public static final class Config {
    public long shortWindowMs = 180_000;
    public long longWindowMs = 600_000;
    public long pauseWindowMs = 2_700_000;
    public long minimumActiveMs = 120_000;
    public int switches = 12;
    public long shortDwellMs = 20_000;
    public int panelOpens = 4;
    public int phonePickups = 2;
    public int pauseRestarts = 1;
    public double driftConsumingShare = 0.45;
    public double driftFeedShare = 0.20;
    public double unfocusedConsumingShare = 0.65;
    public double unfocusedFeedShare = 0.35;
    public double recoveryConsumingShare = 0.30;
    public long driftHoldMs = 45_000;
    public long unfocusedHoldMs = 75_000;
    public long recoveryHoldMs = 90_000;
    public boolean standaloneConsumingChurn;
  }

  public static final class Span {
    public final long start;
    public final long end;
    public final String identity;
    public final Kind kind;

    public Span(long start, long end, String identity, Kind kind) {
      this.start = start;
      this.end = end;
      this.identity = identity;
      this.kind = kind;
    }
  }

  public static final class Signals {
    public final boolean active;
    public final long activeMs;
    public final long sessionStart;
    public final boolean currentConsuming;
    public final boolean recentConsuming;
    public final int switches;
    public final long medianDwellMs;
    public final double creatingShare;
    public final double consumingShare;
    public final double confirmedConsumingShare;
    public final double feedShare;
    public final int panelOpens;
    public final Integer phonePickups;
    public final int pauseRestarts;

    public Signals(boolean active, long activeMs, int switches, long medianDwellMs,
        double creatingShare, double consumingShare, double confirmedConsumingShare, double feedShare,
        int panelOpens, Integer phonePickups, int pauseRestarts, long sessionStart,
        boolean currentConsuming, boolean recentConsuming) {
      this.active = active;
      this.activeMs = activeMs;
      this.sessionStart = sessionStart;
      this.currentConsuming = currentConsuming;
      this.recentConsuming = recentConsuming;
      this.switches = switches;
      this.medianDwellMs = medianDwellMs;
      this.creatingShare = creatingShare;
      this.consumingShare = consumingShare;
      this.confirmedConsumingShare = confirmedConsumingShare;
      this.feedShare = feedShare;
      this.panelOpens = panelOpens;
      this.phonePickups = phonePickups;
      this.pauseRestarts = pauseRestarts;
    }
  }

  public static final class Transition {
    public final long at;
    public final State from;
    public final State to;
    public final String reason;

    private Transition(long at, State from, State to, String reason) {
      this.at = at;
      this.from = from;
      this.to = to;
      this.reason = reason;
    }
  }

  private final Config config;
  private State state;
  private State pending;
  private long since;
  private Long lastTick;

  /** Callers can pass overrides before constructing the detector. */
  public FocusStateDetector(Config config) {
    this.config = config;
  }

  /** Pause restart times represent a new pause starting soon after a completed one.
   *  Use null for unavailable second-device usage. */
  public Signals measure(long now, List<Span> spans, long[] panelOpens,
      long[] phonePickups, long[] pauseRestarts) {
    long lastEnd = 0;
    long sessionStart = 0;
    long lastConsumingEnd = 0;
    boolean currentConsuming = false;
    long from = Math.max(0, now - config.shortWindowMs);
    for (Span span : spans) {
      if (span.start > now) break;
      if (span.start - lastEnd >= ACTIVE_GAP_MS) {
        from = Math.max(from, span.start);
        sessionStart = span.start;
        lastConsumingEnd = 0;
      }
      lastEnd = Math.max(lastEnd, span.end);
      boolean consumingSpan = span.kind == Kind.CONSUMING || span.kind == Kind.FEED;
      if (span.end >= now) currentConsuming = consumingSpan;
      if (consumingSpan) lastConsumingEnd = Math.max(lastConsumingEnd, span.end);
    }

    long previousEnd = 0;
    String previousIdentity = null;
    long dwell = 0;
    int switches = 0;
    long activeMs = 0;
    long creating = 0;
    long consuming = 0;
    long browsing = 0;
    long feeds = 0;
    List<Long> visits = new ArrayList<>();
    for (Span span : spans) {
      if (span.kind == Kind.IGNORED) continue;
      long start = Math.max(Math.max(span.start, previousEnd), from);
      long end = Math.min(span.end, now);
      if (end <= start) continue;
      long duration = end - start;
      boolean continuous = previousIdentity != null && start - previousEnd <= TICK_MS;
      if (continuous && previousIdentity.equals(span.identity)) {
        dwell += duration;
      } else {
        if (continuous) switches++;
        if (dwell > 0) visits.add(dwell);
        dwell = duration;
      }
      previousIdentity = span.identity;
      previousEnd = end;
      activeMs += duration;
      if (span.kind == Kind.CREATING) creating += duration;
      if (span.kind == Kind.CONSUMING || span.kind == Kind.FEED) consuming += duration;
      if (span.kind == Kind.BROWSING) browsing += duration;
      if (span.kind == Kind.FEED) feeds += duration;
    }
    if (dwell > 0) visits.add(dwell);
    Collections.sort(visits);
    int middle = visits.size() / 2;
    long median = visits.isEmpty() ? 0 : visits.size() % 2 == 0
        ? (visits.get(middle - 1) + visits.get(middle)) / 2 : visits.get(middle);
    Integer pickups = null;
    if (phonePickups != null) {
      pickups = 0;
      for (long t : phonePickups) {
        if (t <= now - config.longWindowMs || t > now) continue;
        for (Span span : spans) {
          if (span.start <= t && t < span.end) {
            pickups++;
            break;
          }
        }
      }
    }
    double denominator = Math.max(1, activeMs);
    return new Signals(lastEnd > Math.max(0, now - ACTIVE_GAP_MS), activeMs, switches, median,
        creating / denominator, (consuming + browsing) / denominator, consuming / denominator, feeds / denominator,
        count(panelOpens, from, now), pickups, count(pauseRestarts, now - config.pauseWindowMs, now), sessionStart, currentConsuming,
        currentConsuming || lastConsumingEnd > Math.max(0, now - MAX_CONSUMING_GAP_MS));
  }

  private static int count(long[] points, long after, long through) {
    int count = 0;
    for (long t : points) if (t > after && t <= through) count++;
    return count;
  }

  public Transition step(long now, Signals signals) {
    return step(now, signals, false);
  }

  public Transition step(long now, Signals signals, boolean inputIdle) {
    Long previous = lastTick;
    lastTick = now;
    if (previous != null && (now <= previous || now - previous > 2 * TICK_MS || signals.sessionStart > previous)) {
      pending = null;
      if (state != null) return transition(previous, null, "Activity observation interrupted");
    }
    if (!signals.active) {
      return state == null ? null : transition(now, null, "Activity ended");
    }
    if (state == null) return transition(now, State.FOCUSED, "Activity detected");

    boolean enough = signals.activeMs >= config.minimumActiveMs;
    boolean churn = signals.switches >= config.switches && signals.medianDwellMs <= config.shortDwellMs;
    boolean panel = signals.panelOpens >= config.panelOpens;
    boolean pickup = signals.phonePickups != null && signals.phonePickups >= config.phonePickups;
    boolean pause = signals.pauseRestarts >= config.pauseRestarts;
    int support = (panel ? 1 : 0) + (pickup ? 1 : 0) + (pause ? 1 : 0);
    double consuming = signals.confirmedConsumingShare;
    boolean drift = enough && ((churn && signals.feedShare >= config.driftFeedShare)
        || ((churn || support > 0) && consuming >= config.driftConsumingShare
            && consuming > signals.creatingShare)
        || signals.feedShare >= config.unfocusedFeedShare);
    boolean mobileChurn = config.standaloneConsumingChurn && churn && consuming >= 0.85
        && signals.currentConsuming;
    boolean strong = drift && consuming >= config.unfocusedConsumingShare
        && (signals.feedShare >= config.unfocusedFeedShare || support >= 2 || (churn && support > 0) || mobileChurn);
    boolean holding = drift || (enough && consuming >= config.recoveryConsumingShare
        && (signals.feedShare >= config.driftFeedShare
            || ((churn || support > 0) && consuming > signals.creatingShare)));

    State target;
    long hold;
    String cause;
    boolean canEscalate = !inputIdle && signals.recentConsuming;
    if (state == State.FOCUSED && drift && canEscalate) {
      target = State.DRIFTING;
      hold = config.driftHoldMs;
      cause = "Drift signals persisted";
    } else if (state == State.DRIFTING && strong && canEscalate) {
      target = State.UNFOCUSED;
      hold = config.unfocusedHoldMs;
      cause = "Strong drift signals persisted";
    } else if (state == State.DRIFTING && !holding) {
      target = State.FOCUSED;
      hold = config.recoveryHoldMs;
      cause = "Drift signals stayed low";
    } else if (state == State.UNFOCUSED && !strong) {
      target = State.DRIFTING;
      hold = config.recoveryHoldMs;
      cause = "Strong drift signals subsided";
    } else {
      pending = null;
      return null;
    }

    boolean escalating = (state == State.FOCUSED && target == State.DRIFTING)
        || (state == State.DRIFTING && target == State.UNFOCUSED);
    if (escalating && !signals.currentConsuming) {
      if (pending != target) pending = null;
      return null;
    }
    if (pending != target) since = now;
    pending = target;
    if (now - since < hold) return null;
    String reason = String.format(Locale.ROOT,
        "%s for %ds · %d switches and %.0fs median dwell in %ds · %.0f%% consuming, %.0f%% feeds",
        cause, (now - since) / 1000, signals.switches, signals.medianDwellMs / 1000.0,
        config.shortWindowMs / 1000, signals.consumingShare * 100, signals.feedShare * 100);
    if (panel) reason += " · " + signals.panelOpens + " panel opens";
    if (signals.consumingShare > consuming) {
      reason += String.format(Locale.ROOT, " · %.0f%% consuming outside generic browsing", consuming * 100);
    }
    if (pickup) reason += " · " + signals.phonePickups + " inferred phone pickups";
    if (pause) reason += " · " + signals.pauseRestarts + " quick pause restarts";
    return transition(now, target, reason);
  }

  private Transition transition(long at, State next, String reason) {
    Transition event = new Transition(at, state, next, reason);
    pending = null;
    state = next;
    return event;
  }
}

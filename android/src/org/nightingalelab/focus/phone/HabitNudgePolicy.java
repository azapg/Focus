package org.nightingalelab.focus.phone;

import java.util.HashSet;
import java.util.Deque;
import java.util.List;
import java.util.Set;

final class HabitNudgePolicy {
  static final long COOLDOWN_MS = 2 * 60 * 60_000L;
  static final String LOCKED = "Feeds locked until a habit is done";
  long lastSent;
  private long started;
  private int day;
  private boolean locked;
  private Set<String> existing = new HashSet<>();

  boolean active() { return started != 0; }

  String step(long now, int today, boolean entered, boolean unfocused, boolean consuming,
      boolean lockEnabled, int due, Set<String> completed) {
    if (active() && (today != day || due == 0 || now < started || !existing.containsAll(completed)
        || (locked && !lockEnabled) || (!locked && !unfocused))) {
      started = 0;
      locked = false;
    }
    if (entered && due > 0 && !active() && (lastSent == 0 || now - lastSent >= COOLDOWN_MS)) {
      lastSent = started = now;
      day = today;
      existing = new HashSet<>(completed);
    }
    if (!active()) return null;
    long elapsed = now - started;
    if (lockEnabled && unfocused && consuming && elapsed >= 120_000) locked = true;
    if (locked) return LOCKED;
    if (elapsed >= 20_000) return null;
    return due == 1 ? "1 habit left today" : due + " habits left today";
  }

  static boolean isFeed(BrowserUrlParser.Parsed page) {
    if (page == null) return false;
    String path = page.pathHint.replaceFirst("/$", "");
    switch (page.host) {
      case "youtube.com":
        return path.matches("/(shorts(/[^/]+)?|feed/(subscriptions|trending|explore))");
      case "x.com":
      case "twitter.com":
        return path.equals("/home");
      case "instagram.com":
        return path.matches("/(reels|reel/[^/]+)");
      case "reddit.com":
        return path.matches("/(popular|all|r/all|r/popular)");
      case "tiktok.com":
        return path.equals("/foryou");
      default:
        return false;
    }
  }

  static void appendSpans(Deque<FocusStateDetector.Span> observations,
      List<InsightsEngine.SpanRecord> recent, List<ActivityClassifier.UserRule> rules,
      String foreground, boolean onFeed, long now) {
    for (int i = 0; i < recent.size(); i++) {
      InsightsEngine.SpanRecord span = recent.get(i);
      boolean current = i == recent.size() - 1 && foreground.equals(span.app) && now - span.e <= 2_000;
      long from = observations.isEmpty() ? span.s : Math.max(span.s, observations.peekLast().end);
      long to = current ? now : span.e;
      if (from >= to) continue;
      ActivityClassifier.Result result = ActivityClassifier.classify(span.app, span.label, span.domain, rules);
      observations.add(new FocusStateDetector.Span(from, to, span.domain.isEmpty() ? span.app : span.domain,
          classify(result, current && onFeed)));
    }
  }

  private static FocusStateDetector.Kind classify(ActivityClassifier.Result result, boolean confirmedFeed) {
    if (result.noise || "ignore".equals(result.category)) return FocusStateDetector.Kind.IGNORED;
    if (ActivityClassifier.GROUP_CREATING.equals(result.group)) return FocusStateDetector.Kind.CREATING;
    if (confirmedFeed) return FocusStateDetector.Kind.FEED;
    if ("watching".equals(result.category) || "listening".equals(result.category)) return FocusStateDetector.Kind.CONSUMING;
    if ("browsing".equals(result.category)) return FocusStateDetector.Kind.BROWSING;
    return FocusStateDetector.Kind.NEUTRAL;
  }
}

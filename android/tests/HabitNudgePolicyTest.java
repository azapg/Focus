package org.nightingalelab.focus.phone;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

public final class HabitNudgePolicyTest {
  public static void main(String[] args) {
    HabitNudgePolicy p = new HabitNudgePolicy();
    Set<String> none = Collections.emptySet();
    require(p.step(100, 20_000, true, true, true, false, 0, none) == null, "nothing due");
    require("3 habits left today".equals(p.step(110, 20_000, true, true, true, false, 3, none)), "one-line nudge");
    require(p.step(120_110, 20_000, false, true, true, false, 3, none) == null, "feed lock is opt-in");
    require(p.step(120_110, 20_000, false, true, false, true, 3, none) == null, "no usable feed to cover");
    require(HabitNudgePolicy.LOCKED.equals(p.step(120_110, 20_000, false, true, true, true, 3, none)), "sustained drift can lock feeds");
    require(p.step(120_111, 20_000, false, true, true, true, 2, Collections.singleton("synced-checkoff")) == null,
        "a completed habit releases the lock even when others are due");
    require(p.step(130_000, 20_000, true, true, true, true, 2, none) == null, "cooldown covers the next episode");
    require("1 habit left today".equals(p.step(110 + HabitNudgePolicy.COOLDOWN_MS, 20_000, true, true, true, false, 1, none)),
        "singular copy after cooldown");
    require(HabitNudgePolicy.isFeed(BrowserUrlParser.parse("https://m.youtube.com/shorts/example")), "explicit feed");
    for (String open : new String[]{"x.com/messages", "instagram.com/direct/inbox", "reddit.com/message/inbox",
        "youtube.com/watch?v=course", "youtube.com", "notion.so/work", "slack.com", "www.youtube.com.example.net/shorts/example"}) {
      require(!HabitNudgePolicy.isFeed(BrowserUrlParser.parse(open)), "keep outside feed: " + open);
    }
    switchedAppsReachUnfocused(0);
    switchedAppsReachUnfocused(2_000);
    System.out.println("HabitNudgePolicyTest passed");
  }

  private static void switchedAppsReachUnfocused(long offset) {
    FocusStateDetector.Config config = new FocusStateDetector.Config();
    config.standaloneConsumingChurn = true;
    FocusStateDetector detector = new FocusStateDetector(config);
    Deque<FocusStateDetector.Span> observations = new ArrayDeque<>();
    String[] apps = {"com.netflix.mediaclient", "com.google.android.youtube", "tv.twitch.android.app"};
    long start = 1_000_000 + offset;
    long[] noTimes = {};
    boolean unfocused = false;
    for (long now = 1_005_000; now <= start + 360_000; now += FocusStateDetector.TICK_MS) {
      List<InsightsEngine.SpanRecord> recent = new ArrayList<>();
      for (int n = 0; n < 32; n++) {
        long visit = start + n * 12_000;
        long from = Math.max(visit, now - FocusStateDetector.TICK_MS);
        long through = Math.min(visit + 12_000, now);
        if (from < through) recent.add(new InsightsEngine.SpanRecord(from, through, apps[n % apps.length],
            "", "", "phone", "Phone"));
      }
      if (recent.isEmpty()) continue;
      HabitNudgePolicy.appendSpans(observations, recent, Collections.emptyList(),
          recent.get(recent.size() - 1).app, false, now);
      while (!observations.isEmpty() && observations.peek().end <= now - 180_000) observations.remove();
      FocusStateDetector.Signals signals = detector.measure(now, new ArrayList<>(observations), noTimes, null, noTimes);
      FocusStateDetector.Transition change = detector.step(now, signals);
      unfocused |= change != null && change.to == FocusStateDetector.State.UNFOCUSED;
    }
    require(unfocused, "short consuming-app visits survive the monitor sampling window at offset " + offset);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

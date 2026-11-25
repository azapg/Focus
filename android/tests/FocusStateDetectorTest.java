import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.nightingalelab.focus.phone.FocusStateDetector;
import org.nightingalelab.focus.phone.FocusStateDetector.Config;
import org.nightingalelab.focus.phone.FocusStateDetector.Kind;
import org.nightingalelab.focus.phone.FocusStateDetector.Signals;
import org.nightingalelab.focus.phone.FocusStateDetector.Span;
import org.nightingalelab.focus.phone.FocusStateDetector.Transition;
import static org.nightingalelab.focus.phone.FocusStateDetector.State.*;

public final class FocusStateDetectorTest {
  public static void main(String[] args) {
    transitionsRequireSustainedEvidence();
    workCanSwitchRapidly();
    phoneConsumingChurnNeedsNoAccessibility();
    thresholdsCanBeOverridden();
    mixedUsageLetsUnfocusedRecover();
    idleInputCannotEscalate();
    gapBetweenTicksRestartsSession();
    oldFeedCannotEscalateWhileCreating();
    System.out.println("FocusStateDetectorTest passed");
  }

  private static void check(boolean condition) {
    if (!condition) throw new AssertionError();
  }

  private static Signals strong() {
    return new Signals(true, 180_000, 14, 12_000, 0.30, 0.70, 0.70, 0.40, 0, null, 0, 0, true, true);
  }

  private static void transitionsRequireSustainedEvidence() {
    FocusStateDetector detector = new FocusStateDetector(new Config());
    Signals work = new Signals(true, 180_000, 14, 12_000, 1, 0, 0, 0, 0, null, 0, 0, false, false);
    List<Integer> ticks = new ArrayList<>();
    List<FocusStateDetector.State> states = new ArrayList<>();
    for (int tick = 0; tick <= 91; tick++) {
      boolean drift = (tick >= 1 && tick <= 8) || (tick >= 10 && tick <= 35) || tick == 52 || tick == 72;
      Transition event = detector.step(1_000_000 + tick * 5_000, drift ? strong() : work);
      if (event != null) {
        ticks.add(tick);
        states.add(event.to);
        if (event.to == UNFOCUSED) check(event.reason.contains("70% consuming"));
      }
    }
    check(ticks.equals(Arrays.asList(0, 19, 35, 71, 91)));
    check(states.equals(Arrays.asList(FOCUSED, DRIFTING, UNFOCUSED, DRIFTING, FOCUSED)));
  }

  private static void workCanSwitchRapidly() {
    Config config = new Config();
    config.standaloneConsumingChurn = true;
    FocusStateDetector detector = new FocusStateDetector(config);
    List<Span> spans = new ArrayList<>();
    String[] identities = {"IDE", "Terminal", "Browser docs", "T3 Code"};
    for (int i = 0; i < 60; i++) {
      long start = 1_000_000 + i * 10_000;
      spans.add(new Span(start, start + 10_000, identities[i % 4], Kind.CREATING));
    }
    for (int tick = 0; tick <= 120; tick++) {
      long now = 1_000_000 + tick * 5_000;
      Signals signals = detector.measure(now, spans,
          new long[] {now - 4_000, now - 3_000, now - 2_000, now - 1_000}, null, new long[0]);
      Transition event = detector.step(now, signals);
      check(event == null || event.to == FOCUSED);
      if (tick == 100) check(signals.switches == 17 && signals.medianDwellMs == 10_000);
    }
  }

  private static void mixedUsageLetsUnfocusedRecover() {
    FocusStateDetector detector = new FocusStateDetector(new Config());
    Signals mixed = new Signals(true, 180_000, 3, 54_000, .40, .60, .60, .30, 0, null, 0, 0, false, false);
    for (int tick = 0; tick <= 63; tick++) {
      Transition event = detector.step(1_000_000 + tick * 5_000, tick <= 26 ? strong() : mixed);
      if (tick == 45) check(event != null && event.from == UNFOCUSED && event.to == DRIFTING);
      else if (tick > 26) check(event == null);
    }
  }

  private static void phoneConsumingChurnNeedsNoAccessibility() {
    Config config = new Config();
    config.standaloneConsumingChurn = true;
    FocusStateDetector phone = new FocusStateDetector(config);
    List<Span> spans = new ArrayList<>();
    long start = 1_000_000;
    for (int i = 0; i < 40; i++) {
      long from = start + i * 10_000;
      spans.add(new Span(from, from + 10_000, "consumer-" + (i % 3), Kind.CONSUMING));
    }
    boolean unfocused = false;
    long[] empty = {};
    for (long now = start; now < start + 380_000; now += FocusStateDetector.TICK_MS) {
      Transition event = phone.step(now, phone.measure(now, spans, empty, null, empty));
      unfocused |= event != null && event.to == UNFOCUSED;
    }
    check(unfocused);
  }

  private static void thresholdsCanBeOverridden() {
    Config config = new Config();
    config.driftHoldMs = 5_000;
    config.unfocusedConsumingShare = 0.95;
    FocusStateDetector detector = new FocusStateDetector(config);
    check(detector.step(1_000_000, strong()).to == FOCUSED);
    check(detector.step(1_005_000, strong()) == null);
    check(detector.step(1_010_000, strong()).to == DRIFTING);
    for (int i = 3; i <= 50; i++) check(detector.step(1_000_000 + i * 5_000, strong()) == null);
  }

  private static void gapBetweenTicksRestartsSession() {
    FocusStateDetector detector = new FocusStateDetector(new Config());
    List<Span> spans = Arrays.asList(
        new Span(1_000_000, 1_300_000, "X", Kind.FEED),
        new Span(1_315_000, 1_550_000, "IDE", Kind.CREATING));
    long[] noEvents = new long[0];
    for (int tick = 0; tick <= 110; tick++) {
      long now = 1_000_000 + tick * 5_000;
      Transition event = detector.step(now, detector.measure(now, spans, noEvents, null, noEvents));
      if (tick == 63) check(event != null && event.from == UNFOCUSED && event.to == null);
      if (tick == 64) check(event != null && event.from == null && event.to == FOCUSED);
      if (tick > 64) check(event == null);
    }
  }

  private static void idleInputCannotEscalate() {
    FocusStateDetector detector = new FocusStateDetector(new Config());
    for (int tick = 0; tick <= 100; tick++) {
      Transition event = detector.step(1_000_000 + tick * 5_000, strong(), tick < 51 || tick > 60);
      if (tick == 0) check(event != null && event.to == FOCUSED);
      else if (tick == 60) check(event != null && event.to == DRIFTING);
      else check(event == null);
    }
  }

  private static void oldFeedCannotEscalateWhileCreating() {
    FocusStateDetector detector = new FocusStateDetector(new Config());
    List<Span> spans = Arrays.asList(
        new Span(1_000_000, 1_180_000, "X", Kind.FEED),
        new Span(1_180_000, 1_360_000, "IDE", Kind.CREATING));
    long[] noEvents = new long[0];
    for (int tick = 0; tick <= 72; tick++) {
      long now = 1_000_000 + tick * 5_000;
      Signals signals = detector.measure(now, spans, noEvents, null, noEvents);
      Transition event = detector.step(now, signals, tick < 36);
      if (tick == 0) check(event != null && event.to == FOCUSED);
      else check(event == null);
    }
  }
}

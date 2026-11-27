package org.nightingalelab.focus.phone;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class IntentionTest {
  private static final long M = Intention.MINUTE;

  public static void main(String[] args) {
    parsesTheCommonSentences();
    parsesUntilAClock();
    fallsBackToTheWholeSentence();
    asksTheDoneQuestion();
    extendsFromTheLaterOfNowAndTheEnd();
    driftNeedsAMinuteAndNudgesOnceAfterThree();
    aShortGlanceDoesNotBreakARun();
    leavingEndsTheRunButItLingers();
    fittingUseDoesNotCount();
    System.out.println("IntentionTest passed");
  }

  private static void check(boolean condition, String what) {
    if (!condition) throw new AssertionError(what);
  }

  private static void eq(Object expected, Object actual) {
    if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }

  private static void parsesTheCommonSentences() {
    Intention.Parsed p = Intention.parseLocal("cleaning the house, 45 min, done when the trash is out", 600);
    eq("Cleaning the house", p.label);
    eq(45, p.minutes);
    eq("the trash is out", p.doneWhen);
    eq("local", p.source);

    p = Intention.parseLocal("Reading chapter 3 for half an hour", 600);
    eq("Reading chapter 3", p.label);
    eq(30, p.minutes);

    p = Intention.parseLocal("write the essay 1h 30m", 600);
    eq("Write the essay", p.label);
    eq(90, p.minutes);

    p = Intention.parseLocal("walk the dog for 1.5 hours", 600);
    eq("Walk the dog", p.label);
    eq(90, p.minutes);

    p = Intention.parseLocal("fix the bike", 600);
    eq("Fix the bike", p.label);
    eq(Intention.DEFAULT_MINUTES, p.minutes);
    eq("", p.doneWhen);

    // Durations are clamped like on the laptop.
    eq(Intention.MAX_MINUTES, Intention.parseLocal("study 9 hours", 600).minutes);
  }

  private static void parsesUntilAClock() {
    // 11:00 local; "until 11:45" is 45 minutes.
    Intention.Parsed p = Intention.parseLocal("cleaning house until 11:45", 660);
    eq("Cleaning house", p.label);
    eq(45, p.minutes);
    // 14:00; "until 3pm" is 60 minutes.
    eq(60, Intention.parseLocal("emails until 3pm", 840).minutes);
    // 14:00; "until 3:30" means 15:30 in the afternoon.
    eq(90, Intention.parseLocal("emails until 3:30", 840).minutes);
    // A bare "by 3" is too ambiguous and stays in the label.
    Intention.Parsed bare = Intention.parseLocal("report by 3", 840);
    eq("Report by 3", bare.label);
    eq(Intention.DEFAULT_MINUTES, bare.minutes);
  }

  private static void fallsBackToTheWholeSentence() {
    Intention.Parsed p = Intention.parseLocal("45 min", 600);
    eq("45 min", p.label);
    eq(45, p.minutes);
  }

  private static void asksTheDoneQuestion() {
    eq("Is the trash out?", running("the trash is out").question());
    eq("Are the dishes done?", running("the dishes are done").question());
    eq("Inbox zero?", running("inbox zero.").question());
    eq("Done?", running("").question());
  }

  private static Intention running(String doneWhen) {
    return new Intention("int-1", 0, 45 * M, 45, "Cleaning house", doneWhen, "", "local", 0);
  }

  private static void extendsFromTheLaterOfNowAndTheEnd() {
    Intention i = running("");
    eq(55 * M, i.extended(10 * M, 10).until);
    eq(70 * M, i.extended(60 * M, 10).until);
    eq(1, i.extended(60 * M, 10).extensions);
    check(i.timeUp(45 * M) && !i.timeUp(44 * M), "time's up at the planned end");
    eq("31m left", Intention.left(30 * M + 1));
    eq("1h 05m left", Intention.left(65 * M));
  }

  private static Intention.Use game(long from, long to) {
    return new Intention.Use(from, to, "Block Puzzle", true);
  }

  private static void driftNeedsAMinuteAndNudgesOnceAfterThree() {
    long now = 20 * M;
    Intention.Drift glance = Intention.drift(Collections.singletonList(game(now - 40_000, now)), 0, now);
    check(glance.current && !glance.showing(), "40 seconds is a glance");

    Intention.Drift two = Intention.drift(Collections.singletonList(game(now - 2 * M, now)), 0, now);
    check(two.showing(), "two minutes shows as drift");
    check(!Intention.nudgeDue(two, 0, now), "no nudge before three minutes");
    eq("Block Puzzle 2m off-plan", two.text());

    Intention.Drift four = Intention.drift(Collections.singletonList(game(now - 4 * M, now)), 0, now);
    check(Intention.nudgeDue(four, 0, now), "nudge after three minutes");
    check(!Intention.nudgeDue(four, now - 5 * M, now), "no repeat within fifteen minutes");
    check(Intention.nudgeDue(four, now - 16 * M, now), "a later run can nudge again");

    // Time before the intention started doesn't count.
    Intention.Drift clipped = Intention.drift(Collections.singletonList(game(now - 10 * M, now)), now - 2 * M, now);
    eq(2 * M, clipped.ms);
  }

  private static void aShortGlanceDoesNotBreakARun() {
    long now = 20 * M;
    List<Intention.Use> uses = Arrays.asList(
        game(now - 4 * M, now - 2 * M),
        new Intention.Use(now - 2 * M, now - 2 * M + 5_000, "Launcher", false),
        game(now - 2 * M + 5_000, now));
    Intention.Drift d = Intention.drift(uses, 0, now);
    check(d.current, "still in the game");
    eq(4 * M, d.ms);
  }

  private static void leavingEndsTheRunButItLingers() {
    long now = 20 * M;
    List<Intention.Use> uses = Arrays.asList(
        game(now - 7 * M, now - 1 * M),
        new Intention.Use(now - 1 * M, now, "Focus", false));
    Intention.Drift d = Intention.drift(uses, 0, now);
    check(!d.current && d.showing(), "opening Focus right after still shows the slip");
    eq(6 * M, d.ms);
    check(!Intention.nudgeDue(d, 0, now), "no nudge once you've left");

    List<Intention.Use> later = Arrays.asList(
        game(now - 9 * M, now - 3 * M),
        new Intention.Use(now - 3 * M, now, "Focus", false));
    check(!Intention.drift(later, 0, now).showing(), "it clears two minutes after leaving");
  }

  private static void fittingUseDoesNotCount() {
    long now = 20 * M;
    List<Intention.Use> uses = Collections.singletonList(new Intention.Use(now - 10 * M, now, "Recipes", false));
    check(!Intention.drift(uses, 0, now).showing(), "apps that fit the plan are not drift");
  }
}

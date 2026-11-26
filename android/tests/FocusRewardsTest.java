package org.nightingalelab.focus.habits;

import java.util.*;

public final class FocusRewardsTest {
  private static final long MIN = 60_000L;
  private static final long SEC = 1_000L;
  private static final int DAY_2026_11_25 = 20_782;

  private static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError("FAILED: " + msg);
  }

  public static void main(String[] args) {
    testPureProjection();
    testAwardAdapterLifecycle();
    System.out.println("FocusRewardsTest: ALL PASSED");
  }

  private static void testPureProjection() {
    FocusRewards.DeviceStream laptop = new FocusRewards.DeviceStream(
        Arrays.asList(
            // 15m + 30s glance to Terminal + 15m -> 30m unbroken T3 Code
            new FocusRewards.Span(0, 15 * MIN, "T3 Code", FocusRewards.Category.CODING),
            new FocusRewards.Span(15 * MIN, 15 * MIN + 30 * SEC, "Terminal", FocusRewards.Category.OTHER),
            new FocusRewards.Span(15 * MIN + 30 * SEC, 30 * MIN + 30 * SEC, "T3 Code", FocusRewards.Category.CODING),
            // 20m Notion broken by 10s Waste
            new FocusRewards.Span(35 * MIN, 55 * MIN, "Notion", FocusRewards.Category.WRITING),
            new FocusRewards.Span(55 * MIN, 55 * MIN + 10 * SEC, "X", FocusRewards.Category.WASTE),
            new FocusRewards.Span(55 * MIN + 10 * SEC, 65 * MIN + 10 * SEC, "Notion", FocusRewards.Category.WRITING),
            // 60m YouTube -> never eligible
            new FocusRewards.Span(70 * MIN, 130 * MIN, "YouTube", FocusRewards.Category.WASTE),
            // 40m T3 Code split by a 5m pause -> 18m + 17m
            new FocusRewards.Span(135 * MIN, 175 * MIN, "T3 Code", FocusRewards.Category.CODING)),
        Collections.singletonList(new long[] {153 * MIN, 158 * MIN}));

    FocusRewards.DeviceStream phone = new FocusRewards.DeviceStream(
        Arrays.asList(
            new FocusRewards.Span(10 * MIN, 26 * MIN, "Duolingo", FocusRewards.Category.LEARNING),
            new FocusRewards.Span(40 * MIN, 54 * MIN, "Readest", FocusRewards.Category.READING),
            new FocusRewards.Span(162 * MIN, 180 * MIN, "NotebookLM", FocusRewards.Category.LEARNING)),
        Collections.emptyList());

    List<long[]> laptopIvs = FocusRewards.verifiedFocusIntervals(laptop, 0, 200 * MIN);
    check(laptopIvs.size() == 2
        && laptopIvs.get(0)[0] == 0 && laptopIvs.get(0)[1] == 15 * MIN
        && laptopIvs.get(1)[0] == 15 * MIN + 30 * SEC && laptopIvs.get(1)[1] == 30 * MIN + 30 * SEC,
        "Expected 2 laptop focus intervals");
    List<long[]> phoneIvs = FocusRewards.verifiedFocusIntervals(phone, 0, 200 * MIN);
    check(phoneIvs.size() == 2
        && phoneIvs.get(0)[0] == 10 * MIN && phoneIvs.get(0)[1] == 26 * MIN
        && phoneIvs.get(1)[0] == 162 * MIN && phoneIvs.get(1)[1] == 180 * MIN,
        "Expected 2 phone focus intervals");

    long union = FocusRewards.unionFocusMs(Arrays.asList(laptop, phone), 0, 200 * MIN);
    check(union == 48 * MIN + 30 * SEC, "Expected 48.5m unioned focus, got " + union);
  }

  private static void testAwardAdapterLifecycle() {
    Habits habits = new Habits();
    Wallet wallet = habits.wallet;
    FocusRewards.Rate rate = FocusRewards.Rate.TRIAL_DEFAULT;
    FocusRewards.FinalizedDay baseDay = new FocusRewards.FinalizedDay(
        "2026-11-25", DAY_2026_11_25, 1_795_564_800_000L, 1_795_651_200_000L,
        true, 135 * MIN, 1_795_651_205_000L);

    // 1. Dormant wallet awards nothing.
    FocusRewards.AwardOutcome dormant = FocusRewards.awardFinalizedDay(wallet, baseDay, rate);
    check(dormant.status == FocusRewards.Status.WALLET_DORMANT && wallet.balance() == 0, "Dormant awards nothing");

    wallet.start(DAY_2026_11_25 - 1);

    // 2. Incomplete / Pending day awards nothing.
    FocusRewards.FinalizedDay pending = new FocusRewards.FinalizedDay(
        "2026-11-25", DAY_2026_11_25, baseDay.dayStartMs, baseDay.dayEndMs, false, 135 * MIN, baseDay.atMs);
    FocusRewards.AwardOutcome pendingRes = FocusRewards.awardFinalizedDay(wallet, pending, rate);
    check(pendingRes.status == FocusRewards.Status.NOT_FINALIZED && wallet.balance() == 0, "Pending awards nothing");

    // 3. Mismatched date/epoch day throws and writes nothing.
    boolean threw = false;
    try {
      FocusRewards.awardFinalizedDay(wallet, new FocusRewards.FinalizedDay(
          "2026-11-26", DAY_2026_11_25, baseDay.dayStartMs, baseDay.dayEndMs, true, 90 * MIN, baseDay.atMs), rate);
    } catch (IllegalArgumentException expected) {
      threw = true;
    }
    check(threw && wallet.balance() == 0, "Mismatched date rejected");

    // 4. Complete finalized day awards 4 milestones = 20 coins.
    FocusRewards.AwardOutcome awarded = FocusRewards.awardFinalizedDay(wallet, baseDay, rate);
    check(awarded.status == FocusRewards.Status.AWARDED && awarded.milestonesAwarded == 4 && awarded.coinsAwarded == 20,
        "Expected 4 milestones / 20 coins");
    check(wallet.balance() == 20 && FocusRewards.coinsForDay(wallet, DAY_2026_11_25) == 20, "Balance and day coins match");
    List<Wallet.Entry> entries = wallet.entries(FocusRewards.FOCUS_KIND, DAY_2026_11_25, DAY_2026_11_25);
    check(entries.size() == 4
        && "focus:2026-11-25:1".equals(entries.get(0).id)
        && "focus:2026-11-25:4".equals(entries.get(3).id)
        && "focus:2026-11-25".equals(entries.get(0).reference), "Milestone keys and ref match contract");

    // 5. Retry is idempotent.
    FocusRewards.AwardOutcome retry = FocusRewards.awardFinalizedDay(wallet, baseDay, rate);
    check(retry.status == FocusRewards.Status.ALREADY_AWARDED && retry.milestonesAwarded == 0
        && retry.alreadyPresent == 4 && wallet.balance() == 20, "Retry idempotent");

    // 6. Peer merge does not double-count.
    Habits peer = new Habits();
    peer.wallet.start(DAY_2026_11_25 - 1);
    FocusRewards.awardFinalizedDay(peer.wallet, baseDay, rate);
    habits.merge(peer);
    check(wallet.balance() == 20 && FocusRewards.coinsForDay(wallet, DAY_2026_11_25) == 20, "Merge deduplicates milestones");

    // 7. Reversal lowers active focus coins for day.
    check(wallet.reverse("focus:2026-11-25:4", baseDay.atMs + 1000L, DAY_2026_11_25), "Reversal succeeds");
    check(FocusRewards.coinsForDay(wallet, DAY_2026_11_25) == 15, "Reversal updates active day coins");
  }
}

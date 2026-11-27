package org.nightingalelab.focus.habits;

import static org.nightingalelab.focus.habits.Wallet.Purchase.Status.*;

public final class WalletCoreTest {
  private static final int DAY = 20_000;
  private static final long AT = DAY * 86_400_000L;

  public static void main(String[] args) {
    opensByDefault();
    cutoverAndUndo();
    offlinePurchases();
    System.out.println("Android wallet core passed");
  }

  private static void opensByDefault() {
    Habits ledger = new Habits();
    ledger.put(event("before-upgrade", "read", DAY, false, null));
    ledger.wallet.activate(DAY);
    ledger.wallet.activate(DAY + 1);
    ledger.put(event("after-upgrade", "walk", DAY, true, null));
    check(ledger.wallet.active(DAY) && ledger.wallet.startDay() == DAY, "Wallet stays open from upgrade");
    check(ledger.wallet.balance() == 15, "New rewards become coins");
    check(ledger.wallet.extraPauseMinutes(DAY) == 15 && ledger.wallet.extraPauseMinutes(DAY + 1) == 0,
        "Previously earned minutes survive until midnight");
  }

  private static void cutoverAndUndo() {
    Habits ledger = new Habits();
    ledger.wallet.start(DAY - 1);
    ledger.put(event("old", "walk", DAY - 1, false, null));
    ledger.put(event("old-offline", "read", DAY, false, null));
    ledger.put(event("new", "walk", DAY, false, null));
    ledger.put(event("new", "walk", DAY, true, null)); // A legacy relay can arrive first.
    ledger.put(event("new-duplicate", "walk", DAY, true, null));
    check(ledger.wallet.extraPauseMinutes(DAY - 1) == 15 && ledger.wallet.extraPauseMinutes(DAY) == 15,
        "Legacy rewards remain minutes through cutover");
    check(ledger.wallet.balance() == 15, "Only one wallet-qualified completion earns coins");
    check(ledger.wallet.buyTimeOff("request", 5, AT, DAY).status == BOUGHT, "Purchase accepted");
    check(ledger.wallet.buyTimeOff("request", 5, AT, DAY + 1).status == ALREADY_BOUGHT, "Retry across midnight");
    check(ledger.wallet.extraPauseMinutes(DAY) == 20 && ledger.wallet.extraPauseMinutes(DAY + 1) == 0, "Local-day entitlement expires");
    ledger.put(event("undo", "walk", DAY, false, "new"));
    check(ledger.wallet.balance() == 0, "Deduplicated check-off remains after undo");
    ledger.put(event("undo-duplicate", "walk", DAY, false, "new-duplicate"));
    check(ledger.wallet.balance() == -15, "Undo can create debt");
    Wallet.Purchase blocked = ledger.wallet.buyTimeOff("more", 5, AT, DAY);
    check(blocked.status == NOT_ENOUGH && blocked.missing == 30, "Debt blocks further purchases");
    check(ledger.wallet.timeOffBought(DAY) == 5, "Undo retains purchases");
  }

  private static void offlinePurchases() {
    Habits phone = new Habits();
    phone.wallet.start(DAY - 1);
    check(phone.wallet.earn("credit", "future_kind", 40, "", AT, DAY), "An unfamiliar earning kind is accepted");
    check(!phone.wallet.earn("credit", "future_kind", 40, "", AT, DAY), "Earning retries once");
    Habits laptop = new Habits();
    laptop.merge(phone);
    check(phone.wallet.buyTimeOff("phone", 10, AT, DAY).status == BOUGHT, "Phone buys time offline");
    check(laptop.wallet.buyTimeOff("laptop", 10, AT, DAY).status == BOUGHT, "Laptop buys time offline");
    check(laptop.wallet.buyTimeOff("over-cap", 10, AT, DAY).status == LIMIT_REACHED, "Cap enforced locally");
    phone.merge(laptop);
    laptop.merge(phone);
    check(phone.wallet.balance() == -20 && laptop.wallet.balance() == -20, "Offline overspend converges");
    check(phone.wallet.timeOffBought(DAY) == 20, "Offline purchases survive cap overage");

    phone.wallet.earn("fund", "future_kind", 200, "", AT, DAY);
    laptop.wallet.earn("fund", "future_kind", 200, "", AT, DAY);
    String key = "streak:clean:2024-10-03";
    check(phone.wallet.spend(key, "streak_repair", 60, "clean:2024-10-03", AT, DAY).status == BOUGHT, "Phone repairs");
    check(laptop.wallet.spend(key, "streak_repair", 120, "clean:2024-10-03", AT, DAY).status == BOUGHT, "Laptop repairs with a different price");
    Habits snapshot = new Habits();
    snapshot.merge(phone);
    phone.merge(laptop);
    laptop.merge(snapshot);
    check(phone.wallet.ledger.equals(laptop.wallet.ledger) && phone.wallet.balance() == 60, "Tie picks lower signed amount");
    check(phone.wallet.reverse(key, AT, DAY) && !phone.wallet.reverse(key, AT, DAY), "One reversal");
    check(phone.wallet.balance() == 180 && phone.wallet.entries("streak_repair", DAY, DAY).isEmpty(), "Reversed entries are excluded");

    Habits reverseFirst = new Habits();
    String reverseId = "reverse:" + key;
    reverseFirst.wallet.mergeEntry(reverseId, phone.wallet.ledger.get(reverseId));
    reverseFirst.wallet.mergeEntry("bad", new Wallet.Entry("bad", AT, DAY, 10_001, "other", "", null));
    check(reverseFirst.wallet.ledger.size() == 1, "Reject invalid amounts");
    reverseFirst.merge(laptop);
    check(reverseFirst.wallet.balance() == 180 && reverseFirst.wallet.entries("streak_repair", DAY, DAY).isEmpty(),
        "Reversal arriving before its target still applies");
  }

  private static Habits.Event event(String id, String habit, int day, boolean reward, String undo) {
    return new Habits.Event(id, habit, AT, day, day, undo == null ? 15 : 0, -1, undo, reward);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

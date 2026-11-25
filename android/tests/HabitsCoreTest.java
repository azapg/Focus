package org.nightingalelab.focus.habits;

import java.util.*;

public final class HabitsCoreTest {
  private static final int MON = 20_000 - (20_000 + 3) % 7;

  public static void main(String[] args) {
    periodStreak(1, 0, new int[] {0, 1, 2, 3});
    periodStreak(3, 0, new int[] {0, 3, 6, 9});
    periodStreak(1, 5, new int[] {0, 2, 7, 9});
    mergeAndCredit();
    screenEvidence();
    cadenceEdit();
    preserveScheduleHistory();
    capMergeRatchet();
    offlineCadenceEdits();
    tvUsesLaptopLimitWhileDisconnected();
    System.out.println("Android habits core passed");
  }

  private static Habits.Habit habit(String id, String name, int interval, int days, String revision) {
    return new Habits.Habit(id, new Habits.Revision(100, revision), name, interval, days, 720, 15, MON, false);
  }

  private static Habits.Event event(String id, String habit, int day, int period, String undo) {
    return new Habits.Event(id, habit, day * 86_400_000L, day, period, undo == null ? 15 : 0, 11, undo);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void periodStreak(int interval, int days, int[] p) {
    Habits ledger = new Habits();
    ledger.put(habit("a","Dishes", interval, days, "a"));
    ledger.put(event("a1", "a", MON, MON, null));
    require(!ledger.status(MON + p[1], 719).get(0).overdue, "before optional time");
    require(ledger.status(MON + p[1], 720).get(0).overdue, "at optional time");
    Habits.Status oneMiss = ledger.status(MON + p[2], 0).get(0);
    require(oneMiss.grace && oneMiss.streak == 1, "one miss preserves streak");
    require(ledger.status(MON + p[3], 0).get(0).streak == 0, "second miss resets");
    ledger.put(event("a2", "a", MON + p[2], MON + p[2], null));
    require(ledger.status(MON + p[2], 0).get(0).streak == 2, "recovery after one miss");
  }

  private static void mergeAndCredit() {
    Habits first = new Habits(), second = new Habits();
    first.put(habit("h","Laundry", 1, 0, "a"));
    second.put(habit("h","Laundry towels", 1, 0, "b"));
    first.put(event("a", "h", MON, MON, null));
    second.put(event("u", "h", MON, MON, "a"));
    second.put(event("b", "h", MON, MON, null));
    first.merge(second);
    second.merge(first);
    for (Habits ledger : Arrays.asList(first, second)) {
      Habits.Status status = ledger.status(MON, 0).get(0);
      require(status.habit.name.equals("Laundry towels"), "concurrent revision tie");
      require(status.completion.id.equals("b") && ledger.credit(MON) == 15, "undo one check-off");
      for (int i = 0; i < 6; i++) {
        String id = "h" + i;
        ledger.put(habit(id, id, 1, 0, "a"));
        ledger.put(event("e" + i, id, MON, MON, null));
        ledger.put(event("f" + i, id, MON + 1, MON + 1, null));
      }
      require(ledger.credit(MON) == 45, "default cap");
      ledger.put(new Habits.Cap(new Habits.Revision(100, "cap"), 60, 30, MON + 1));
      require(ledger.credit(MON) == 30 && ledger.credit(MON + 1) == 60, "cap ratchet");
    }
  }

  private static void cadenceEdit() {
    Habits ledger = new Habits();
    int day = MON + 2;
    ledger.put(event("first", "laundry", day, MON + 1, null));
    ledger.put(habit("laundry", "Laundry", 1, 1 << 2, "edit"));
    require(!ledger.status(day, 0).get(0).due, "cadence edits preserve today's completion");
    Habits offline = new Habits();
    offline.put(event("second", "laundry", day, day, null));
    ledger.merge(offline);
    offline.merge(ledger);
    for (Habits replica : Arrays.asList(ledger, offline)) {
      Habits.Status row = replica.status(day, 0).get(0);
      require(row.streak == 1 && row.completion.id.equals("first"), "one streak check-off per day");
      require(row.week.subList(5, 7).equals(Arrays.asList("done", "miss")), "one week mark per check-off");
    }
    require(ledger.credit(day) == 15, "at most one credit per habit per day");
    ledger.put(event("undo-first", "laundry", day, MON + 1, "first"));
    Habits.Status row = ledger.status(day, 0).get(0);
    require(row.streak == 1 && row.completion.id.equals("second"), "undo selects the other check-off");
    require(row.week.subList(5, 7).equals(Arrays.asList("off", "done")), "undo moves the week mark");
    require(ledger.credit(day) == 15, "undo preserves the competing credit");
  }

  private static void preserveScheduleHistory() {
    Habits first = new Habits(), second = new Habits();
    Habits.Habit daily = habit("a", "Dishes", 1, 0, "a");
    first.put(daily);
    int edit = MON + 7;
    for (int day = MON; day < edit; day++) first.put(event("c" + day, "a", day, day, null));
    second.put(habit("a", "Dishes", 1, 1, "b").keepScheduleHistory(daily, edit));
    first.merge(second);
    second.merge(first);
    for (Habits replica : Arrays.asList(first, second)) {
      Habits.Status status = replica.status(edit, 0).get(0);
      require(status.due && status.streak == 7, "cadence change keeps prior check-offs");
      require(status.week.equals(Arrays.asList("done", "done", "done", "done", "done", "done", "due")),
          "week strip uses the schedule that applied each day");
      require(replica.status(edit + 7, 0).get(0).streak == 7, "one weekly miss preserves streak");
      require(replica.status(edit + 14, 0).get(0).streak == 0, "two weekly misses reset streak");
    }
  }

  private static void capMergeRatchet() {
    Habits first = new Habits(), second = new Habits();
    first.put(new Habits.Cap(new Habits.Revision(10, "lower"), 30, 45, MON));
    second.put(new Habits.Cap(new Habits.Revision(11, "raise"), 60, 45, MON + 1));
    first.merge(second);
    second.merge(first);
    for (Habits replica : Arrays.asList(first, second)) {
      require(replica.capOn(MON) == 30, "an offline increase cannot restore today's credit");
      require(replica.capOn(MON + 1) == 60, "the latest increase takes effect tomorrow");
    }
  }

  private static void offlineCadenceEdits() {
    Habits laptop = new Habits(), phone = new Habits();
    Habits.Habit original = habit("a", "Dishes", 1, 1, "a");
    laptop.put(habit("a", "Dishes", 1, 0, "b").keepScheduleHistory(original, MON + 1));
    laptop.put(event("first", "a", MON + 1, MON + 1, null));
    laptop.put(event("second", "a", MON + 2, MON + 2, null));
    phone.put(habit("a", "Dishes", 1, 1 << 4, "c").keepScheduleHistory(original, MON + 3));
    laptop.merge(phone);
    phone.merge(laptop);
    for (Habits replica : Arrays.asList(laptop, phone)) {
      Habits.Status row = replica.status(MON + 4, 0).get(0);
      require(row.habit.weekdays == 1 << 4 && row.streak == 2, "new cadence keeps offline check-offs");
      require(row.week.subList(3, 7).equals(Arrays.asList("done", "done", "off", "due")),
          "both replicas retain the dates the older cadence applied");
    }
  }

  private static void tvUsesLaptopLimitWhileDisconnected() {
    Habits.PauseLimit laptop = new Habits.PauseLimit("laptop", true, 100, 2, 60, 90, 200);
    Habits.PauseLimit phone = new Habits.PauseLimit("phone", false, 210, 0, 60, 0, 0);
    require(laptop.replaces(phone), "the laptop's settings take priority over a fresher phone sample");
    require(laptop.effective(199) == 60 && laptop.effective(200) == 90,
        "a cached scheduled increase applies without a new sample");
    Habits.PauseLimit canceled = new Habits.PauseLimit("laptop", true, 95, 3, 30, 0, 0);
    require(canceled.replaces(laptop) && canceled.effective(200) == 30,
        "a newer laptop sample replaces the cached change after a clock adjustment");
    require(!laptop.replaces(canceled), "an older laptop sample cannot restore the cached increase");
  }

  private static void screenEvidence() {
    long now = 7_200_000;
    Habits.Screen phone = new Habits.Screen(now - 1_920_000, now,
        Arrays.asList(new long[] {now - 1_200_000, now - 900_000}, new long[] {now - 10_000, now}));
    Habits.Screen peer = new Habits.Screen(phone.from, now - 25_000,
        Collections.singletonList(new long[] {now - 1_080_000, now - 720_000}));
    require(Habits.away(now, phone, Collections.singletonList(peer)) == 11, "measured across both screens");
    require(Habits.away(now, phone, Collections.singletonList(null)) == -1, "unknown unavailable screen");
    Habits.Screen active = new Habits.Screen(peer.from, peer.to,
        Collections.singletonList(new long[] {now - 50_000, peer.to}));
    require(Habits.away(now, phone, Collections.singletonList(active)) == 0, "peer active");
    Habits.Screen older = new Habits.Screen(peer.from, now - 70_000, peer.spans);
    Habits.Screen stale = new Habits.Screen(peer.from, now - 120_000, peer.spans);
    require(Habits.away(now, phone, Arrays.asList(older, stale)) == -1, "stale samples remain stale regardless of order");
  }
}

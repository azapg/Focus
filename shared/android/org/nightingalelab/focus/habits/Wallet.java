package org.nightingalelab.focus.habits;

import java.util.*;

public final class Wallet {
  public static final int SYNC_VERSION = 1;
  private static final int MAX_AMOUNT = 10_000;
  private static final int MAX_TIME_OFF = 60;
  public static final int TIME_OFF_PRICE_PER_MINUTE = 1;
  private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]+");
  private static final String TIME_OFF = "time_off", TIME_PREFIX = "time:";
  private static final String REVERSAL = "reversal", REVERSE_PREFIX = "reverse:";

  public static final class Entry implements Comparable<Entry> {
    public final String id, kind, reference, reverses;
    public final long at;
    public final int day, amount;

    Entry(String id, long at, int day, int amount, String kind, String reference, String reverses) {
      this.id = id;
      this.at = at;
      this.day = day;
      this.amount = amount;
      this.kind = kind;
      this.reference = reference;
      this.reverses = reverses;
    }

    boolean valid() {
      if (at <= 0 || at > 253_402_300_799_999L || !validDay(day) || !validToken(kind, 32)
          || (!reference.isEmpty() && !validToken(reference, 120))) return false;
      if (reverses != null) {
        return validKey(reverses) && id.equals(REVERSE_PREFIX + reverses)
            && kind.equals(REVERSAL) && reference.isEmpty() && amount == 0;
      }
      if (!validKey(id) || kind.equals(REVERSAL) || amount == 0
          || amount < -MAX_AMOUNT || amount > MAX_AMOUNT) return false;
      if (kind.equals(TIME_OFF)) {
        return id.startsWith(TIME_PREFIX) && validToken(id.substring(TIME_PREFIX.length()), 75)
            && amount < 0 && purchasedMinutes() > 0;
      }
      return !id.startsWith(TIME_PREFIX);
    }

    int purchasedMinutes() {
      if (!reference.startsWith("minutes:")) return 0;
      String value = reference.substring("minutes:".length());
      if (value.isEmpty() || value.length() > 2) return 0;
      try {
        int minutes = Integer.parseInt(value);
        return minutes >= 1 && minutes <= MAX_TIME_OFF && value.equals(Integer.toString(minutes)) ? minutes : 0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    @Override public int compareTo(Entry other) {
      int order = id.compareTo(other.id);
      if (order == 0) order = Long.compare(at, other.at);
      if (order == 0) order = Integer.compare(day, other.day);
      if (order == 0) order = Integer.compare(amount, other.amount);
      if (order == 0) order = kind.compareTo(other.kind);
      if (order == 0) order = reference.compareTo(other.reference);
      if (order == 0 && !Objects.equals(reverses, other.reverses)) {
        order = reverses == null ? -1 : other.reverses == null ? 1 : reverses.compareTo(other.reverses);
      }
      return order;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Entry && compareTo((Entry) other) == 0;
    }

    @Override public int hashCode() {
      return Objects.hash(id, at, day, amount, kind, reference, reverses);
    }
  }

  public static final class Purchase {
    public enum Status { BOUGHT, ALREADY_BOUGHT, NOT_ENOUGH, LIMIT_REACHED }
    public final Status status;
    public final long missing;

    private Purchase(Status status, long missing) {
      this.status = status;
      this.missing = missing;
    }
  }

  Integer start;
  final Map<String, Entry> ledger = new TreeMap<>();
  private final Habits habits;

  Wallet(Habits habits) {
    this.habits = habits;
  }

  private static boolean validToken(String value, int max) {
    return value != null && !value.isEmpty() && value.length() <= max && TOKEN.matcher(value).matches();
  }

  private static boolean validKey(String key) {
    return validToken(key, 80) && !key.startsWith(REVERSE_PREFIX);
  }

  private static boolean validDay(int day) {
    return day >= 18_262 && day <= 100_000;
  }

  /** Keep legacy credit on the cutover day. The earliest scheduled start wins across peers. */
  public void start(int today) {
    if (!validDay(Math.addExact(today, 1))) throw new IllegalArgumentException("Invalid wallet day");
    mergeStart(today + 1);
  }

  void mergeStart(Integer incoming) {
    if (incoming != null && validDay(incoming)) start = start == null ? incoming : Math.min(start, incoming);
  }

  void merge(Wallet other) {
    mergeStart(other.start);
    other.ledger.forEach(this::mergeEntry);
  }

  void mergeEntry(String key, Entry entry) {
    if (!key.equals(entry.id) || !entry.valid()) return;
    ledger.merge(key, entry, (left, right) -> left.compareTo(right) <= 0 ? left : right);
  }

  public boolean active(int day) {
    return start != null && day >= start;
  }

  public Integer startDay() {
    return start;
  }

  public long balance() {
    long balance = habits.habitCoins();
    for (Entry entry : ledger.values()) if (active(entry)) balance += entry.amount;
    return balance;
  }

  private boolean active(Entry entry) {
    return entry.reverses == null && !ledger.containsKey(REVERSE_PREFIX + entry.id);
  }

  public List<Entry> entries(String kind, int fromDay, int toDay) {
    List<Entry> result = new ArrayList<>();
    for (Entry entry : ledger.values()) {
      if (entry.kind.equals(kind) && fromDay <= entry.day && entry.day <= toDay && active(entry)) result.add(entry);
    }
    return result;
  }

  public long timeOffBought(int day) {
    long minutes = 0;
    for (Entry entry : entries(TIME_OFF, day, day)) minutes += entry.purchasedMinutes();
    return minutes;
  }

  public int timeOffLimitRemaining(int day) {
    return (int) Math.max(0, habits.capOn(day) - timeOffBought(day));
  }

  public int extraPauseMinutes(int day) {
    return active(day) ? (int) Math.min(Integer.MAX_VALUE - 300, timeOffBought(day)) : habits.credit(day);
  }

  public boolean earn(String key, String kind, int amount, String reference, long at, int day) {
    if (amount <= 0 || TIME_OFF.equals(kind)) throw new IllegalArgumentException("Invalid wallet earning");
    Entry entry = newEntry(key, kind, amount, reference, at, day);
    return ledger.putIfAbsent(entry.id, entry) == null;
  }

  public Purchase spend(String key, String kind, int amount, String reference, long at, int day) {
    if (amount <= 0 || TIME_OFF.equals(kind)) throw new IllegalArgumentException("Invalid wallet spend");
    return purchase(newEntry(key, kind, -amount, reference, at, day), 0);
  }

  public Purchase buyTimeOff(String requestId, int minutes, long at, int day) {
    if (minutes < 1 || minutes > MAX_TIME_OFF || !validToken(requestId, 75)) {
      throw new IllegalArgumentException("Invalid time off purchase");
    }
    return purchase(newEntry(TIME_PREFIX + requestId, TIME_OFF,
        -minutes * TIME_OFF_PRICE_PER_MINUTE, "minutes:" + minutes, at, day), minutes);
  }

  private Purchase purchase(Entry entry, int minutes) {
    if (ledger.containsKey(entry.id)) return new Purchase(Purchase.Status.ALREADY_BOUGHT, 0);
    if (!active(entry.day)) throw new IllegalStateException("Wallet has not opened yet");
    if (minutes > 0 && timeOffLimitRemaining(entry.day) < minutes) {
      return new Purchase(Purchase.Status.LIMIT_REACHED, 0);
    }
    long missing = -(long) entry.amount - balance();
    if (missing > 0) return new Purchase(Purchase.Status.NOT_ENOUGH, missing);
    ledger.put(entry.id, entry);
    return new Purchase(Purchase.Status.BOUGHT, 0);
  }

  public boolean reverse(String key, long at, int day) {
    String id = REVERSE_PREFIX + key;
    if (key == null || !ledger.containsKey(key) || ledger.containsKey(id)) return false;
    Entry entry = new Entry(id, at, day, 0, REVERSAL, "", key);
    if (!entry.valid()) throw new IllegalArgumentException("Invalid wallet reversal");
    ledger.put(entry.id, entry);
    return true;
  }

  private static Entry newEntry(String key, String kind, int amount, String reference, long at, int day) {
    Entry entry = new Entry(key, at, day, amount, kind, reference, null);
    if (key == null || reference == null || !entry.valid()) throw new IllegalArgumentException("Invalid wallet entry");
    return entry;
  }
}

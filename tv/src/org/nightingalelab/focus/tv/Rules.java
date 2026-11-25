package org.nightingalelab.focus.tv;

/** Pure policy; elapsed time controls exceptions, local minutes control schedules. */
public final class Rules {
  enum CheckIn {
    TIRED("tired", "Tired"), BORED("bored", "Bored"), STUCK("stuck", "Stuck"),
    ANXIOUS("anxious", "Anxious"), SPECIFIC("specific", "Need something specific");

    final String value;
    final String label;

    CheckIn(String value, String label) {
      this.value = value;
      this.label = label;
    }

    static CheckIn parse(String value) {
      for (CheckIn feeling : values()) if (feeling.value.equals(value)) return feeling;
      throw new IllegalArgumentException("Choose a valid check-in");
    }
  }
  public boolean blocked, scheduled;
  public long dailyLimitMs;
  public int fromMinute = 22 * 60, toMinute = 7 * 60;

  public boolean configured() {
    return blocked || scheduled || dailyLimitMs > 0;
  }

  public boolean inSchedule(int minute) {
    return scheduled
        && (fromMinute == toMinute
            || (fromMinute < toMinute
                ? minute >= fromMinute && minute < toMinute
                : minute >= fromMinute || minute < toMinute));
  }

  public String restriction(long used, int minute) {
    if (blocked) return "Blocked";
    if (dailyLimitMs > 0 && used >= dailyLimitMs) return "Daily limit reached";
    return inSchedule(minute) ? "Scheduled restriction" : null;
  }

  public static boolean exceptionValid(
      long deadlineElapsed, int savedBoot, int currentBoot, long elapsed) {
    return savedBoot >= 0 && savedBoot == currentBoot && elapsed < deadlineElapsed;
  }

  public static long deadline(long elapsed, long duration) {
    if (duration <= 0 || duration > 30 * 60_000L || elapsed > Long.MAX_VALUE - duration)
      throw new IllegalArgumentException("Invalid duration");
    return elapsed + duration;
  }
}

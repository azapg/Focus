package org.nightingalelab.focus.habits;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public final class Habits {
  static final int START_DAY = 18_262;

  public static int day(long now) {
    return (int) clock(now).toLocalDate().toEpochDay();
  }

  static ZonedDateTime clock(long now) {
    return Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());
  }

  public static final class Revision implements Comparable<Revision> {
    public final long timestamp;
    public final String id;

    public Revision(long timestamp, String id) {
      this.timestamp = timestamp;
      this.id = id;
    }

    public int compareTo(Revision other) {
      int result = Long.compare(timestamp, other.timestamp);
      return result == 0 ? id.compareTo(other.id) : result;
    }
  }

  public static final class Habit {
    public final String id, name;
    public final Revision rev;
    public final int interval, weekdays, time, reward, start;
    public final boolean deleted;
    final List<Schedule> schedules;

    public Habit(String id, Revision rev, String name, int interval, int weekdays,
        int time, int reward, int start, boolean deleted) {
      this(id, rev, name, interval, weekdays, time, reward, start, deleted, Collections.emptyList());
    }

    Habit(String id, Revision rev, String name, int interval, int weekdays,
        int time, int reward, int start, boolean deleted, List<Schedule> schedules) {
      this.id = id;
      this.rev = rev;
      this.name = name;
      this.interval = interval;
      this.weekdays = weekdays;
      this.time = time;
      this.reward = reward;
      this.start = start;
      this.deleted = deleted;
      this.schedules = Collections.unmodifiableList(schedules);
    }

    boolean valid() {
      return validId(id) && validId(rev.id) && rev.timestamp >= 0 && !name.trim().isEmpty()
          && name.codePointCount(0, name.length()) <= 80 && interval >= 1 && interval <= 365
          && weekdays >= 0 && weekdays <= 127 && time >= -1 && time < 1440
          && reward >= 0 && reward <= 15 && start >= START_DAY && start <= 100_000
          && schedules.stream().allMatch(s -> s.day >= start && s.day <= 100_000
              && s.interval >= 1 && s.interval <= 365 && s.weekdays >= 0 && s.weekdays <= 127
              && validId(s.rev.id) && s.rev.timestamp >= 0);
    }

    Habit withSchedules(List<Schedule> history) {
      Schedule latest = scheduleAt(history, Integer.MAX_VALUE);
      return new Habit(id, rev, name, latest == null ? interval : latest.interval,
          latest == null ? weekdays : latest.weekdays, time, reward, start, deleted, history);
    }

    Habit keepScheduleHistory(Habit prior, int day) {
      if (interval == prior.interval && weekdays == prior.weekdays) return withSchedules(prior.schedules);
      List<Schedule> history = prior.schedules.isEmpty()
          ? Collections.singletonList(new Schedule(prior.start, prior.interval, prior.weekdays, new Revision(0, prior.id)))
          : prior.schedules;
      return withSchedules(mergeSchedules(history, Collections.singletonList(schedule(Math.max(day, start)))));
    }

    private Schedule schedule(int day) {
      return new Schedule(day, interval, weekdays, rev);
    }

    public boolean scheduled(int day) {
      return period(day) == day;
    }

    int period(int day) {
      if (schedules.isEmpty()) return schedule(start).period(day);
      Schedule schedule = scheduleAt(schedules, day);
      return schedule == null ? -1 : schedule.period(day);
    }

    private static Schedule scheduleAt(List<Schedule> history, int day) {
      Schedule result = null;
      for (Schedule candidate : history) {
        if (candidate.day <= day && (result == null || result.rev.compareTo(candidate.rev) < 0)) {
          result = candidate;
        }
      }
      return result;
    }

    int previousPeriod(int day) {
      if (schedules.isEmpty()) return period(day);
      Schedule schedule;
      while ((schedule = scheduleAt(schedules, day)) != null) {
        int period = schedule.period(day);
        if (period >= 0) return period;
        day = schedule.day - 1;
      }
      return -1;
    }

    public String cadence() {
      String result;
      if (weekdays == 0) {
        result = interval == 1 ? "Daily" : "Every " + interval + " days";
      } else {
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        List<String> days = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
          if ((weekdays & (1 << i)) != 0) days.add(names[i]);
        }
        result = String.join(", ", days);
      }
      return time < 0 ? result : result + String.format(Locale.ROOT, " · %02d:%02d", time / 60, time % 60);
    }

    public String streakLabel(int streak) {
      return streak + (weekdays == 0 && interval == 1 ? "d" : "×");
    }
  }

  static final class Schedule {
    final int day, interval, weekdays;
    final Revision rev;

    Schedule(int day, int interval, int weekdays, Revision rev) {
      this.day = day;
      this.interval = interval;
      this.weekdays = weekdays;
      this.rev = rev;
    }

    int period(int end) {
      if (end < day) return -1;
      if (weekdays == 0) return end - (end - day) % interval;
      for (int d = end; d >= day && d > end - 7; d--) {
        if ((weekdays & (1 << Math.floorMod(d + 3, 7))) != 0) return d;
      }
      return -1;
    }

    @Override public boolean equals(Object other) {
      if (!(other instanceof Schedule)) return false;
      Schedule value = (Schedule) other;
      return day == value.day && interval == value.interval && weekdays == value.weekdays
          && rev.compareTo(value.rev) == 0;
    }

    @Override public int hashCode() {
      return Objects.hash(day, interval, weekdays, rev.timestamp, rev.id);
    }
  }

  private static List<Schedule> mergeSchedules(List<Schedule> left, List<Schedule> right) {
    Map<Integer, Schedule> history = new TreeMap<>();
    for (List<Schedule> list : Arrays.asList(left, right)) {
      for (Schedule schedule : list) {
        history.merge(schedule.day, schedule, (a, b) -> a.rev.compareTo(b.rev) >= 0 ? a : b);
      }
    }
    return new ArrayList<>(history.values());
  }

  public static final class Event {
    public final String id, habit, undo;
    public final long at;
    public final int day, period, reward, away;
    public final boolean walletReward;

    public Event(String id, String habit, long at, int day, int period, int reward, int away, String undo) {
      this(id, habit, at, day, period, reward, away, undo, false);
    }

    Event(String id, String habit, long at, int day, int period, int reward, int away, String undo, boolean walletReward) {
      this.id = id;
      this.habit = habit;
      this.at = at;
      this.day = day;
      this.period = period;
      this.reward = reward;
      this.away = away;
      this.undo = undo;
      this.walletReward = walletReward;
    }

    Event withoutWalletReward() {
      return walletReward ? new Event(id, habit, at, day, period, reward, away, undo) : this;
    }

    boolean sameCompletion(Event other) {
      return id.equals(other.id) && habit.equals(other.habit) && at == other.at && day == other.day
          && period == other.period && reward == other.reward && away == other.away && Objects.equals(undo, other.undo);
    }

    boolean valid() {
      return validId(id) && validId(habit) && at >= 0 && day >= START_DAY && day <= 100_000
          && period >= START_DAY && period <= day && reward >= 0 && reward <= 15
          && away >= -1 && away <= 30 && (undo == null || validId(undo));
    }
  }

  public static final class Cap {
    public final Revision rev;
    public final int minutes, previous, effective;

    public Cap(Revision rev, int minutes, int previous, int effective) {
      this.rev = rev;
      this.minutes = minutes;
      this.previous = previous;
      this.effective = effective;
    }

    boolean valid() {
      return validId(rev.id) && rev.timestamp >= 0 && minutes >= 0 && minutes <= 60
          && previous >= 0 && previous <= 60 && effective >= START_DAY && effective <= 100_001;
    }
  }

  public static final class Status {
    public final Habit habit;
    public final boolean due, overdue, grace;
    public final int streak;
    public final Event completion;
    public final List<String> week;

    Status(Habit habit, boolean due, boolean overdue, boolean grace, int streak,
        Event completion, List<String> week) {
      this.habit = habit;
      this.due = due;
      this.overdue = overdue;
      this.grace = grace;
      this.streak = streak;
      this.completion = completion;
      this.week = week;
    }

    public String dueLabel() {
      return overdue ? "Overdue" : grace ? "Missed last time" : "Due";
    }
  }

  final Map<String, Habit> definitions = new TreeMap<>();
  final Map<String, Event> events = new TreeMap<>();
  Cap cap;
  public final Wallet wallet = new Wallet(this);
  final Map<String, Cap> capEdits = new TreeMap<>();

  private static boolean validId(String id) {
    return id != null && !id.isEmpty() && id.length() <= 80;
  }

  public boolean put(Habit habit) {
    if (!habit.valid()) return false;
    Habit prior = definitions.get(habit.id);
    List<Schedule> history = mergeSchedules(prior == null ? Collections.emptyList() : prior.schedules, habit.schedules);
    Habit latest = prior != null && prior.rev.compareTo(habit.rev) >= 0 ? prior : habit;
    if (latest == prior && history.equals(prior.schedules)) return false;
    definitions.put(habit.id, history.isEmpty() ? latest : latest.withSchedules(history));
    return true;
  }

  public boolean put(Event event) {
    if (!event.valid()) return false;
    Event prior = events.putIfAbsent(event.id, event);
    if (prior == null) return true;
    // Old peers can relay the completion without the wallet flag.
    if (event.walletReward && !prior.walletReward && event.sameCompletion(prior)) {
      events.put(event.id, event);
      return true;
    }
    return false;
  }

  public boolean put(Cap candidate) {
    if (candidate == null || !candidate.valid()) return false;
    if (cap != null) capEdits.putIfAbsent(cap.rev.id, cap);
    boolean added = capEdits.putIfAbsent(candidate.rev.id, candidate) == null;
    if (cap == null || cap.rev.compareTo(candidate.rev) < 0) cap = candidate;
    return added;
  }

  public void merge(Habits other) {
    wallet.merge(other.wallet);
    other.definitions.values().forEach(this::put);
    other.events.values().forEach(this::put);
    other.capEdits.values().forEach(this::put);
    put(other.cap);
  }

  private Map<String, NavigableMap<Integer, Event>> completions() {
    Set<String> undone = new HashSet<>();
    for (Event e : events.values()) {
      if (e.undo == null) continue;
      Event target = events.get(e.undo);
      if (target != null && e.habit.equals(target.habit)) undone.add(e.undo);
    }
    List<Event> candidates = new ArrayList<>();
    for (Event event : events.values()) {
      if (event.undo == null && !undone.contains(event.id)) candidates.add(event);
    }
    candidates.sort(Comparator.comparingLong((Event event) -> event.at).thenComparing(event -> event.id));
    Map<String, NavigableMap<Integer, Event>> result = new HashMap<>();
    Map<String, Set<Integer>> checkedDays = new HashMap<>();
    for (Event event : candidates) {
      NavigableMap<Integer, Event> periods = result.computeIfAbsent(event.habit, key -> new TreeMap<>());
      Set<Integer> days = checkedDays.computeIfAbsent(event.habit, key -> new HashSet<>());
      if (!periods.containsKey(event.period) && days.add(event.day)) periods.put(event.period, event);
    }
    return result;
  }

  public int capOn(int day) {
    Cap effective = null;
    int ceiling = 60;
    for (Cap edit : capEdits.values()) {
      if (edit.effective <= day && (effective == null || effective.rev.compareTo(edit.rev) < 0)) effective = edit;
      if (edit.minutes > edit.previous) {
        if (edit.effective - 1 == day) ceiling = Math.min(ceiling, edit.previous);
      } else if (edit.effective == day) {
        ceiling = Math.min(ceiling, edit.minutes);
      }
    }
    return Math.min(ceiling, effective == null ? 45 : effective.minutes);
  }

  long habitCoins() {
    long coins = 0;
    for (Map<Integer, Event> periods : completions().values()) {
      for (Event event : periods.values()) if (event.walletReward && wallet.active(event.day)) coins += event.reward;
    }
    return coins;
  }

  public int credit(int day) {
    return creditFor(day, false);
  }

  int legacyCredit(int day) {
    return creditFor(day, true);
  }

  private int creditFor(int day, boolean legacyOnly) {
    int total = 0;
    int limit = capOn(day);
    for (Map<Integer, Event> periods : completions().values()) {
      Event earned = today(periods, day);
      if (earned != null && (!legacyOnly || !earned.walletReward)) total += earned.reward;
      if (total >= limit) return limit;
    }
    return total;
  }

  public List<Status> status(long now) {
    ZonedDateTime clock = clock(now);
    return status((int) clock.toLocalDate().toEpochDay(), clock.getHour() * 60 + clock.getMinute());
  }

  public List<Status> status(int day, int minute) {
    Map<String, NavigableMap<Integer, Event>> all = completions();
    List<Status> rows = new ArrayList<>();
    for (Habit habit : definitions.values()) {
      if (habit.deleted) continue;
      NavigableMap<Integer, Event> periods = all.getOrDefault(habit.id, Collections.emptyNavigableMap());
      int period = habit.period(day);
      Event checkedToday = today(periods, day);
      Event current = completion(periods, period, day);
      if (current == null) current = checkedToday;
      boolean due = period >= 0 && current == null;
      boolean overdue = due && (period != day || habit.time >= 0 && minute >= habit.time);
      int previous = previousOccurrence(habit, periods, period >= 0 ? period - 1 : day, day);
      int streak = 0;
      int misses = 0;
      for (int cursor = previousOccurrence(habit, periods, day, day); cursor >= 0; cursor = previousOccurrence(habit, periods, cursor - 1, day)) {
        if (completion(periods, cursor, day) != null) {
          streak++;
          misses = 0;
        } else if (cursor != period && ++misses == 2) {
          break;
        }
      }
      boolean grace = due && streak > 0 && previous >= 0 && completion(periods, previous, day) == null;
      List<String> week = new ArrayList<>();
      for (int d = day - 6; d <= day; d++) {
        week.add(completion(periods, d, day) != null ? "done" : !habit.scheduled(d) ? "off" : due && d == period ? "due" : "miss");
      }
      rows.add(new Status(habit, due, overdue, grace, streak, checkedToday, week));
    }
    rows.sort(Comparator.comparing((Status row) -> !row.overdue)
        .thenComparing(row -> !row.grace).thenComparingInt(row -> row.habit.time)
        .thenComparing(row -> row.habit.name).thenComparing(row -> row.habit.id));
    return rows;
  }

  private static int previousOccurrence(Habit habit, NavigableMap<Integer, Event> periods, int through, int day) {
    int scheduled = habit.previousPeriod(through);
    for (Event event : periods.headMap(through, true).descendingMap().values()) {
      if (event.day <= day) return Math.max(scheduled, event.period);
    }
    return scheduled;
  }

  private static Event today(Map<Integer, Event> periods, int day) {
    return periods.values().stream().filter(event -> event.day == day)
        .findFirst().orElse(null);
  }

  private static Event completion(Map<Integer, Event> periods, int period, int day) {
    Event event = periods.get(period);
    return event != null && event.day <= day ? event : null;
  }

  public Event complete(String habit, String id, long now, int away) {
    int day = day(now);
    for (Status row : status(now)) {
      if (!row.due || !row.habit.id.equals(habit)) continue;
      Event event = new Event(id, habit, now, day, row.habit.period(day), row.habit.reward, away, null, wallet.active(day));
      if (put(event)) return event;
    }
    return null;
  }

  public void undo(String target, String id, long now) {
    Event prior = events.get(target);
    if (prior != null && prior.undo == null) {
      put(new Event(id, prior.habit, now, day(now), prior.period, 0, -1, target));
    }
  }

  public static final class PauseLimit {
    final String peer;
    final boolean laptop;
    final long sampledAt, revision, pendingFrom;
    final int base, pending;

    PauseLimit(String peer, boolean laptop, long sampledAt, long revision, int base, int pending, long pendingFrom) {
      this.peer = peer;
      this.laptop = laptop;
      this.sampledAt = sampledAt;
      this.revision = revision;
      this.base = base;
      this.pending = pending;
      this.pendingFrom = pendingFrom;
    }

    boolean replaces(PauseLimit prior) {
      if (prior == null) return true;
      if (laptop != prior.laptop) return laptop;
      if (peer.equals(prior.peer) && revision > 0 && prior.revision > 0) return revision > prior.revision;
      if (sampledAt != prior.sampledAt) return sampledAt > prior.sampledAt;
      return peer.compareTo(prior.peer) > 0;
    }

    int effective(long now) {
      return pendingFrom > 0 && now >= pendingFrom && pending >= 1 && pending <= 300 ? pending : base;
    }
  }

  public static final class Screen {
    public final long from, to;
    public final List<long[]> spans;

    public Screen(long from, long to, List<long[]> spans) {
      this.from = from;
      this.to = to;
      this.spans = spans;
    }
  }

  public static int away(long now, Screen local, List<Screen> peers) {
    if (local == null) return -1;
    long end = now;
    for (long[] span : local.spans) {
      if (span[1] > now - 3_000 && span[0] < end) end = span[0];
    }
    if (now - end > 30_000) end = now;
    if (local.to < now - 3_000) return -1;
    long oldestSample = end - 90_000;
    for (Screen peer : peers) {
      if (peer == null || peer.to < oldestSample) return -1;
      end = Math.min(end, peer.to);
    }
    if (local.from >= end) return -1;
    long start = Math.max(end - 30 * 60_000, local.from);
    List<Screen> all = new ArrayList<>();
    all.add(local);
    all.addAll(peers);
    boolean active = false;
    for (int i = 0; i < all.size(); i++) {
      Screen screen = all.get(i);
      if (screen == null || screen.to < end - 90_000 || screen.from >= end) return -1;
      start = Math.max(start, screen.from);
      for (long[] span : screen.spans) {
        if (span[0] >= end || span[1] <= span[0]) continue;
        start = Math.max(start, Math.min(span[1], end));
        if (i != 0 && span[1] >= screen.to - 3_000) active = true;
      }
    }
    return active ? 0 : (int) ((end - start) / 60_000);
  }
}

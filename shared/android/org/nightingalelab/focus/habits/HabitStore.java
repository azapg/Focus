package org.nightingalelab.focus.habits;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class HabitStore {
  private static HabitStore instance;

  public static synchronized HabitStore get(Context context) {
    if (instance == null) instance = new HabitStore(context);
    return instance;
  }

  private final AtomicFile file;
  private final SharedPreferences allowancePrefs;
  private Habits.PauseLimit allowanceLimit;
  private String allowanceGeneration;
  private Habits ledger;
  private final Map<String, Habits.Screen> peers = new HashMap<>();
  private final Map<String, JSONObject> usage = new HashMap<>();
  private final Set<Runnable> listeners = new HashSet<>();

  private HabitStore(Context context) {
    allowancePrefs = context.getSharedPreferences("habit_allowance", Context.MODE_PRIVATE);
    allowanceGeneration = allowancePrefs.getString("generation", "");
    try {
      JSONObject saved = new JSONObject(allowancePrefs.getString("limit", "{}"));
      allowanceLimit = decodePauseLimit(saved.optString("peer"), saved);
    } catch (JSONException ignored) {}
    try {
      JSONObject savedUsage = new JSONObject(allowancePrefs.getString("usage", "{}"));
      Iterator<String> keys = savedUsage.keys();
      while (keys.hasNext()) {
        String peer = keys.next();
        JSONObject sample = savedUsage.optJSONObject(peer);
        if (sample != null) usage.put(peer, sample);
      }
    } catch (JSONException ignored) {}
    file = new AtomicFile(new File(context.getFilesDir(), "habits.json"));
    try {
      ledger = decode(new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)));
    } catch (FileNotFoundException e) {
      ledger = new Habits();
    } catch (IOException | JSONException e) {
      throw new IllegalStateException("Could not open habits", e);
    }
    if (ledger.wallet.startDay() == null) {
      updateWallet(wallet -> {
        wallet.activate(Habits.day(System.currentTimeMillis()));
        return null;
      });
    }
  }

  public synchronized Habits read() {
    Habits copy = new Habits();
    copy.merge(ledger);
    return copy;
  }

  public synchronized void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public synchronized void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void update(java.util.function.Consumer<Habits> change) {
    transact(next -> {
      change.accept(next);
      return null;
    });
  }

  public <T> T updateWallet(java.util.function.Function<Wallet, T> change) {
    return transact(next -> change.apply(next.wallet));
  }

  public Wallet.Purchase buyTimeOff(String requestId, int minutes) {
    return updateWallet(wallet -> {
      long now = System.currentTimeMillis();
      return wallet.buyTimeOff(requestId, minutes, now, Habits.day(now));
    });
  }

  private <T> T transact(java.util.function.Function<Habits, T> change) {
    List<Runnable> notify;
    T result;
    synchronized (this) {
      Habits next = read();
      result = change.apply(next);
      if (next.definitions.equals(ledger.definitions) && next.events.equals(ledger.events) && next.cap == ledger.cap
          && next.capEdits.equals(ledger.capEdits) && Objects.equals(next.wallet.start, ledger.wallet.start)
          && next.wallet.ledger.equals(ledger.wallet.ledger)) return result;
      FileOutputStream out = null;
      try {
        out = file.startWrite();
        out.write(encode(next).toString().getBytes(StandardCharsets.UTF_8));
        file.finishWrite(out);
      } catch (IOException | JSONException e) {
        if (out != null) file.failWrite(out);
        throw new IllegalStateException("Could not save habits", e);
      }
      ledger = next;
      notify = new ArrayList<>(listeners);
    }
    for (Runnable listener : notify) listener.run();
    return result;
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static Habits.Revision revision(Habits.Revision previous, long now) {
    return new Habits.Revision(previous == null ? now : Math.max(now, previous.timestamp + 1), id());
  }

  public void save(String habitId, String name, int interval, int weekdays, int time, int reward, boolean delete) {
    long now = System.currentTimeMillis();
    update(next -> {
      Habits.Habit existing = next.definitions.get(habitId == null ? "" : habitId);
      if (habitId != null && (existing == null || existing.deleted)) throw new IllegalStateException("Habit was removed");
      Habits.Habit habit = new Habits.Habit(habitId == null ? id() : habitId,
          revision(existing == null ? null : existing.rev, now), name.trim(), interval, weekdays,
          time, reward, existing == null ? Habits.day(now) : existing.start, delete);
      if (existing != null) {
        boolean alreadyDone = next.status(now).stream()
            .anyMatch(row -> row.habit.id.equals(existing.id) && row.completion != null);
        habit = habit.keepScheduleHistory(existing, Habits.day(now) + (alreadyDone ? 1 : 0));
      }
      if (!next.put(habit)) throw new IllegalArgumentException("Invalid habit");
    });
  }

  public void setCap(int minutes) {
    long now = System.currentTimeMillis();
    int day = Habits.day(now);
    update(next -> {
      int current = next.capOn(day);
      if (!next.put(new Habits.Cap(revision(next.cap == null ? null : next.cap.rev, now),
          minutes, current, minutes > current ? day + 1 : day))) {
        throw new IllegalArgumentException("Invalid credit cap");
      }
    });
  }

  public Habits.Event complete(String habit, long now, int away) {
    Habits.Event[] result = new Habits.Event[1];
    update(next -> result[0] = next.complete(habit, id(), now, away));
    return result[0];
  }

  public void undo(String target) {
    update(next -> next.undo(target, id(), System.currentTimeMillis()));
  }

  public synchronized Map<String, Habits.Screen> screens() {
    return new HashMap<>(peers);
  }

  public synchronized long peerDnsMs(int day) {
    return usage.values().stream().filter(u -> u.optInt("day") == day)
        .mapToLong(u -> u.optLong("off")).sum();
  }

  public synchronized int dailyBase(long now) {
    return allowanceLimit == null ? allowancePrefs.getInt("daily_base", 60) : allowanceLimit.effective(now);
  }

  public synchronized boolean usesDnsAllowance() {
    return allowanceLimit != null || ledger.wallet.active(Habits.day(System.currentTimeMillis())) || ledger.definitions.values().stream().anyMatch(habit -> !habit.deleted);
  }

  public synchronized long remainingDnsMs(int day, long now, long localUsed) {
    return Math.max(0, (dailyBase(now) + (long) ledger.wallet.extraPauseMinutes(day)) * 60_000L - localUsed - peerDnsMs(day));
  }

  public synchronized void revokePauseLimit() {
    String generation = UUID.randomUUID().toString();
    if (!allowancePrefs.edit().remove("limit").remove("daily_base").putString("generation", generation).commit()) {
      throw new IllegalStateException("Could not clear the paired pause limit");
    }
    allowanceGeneration = generation;
    allowanceLimit = null;
  }

  public synchronized String allowanceGeneration() {
    return allowanceGeneration;
  }

  private static Habits.PauseLimit decodePauseLimit(String peer, JSONObject sample) {
    JSONObject limit = sample.optJSONObject("limit");
    int base = limit == null ? sample.optInt("base", 0) : limit.optInt("minutes", 0);
    long sampledAt = sample.optLong("now");
    if (peer.isEmpty() || sampledAt <= 0 || base < 1 || base > 300) return null;
    JSONObject pending = limit == null ? null : limit.optJSONObject("pending");
    return new Habits.PauseLimit(peer, "laptop".equals(sample.optString("kind")), sampledAt, sample.optLong("revision"),
        base, pending == null ? 0 : pending.optInt("value"), pending == null ? 0 : pending.optLong("from"));
  }

  private static boolean newerUsage(JSONObject sample, JSONObject prior) {
    if (prior == null) return true;
    long revision = sample.optLong("revision");
    long priorRevision = prior.optLong("revision");
    if (revision > 0 && priorRevision > 0) {
      if (revision != priorRevision) return revision > priorRevision;
      int day = sample.optInt("day");
      int priorDay = prior.optInt("day");
      if (day != priorDay) return day > priorDay;
      return sample.optLong("off") > prior.optLong("off");
    }
    return sample.optLong("now") > prior.optLong("now");
  }

  public static JSONObject page(Habits ledger, String after, boolean wallet) throws JSONException {
    Habits part = new Habits();
    part.cap = ledger.cap;
    if (wallet) part.wallet.start = ledger.wallet.start;
    int count = 0;
    String cursor = null;
    Map<String, Object> items = new LinkedHashMap<>();
    ledger.capEdits.forEach((key, value) -> items.put("c:" + key, value));
    ledger.definitions.forEach((key, value) -> items.put("d:" + key, value));
    ledger.events.forEach((key, value) -> items.put("e:" + key, value));
    if (wallet) ledger.wallet.ledger.forEach((key, value) -> items.put("w:" + key, value));
    for (Map.Entry<String, Object> item : items.entrySet()) {
      if (item.getKey().compareTo(after) <= 0) continue;
      if (count == 512) return new JSONObject().put("habits", encode(part)).put("next", cursor);
      Object value = item.getValue();
      if (value instanceof Habits.Cap) {
        Habits.Cap edit = (Habits.Cap) value;
        part.capEdits.put(edit.rev.id, edit);
      } else if (value instanceof Habits.Habit) part.put((Habits.Habit) value);
      else if (value instanceof Habits.Event) {
        Habits.Event event = (Habits.Event) value;
        part.put(wallet ? event : event.withoutWalletReward());
      } else {
        Wallet.Entry entry = (Wallet.Entry) value;
        part.wallet.ledger.put(entry.id, entry);
      }
      count++;
      cursor = item.getKey();
    }
    return new JSONObject().put("habits", encode(part)).put("next", JSONObject.NULL);
  }

  public JSONObject exchange(JSONObject input) throws JSONException {
    boolean wallet = input != null && input.optInt("wallet_sync") == Wallet.SYNC_VERSION;
    if (input != null) {
      JSONObject remote = input.optJSONObject("habits");
      if (remote != null) {
        Habits decoded = decode(remote, wallet);
        update(next -> next.merge(decoded));
      }
      JSONObject observations = input.optJSONObject("screens");
      if (observations != null) {
        synchronized (this) {
          Iterator<String> keys = observations.keys();
          while (keys.hasNext()) {
            String key = keys.next();
            Habits.Screen screen = decodeScreen(observations.optJSONObject(key));
            Habits.Screen old = peers.get(key);
            if (screen == null || old == null || old.to < screen.to) peers.put(key, screen);
          }
        }
      }
    }
    JSONObject allowance = input == null ? null : input.optJSONObject("allowance");
    if (allowance != null) mergeAllowance(allowance);
    JSONObject response = input == null || input.isNull("after") ? new JSONObject() : page(read(), input.getString("after"), wallet);
    return wallet ? response.put("wallet_sync", Wallet.SYNC_VERSION) : response;
  }

  private synchronized void mergeAllowance(JSONObject incoming) throws JSONException {
    Map<String, JSONObject> updatedUsage = new HashMap<>(usage);
    Habits.PauseLimit updatedLimit = allowanceLimit;
    JSONObject savedLimit = null;
    Iterator<String> keys = incoming.keys();
    while (keys.hasNext()) {
      String peer = keys.next();
      JSONObject item = incoming.optJSONObject(peer);
      if (item == null) continue;
      if (newerUsage(item, updatedUsage.get(peer))) {
        updatedUsage.put(peer, new JSONObject().put("day", item.optInt("day"))
            .put("now", item.optLong("now")).put("off", item.optLong("off"))
            .put("revision", item.optLong("revision")));
      }
      // Relays must confirm their source again after the TV revokes its managers.
      if (!item.optBoolean("direct") && !allowanceGeneration.isEmpty()
          && !allowanceGeneration.equals(item.optString("generation"))) continue;
      Habits.PauseLimit candidate = decodePauseLimit(peer, item);
      if (candidate != null && candidate.replaces(updatedLimit)) {
        updatedLimit = candidate;
        savedLimit = new JSONObject().put("peer", peer).put("kind", item.optString("kind"))
            .put("now", item.optLong("now")).put("base", candidate.base)
            .put("revision", candidate.revision).put("limit", item.optJSONObject("limit"));
      }
    }
    boolean usageChanged = !usage.equals(updatedUsage);
    if (!usageChanged && savedLimit == null) return;
    SharedPreferences.Editor edit = allowancePrefs.edit();
    if (usageChanged) edit.putString("usage", new JSONObject(updatedUsage).toString());
    if (savedLimit != null) edit.putString("limit", savedLimit.toString());
    if (!edit.commit()) throw new IllegalStateException("Could not save the shared pause allowance");
    usage.clear();
    usage.putAll(updatedUsage);
    allowanceLimit = updatedLimit;
  }

  public static JSONObject allowanceFromSnapshot(JSONObject snapshot, int day, long from, long now) throws JSONException {
    long used = 0;
    JSONArray pauses = snapshot.optJSONArray("pauses");
    if (pauses != null) for (int i = 0; i < pauses.length(); i++) {
      JSONObject pause = pauses.optJSONObject(i);
      if (pause == null || "app".equals(pause.optString("kind"))) continue;
      used += Math.max(0, Math.min(now, pause.optLong("end")) - Math.max(from, pause.optLong("t")));
    }
    JSONObject result = new JSONObject().put("day", day).put("now", snapshot.optLong("now")).put("off", used)
        .put("revision", snapshot.optLong("snapshot_revision"));
    if (snapshot.has("pause_daily_base")) {
      result.put("base", snapshot.optInt("pause_daily_base")).put("kind", snapshot.optString("kind"))
          .put("limit", snapshot.optJSONObject("pause_daily_limit"));
    }
    return result;
  }

  public static Habits.Screen fromSnapshot(JSONObject snapshot) {
    if (snapshot == null) return null;
    JSONObject permissions = snapshot.optJSONObject("permissions");
    if (permissions == null || !permissions.optBoolean("usage")) return null;
    long age = snapshot.optLong("tracking_age_ms", -1);
    if (age < 0 || age > 30_000) return null;
    long now = snapshot.optLong("now");
    long observed = snapshot.optLong("activity_observed_from");
    JSONArray raw = snapshot.optJSONArray("spans");
    if (observed <= 0 || observed >= now || raw == null) return null;
    long from = Math.max(observed, Math.max(snapshot.optLong("range_from"), now - 32 * 60_000L));
    return new Habits.Screen(from, now, spans(raw, now));
  }

  public static List<long[]> spans(JSONArray raw, long now) {
    List<long[]> spans = new ArrayList<>();
    if (raw == null) return spans;
    for (int i = 0; i < raw.length(); i++) {
      JSONObject row = raw.optJSONObject(i);
      if (row == null) continue;
      long end = row.optLong("e");
      if (end > now - 32 * 60_000L) spans.add(new long[] {row.optLong("s"), end});
    }
    return spans;
  }

  public static JSONObject encodeScreen(Habits.Screen screen) throws JSONException {
    if (screen == null) return null;
    JSONArray spans = new JSONArray();
    for (long[] span : screen.spans) spans.put(new JSONArray().put(span[0]).put(span[1]));
    return new JSONObject().put("from", screen.from).put("to", screen.to).put("spans", spans);
  }

  private static Habits.Screen decodeScreen(JSONObject value) throws JSONException {
    if (value == null) return null;
    JSONArray raw = value.getJSONArray("spans");
    List<long[]> spans = new ArrayList<>();
    for (int i = 0; i < raw.length(); i++) {
      JSONArray span = raw.getJSONArray(i);
      spans.add(new long[] {span.getLong(0), span.getLong(1)});
    }
    return new Habits.Screen(value.getLong("from"), value.getLong("to"), spans);
  }

  private static JSONObject revision(Habits.Revision rev) throws JSONException {
    return new JSONObject().put("id", rev.id).put("timestamp", rev.timestamp);
  }

  private static Habits.Revision revision(JSONObject raw) throws JSONException {
    return new Habits.Revision(raw.getLong("timestamp"), raw.getString("id"));
  }

  static JSONObject encode(Habits ledger) throws JSONException {
    JSONObject definitions = new JSONObject();
    for (Habits.Habit h : ledger.definitions.values()) {
      JSONObject definition = new JSONObject().put("id", h.id).put("rev", revision(h.rev))
          .put("name", h.name).put("interval", h.interval).put("weekdays", h.weekdays)
          .put("time", h.time < 0 ? JSONObject.NULL : h.time).put("reward", h.reward)
          .put("start", h.start).put("deleted", h.deleted);
      if (!h.schedules.isEmpty()) {
        JSONArray schedules = new JSONArray();
        for (Habits.Schedule schedule : h.schedules) {
          schedules.put(new JSONObject().put("day", schedule.day).put("interval", schedule.interval)
              .put("weekdays", schedule.weekdays).put("rev", revision(schedule.rev)));
        }
        definition.put("schedules", schedules);
      }
      definitions.put(h.id, definition);
    }
    JSONObject events = new JSONObject();
    for (Habits.Event e : ledger.events.values()) {
      JSONObject event = new JSONObject().put("id", e.id).put("habit", e.habit).put("at", e.at)
          .put("day", e.day).put("period", e.period).put("reward", e.reward)
          .put("away", e.away < 0 ? JSONObject.NULL : e.away)
          .put("undo", e.undo == null ? JSONObject.NULL : e.undo);
      if (e.walletReward) event.put("wallet_reward", true);
      events.put(e.id, event);
    }
    JSONObject edits = new JSONObject();
    for (Habits.Cap edit : ledger.capEdits.values()) edits.put(edit.rev.id, encodeCap(edit));
    JSONObject payload = new JSONObject().put("definitions", definitions).put("events", events)
        .put("cap", ledger.cap == null ? JSONObject.NULL : encodeCap(ledger.cap)).put("cap_edits", edits);
    if (ledger.wallet.start != null) payload.put("wallet_start", ledger.wallet.start);
    if (!ledger.wallet.ledger.isEmpty()) {
      JSONObject entries = new JSONObject();
      for (Wallet.Entry entry : ledger.wallet.ledger.values()) {
        entries.put(entry.id, new JSONObject().put("id", entry.id).put("at", entry.at).put("day", entry.day)
            .put("amount", entry.amount).put("kind", entry.kind).put("ref", entry.reference)
            .put("reverses", entry.reverses));
      }
      payload.put("wallet_entries", entries);
    }
    return payload;
  }

  private static JSONObject encodeCap(Habits.Cap cap) throws JSONException {
    return new JSONObject().put("rev", revision(cap.rev))
        .put("minutes", cap.minutes).put("previous", cap.previous).put("effective", cap.effective);
  }

  private static Habits.Cap decodeCap(JSONObject cap) throws JSONException {
    return new Habits.Cap(revision(cap.getJSONObject("rev")), cap.getInt("minutes"),
        cap.getInt("previous"), cap.getInt("effective"));
  }

  static Habits decode(JSONObject raw) throws JSONException {
    return decode(raw, true);
  }

  private static Habits decode(JSONObject raw, boolean wallet) throws JSONException {
    Habits ledger = new Habits();
    JSONObject definitions = raw.getJSONObject("definitions");
    for (Iterator<String> keys = definitions.keys(); keys.hasNext();) {
      String id = keys.next();
      JSONObject h = definitions.getJSONObject(id);
      if (!id.equals(h.getString("id"))) continue;
      List<Habits.Schedule> schedules = new ArrayList<>();
      JSONArray history = h.optJSONArray("schedules");
      if (history != null) for (int i = 0; i < history.length(); i++) {
        JSONObject schedule = history.getJSONObject(i);
        schedules.add(new Habits.Schedule(schedule.getInt("day"), schedule.getInt("interval"),
            schedule.getInt("weekdays"), revision(schedule.getJSONObject("rev"))));
      }
      ledger.put(new Habits.Habit(id, revision(h.getJSONObject("rev")), h.getString("name"),
          h.getInt("interval"), h.getInt("weekdays"), h.optInt("time", -1), h.getInt("reward"),
          h.getInt("start"), h.optBoolean("deleted"), schedules));
    }
    JSONObject events = raw.getJSONObject("events");
    for (Iterator<String> keys = events.keys(); keys.hasNext();) {
      String id = keys.next();
      JSONObject e = events.getJSONObject(id);
      if (!id.equals(e.getString("id"))) continue;
      ledger.put(new Habits.Event(id, e.getString("habit"), e.getLong("at"), e.getInt("day"),
          e.getInt("period"), e.getInt("reward"), e.optInt("away", -1),
          e.isNull("undo") ? null : e.getString("undo"), wallet && Boolean.TRUE.equals(e.opt("wallet_reward"))));
    }
    JSONObject cap = raw.optJSONObject("cap");
    if (cap != null) ledger.put(decodeCap(cap));
    JSONObject edits = raw.optJSONObject("cap_edits");
    if (edits != null) for (Iterator<String> keys = edits.keys(); keys.hasNext();) {
      ledger.put(decodeCap(edits.getJSONObject(keys.next())));
    }
    if (wallet) {
      Long start = integer(raw.opt("wallet_start"));
      if (start != null && start >= Integer.MIN_VALUE && start <= Integer.MAX_VALUE) ledger.wallet.mergeStart(start.intValue());
      JSONObject entries = raw.optJSONObject("wallet_entries");
      if (entries != null) {
        for (Iterator<String> keys = entries.keys(); keys.hasNext();) {
          String key = keys.next();
          JSONObject entry = entries.optJSONObject(key);
          if (entry == null) continue;
          Object id = entry.opt("id"), kind = entry.opt("kind"), reference = entry.opt("ref"), reverses = entry.opt("reverses");
          Long at = integer(entry.opt("at")), day = integer(entry.opt("day")), amount = integer(entry.opt("amount"));
          if (!(id instanceof String) || !(kind instanceof String) || !(reference instanceof String)
              || (reverses != null && reverses != JSONObject.NULL && !(reverses instanceof String))
              || at == null || day == null || day < Integer.MIN_VALUE || day > Integer.MAX_VALUE
              || amount == null || amount < Integer.MIN_VALUE || amount > Integer.MAX_VALUE) continue;
          ledger.wallet.mergeEntry(key, new Wallet.Entry((String) id, at, day.intValue(), amount.intValue(),
              (String) kind, (String) reference, reverses == JSONObject.NULL ? null : (String) reverses));
        }
      }
    }
    return ledger;
  }

  private static Long integer(Object value) {
    return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : null;
  }
}

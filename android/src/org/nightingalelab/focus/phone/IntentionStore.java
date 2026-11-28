package org.nightingalelab.focus.phone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone's copy of the current intention.
 *
 * The laptop owns intentions (intentions.jsonl). The phone adopts the laptop's intention from each
 * synced snapshot, and sends its own changes to the laptop. While the laptop is asleep or away,
 * changes apply on the phone right away and wait in an outbox. The laptop drops a queued change
 * that is older than its own (cause `stale`), and the phone then adopts the laptop's version.
 */
final class IntentionStore {
  static final String ACTION_CHANGED = "org.nightingalelab.focus.phone.INTENTION_CHANGED";
  private static final String PREFS = "focus_intention";
  private static final int MAX_OUTBOX = 20;
  private static final long FLUSH_EVERY_MS = 30_000L;

  private static IntentionStore instance;
  private final Context context;
  private final SharedPreferences prefs;
  private Intention current;
  private JSONArray outbox;
  private long laptopSeenAt;
  private boolean flushing;
  private long lastFlushElapsed;

  static synchronized IntentionStore get(Context c) {
    if (instance == null) instance = new IntentionStore(c.getApplicationContext());
    return instance;
  }

  private IntentionStore(Context c) {
    context = c;
    prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    current = fromJson(parse(prefs.getString("current", "")));
    JSONArray saved;
    try {
      saved = new JSONArray(prefs.getString("outbox", "[]"));
    } catch (JSONException e) {
      saved = new JSONArray();
    }
    outbox = saved;
    laptopSeenAt = prefs.getLong("laptop_seen_at", 0);
  }

  private static JSONObject parse(String text) {
    try {
      return text == null || text.isEmpty() ? null : new JSONObject(text);
    } catch (JSONException e) {
      return null;
    }
  }

  /** Reads the laptop's intention record (`IntentionRec` in app/src/intentions.rs). */
  static Intention fromJson(JSONObject o) {
    if (o == null || o.optString("id").isEmpty() || o.optString("label").isEmpty()) return null;
    long start = o.optLong("start");
    long until = o.optLong("planned_until");
    if (start <= 0 || until < start) return null;
    return new Intention(o.optString("id"), start, until, o.optInt("planned_minutes", Intention.DEFAULT_MINUTES),
        o.optString("label"), o.isNull("done_when") ? "" : o.optString("done_when"), o.optString("raw"),
        o.optString("parse_source", "local"), o.optInt("extensions"));
  }

  static JSONObject toJson(Intention i) {
    try {
      JSONObject o = new JSONObject().put("id", i.id).put("start", i.start).put("planned_until", i.until)
          .put("planned_minutes", i.plannedMinutes).put("label", i.label).put("raw", i.raw)
          .put("parse_source", i.parseSource).put("extensions", i.extensions).put("active", true);
      if (!i.doneWhen.isEmpty()) o.put("done_when", i.doneWhen);
      return o;
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** Reads the laptop's `ParsedIntention` reply. */
  static Intention.Parsed parsedFromJson(JSONObject o) {
    if (o == null || o.optString("label").trim().isEmpty()) return null;
    return new Intention.Parsed(o.optString("label"), o.optInt("planned_minutes", Intention.DEFAULT_MINUTES),
        o.isNull("done_when") ? "" : o.optString("done_when"), o.optString("raw"),
        o.optString("parse_source", "gateway"));
  }

  synchronized Intention current() { return current; }

  /** Apps marked "Not drifting" for the current intention. */
  synchronized Set<String> fits() {
    Set<String> out = new HashSet<>();
    if (current == null || !current.id.equals(prefs.getString("fits_for", ""))) return out;
    JSONArray apps = parseArray(prefs.getString("fits", "[]"));
    for (int i = 0; i < apps.length(); i++) out.add(apps.optString(i));
    return out;
  }

  synchronized void markFits(String app) {
    if (current == null || app == null || app.isEmpty()) return;
    Set<String> apps = fits();
    apps.add(app);
    prefs.edit().putString("fits_for", current.id).putString("fits", new JSONArray(apps).toString()).apply();
    event(FocusEventCodes.STAGE_NUDGE, FocusEventCodes.OUTCOME_SKIPPED, FocusEventCodes.OK, null);
  }

  synchronized long lastNudgeAt() {
    return current != null && current.id.equals(prefs.getString("nudge_for", "")) ? prefs.getLong("nudge_at", 0) : 0;
  }

  synchronized void nudged(long at) {
    if (current == null) return;
    prefs.edit().putString("nudge_for", current.id).putLong("nudge_at", at).apply();
    event(FocusEventCodes.STAGE_NUDGE, FocusEventCodes.OUTCOME_OK, FocusEventCodes.OK, null);
  }

  /** True once per intention and planned end, so "time's up" shows once and again after +10 min. */
  synchronized boolean claimTimeUp() {
    if (current == null) return false;
    String key = current.id + ":" + current.until;
    if (key.equals(prefs.getString("time_up_for", ""))) return false;
    prefs.edit().putString("time_up_for", key).apply();
    event(FocusEventCodes.STAGE_TIME_UP, FocusEventCodes.OUTCOME_OK, FocusEventCodes.OK, null);
    return true;
  }

  // --- Changes made on the phone ----------------------------------------------------------------

  /** Reads one sentence. Uses the laptop's Gateway reader when reachable, else the offline parser. Blocking. */
  Intention.Parsed read(String text) {
    long started = SystemClock.elapsedRealtime();
    DeviceHub.PairedDevice laptop = laptop();
    String cause = FocusEventCodes.LAPTOP_UNREACHABLE;
    if (laptop != null) {
      try {
        JSONObject reply = DeviceHub.get(context).laptopRequest(laptop, "/v1/command",
            new JSONObject().put("action", "intention_parse").put("text", text),
            SystemClock.elapsedRealtime() + 2500, 12_000, FocusEvents.newId()).json;
        Intention.Parsed parsed = parsedFromJson(reply);
        if (parsed != null) return parsed;
        cause = FocusEventCodes.LAPTOP_INVALID_REPLY;
      } catch (LanClient.CommandException e) {
        cause = FocusEventCodes.LAPTOP_REJECTED;
      } catch (Exception e) {
        cause = FocusEventCodes.LAPTOP_UNREACHABLE;
      }
    }
    Calendar now = Calendar.getInstance();
    Intention.Parsed local = Intention.parseLocal(text,
        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
    FocusEvents.record(context, FocusEventCodes.EVENT_INTENTION, FocusEventCodes.STAGE_PARSE_FALLBACK,
        FocusEventCodes.OUTCOME_OK, cause, SystemClock.elapsedRealtime() - started, fields("parse_source", "local"));
    return local;
  }

  void start(Intention.Parsed parsed) {
    long at = System.currentTimeMillis();
    synchronized (this) {
      current = Intention.started("phone-" + at, at, parsed);
      enqueue(op("intention_start", current.id, at).put("label", parsed.label)
          .put("planned_minutes", parsed.minutes).put("done_when", parsed.doneWhen.isEmpty() ? null : parsed.doneWhen)
          .put("raw", parsed.raw).put("parse_source", parsed.source));
      save();
    }
    event(FocusEventCodes.STAGE_SET, FocusEventCodes.OUTCOME_OK, FocusEventCodes.OK,
        fields("planned_minutes", parsed.minutes));
    changed();
  }

  void extend(int minutes) {
    long at = System.currentTimeMillis();
    synchronized (this) {
      if (current == null) return;
      current = current.extended(at, minutes);
      enqueue(op("intention_extend", current.id, at).put("minutes", minutes));
      save();
    }
    event(FocusEventCodes.STAGE_EXTEND, FocusEventCodes.OUTCOME_OK, FocusEventCodes.OK,
        fields("planned_minutes", minutes));
    changed();
  }

  /** Ends the intention: `done`, `partly`, `no` or `cleared`. */
  void end(String outcome) {
    long at = System.currentTimeMillis();
    synchronized (this) {
      if (current == null) return;
      enqueue(op("intention_end", current.id, at).put("outcome", outcome));
      current = null;
      save();
    }
    event(FocusEventCodes.STAGE_END, FocusEventCodes.OUTCOME_OK, FocusEventCodes.OK,
        fields("intention_outcome", outcome));
    changed();
  }

  private static Op op(String action, String id, long at) {
    return new Op(action, id, at);
  }

  /** Small builder so callers can chain puts without checked exceptions. */
  static final class Op {
    final JSONObject json = new JSONObject();

    Op(String action, String id, long at) {
      put("action", action).put("id", id).put("at", at);
    }

    Op put(String key, Object value) {
      try {
        json.put(key, value == null ? JSONObject.NULL : value);
      } catch (JSONException impossible) {
        throw new AssertionError(impossible);
      }
      return this;
    }
  }

  private void enqueue(Op op) {
    // Without a paired laptop there is nobody to tell; the phone keeps its own intention.
    if (laptop() == null) return;
    JSONArray next = new JSONArray();
    for (int i = Math.max(0, outbox.length() - MAX_OUTBOX + 1); i < outbox.length(); i++) next.put(outbox.opt(i));
    next.put(op.json);
    outbox = next;
  }

  private void save() {
    prefs.edit().putString("current", current == null ? "" : toJson(current).toString())
        .putString("outbox", outbox.toString()).putLong("laptop_seen_at", laptopSeenAt).apply();
  }

  // --- Sync with the laptop ---------------------------------------------------------------------

  private DeviceHub.PairedDevice laptop() {
    for (DeviceHub.PairedDevice device : DeviceHub.get(context).list()) {
      if ("laptop".equals(device.kind) && !device.relayed && !device.token.isEmpty()) return device;
    }
    return null;
  }

  /** Adopts the laptop's intention from its latest snapshot, unless phone changes are still queued. */
  void adoptFromLaptop() {
    DeviceHub.PairedDevice laptop = laptop();
    JSONObject snapshot = laptop == null ? null : laptop.snapshot;
    // Laptops without intentions don't send the field; keep the phone's own copy.
    if (snapshot == null || !snapshot.has("intention")) return;
    boolean changed;
    synchronized (this) {
      long seen = snapshot.optLong("now");
      if (outbox.length() > 0 || seen <= laptopSeenAt) return;
      Intention next = fromJson(snapshot.optJSONObject("intention"));
      changed = !(next == null ? current == null : next.sameAs(current));
      if (changed && next != null && current != null && !next.id.equals(current.id)) clearPerIntention();
      current = next;
      laptopSeenAt = seen;
      save();
    }
    if (changed) changed();
  }

  void flushIfDue() {
    long now = SystemClock.elapsedRealtime();
    synchronized (this) {
      if (outbox.length() == 0 || flushing || now - lastFlushElapsed < FLUSH_EVERY_MS) return;
    }
    flushAsync();
  }

  void flushAsync() {
    synchronized (this) {
      if (outbox.length() == 0 || flushing) return;
      flushing = true;
      lastFlushElapsed = SystemClock.elapsedRealtime();
    }
    new Thread(this::flush, "focus-intention-sync").start();
  }

  /** Sends queued changes in order. Stops at the first unreachable attempt and retries later. */
  private void flush() {
    long started = SystemClock.elapsedRealtime();
    String cause = FocusEventCodes.OK;
    String outcome = FocusEventCodes.OUTCOME_OK;
    JSONObject last = null;
    String mappedFrom = "";
    String mappedTo = "";
    try {
      DeviceHub.PairedDevice laptop = laptop();
      while (laptop != null) {
        JSONObject op;
        synchronized (this) {
          if (outbox.length() == 0) break;
          op = new JSONObject(outbox.getJSONObject(0).toString());
        }
        if (op.optString("id").equals(mappedFrom)) op.put("id", mappedTo);
        if ("intention_start".equals(op.optString("action"))) op.remove("id");
        JSONObject reply;
        try {
          reply = DeviceHub.get(context).laptopRequest(laptop, "/v1/command", op,
              SystemClock.elapsedRealtime() + 2500, 8000, FocusEvents.newId()).json;
        } catch (LanClient.CommandException rejected) {
          // An older laptop or a change it can't apply. Drop it instead of retrying forever.
          cause = FocusEventCodes.LAPTOP_REJECTED;
          outcome = FocusEventCodes.OUTCOME_SKIPPED;
          reply = null;
        }
        synchronized (this) {
          JSONObject head = outbox.optJSONObject(0);
          JSONArray rest = new JSONArray();
          for (int i = 1; i < outbox.length(); i++) rest.put(outbox.get(i));
          outbox = rest;
          if (reply != null) {
            last = reply;
            if (!reply.optBoolean("applied", true)) {
              cause = FocusEventCodes.STALE;
              outcome = FocusEventCodes.OUTCOME_SKIPPED;
            }
            JSONObject laptopIntention = reply.optJSONObject("intention");
            if (head != null && "intention_start".equals(head.optString("action"))
                && reply.optBoolean("applied") && laptopIntention != null) {
              mappedFrom = head.optString("id");
              mappedTo = laptopIntention.optString("id");
            }
          }
          save();
        }
      }
    } catch (Exception unreachable) {
      cause = FocusEventCodes.LAPTOP_UNREACHABLE;
      outcome = FocusEventCodes.OUTCOME_FAILED;
    } finally {
      synchronized (this) {
        flushing = false;
      }
    }
    boolean changed = false;
    synchronized (this) {
      if (last != null && outbox.length() == 0) {
        Intention next = fromJson(last.optJSONObject("intention"));
        changed = !(next == null ? current == null : next.sameAs(current));
        if (current != null && next != null && current.id.equals(mappedFrom) && next.id.equals(mappedTo)) {
          // Same intention, now under the laptop's ID. Keep "Not drifting" choices with it.
          prefs.edit().putString("fits_for", mappedTo).putString("nudge_for", mappedTo).apply();
        }
        current = next;
        laptopSeenAt = Math.max(laptopSeenAt, last.optLong("now"));
        save();
      }
    }
    FocusEvents.record(context, FocusEventCodes.EVENT_INTENTION, FocusEventCodes.STAGE_SYNC, outcome, cause,
        SystemClock.elapsedRealtime() - started, fields("direction", "phone_to_laptop"));
    if (changed) changed();
  }

  private void clearPerIntention() {
    prefs.edit().remove("fits_for").remove("fits").remove("nudge_for").remove("nudge_at").apply();
  }

  private void changed() {
    IntentionMonitor.refresh(context);
    try {
      context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
    } catch (RuntimeException ignored) {}
  }

  private void event(String stage, String outcome, String cause, JSONObject extra) {
    JSONObject f = extra == null ? new JSONObject() : extra;
    try {
      f.put("origin", "phone");
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
    FocusEvents.record(context, FocusEventCodes.EVENT_INTENTION, stage, outcome, cause, 0, f);
  }

  private static JSONObject fields(String key, Object value) {
    try {
      return new JSONObject().put(key, value);
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static JSONArray parseArray(String text) {
    try {
      return new JSONArray(text);
    } catch (JSONException e) {
      return new JSONArray();
    }
  }
}

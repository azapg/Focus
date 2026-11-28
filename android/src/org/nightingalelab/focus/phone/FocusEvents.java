package org.nightingalelab.focus.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded, transactional append log. Call from the worker performing the operation. */
public final class FocusEvents {
  private static final Pattern ID = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern BUILD = Pattern.compile("(?:[0-9a-f]{7,40}|dev|local|unknown)");
  private static final long RETENTION_MS = 14 * 86_400_000L;
  private static final int MAX_ROWS = 1024;
  private static final int PAGE_ROWS = 32;
  private static final Set<String> NUMBERS = Set.of("expected_bytes", "actual_bytes", "limit_bytes",
      "http_status", "status_class", "attempt", "install_status", "port", "service_count",
      "planned_minutes", "active_minutes", "extensions");
  private static final Set<String> FLAGS = Set.of("vpn_default", "strict_mode", "private_dns_active",
      "scope_available", "dns_enabled", "dns_paused", "authenticated", "manual_override");
  private static Log log;

  private FocusEvents() {}

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  public static boolean validId(String value) {
    return value != null && ID.matcher(value).matches();
  }

  static String buildId(String value) {
    return value != null && BUILD.matcher(value).matches() ? value : "unknown";
  }

  /**
   * Records only known values. Optional correlation_id should come from newId().
   * An invalid field rejects the whole event. Never pass an exception message or request data.
   */
  public static void record(Context context, String event, String stage, String outcome, String cause,
      long durationMs, JSONObject fields) {
    try {
      String id = newId();
      long at = System.currentTimeMillis();
      JSONObject row = row(at, id, event, stage, outcome, cause, durationMs, fields);
      if (row == null) return;
      String peerId = context.getSharedPreferences("phone_lan_server", Context.MODE_PRIVATE)
          .getString("id", "");
      if (validId(peerId)) row.getJSONObject("fields").put("peer_id", peerId);
      SQLiteDatabase db = log(context).getWritableDatabase();
      db.beginTransaction();
      try {
        ContentValues values = new ContentValues();
        values.put("event_id", id);
        values.put("at_ms", at);
        values.put("json", row.toString());
        long seq = db.insertOrThrow("events", null, values);
        db.delete("events", "at_ms < ? OR seq <= ?",
            new String[] {Long.toString(at - RETENTION_MS), Long.toString(seq - MAX_ROWS)});
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    } catch (RuntimeException | JSONException ignored) {
      // Diagnostics must never change the result of an app operation.
    }
  }

  static JSONObject row(long at, String id, String event, String stage, String outcome,
      String cause, long durationMs, JSONObject fields) throws JSONException {
    if (event == null || stage == null || outcome == null || cause == null
        || !FocusEventCodes.EVENTS.contains(event) || !FocusEventCodes.STAGES.contains(stage)
        || !FocusEventCodes.OUTCOMES.contains(outcome) || !FocusEventCodes.CAUSES.contains(cause)
        || durationMs < 0 || !validId(id)) return null;
    JSONObject safe = new JSONObject();
    if (fields != null) {
      for (Iterator<String> keys = fields.keys(); keys.hasNext();) {
        String key = keys.next();
        Object value = fields.get(key);
        if (!allowed(key, value)) return null;
        safe.put(key, value);
      }
    }
    String correlation = safe.optString("correlation_id", id);
    safe.remove("correlation_id");
    return new JSONObject().put("v", 1).put("at_ms", at).put("event_id", id)
        .put("build_id", buildId(BuildInfo.BUILD_ID)).put("device", "phone")
        .put("correlation_id", correlation).put("event", event).put("stage", stage)
        .put("outcome", outcome).put("cause", cause).put("duration_ms", durationMs)
        .put("fields", safe);
  }

  private static boolean allowed(String key, Object value) {
    if (NUMBERS.contains(key)) return (value instanceof Integer || value instanceof Long)
        && (((Number) value).longValue() >= 0 || key.equals("install_status"));
    if (FLAGS.contains(key)) return value instanceof Boolean;
    if (!(value instanceof String)) return false;
    String text = (String) value;
    switch (key) {
      case "failure_detail":
        return Set.of("timeout", "transport", "http", "envelope", "finish_reason", "refusal",
            "not_text", "parse", "minutes_mismatch", "counter_offer_mismatch", "cancelled").contains(text);
      case "correlation_id":
      case "peer_id": return validId(text);
      case "from_build":
      case "to_build": return BUILD.matcher(text).matches();
      case "origin": return text.equals("phone") || text.equals("laptop");
      case "route": return text.equals("laptop") || text.equals("gateway") || text.equals("local");
      case "direction": return text.equals("phone_to_laptop") || text.equals("laptop_to_phone");
      case "decision_action": return Set.of("allow_services", "allow_domains", "full_pause", "counter_offer", "deny", "fallback", "peek").contains(text);
      case "firewall_profiles": return text.equals("domain_private");
      case "network_category": return Set.of("public", "private", "domain", "mixed", "unknown").contains(text);
      case "intention_outcome": return Set.of("done", "partly", "no", "cleared", "replaced").contains(text);
      case "parse_source": return text.equals("gateway") || text.equals("local");
      default: return false;
    }
  }

  static JSONArray page(Context context, String after) throws JSONException {
    JSONArray rows = new JSONArray();
    try (Cursor cursor = recent(context, after, PAGE_ROWS)) {
      while (cursor.moveToNext()) rows.put(new JSONObject(cursor.getString(0)));
    }
    return rows;
  }

  static String export(Context context) {
    StringBuilder text = new StringBuilder();
    try (Cursor cursor = recent(context, "", MAX_ROWS)) {
      while (cursor.moveToNext()) text.append(cursor.getString(0)).append('\n');
    }
    return text.toString();
  }

  private static Cursor recent(Context context, String after, int limit) {
    SQLiteDatabase db = log(context).getWritableDatabase();
    String cutoff = Long.toString(System.currentTimeMillis() - RETENTION_MS);
    if (limit == MAX_ROWS) db.delete("events", "at_ms < ?", new String[] {cutoff});
    return db.rawQuery("SELECT json FROM events WHERE at_ms >= ? AND seq > "
            + "COALESCE((SELECT seq FROM events WHERE event_id = ?), 0) ORDER BY seq LIMIT ?",
        new String[] {cutoff, validId(after) ? after : "", Integer.toString(limit)});
  }

  private static synchronized Log log(Context context) {
    if (log == null) log = new Log(context.getApplicationContext());
    return log;
  }

  private static final class Log extends SQLiteOpenHelper {
    Log(Context context) {
      super(context, new java.io.File(context.getNoBackupFilesDir(), "focus-events.db").getPath(),
          null, 1);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
      db.setMaximumSize(4 * 1024 * 1024L);
    }

    @Override public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY, event_id TEXT NOT NULL UNIQUE,"
          + " at_ms INTEGER NOT NULL, json TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
  }
}

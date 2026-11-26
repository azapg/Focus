package org.nightingalelab.focus.tv;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.*;
import android.provider.Settings;
import java.util.*;
import org.json.*;

public final class Store extends SQLiteOpenHelper {
  private static Store instance;
  private final Context context;
  private String lastHealth = "";
  private boolean recoveredToday;
  public static volatile String foreground = "";
  public static volatile long heartbeat;
  private volatile long habitObservedFrom;

  public long habitObservedFrom() { return habitObservedFrom; }

  public static synchronized Store get(Context c) {
    if (instance == null) instance = new Store(c.getApplicationContext());
    return instance;
  }

  private Store(Context c) {
    super(c, "focus.db", null, 2);
    context = c;
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE spans(s INTEGER NOT NULL,e INTEGER NOT NULL,pkg TEXT NOT"
            + " NULL,CHECK(e>s),UNIQUE(s,e,pkg))");
    db.execSQL("CREATE INDEX spans_range ON spans(e,s)");
    db.execSQL(
        "CREATE TABLE tracker(id INTEGER PRIMARY KEY CHECK(id=1),cursor INTEGER NOT NULL,pkg TEXT"
            + " NOT NULL,cls TEXT NOT NULL,interactive INTEGER NOT NULL,boot INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE rules(pkg TEXT PRIMARY KEY,blocked INTEGER NOT NULL,limit_ms INTEGER NOT"
            + " NULL,scheduled INTEGER NOT NULL,start_min INTEGER NOT NULL,end_min INTEGER NOT"
            + " NULL)");
    db.execSQL(
        "CREATE TABLE pauses(id TEXT PRIMARY KEY,pkg TEXT NOT NULL,started INTEGER NOT"
            + " NULL,deadline_wall INTEGER NOT NULL,deadline_elapsed INTEGER NOT NULL,boot INTEGER"
            + " NOT NULL,reason TEXT NOT NULL,ended INTEGER,end_kind TEXT,check_in TEXT)");
    db.execSQL("CREATE TABLE diagnostics(at INTEGER NOT NULL,kind TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) db.execSQL("ALTER TABLE pauses ADD COLUMN check_in TEXT");
  }

  public static long dayStart(long wall) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(wall);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  public static int minute() {
    Calendar c = Calendar.getInstance();
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
  }

  public int boot() {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  public boolean usageAllowed() {
    AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.getPackageName())
        == AppOpsManager.MODE_ALLOWED;
  }

  public synchronized void diagnostic(String kind) {
    SQLiteDatabase db = getWritableDatabase();
    ContentValues v = new ContentValues();
    v.put("at", System.currentTimeMillis());
    v.put("kind", kind);
    db.insert("diagnostics", null, v);
    android.util.Log.i("FocusTV", kind);
    db.delete(
        "diagnostics",
        "at<?",
        new String[] {String.valueOf(System.currentTimeMillis() - 14 * 86_400_000L)});
  }

  private void health(String value) {
    if (!lastHealth.equals(value)) {
      lastHealth = value;
      diagnostic(value);
    }
  }

  private boolean interactive() {
    return ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive()
        && !((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE))
            .isKeyguardLocked();
  }

  /** Recover event-bounded activity missed when the monitor did not start or was stopped. */
  private UsageTimeline recoverToday(SQLiteDatabase db, long now, Set<String> tracked) {
    long installed;
    try {
      installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).firstInstallTime;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return null;
    }
    long start = Math.max(installed, dayStart(now));
    if (start >= now) return UsageTimeline.historical(now);
    UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(start, now);
    if (events == null) return null;
    UsageTimeline timeline = UsageTimeline.historical(start);
    UsageEvents.Event event = new UsageEvents.Event();
    UsageTimeline.Sink insert = (from, to, pkg) -> insertMissingUsage(db, from, to, pkg);
    while (events.hasNextEvent()) {
      events.getNextEvent(event);
      timeline.event(event.getTimeStamp(), event.getEventType(), event.getPackageName(),
          event.getClassName(), tracked.contains(event.getPackageName()), insert);
    }
    // The open tail has no ending event and remains the live collector's responsibility.
    return timeline;
  }

  private void insertMissingUsage(SQLiteDatabase db, long from, long to, String pkg) {
    List<long[]> gaps = new ArrayList<>();
    try (Cursor recorded = db.rawQuery("SELECT s,e FROM spans WHERE e>? AND s<? ORDER BY s",
        new String[] {Long.toString(from), Long.toString(to)})) {
      while (recorded.moveToNext()) {
        long next = recorded.getLong(0);
        if (next > from) gaps.add(new long[] {from, next});
        from = Math.max(from, recorded.getLong(1));
      }
    }
    if (to > from) gaps.add(new long[] {from, to});
    for (long[] gap : gaps) {
      db.execSQL("INSERT INTO spans(s,e,pkg) VALUES(?,?,?)", new Object[] {gap[0], gap[1], pkg});
    }
  }

  public synchronized void collect() {
    if (!usageAllowed()) {
      foreground = "";
      health("usage_access_off");
      clearTimeline();
      return;
    }
    if (!((UserManager) context.getSystemService(Context.USER_SERVICE)).isUserUnlocked()) {
      foreground = "";
      health("user_locked");
      return;
    }
    long sampledElapsed = SystemClock.elapsedRealtime();
    long now = System.currentTimeMillis();
    int boot = boot();
    SQLiteDatabase db = getWritableDatabase();
    UsageTimeline t;
    boolean continuous;
    try (Cursor r =
        db.rawQuery("SELECT cursor,pkg,cls,interactive,boot FROM tracker WHERE id=1", null)) {
      boolean has = r.moveToFirst();
      continuous = has && r.getInt(4) == boot && r.getLong(0) <= now && now - r.getLong(0) <= 30_000;
      if (continuous) {
        t = new UsageTimeline(r.getLong(0), r.getInt(3) != 0);
        t.pkg = r.getString(1);
        t.cls = r.getString(2);
      } else {
        t = new UsageTimeline(now, interactive());
        recoveredToday = false;
        if (has) health("tracking_gap");
      }
    }
    UsageEvents events =
        ((UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE))
            .queryEvents(Math.max(0, t.cursor - 60_000), now);
    if (events == null) {
      health("usage_unavailable");
      foreground = "";
      return;
    }
    if (habitObservedFrom == 0 || now - sampledElapsed > habitObservedFrom || SystemClock.elapsedRealtime() - heartbeat > 30_000) {
      habitObservedFrom = t.cursor;
    }
    Set<String> tracked = new HashSet<>();
    for (AppCatalog.App a : AppCatalog.apps(context)) tracked.add(a.pkg);
    db.beginTransaction();
    try {
      if (!recoveredToday) {
        UsageTimeline historical = recoverToday(db, t.cursor, tracked);
        if (historical != null && !continuous) {
          t.pkg = historical.pkg;
          t.cls = historical.cls;
        }
        recoveredToday = historical != null;
      }
      UsageTimeline.Sink sink =
          (start, end, pkg) -> {
            try (Cursor last =
                db.rawQuery("SELECT rowid,s,e,pkg FROM spans ORDER BY e DESC LIMIT 1", null)) {
              if (last.moveToFirst()
                  && last.getLong(2) == start
                  && last.getString(3).equals(pkg)
                  && end - last.getLong(1) <= 60_000) {
                ContentValues update = new ContentValues();
                update.put("e", end);
                db.update("spans", update, "rowid=?", new String[] {"" + last.getLong(0)});
                return;
              }
            }
            ContentValues v = new ContentValues();
            v.put("s", start);
            v.put("e", end);
            v.put("pkg", pkg);
            db.insertOrThrow("spans", null, v);
          };
      long checkpoint = t.cursor;
      UsageEvents.Event e = new UsageEvents.Event();
      while (events.hasNextEvent()) {
        events.getNextEvent(e);
        if (e.getTimeStamp() < checkpoint) {
          t.replay(
              e.getEventType(),
              e.getPackageName(),
              e.getClassName(),
              tracked.contains(e.getPackageName()));
        } else {
          t.event(
              e.getTimeStamp(),
              e.getEventType(),
              e.getPackageName(),
              e.getClassName(),
              tracked.contains(e.getPackageName()),
              sink);
        }
      }
      t.advance(now, sink);
      t.interactive = interactive();
      ContentValues v = new ContentValues();
      v.put("id", 1);
      v.put("cursor", t.cursor);
      v.put("pkg", t.pkg);
      v.put("cls", t.cls);
      v.put("interactive", t.interactive ? 1 : 0);
      v.put("boot", boot);
      db.insertWithOnConflict("tracker", null, v, SQLiteDatabase.CONFLICT_REPLACE);
      db.delete("spans", "e<?", new String[] {String.valueOf(now - 90 * 86_400_000L)});
      db.setTransactionSuccessful();
    } catch (RuntimeException e) {
      recoveredToday = false;
      throw e;
    } finally {
      db.endTransaction();
    }
    foreground = t.interactive ? t.pkg : "";
    heartbeat = sampledElapsed;
    health("tracking");
    expirePauses();
  }

  public synchronized void clearTimeline() {
    habitObservedFrom = 0;
    getWritableDatabase().delete("tracker", null, null);
    foreground = "";
  }

  public synchronized Map<String, Long> usage(long from, long to) {
    Map<String, Long> out = new HashMap<>();
    try (Cursor r =
        getReadableDatabase()
            .rawQuery(
                "SELECT pkg,SUM(min(e,CAST(? AS INTEGER))-max(s,CAST(? AS INTEGER))) FROM spans"
                    + " WHERE e>? AND s<? GROUP BY pkg",
                new String[] {"" + to, "" + from, "" + from, "" + to})) {
      while (r.moveToNext()) out.put(r.getString(0), r.getLong(1));
    }
    return out;
  }

  public long usedToday(String pkg) {
    return usage(dayStart(System.currentTimeMillis()), System.currentTimeMillis())
        .getOrDefault(pkg, 0L);
  }

  public synchronized Rules rule(String pkg) {
    Rules rule = new Rules();
    try (Cursor r =
        getReadableDatabase()
            .rawQuery(
                "SELECT blocked,limit_ms,scheduled,start_min,end_min FROM rules WHERE pkg=?",
                new String[] {pkg})) {
      if (r.moveToFirst()) {
        rule.blocked = r.getInt(0) != 0;
        rule.dailyLimitMs = r.getLong(1);
        rule.scheduled = r.getInt(2) != 0;
        rule.fromMinute = r.getInt(3);
        rule.toMinute = r.getInt(4);
      }
    }
    return rule;
  }

  public synchronized void saveRule(String pkg, Rules rule) {
    if (!AppCatalog.allowed(context, pkg))
      throw new IllegalArgumentException("Protected or unavailable app");
    if (rule.dailyLimitMs < 0
        || rule.dailyLimitMs > 24 * 60 * 60_000L
        || rule.fromMinute < 0
        || rule.fromMinute > 1439
        || rule.toMinute < 0
        || rule.toMinute > 1439) throw new IllegalArgumentException("Invalid rule");
    ContentValues v = new ContentValues();
    v.put("pkg", pkg);
    v.put("blocked", rule.blocked ? 1 : 0);
    v.put("limit_ms", rule.dailyLimitMs);
    v.put("scheduled", rule.scheduled ? 1 : 0);
    v.put("start_min", rule.fromMinute);
    v.put("end_min", rule.toMinute);
    getWritableDatabase().insertWithOnConflict("rules", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    diagnostic("rule_saved");
  }

  public synchronized long pause(String pkg, long duration, String reason) {
    return pause(pkg, duration, reason, null);
  }

  synchronized long pause(String pkg, long duration, String reason, Rules.CheckIn checkIn) {
    if (!DnsController.KEY.equals(pkg) && !AppCatalog.allowed(context, pkg))
      throw new IllegalArgumentException("Protected or unavailable app");
    if (reason == null || reason.trim().isEmpty())
      throw new IllegalArgumentException("Add a reason");
    int boot = boot();
    if (boot < 0) throw new IllegalStateException("Pause clock unavailable");
    long deadline = Rules.deadline(SystemClock.elapsedRealtime(), duration);
    endPause(pkg, "replaced");
    ContentValues v = new ContentValues();
    v.put("id", UUID.randomUUID().toString());
    v.put("pkg", pkg);
    v.put("started", System.currentTimeMillis());
    v.put("deadline_wall", System.currentTimeMillis() + duration);
    v.put("deadline_elapsed", deadline);
    v.put("boot", boot);
    v.put("reason", reason.trim());
    if (checkIn != null) v.put("check_in", checkIn.value);
    getWritableDatabase().insertOrThrow("pauses", null, v);
    diagnostic("pause_started");
    return deadline;
  }

  public synchronized long pauseDeadline(String pkg) {
    try (Cursor r =
        getReadableDatabase()
            .rawQuery(
                "SELECT deadline_elapsed,boot FROM pauses WHERE pkg=? AND ended IS NULL ORDER BY"
                    + " started DESC LIMIT 1",
                new String[] {pkg})) {
      if (r.moveToFirst()
          && Rules.exceptionValid(r.getLong(0), r.getInt(1), boot(), SystemClock.elapsedRealtime()))
        return r.getLong(0);
    }
    return 0;
  }

  public synchronized void endPause(String pkg, String kind) {
    ContentValues v = new ContentValues();
    v.put("ended", System.currentTimeMillis());
    v.put("end_kind", kind);
    if (getWritableDatabase().update("pauses", v, "pkg=? AND ended IS NULL", new String[] {pkg})
        > 0) diagnostic("pause_ended");
  }

  public synchronized void expirePauses() {
    List<String> ended = new ArrayList<>();
    try (Cursor r =
        getReadableDatabase()
            .rawQuery("SELECT pkg,deadline_elapsed,boot FROM pauses WHERE ended IS NULL", null)) {
      while (r.moveToNext())
        // DNS off time ends only after DnsController confirms the settings write.
        // An expired timer or lost permission must not close its audit row early.
        if (!DnsController.KEY.equals(r.getString(0))
            && !Rules.exceptionValid(r.getLong(1), r.getInt(2), boot(), SystemClock.elapsedRealtime()))
          ended.add(r.getString(0));
    }
    for (String pkg : ended) endPause(pkg, "expired_or_rebooted");
  }

  public String restricted(String pkg) {
    if (!AppCatalog.allowed(context, pkg) || pauseDeadline(pkg) > 0) return null;
    return rule(pkg).restriction(usedToday(pkg), minute());
  }

  public synchronized long dnsPauseUsed(long from, long now) {
    long used = 0;
    try (Cursor rows = getReadableDatabase().rawQuery(
        "SELECT started,ended FROM pauses WHERE pkg=? AND started<? AND (ended IS NULL OR ended>?)",
        new String[] {DnsController.KEY, Long.toString(now), Long.toString(from)})) {
      while (rows.moveToNext()) {
        long start = Math.max(from, rows.getLong(0));
        long end = rows.isNull(1) ? now : Math.min(now, rows.getLong(1));
        used += Math.max(0, end - start);
      }
    }
    return used;
  }

  /** Range-limited snapshot. Coalesce adjacent tracking samples into sessions on the wire. */
  public synchronized JSONObject historyRange(long from, long now) throws JSONException {
    JSONObject root = new JSONObject();
    JSONArray spans = new JSONArray();
    Map<String, String> labels = new HashMap<>();
    for (AppCatalog.App app : AppCatalog.apps(context)) labels.put(app.pkg, app.label);
    JSONObject previous = null;
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT max(s,CAST(? AS INTEGER)),min(e,CAST(? AS INTEGER)),pkg FROM spans WHERE e>? AND s<? ORDER BY s",
        new String[] {"" + from, "" + now, "" + from, "" + now})) {
      while (r.moveToNext()) {
        String pkg = r.getString(2);
        if (previous != null && previous.getLong("e") == r.getLong(0) && previous.getString("app").equals(pkg)) {
          previous.put("e", r.getLong(1));
        } else {
          previous = new JSONObject().put("s", r.getLong(0)).put("e", r.getLong(1))
              .put("app", pkg).put("label", labels.getOrDefault(pkg, pkg));
          spans.put(previous);
        }
      }
    }
    JSONArray pauses = new JSONArray();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT pkg,started,deadline_wall,reason,ended,end_kind,check_in FROM pauses WHERE started<? AND (ended IS NULL OR ended>?) ORDER BY started",
        new String[] {"" + now, "" + from})) {
      while (r.moveToNext()) {
        String pkg = r.getString(0);
        String reason = r.getString(3);
        boolean freeTime = reason != null && reason.startsWith("Store free time");
        String kind = freeTime ? "free_time" : (DnsController.KEY.equals(pkg) ? "dns" : "app");
        pauses.put(new JSONObject().put("package", pkg).put("kind", kind)
            .put("free_time", freeTime)
            .put("t", r.getLong(1)).put("end", r.isNull(4) ? now : r.getLong(4))
            .put("minutes", Math.round((r.getLong(2) - r.getLong(1)) / 60000.0))
            .put("reason", reason).put("check_in", r.getString(6))
            .put("end_kind", r.isNull(5) ? JSONObject.NULL : r.getString(5)));
      }
    }
    return root.put("spans", spans).put("pauses", pauses);
  }

  public synchronized boolean hasAnyRules() {
    try (Cursor r = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM rules WHERE blocked=1 OR limit_ms>0 OR scheduled=1", null)) {
      return r.moveToFirst() && r.getInt(0) > 0;
    }
  }

  public synchronized void ensureDefaultYouTubeBlock() {
    if (hasAnyRules()) return;
    for (String yt : new String[] {"com.google.android.youtube.tv", "com.google.android.youtube.tvmusic"}) {
      if (AppCatalog.allowed(context, yt)) {
        Rules rule = rule(yt);
        rule.blocked = true;
        saveRule(yt, rule);
      }
    }
  }

  public synchronized JSONObject exportHistory() throws JSONException {
    JSONObject root = new JSONObject();
    root.put("version", 1);
    root.put("device", "tv");
    root.put("scope", "personal");
    root.put("exportedAt", System.currentTimeMillis());
    JSONArray spans = new JSONArray();
    try (Cursor r = getReadableDatabase().rawQuery("SELECT s,e,pkg FROM spans ORDER BY s", null)) {
      while (r.moveToNext())
        spans.put(
            new JSONObject()
                .put("s", r.getLong(0))
                .put("e", r.getLong(1))
                .put("package", r.getString(2)));
    }
    root.put("spans", spans);
    JSONArray pauses = new JSONArray();
    try (Cursor r =
        getReadableDatabase()
            .rawQuery(
                "SELECT pkg,started,deadline_wall,reason,ended,end_kind,check_in FROM pauses ORDER BY"
                    + " started",
                null)) {
      while (r.moveToNext())
        pauses.put(
            new JSONObject()
                .put("package", r.getString(0))
                .put("started", r.getLong(1))
                .put("deadline", r.getLong(2))
                .put("reason", r.getString(3))
                .put("check_in", r.getString(6))
                .put("ended", r.isNull(4) ? JSONObject.NULL : r.getLong(4))
                .put("endKind", r.isNull(5) ? JSONObject.NULL : r.getString(5)));
    }
    return root.put("pauses", pauses);
  }
}

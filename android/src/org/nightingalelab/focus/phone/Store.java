package org.nightingalelab.focus.phone;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.*;
import android.provider.Settings;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * Local SQLite WAL store for phone usage spans (including per-site mobile browser domains),
 * pause audit history, custom Creating/Consuming rules, policy ratchet state, and diagnostics.
 */
public final class Store extends SQLiteOpenHelper {
  private static Store instance;
  private final Context context;
  private final SharedPreferences policyPrefs;
  private String lastHealth = "";
  public static volatile String foreground = "";
  private String currentBrowserPkg = "";
  private String currentBrowserDomain = "";
  public static volatile long heartbeat;
  private volatile long habitObservedFrom;

  public long habitObservedFrom() { return habitObservedFrom; }

  public static synchronized Store get(Context c) {
    if (instance == null) instance = new Store(c.getApplicationContext());
    return instance;
  }

  private Store(Context c) {
    super(c, "focus_phone.db", null, 3);
    context = c;
    policyPrefs = c.getSharedPreferences("focus_policy", Context.MODE_PRIVATE);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE spans(s INTEGER NOT NULL,e INTEGER NOT NULL,pkg TEXT NOT NULL,domain TEXT NOT NULL DEFAULT '',CHECK(e>s))");
    db.execSQL("CREATE INDEX spans_range ON spans(e,s)");
    db.execSQL("CREATE TABLE tracker(id INTEGER PRIMARY KEY CHECK(id=1),cursor INTEGER NOT NULL,pkg TEXT NOT NULL,cls TEXT NOT NULL,domain TEXT NOT NULL,interactive INTEGER NOT NULL,boot INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE pauses(id TEXT PRIMARY KEY,pkg TEXT NOT NULL,started INTEGER NOT NULL,deadline_wall INTEGER NOT NULL,deadline_elapsed INTEGER NOT NULL,boot INTEGER NOT NULL,reason TEXT NOT NULL,after_plan TEXT NOT NULL DEFAULT '',ended INTEGER,end_kind TEXT)");
    addPauseFeedbackColumns(db);
    db.execSQL("CREATE TABLE user_rules(match_type TEXT NOT NULL,value TEXT NOT NULL,app TEXT NOT NULL,category TEXT NOT NULL,PRIMARY KEY(match_type,value))");
    db.execSQL("CREATE TABLE diagnostics(at INTEGER NOT NULL,kind TEXT NOT NULL)");
    createPeekHistory(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) addPauseFeedbackColumns(db);
    if (oldVersion < 3) createPeekHistory(db);
  }

  @Override
  public void onOpen(SQLiteDatabase db) {
    super.onOpen(db);
    if (!db.isReadOnly()) exportPauseHistory(db);
  }

  private static void addPauseFeedbackColumns(SQLiteDatabase db) {
    db.execSQL("ALTER TABLE pauses ADD COLUMN check_in TEXT");
    db.execSQL("ALTER TABLE pauses ADD COLUMN rating INTEGER");
    db.execSQL("ALTER TABLE pauses ADD COLUMN rating_note TEXT");
    db.execSQL("ALTER TABLE pauses ADD COLUMN rated_at INTEGER");
  }

  private static void createPeekHistory(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE peeks(at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX peeks_at ON peeks(at)");
  }

  public synchronized void recordPeek() {
    try {
      ContentValues row = new ContentValues();
      row.put("at", System.currentTimeMillis());
      getWritableDatabase().insertOrThrow("peeks", null, row);
    } catch (SQLiteException ignored) {}
  }

  public synchronized int peekCount(long from, long to) {
    try (Cursor rows = getReadableDatabase().rawQuery(
        "SELECT COUNT(*) FROM peeks WHERE at>=? AND at<?",
        new String[] {String.valueOf(from), String.valueOf(to)})) {
      return rows.moveToFirst() ? rows.getInt(0) : 0;
    }
  }

  public int boot() {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  public boolean usageAllowed() {
    AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    return ops != null
        && ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.getPackageName())
        == AppOpsManager.MODE_ALLOWED;
  }

  public synchronized void diagnostic(String kind) {
    try {
      SQLiteDatabase db = getWritableDatabase();
      ContentValues v = new ContentValues();
      v.put("at", System.currentTimeMillis());
      v.put("kind", kind);
      db.insert("diagnostics", null, v);
      android.util.Log.i("FocusPhone", kind);
      db.delete("diagnostics", "at<?", new String[] {String.valueOf(System.currentTimeMillis() - 14 * 86_400_000L)});
    } catch (RuntimeException ignored) {}
  }

  public synchronized List<String> recentDiagnostics(int limit) {
    List<String> out = new ArrayList<>();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT at,kind FROM diagnostics ORDER BY at DESC LIMIT ?", new String[] {String.valueOf(limit)})) {
      java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT);
      while (r.moveToNext()) {
        out.add(fmt.format(new Date(r.getLong(0))) + "  " + r.getString(1));
      }
    }
    return out;
  }

  private void health(String value) {
    if (!lastHealth.equals(value)) {
      lastHealth = value;
      diagnostic(value);
    }
  }

  private boolean interactive() {
    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    return pm != null && pm.isInteractive() && (km == null || !km.isKeyguardLocked());
  }

  /** Called by FocusAccessibilityService when the active browser shows a domain or a page without one. */
  public synchronized void observeBrowserDomain(String browserPkg, String domain) {
    if (browserPkg == null || domain == null) return;
    if (browserPkg.equals(currentBrowserPkg) && domain.equals(currentBrowserDomain)) return;
    // Flush the span up to right now under the previous domain before switching.
    if (usageAllowed()) collect();
    currentBrowserPkg = browserPkg;
    currentBrowserDomain = domain;
  }

  public synchronized void clearBrowserDomain() {
    currentBrowserPkg = "";
    currentBrowserDomain = "";
  }

  public synchronized void collect() {
    if (!usageAllowed()) {
      foreground = "";
      health("usage_access_off");
      clearTimeline();
      return;
    }
    UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
    if (um != null && !um.isUserUnlocked()) {
      foreground = "";
      health("user_locked");
      return;
    }
    long sampledElapsed = SystemClock.elapsedRealtime();
    long now = System.currentTimeMillis();
    int boot = boot();
    SQLiteDatabase db = getWritableDatabase();
    UsageTimeline t;
    try (Cursor r = db.rawQuery("SELECT cursor,pkg,cls,domain,interactive,boot FROM tracker WHERE id=1", null)) {
      boolean has = r.moveToFirst();
      if (has && r.getInt(5) == boot && r.getLong(0) <= now && now - r.getLong(0) <= 30_000) {
        t = new UsageTimeline(r.getLong(0), r.getInt(4) != 0);
        t.pkg = r.getString(1);
        t.cls = r.getString(2);
        t.domain = r.getString(3);
      } else {
        t = new UsageTimeline(now, interactive());
        if (has) health("tracking_gap");
      }
    }
    UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    UsageEvents events = usm == null ? null : usm.queryEvents(Math.max(0, t.cursor - 60_000), now);
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
      UsageTimeline.Sink sink = (start, end, pkg, domain) -> {
        try (Cursor last = db.rawQuery("SELECT rowid,s,e,pkg,domain FROM spans ORDER BY e DESC LIMIT 1", null)) {
          if (last.moveToFirst()
              && last.getLong(2) == start
              && last.getString(3).equals(pkg)
              && last.getString(4).equals(domain)
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
        v.put("domain", domain);
        db.insertOrThrow("spans", null, v);
      };
      long checkpoint = t.cursor;
      UsageEvents.Event e = new UsageEvents.Event();
      while (events.hasNextEvent()) {
        events.getNextEvent(e);
        boolean isTracked = tracked.contains(e.getPackageName()) || BrowserUrlParser.isBrowser(e.getPackageName());
        if (e.getTimeStamp() < checkpoint) {
          t.replay(e.getEventType(), e.getPackageName(), e.getClassName(), isTracked);
        } else {
          t.event(e.getTimeStamp(), e.getEventType(), e.getPackageName(), e.getClassName(), isTracked, sink);
        }
      }
      t.domain = t.pkg.equals(currentBrowserPkg) ? currentBrowserDomain : "";
      t.advance(now, sink);
      t.interactive = interactive();
      if (!t.interactive) {
        t.domain = "";
        clearBrowserDomain();
      }
      ContentValues v = new ContentValues();
      v.put("id", 1);
      v.put("cursor", t.cursor);
      v.put("pkg", t.pkg);
      v.put("cls", t.cls);
      v.put("domain", t.domain);
      v.put("interactive", t.interactive ? 1 : 0);
      v.put("boot", boot);
      db.insertWithOnConflict("tracker", null, v, SQLiteDatabase.CONFLICT_REPLACE);
      db.delete("spans", "e<?", new String[] {String.valueOf(now - 90 * 86_400_000L)});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    foreground = t.interactive ? t.pkg : "";
    heartbeat = sampledElapsed;
    health("tracking");
  }

  public synchronized void clearTimeline() {
    habitObservedFrom = 0;
    getWritableDatabase().delete("tracker", null, null);
    foreground = "";
    clearBrowserDomain();
  }

  public synchronized long pause(String pkg, long duration, String reason, String afterPlan, PausePolicy.CheckIn checkIn) {
    if (reason == null || reason.trim().length() < 3)
      throw new IllegalArgumentException("Add a reason (at least 3 characters)");
    int boot = boot();
    if (boot < 0) throw new IllegalStateException("Pause clock unavailable");
    long deadline = SystemClock.elapsedRealtime() + duration;
    endPause(pkg, "replaced");
    long started = System.currentTimeMillis();
    ContentValues v = new ContentValues();
    v.put("id", UUID.randomUUID().toString());
    v.put("pkg", pkg);
    v.put("started", started);
    v.put("deadline_wall", started + duration);
    v.put("deadline_elapsed", deadline);
    v.put("boot", boot);
    v.put("reason", reason.trim());
    v.put("after_plan", afterPlan == null ? "" : afterPlan.trim());
    if (checkIn != null) v.put("check_in", checkIn.value);
    getWritableDatabase().insertOrThrow("pauses", null, v);
    if (DnsController.KEY.equals(pkg)) exportPauseHistory(getReadableDatabase());
    diagnostic("pause_started");
    return deadline;
  }

  public synchronized long pauseDeadline(String pkg) {
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT deadline_elapsed,boot FROM pauses WHERE pkg=? AND ended IS NULL ORDER BY started DESC LIMIT 1",
        new String[] {pkg})) {
      if (r.moveToFirst()) {
        long dl = r.getLong(0);
        int b = r.getInt(1);
        if (b >= 0 && b == boot() && SystemClock.elapsedRealtime() < dl) return dl;
      }
    }
    return 0;
  }

  public synchronized InsightsEngine.PauseRecord activeDnsPause() {
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT started,deadline_wall,deadline_elapsed,boot,reason,after_plan FROM pauses WHERE pkg=? AND ended IS NULL ORDER BY started DESC LIMIT 1",
        new String[] {DnsController.KEY})) {
      if (r.moveToFirst()) {
        long dl = r.getLong(2);
        int b = r.getInt(3);
        if (b >= 0 && b == boot() && SystemClock.elapsedRealtime() < dl) {
          long started = r.getLong(0);
          long dlWall = r.getLong(1);
          int mins = (int) Math.max(1, Math.round((dlWall - started) / 60_000.0));
          long remainMs = Math.max(0, dl - SystemClock.elapsedRealtime());
          return new InsightsEngine.PauseRecord("phone", "phone", "Phone", started,
              System.currentTimeMillis() + remainMs, true, mins, r.getString(4), r.getString(5));
        }
      }
    }
    return null;
  }

  synchronized long dnsPauseDurationMs(long deadline) {
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT deadline_wall - started FROM pauses WHERE pkg=? AND deadline_elapsed=? AND ended IS NULL LIMIT 1",
        new String[] {DnsController.KEY, String.valueOf(deadline)})) {
      return r.moveToFirst() ? r.getLong(0) : 0;
    }
  }

  public synchronized InsightsEngine.PauseRecord endPause(String pkg, String kind) {
    InsightsEngine.PauseRecord closed = null;
    long endedAt = System.currentTimeMillis();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT started,deadline_wall,reason,after_plan,check_in FROM pauses WHERE pkg=? AND ended IS NULL ORDER BY started DESC LIMIT 1",
        new String[] {pkg})) {
      if (r.moveToFirst()) {
        long started = r.getLong(0);
        long dlWall = r.getLong(1);
        int mins = (int) Math.max(1, Math.round((dlWall - started) / 60_000.0));
        closed = new InsightsEngine.PauseRecord("phone", "phone", "Phone", started,
            endedAt, false, mins, r.getString(2), r.getString(3), PausePolicy.CheckIn.parse(r.getString(4)), 0, "");
      }
    }
    ContentValues v = new ContentValues();
    v.put("ended", endedAt);
    v.put("end_kind", kind);
    if (getWritableDatabase().update("pauses", v, "pkg=? AND ended IS NULL", new String[] {pkg}) > 0) {
      if (DnsController.KEY.equals(pkg)) exportPauseHistory(getReadableDatabase());
      diagnostic("pause_ended_" + kind);
    }
    return closed;
  }

  private static final String PENDING_RATING =
      "pkg=? AND started=? AND ended=? AND end_kind='dns_resumed' AND rating IS NULL";

  private static String[] ratingArgs(long started, long ended) {
    return new String[] {DnsController.KEY, String.valueOf(started), String.valueOf(ended)};
  }

  public synchronized boolean canRateDnsPause(long started, long ended) {
    if (!PausePolicy.ratingOpen(ended, System.currentTimeMillis())) return false;
    try (Cursor r = getReadableDatabase().query("pauses", new String[] {"id"},
        PENDING_RATING, ratingArgs(started, ended), null, null, null)) {
      return r.getCount() == 1;
    }
  }

  public synchronized boolean rateDnsPause(long started, long ended, int score, String note) {
    if (!PausePolicy.validRating(score) || !canRateDnsPause(started, ended)) return false;
    long ratedAt = System.currentTimeMillis();
    if (!PausePolicy.ratingOpen(ended, ratedAt)) return false;
    ContentValues v = new ContentValues();
    v.put("rating", score);
    v.put("rating_note", PausePolicy.ratingNote(note));
    v.put("rated_at", ratedAt);
    if (getWritableDatabase().update("pauses", v, PENDING_RATING, ratingArgs(started, ended)) != 1) return false;
    exportPauseHistory(getReadableDatabase());
    return true;
  }

  // SQLite owns phone pause state; refresh its JSONL history atomically for local consumers.
  private void exportPauseHistory(SQLiteDatabase db) {
    AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "pauses.jsonl"));
    FileOutputStream out = null;
    try (Cursor r = db.rawQuery(
        "SELECT started,deadline_wall,reason,after_plan,check_in,ended,end_kind,rating,rating_note,rated_at"
            + " FROM pauses WHERE pkg=? ORDER BY started",
        new String[] {DnsController.KEY})) {
      out = file.startWrite();
      BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
      while (r.moveToNext()) {
        long started = r.getLong(0);
        long deadline = r.getLong(1);
        writer.write(new JSONObject().put("type", "pause").put("t", started)
            .put("until", deadline).put("minutes", Math.max(1, Math.round((deadline - started) / 60_000.0)))
            .put("reason", r.getString(2)).put("after_plan", r.getString(3))
            .put("check_in", r.getString(4)).toString());
        writer.newLine();
        if (r.isNull(5)) continue;
        writer.write(new JSONObject().put("type", "resume").put("t", r.getLong(5))
            .put("paused_at", started).put("why", r.getString(6)).toString());
        writer.newLine();
        if (r.isNull(7)) continue;
        writer.write(new JSONObject().put("type", "rating").put("rated_at", r.getLong(9))
            .put("paused_at", started).put("rating", r.getInt(7)).put("rating_note", r.getString(8)).toString());
        writer.newLine();
      }
      writer.flush();
      file.finishWrite(out);
    } catch (IOException | JSONException | RuntimeException e) {
      if (out != null) file.failWrite(out);
      android.util.Log.w("FocusPhone", "pause_history_export_failed");
    }
  }

  public synchronized List<InsightsEngine.SpanRecord> localSpans(long from, long to) {
    List<InsightsEngine.SpanRecord> out = new ArrayList<>();
    Map<String, String> labels = new HashMap<>();
    for (AppCatalog.App app : AppCatalog.apps(context)) labels.put(app.pkg, app.label);
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT max(s,CAST(? AS INTEGER)),min(e,CAST(? AS INTEGER)),pkg,domain FROM spans WHERE e>? AND s<? ORDER BY s",
        new String[] {"" + from, "" + to, "" + from, "" + to})) {
      while (r.moveToNext()) {
        long s = r.getLong(0), e = r.getLong(1);
        if (e <= s) continue;
        String pkg = r.getString(2);
        String dom = r.getString(3);
        String label = !dom.isEmpty() ? ActivityClassifier.prettyHost(dom) : labels.getOrDefault(pkg, pkg);
        out.add(new InsightsEngine.SpanRecord(s, e, pkg, label, dom, "phone", "Phone", "phone"));
      }
    }
    return out;
  }

  public synchronized List<InsightsEngine.PauseRecord> localPauses(long from, long to) {
    List<InsightsEngine.PauseRecord> out = new ArrayList<>();
    long now = System.currentTimeMillis();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT started,deadline_wall,reason,after_plan,ended,check_in,rating,rating_note"
            + " FROM pauses WHERE started<? AND (ended IS NULL OR ended>?) ORDER BY started",
        new String[] {"" + to, "" + from})) {
      while (r.moveToNext()) {
        long started = r.getLong(0);
        long dlWall = r.getLong(1);
        boolean active = r.isNull(4);
        long ended = active ? now : r.getLong(4);
        int mins = (int) Math.max(1, Math.round((dlWall - started) / 60_000.0));
        out.add(new InsightsEngine.PauseRecord("phone", "phone", "Phone", started, ended, active,
            mins, r.getString(2), r.getString(3), PausePolicy.CheckIn.parse(r.getString(5)), r.getInt(6), r.getString(7)));
      }
    }
    return out;
  }

  // --- User classification rules ---

  public synchronized List<ActivityClassifier.UserRule> userRules() {
    List<ActivityClassifier.UserRule> out = new ArrayList<>();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT match_type,value,app,category FROM user_rules ORDER BY app COLLATE NOCASE", null)) {
      while (r.moveToNext()) {
        out.add(new ActivityClassifier.UserRule(r.getString(0), r.getString(1), r.getString(2), r.getString(3)));
      }
    }
    return out;
  }

  public synchronized void saveUserRule(String matchType, String value, String app, String category) {
    ContentValues v = new ContentValues();
    v.put("match_type", matchType.toLowerCase(Locale.ROOT));
    v.put("value", value.trim());
    v.put("app", app.trim());
    v.put("category", category.toLowerCase(Locale.ROOT));
    getWritableDatabase().insertWithOnConflict("user_rules", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    diagnostic("category_rule_saved");
  }

  public synchronized void deleteUserRule(String matchType, String value) {
    getWritableDatabase().delete("user_rules", "match_type=? AND value=?",
        new String[] {matchType.toLowerCase(Locale.ROOT), value.trim()});
  }

  // --- Policy & strictness ratchet persistence ---

  public synchronized PausePolicy.Config policyConfig() {
    applyDuePolicyChanges();
    PausePolicy.Config c = new PausePolicy.Config();
    c.waitThreshold = policyPrefs.getInt("wait_threshold", 3);
    c.planThreshold = policyPrefs.getInt("plan_threshold", 5);
    c.waitSeconds = policyPrefs.getInt("wait_seconds", 15);
    c.maxPhoneMinutes = policyPrefs.getInt("max_phone_minutes", 30);
    c.maxLaptopMinutes = policyPrefs.getInt("max_laptop_minutes", 30);
    c.maxTvMinutes = policyPrefs.getInt("max_tv_minutes", 300);
    c.dailyLimitMinutes = policyPrefs.getInt("daily_limit_minutes", 60);
    c.guardPrivateDns = policyPrefs.getBoolean("guard_private_dns", true);
    c.notifyHonesty = policyPrefs.getBoolean("notify_honesty", true);
    c.notifyEvening = policyPrefs.getBoolean("notify_evening", true);
    return c;
  }

  public synchronized Map<String, PausePolicy.PendingChange> pendingPolicyChanges() {
    Map<String, PausePolicy.PendingChange> map = new LinkedHashMap<>();
    String raw = policyPrefs.getString("pending_json", "[]");
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        PausePolicy.PendingChange p = new PausePolicy.PendingChange(
            o.getString("key"), o.getString("label"), o.getString("from"), o.getString("to"),
            o.optInt("int_val", 0), o.optBoolean("bool_val", false), o.getLong("effective_at"));
        map.put(p.key, p);
      }
    } catch (JSONException ignored) {}
    return map;
  }

  public synchronized void savePolicy(PausePolicy.Config c, Map<String, PausePolicy.PendingChange> pending) {
    JSONArray arr = new JSONArray();
    for (PausePolicy.PendingChange p : pending.values()) {
      try {
        arr.put(new JSONObject()
            .put("key", p.key).put("label", p.label)
            .put("from", p.fromText).put("to", p.toText)
            .put("int_val", p.intValue).put("bool_val", p.boolValue)
            .put("effective_at", p.effectiveAt));
      } catch (JSONException ignored) {}
    }
    policyPrefs.edit()
        .putInt("wait_threshold", c.waitThreshold)
        .putInt("plan_threshold", c.planThreshold)
        .putInt("wait_seconds", c.waitSeconds)
        .putInt("max_phone_minutes", c.maxPhoneMinutes)
        .putInt("max_laptop_minutes", c.maxLaptopMinutes)
        .putInt("max_tv_minutes", c.maxTvMinutes)
        .putInt("daily_limit_minutes", c.dailyLimitMinutes)
        .putBoolean("guard_private_dns", c.guardPrivateDns)
        .putBoolean("notify_honesty", c.notifyHonesty)
        .putBoolean("notify_evening", c.notifyEvening)
        .putString("pending_json", arr.toString())
        .apply();
  }

  public synchronized void applyDuePolicyChanges() {
    Map<String, PausePolicy.PendingChange> pending = pendingPolicyChanges();
    if (pending.isEmpty()) return;
    PausePolicy.Config c = new PausePolicy.Config();
    c.waitThreshold = policyPrefs.getInt("wait_threshold", 3);
    c.planThreshold = policyPrefs.getInt("plan_threshold", 5);
    c.waitSeconds = policyPrefs.getInt("wait_seconds", 15);
    c.maxPhoneMinutes = policyPrefs.getInt("max_phone_minutes", 30);
    c.maxLaptopMinutes = policyPrefs.getInt("max_laptop_minutes", 30);
    c.maxTvMinutes = policyPrefs.getInt("max_tv_minutes", 300);
    c.dailyLimitMinutes = policyPrefs.getInt("daily_limit_minutes", 60);
    c.guardPrivateDns = policyPrefs.getBoolean("guard_private_dns", true);
    c.notifyHonesty = policyPrefs.getBoolean("notify_honesty", true);
    c.notifyEvening = policyPrefs.getBoolean("notify_evening", true);
    if (PausePolicy.applyDuePending(c, pending, System.currentTimeMillis())) {
      savePolicy(c, pending);
      diagnostic("policy_pending_applied");
    }
  }

  public synchronized PausePolicy.UpdateOutcome updatePolicyInt(String key, int value) {
    PausePolicy.Config c = policyConfig();
    Map<String, PausePolicy.PendingChange> pending = pendingPolicyChanges();
    PausePolicy.UpdateOutcome out = PausePolicy.proposeInt(c, pending, key, value, System.currentTimeMillis(), TimeZone.getDefault());
    savePolicy(c, pending);
    diagnostic(out.appliedNow ? "policy_tightened_" + key : "policy_loosening_queued_" + key);
    return out;
  }

  public synchronized PausePolicy.UpdateOutcome updateGuardPrivateDns(boolean enabled) {
    PausePolicy.Config c = policyConfig();
    Map<String, PausePolicy.PendingChange> pending = pendingPolicyChanges();
    PausePolicy.UpdateOutcome out = PausePolicy.proposeGuardPrivateDns(c, pending, enabled, System.currentTimeMillis(), TimeZone.getDefault());
    savePolicy(c, pending);
    diagnostic(out.appliedNow ? "guard_updated" : "guard_loosening_queued");
    return out;
  }

  public synchronized void cancelPendingPolicy(String key) {
    PausePolicy.Config c = policyConfig();
    Map<String, PausePolicy.PendingChange> pending = pendingPolicyChanges();
    pending.remove(key);
    savePolicy(c, pending);
  }

  public synchronized void setNotificationToggle(String key, boolean value) {
    policyPrefs.edit().putBoolean(key, value).apply();
  }

  /** LAN snapshot payload compatible with Windows FocusDNS DeviceManager. */
  public synchronized JSONObject historyRange(long from, long now) throws JSONException {
    JSONObject root = new JSONObject();
    JSONArray spans = new JSONArray();
    Map<String, String> labels = new HashMap<>();
    for (AppCatalog.App app : AppCatalog.apps(context)) labels.put(app.pkg, app.label);
    JSONObject previous = null;
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT max(s,CAST(? AS INTEGER)),min(e,CAST(? AS INTEGER)),pkg,domain FROM spans WHERE e>? AND s<? ORDER BY s",
        new String[] {"" + from, "" + now, "" + from, "" + now})) {
      while (r.moveToNext()) {
        String pkg = r.getString(2);
        String dom = r.getString(3);
        String wireApp = !dom.isEmpty() ? "site:" + dom : pkg;
        String wireLabel = !dom.isEmpty() ? ActivityClassifier.prettyHost(dom) : labels.getOrDefault(pkg, pkg);
        if (previous != null && previous.getLong("e") == r.getLong(0) && previous.getString("app").equals(wireApp)) {
          previous.put("e", r.getLong(1));
        } else {
          previous = new JSONObject()
              .put("s", r.getLong(0))
              .put("e", r.getLong(1))
              .put("app", wireApp)
              .put("label", wireLabel);
          if (!dom.isEmpty()) previous.put("domain", dom);
          spans.put(previous);
        }
      }
    }
    JSONArray pauses = new JSONArray();
    try (Cursor r = getReadableDatabase().rawQuery(
        "SELECT pkg,started,deadline_wall,reason,ended,end_kind,check_in,rating,rating_note"
            + " FROM pauses WHERE started<? AND (ended IS NULL OR ended>?) ORDER BY started",
        new String[] {"" + now, "" + from})) {
      while (r.moveToNext()) {
        String pkg = r.getString(0);
        pauses.put(new JSONObject()
            .put("package", pkg)
            .put("kind", DnsController.KEY.equals(pkg) ? "dns" : "app")
            .put("t", r.getLong(1))
            .put("end", r.isNull(4) ? now : r.getLong(4))
            .put("minutes", Math.max(1, Math.round((r.getLong(2) - r.getLong(1)) / 60_000.0)))
            .put("reason", r.getString(3))
            .put("check_in", r.getString(6))
            .put("rating", r.isNull(7) ? JSONObject.NULL : r.getInt(7))
            .put("rating_note", r.getString(8))
            .put("end_kind", r.isNull(5) ? JSONObject.NULL : r.getString(5)));
      }
    }
    return root.put("spans", spans).put("pauses", pauses);
  }
}

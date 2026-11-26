package org.nightingalelab.focus.phone;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

import static org.nightingalelab.focus.phone.FocusEventCodes.*;

/**
 * Native Android Private DNS controller for the Focus phone app.
 * Uses Settings.Global (WRITE_SECURE_SETTINGS) with instant tamper snap-back (Option 1)
 * and exact-alarm timed pauses, without occupying the VPN slot so Tailscale keeps working.
 */
public final class DnsController {
  public static final String KEY = "focus:dns";
  public static final String MODE = "private_dns_mode";
  public static final String HOST = "private_dns_specifier";
  private static DnsController instance;
  private final Context context;
  private final SharedPreferences prefs;
  private volatile long verifiedAt;
  private volatile boolean checking;
  private volatile long lastCheck;
  private volatile Network verifiedNetwork;
  private volatile LinkProperties verifiedLink;

  public static synchronized DnsController get(Context c) {
    if (instance == null) instance = new DnsController(c.getApplicationContext());
    return instance;
  }

  private DnsController(Context c) {
    context = c;
    prefs = c.getSharedPreferences("nextdns_phone", Context.MODE_PRIVATE);
  }

  public boolean permitted() {
    return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        == PackageManager.PERMISSION_GRANTED;
  }

  public boolean enabled() {
    return prefs.getBoolean("enabled", false);
  }

  private static final String NEXTDNS_SUFFIX = ".dns.nextdns.io";

  public synchronized String profile() {
    String stored = prefs.getString("profile", "");
    if (stored.matches("[a-fA-F0-9]{6}")) return stored.toLowerCase(Locale.ROOT);
    if (!stored.isEmpty() || !enabled()) return "";

    // Older builds could enable Focus without storing the profile preference.
    // A Focus pause switches only the mode, leaving the original hostname in place.
    String mode = read(MODE);
    if (!"hostname".equals(mode)
        && !("off".equals(mode) && (prefs.getBoolean("restore_pending", false) || deadline() > 0)))
      return "";
    String host = read(HOST);
    if (host == null) return "";
    host = host.toLowerCase(Locale.ROOT);
    if (!host.matches("[a-f0-9]{6}\\.dns\\.nextdns\\.io")) return "";
    String recovered = host.substring(0, 6);
    return prefs.edit().putString("profile", recovered).commit() ? recovered : "";
  }

  public String hostname() {
    String id = profile();
    return id.isEmpty() ? "" : id + NEXTDNS_SUFFIX;
  }

  private String requireHostname() {
    String host = hostname();
    if (host.isEmpty()) throw new IllegalStateException("Enter the original NextDNS profile to restore protection");
    return host;
  }

  private String read(String key) {
    return Settings.Global.getString(context.getContentResolver(), key);
  }

  private void write(String key, String value) {
    if (Objects.equals(read(key), value)) return;
    if (!Settings.Global.putString(context.getContentResolver(), key, value))
      throw new IllegalStateException("DNS setting refused");
  }

  private void save(SharedPreferences.Editor e) {
    if (!e.commit()) throw new IllegalStateException("Could not save DNS state");
  }

  private void requirePermission() {
    if (!permitted()) throw new IllegalStateException("Grant WRITE_SECURE_SETTINGS via ADB first");
  }

  private Network network() {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    Network n = cm == null ? null : cm.getActiveNetwork();
    if (n == null) throw new IllegalStateException("No active network");
    return n;
  }

  public boolean configured() {
    String host = hostname();
    return !host.isEmpty() && "hostname".equals(read(MODE)) && host.equals(read(HOST));
  }

  public boolean active() {
    try {
      ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
      Network current = network();
      return privateDnsActive(configured(), matchesPrivateDns(cm.getLinkProperties(current), hostname()),
          isVpn(cm, current), () -> {
            Network underlying = underlyingDefault(cm);
            return underlying != null && current.equals(cm.getActiveNetwork())
                && matchesPrivateDns(cm.getLinkProperties(underlying), hostname());
          });
    } catch (RuntimeException e) {
      return false;
    }
  }

  static boolean privateDnsActive(boolean configured, boolean directlyActive,
      boolean vpnDefault, BooleanSupplier underlyingActive) {
    return configured && (directlyActive || vpnDefault && underlyingActive.getAsBoolean());
  }

  // Peek requires protection on the very network it uses for the request.
  Network activePrivateDnsNetwork() {
    try {
      Network current = network();
      ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
      return configured() && matchesPrivateDns(cm.getLinkProperties(current), hostname()) ? current : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static boolean matchesPrivateDns(LinkProperties link, String hostname) {
    return link != null && link.isPrivateDnsActive() && hostname.equals(link.getPrivateDnsServerName());
  }

  boolean vpnDefault() {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    return cm != null && isVpn(cm, cm.getActiveNetwork());
  }

  private static boolean isVpn(ConnectivityManager cm, Network network) {
    NetworkCapabilities capabilities = network == null ? null : cm.getNetworkCapabilities(network);
    return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
  }

  @SuppressWarnings("deprecation")
  private static Network underlyingDefault(ConnectivityManager cm) {
    // AOSP getActiveNetworkInfo reports the VPN's primary underlying network (or
    // the system default if the VPN declares none). The Network-valued API returns
    // the VPN itself. Match the reported type and require an unambiguous candidate.
    NetworkInfo info = cm.getActiveNetworkInfo();
    if (info == null || !info.isConnected() || info.getType() == ConnectivityManager.TYPE_VPN) return null;
    List<Network> matches = new ArrayList<>();
    for (Network candidate : cm.getAllNetworks()) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
      NetworkInfo direct = cm.getNetworkInfo(candidate);
      if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          && direct != null && direct.isConnected() && info.getType() == direct.getType()) {
        matches.add(candidate);
      }
    }
    return uniqueUnderlying(matches);
  }

  static Network uniqueUnderlying(List<Network> candidates) {
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  boolean verifiedOn(Network network) {
    if (network == null || verifiedAt == 0 || SystemClock.elapsedRealtime() - verifiedAt >= 300_000
        || !network.equals(verifiedNetwork)) return false;
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    return cm != null && Objects.equals(verifiedLink, cm.getLinkProperties(network));
  }

  public boolean verify() {
    long started = SystemClock.elapsedRealtime();
    String correlation = FocusEvents.newId();
    String cause = NEXTDNS_UNREACHABLE;
    verifiedAt = 0;
    HttpURLConnection connection = null;
    try {
      if (deadline() > 0) {
        cause = DNS_PAUSED;
        return false;
      }
      String expectedHostname = hostname();
      Network n = activePrivateDnsNetwork();
      if (n == null) {
        cause = PRIVATE_DNS_INACTIVE;
        return false;
      }
      URL url = new URL("https://" + UUID.randomUUID().toString().replace("-", "") + ".test.nextdns.io/");
      connection = (HttpURLConnection) n.openConnection(url);
      connection.setConnectTimeout(4000);
      connection.setReadTimeout(4000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      if (connection.getResponseCode() != 200) return false;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[512];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (bytes.size() + count > 8192) return false;
          bytes.write(buffer, 0, count);
        }
      }
      JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
      LinkProperties link = cm.getLinkProperties(n);
      boolean ok = "ok".equals(result.optString("status"))
          && "DOT".equalsIgnoreCase(result.optString("protocol"))
          && !result.optString("profile").isEmpty()
          && n.equals(cm.getActiveNetwork())
          && configured()
          && expectedHostname.equals(hostname())
          && matchesPrivateDns(link, expectedHostname)
          && deadline() == 0;
      if (ok) {
        verifiedNetwork = n;
        verifiedLink = link;
        verifiedAt = SystemClock.elapsedRealtime();
      }
      Store.get(context).diagnostic(ok ? "nextdns_verified" : "nextdns_verification_failed");
      cause = ok ? OK : NEXTDNS_REJECTED;
      return ok;
    } catch (Exception e) {
      Store.get(context).diagnostic("nextdns_check_" + e.getClass().getSimpleName());
      return false;
    } finally {
      if (connection != null) connection.disconnect();
      record("dns", "verify", cause, correlation, "local", started, true);
    }
  }

  public synchronized void checkAsync(Runnable done) {
    if (checking) return;
    checking = true;
    lastCheck = SystemClock.elapsedRealtime();
    new Thread(() -> {
      try {
        verify();
      } finally {
        checking = false;
        if (done != null) new Handler(Looper.getMainLooper()).post(done);
      }
    }, "focus-phone-dns-check").start();
  }

  public synchronized void setProfile(String id) {
    if (id == null || !id.matches("[a-fA-F0-9]{6}"))
      throw new IllegalArgumentException("Enter a six-character NextDNS profile ID");
    String clean = id.toLowerCase(Locale.ROOT);
    save(prefs.edit().putString("profile", clean));
    if (enabled() && permitted() && deadline() == 0 && !prefs.getBoolean("restore_pending", false)) {
      write(HOST, requireHostname());
      write(MODE, "hostname");
      verifiedAt = 0;
      checkAsync(null);
    }
  }

  public synchronized void connect(String id) {
    requirePermission();
    if (id == null || !id.matches("[a-fA-F0-9]{6}"))
      throw new IllegalArgumentException("Enter a six-character profile ID");
    if (deadline() > 0 || prefs.getBoolean("restore_pending", false)) resume();
    SharedPreferences.Editor e = prefs.edit();
    if (!enabled()) e.putString("before_mode", read(MODE)).putString("before_host", read(HOST));
    save(e.putString("profile", id.toLowerCase(Locale.ROOT)).putBoolean("enabled", true));
    try {
      write(HOST, requireHostname());
      write(MODE, "hostname");
      verifiedAt = 0;
      MonitorService.ensure(context);
      Store.get(context).diagnostic("nextdns_connected");
    } catch (RuntimeException failure) {
      Store.get(context).diagnostic("nextdns_connect_failed");
      throw failure;
    }
  }

  public long deadline() {
    return Store.get(context).pauseDeadline(KEY);
  }

  public long remainingMs() {
    long dl = deadline();
    return dl == 0 ? 0 : Math.max(0, dl - SystemClock.elapsedRealtime());
  }

  private PendingIntent alarmIntent() {
    return PendingIntent.getBroadcast(
        context,
        732,
        new Intent(context, DnsExpiryReceiver.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public boolean isEmergencyShieldActive() {
    return deadline() > 0 && prefs.getBoolean("emergency_shield", false);
  }

  public boolean isFreeTimeActive() {
    return deadline() > 0 && prefs.getBoolean("free_time", false);
  }

  public long lastPauseEndedElapsed() {
    return prefs.getLong("last_pause_ended_elapsed", 0);
  }

  public synchronized void startFreeTime(int minutes, String requestId) {
    if (minutes < 1 || minutes > 30) throw new IllegalArgumentException("Invalid free time duration");
    long started = SystemClock.elapsedRealtime();
    String cause = DNS_APPLY_FAILED;
    try {
      pauseInternal(minutes * 60_000L, "Store free time (" + minutes + "m)", "", null,
          requestId == null || requestId.isEmpty() ? FocusEvents.newId() : requestId, "store");
      save(prefs.edit().putBoolean("free_time", true).putBoolean("emergency_shield", false));
      cause = OK;
    } finally {
      record("pause", "pause", cause, FocusEvents.newId(), "store", started, false);
    }
  }

  public synchronized void startEmergencyShield(int minutes, String reason) {
    if (minutes < 1 || minutes > EmergencyShieldStore.SHIELD_MINUTES) {
      throw new IllegalArgumentException("Invalid emergency shield duration");
    }
    long started = SystemClock.elapsedRealtime();
    String cause = DNS_APPLY_FAILED;
    try {
      pauseInternal(minutes * 60_000L, "Emergency shield: " + reason, "", null,
          FocusEvents.newId(), "emergency_shield");
      save(prefs.edit().putBoolean("free_time", false).putBoolean("emergency_shield", true));
      cause = OK;
    } finally {
      record("pause", "pause", cause, FocusEvents.newId(), "emergency_shield", started, false);
    }
  }

  public synchronized void pause(long duration, String reason, String afterPlan) {
    pause(duration, reason, afterPlan, null);
  }

  boolean canPause() {
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    return enabled() && !profile().isEmpty() && permitted() && deadline() == 0 && !prefs.getBoolean("restore_pending", false)
        && (Build.VERSION.SDK_INT < 31 || alarms == null || alarms.canScheduleExactAlarms());
  }

  public synchronized void pause(long duration, String reason, String afterPlan, PausePolicy.CheckIn checkIn) {
    pause(duration, reason, afterPlan, checkIn, FocusEvents.newId(), "local");
  }

  synchronized void pause(long duration, String reason, String afterPlan, PausePolicy.CheckIn checkIn,
      String correlation, String route) {
    long started = SystemClock.elapsedRealtime();
    String cause = DNS_APPLY_FAILED;
    try {
      pauseInternal(duration, reason, afterPlan, checkIn, correlation, route);
      cause = OK;
    } finally {
      record("pause", "pause", cause, correlation, route, started, false);
    }
  }

  private void pauseInternal(long duration, String reason, String afterPlan, PausePolicy.CheckIn checkIn,
      String correlation, String route) {
    requirePermission();
    if (!enabled()) throw new IllegalStateException("Connect NextDNS on this phone first");
    requireHostname();
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    if (Build.VERSION.SDK_INT >= 31 && alarms != null && !alarms.canScheduleExactAlarms())
      throw new IllegalStateException("Allow exact alarms in Android Settings first");
    if (prefs.getBoolean("restore_pending", false))
      throw new IllegalStateException("Resume DNS first");
    long until = Store.get(context).pause(KEY, duration, reason, afterPlan, checkIn);
    try {
      save(prefs.edit().putBoolean("restore_pending", true)
          .putBoolean("free_time", false).putBoolean("emergency_shield", false)
          .putString("pause_correlation", correlation).putString("pause_route", route));
      if (alarms != null) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, until, alarmIntent());
        Metric.DNS_ALARM_SCHEDULED.count(1);
      }
      write(MODE, "off");
      FocusNotifications.updateDnsPause(context, until, false);
      verifiedAt = 0;
      MonitorService.ensure(context);
      Store.get(context).diagnostic("nextdns_paused");
      WidgetUpdater.updateAll(context);
    } catch (RuntimeException e) {
      resume(false);
      throw e;
    }
  }

  public synchronized void resume() {
    resume(true);
  }

  private void resume(boolean askForRating) {
    long started = SystemClock.elapsedRealtime();
    String correlation = prefs.getString("pause_correlation", null);
    if (!FocusEvents.validId(correlation)) correlation = FocusEvents.newId();
    String route = prefs.getString("pause_route", "local");
    String cause = DNS_APPLY_FAILED;
    try {
      resumeInternal(askForRating);
      cause = OK;
    } finally {
      record("pause", "resume", cause, correlation, route, started, false);
    }
  }

  private void resumeInternal(boolean askForRating) {
    requirePermission();
    boolean wasFreeTime = prefs.getBoolean("free_time", false);
    write(HOST, requireHostname());
    write(MODE, "hostname");
    FocusNotifications.updateDnsPause(context, 0, false);
    save(prefs.edit().putBoolean("restore_pending", false)
        .putBoolean("free_time", false).putBoolean("emergency_shield", false)
        .putLong("last_pause_ended_elapsed", SystemClock.elapsedRealtime())
        .remove("pause_correlation").remove("pause_route"));
    InsightsEngine.PauseRecord ended = Store.get(context).endPause(KEY, askForRating ? "dns_resumed" : "dns_pause_failed");
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    if (alarms != null) alarms.cancel(alarmIntent());
    verifiedAt = 0;
    Store.get(context).diagnostic("nextdns_restored");
    checkAsync(null);
    WidgetUpdater.updateAll(context);
    FocusAccessibilityService.onProtectionRestored(context);
    if (ended != null && askForRating && !wasFreeTime) FocusNotifications.sendPauseRating(context, ended);
  }

  private void record(String event, String stage, String cause, String correlation, String route,
      long started, boolean dnsFlags) {
    try {
      JSONObject fields = new JSONObject().put("correlation_id", correlation).put("origin", "phone").put("route", route);
      if (dnsFlags) fields.put("dns_enabled", enabled()).put("dns_paused", deadline() > 0)
          .put("vpn_default", vpnDefault()).put("strict_mode", configured()).put("private_dns_active", active());
      FocusEvents.record(context, event, stage, OK.equals(cause) ? "ok" : "failed", cause,
          SystemClock.elapsedRealtime() - started, fields);
    } catch (JSONException | RuntimeException ignored) {}
  }

  synchronized void resumePause(long expectedDeadline) {
    if (expectedDeadline > 0 && deadline() == expectedDeadline) resume();
  }

  synchronized void refreshPauseNotification(boolean onlyIfShowing) {
    FocusNotifications.updateDnsPause(
        context, enabled() && "off".equals(read(MODE)) ? deadline() : 0, onlyIfShowing);
  }

  /**
   * Option 1 instant snap-back: if Private DNS was changed in Android Settings while
   * Focus protection is active and not in a timed pause, revert it immediately.
   */
  public synchronized boolean snapBackIfTampered() {
    if (!enabled() || !permitted() || profile().isEmpty()) return false;
    if (deadline() > 0 || prefs.getBoolean("restore_pending", false)) return false;
    if (configured()) return false;
    try {
      write(HOST, requireHostname());
      write(MODE, "hostname");
      verifiedAt = 0;
      Store.get(context).diagnostic("dns_tamper_reverted");
      return true;
    } catch (RuntimeException e) {
      Store.get(context).diagnostic("dns_tamper_revert_failed_" + e.getClass().getSimpleName());
      return false;
    }
  }

  public synchronized void tick() {
    if (enabled() && prefs.getBoolean("restore_pending", false) && deadline() == 0) {
      try {
        resume();
      } catch (RuntimeException e) {
        Store.get(context).diagnostic("nextdns_restore_failed_" + e.getClass().getSimpleName());
      }
    } else {
      snapBackIfTampered();
    }
    if (enabled() && deadline() == 0 && SystemClock.elapsedRealtime() - lastCheck > 60_000) {
      checkAsync(null);
    }
  }

  public synchronized void disconnect() {
    requirePermission();
    if (!enabled()) return;
    write(HOST, prefs.getString("before_host", null));
    write(MODE, prefs.getString("before_mode", null));
    FocusNotifications.updateDnsPause(context, 0, false);
    Store.get(context).endPause(KEY, "dns_disconnected");
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    if (alarms != null) alarms.cancel(alarmIntent());
    save(prefs.edit().clear());
    verifiedAt = 0;
    Store.get(context).diagnostic("nextdns_disconnected");
    WidgetUpdater.updateAll(context);
  }

  public String status() {
    if (profile().isEmpty()) return "NextDNS profile needed";
    if (!enabled()) return configured() ? "Configured outside Focus" : "Not connected";
    if (!permitted()) return "ADB setup needed";
    if (deadline() > 0) return "Paused · " + InsightsEngine.formatCountdown(remainingMs());
    if (prefs.getBoolean("restore_pending", false)) return "Restoring";
    if (!configured()) return "Snapping back";
    if (!active()) return "Active (waiting on link)";
    if (checking) return "Checking";
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    Network current = cm == null ? null : cm.getActiveNetwork();
    if (current == null) return "Offline";
    return verifiedOn(current) ? "Filtering on" : "On";
  }
}

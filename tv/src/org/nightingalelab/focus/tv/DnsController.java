package org.nightingalelab.focus.tv;

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
import org.json.*;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/** Native Private DNS only: never opens a VPN or changes another network setting. */
public final class DnsController {
  public static final String KEY = "focus:dns";
  private static final String MODE = "private_dns_mode", HOST = "private_dns_specifier";
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
    prefs = c.getSharedPreferences("nextdns", Context.MODE_PRIVATE);
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
    // Even a same-value Settings write can restart TCL's native DNS validation.
    if (Objects.equals(read(key), value)) return;
    if (!Settings.Global.putString(context.getContentResolver(), key, value))
      throw new IllegalStateException("DNS setting refused");
  }

  private void save(SharedPreferences.Editor e) {
    if (!e.commit()) throw new IllegalStateException("Could not save DNS state");
  }

  private void requirePermission() {
    if (!permitted()) throw new IllegalStateException("Computer setup required");
  }

  private Network network() {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    Network n = cm.getActiveNetwork();
    NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
    if (caps == null) throw new IllegalStateException("No network");
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
      throw new IllegalStateException("VPN DNS must be configured separately");
    return n;
  }

  public boolean configured() {
    String host = hostname();
    return !host.isEmpty() && "hostname".equals(read(MODE)) && host.equals(read(HOST));
  }

  public boolean active() {
    try {
      Network n = network();
      LinkProperties lp = context.getSystemService(ConnectivityManager.class).getLinkProperties(n);
      return configured()
          && lp != null
          && lp.isPrivateDnsActive()
          && hostname().equals(lp.getPrivateDnsServerName());
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Called off the UI thread. Uses a fresh name; never persists IPs or browsing history. */
  public boolean verify() {
    verifiedAt = 0;
    if (!active() || deadline() > 0) return false;
    HttpURLConnection connection = null;
    try {
      Network n = network();
      URL url =
          new URL("https://" + UUID.randomUUID().toString().replace("-", "") + ".test.nextdns.io/");
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
      boolean ok =
          "ok".equals(result.optString("status"))
              && "DOT".equalsIgnoreCase(result.optString("protocol"))
              && !result.optString("profile").isEmpty()
              && active()
              && deadline() == 0;
      if (ok && n.equals(context.getSystemService(ConnectivityManager.class).getActiveNetwork())) {
        verifiedNetwork = n;
        verifiedLink = context.getSystemService(ConnectivityManager.class).getLinkProperties(n);
        verifiedAt = SystemClock.elapsedRealtime();
      } else ok = false;
      Store.get(context).diagnostic(ok ? "nextdns_verified" : "nextdns_verification_failed");
      return ok;
    } catch (Exception e) {
      Store.get(context).diagnostic("nextdns_check_" + e.getClass().getSimpleName());
      return false;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public synchronized void checkAsync(Runnable done) {
    if (checking) return;
    checking = true;
    lastCheck = SystemClock.elapsedRealtime();
    new Thread(
            () -> {
              try {
                verify();
              } finally {
                checking = false;
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
              }
            },
            "focus-dns-check")
        .start();
  }

  /** Save exact pre-Focus values once. Reconnecting never overwrites that snapshot. */
  public synchronized void connect(String id) {
    requirePermission();
    if (id == null || !id.matches("[a-fA-F0-9]{6}"))
      throw new IllegalArgumentException("Enter a six-character profile ID");
    boolean paused = deadline() > 0 || prefs.getBoolean("restore_pending", false);
    if (paused && enabled() && profile().isEmpty()) {
      save(prefs.edit().putString("profile", id.toLowerCase(Locale.ROOT)));
      if (deadline() == 0) resume();
      return;
    }
    network();
    if (paused) resume();
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
      // The snapshot survives a partial failure so Restore previous DNS remains available.
      Store.get(context).diagnostic("nextdns_connect_failed");
      throw failure;
    }
  }

  public long deadline() {
    return Store.get(context).pauseDeadline(KEY);
  }

  private PendingIntent alarmIntent() {
    return PendingIntent.getBroadcast(
        context,
        731,
        new Intent(context, DnsExpiryReceiver.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public synchronized void pause(long duration, String reason) {
    pause(duration, reason, null);
  }

  static long remainingPauseMs(Context context, long now) {
    long used = Store.get(context).dnsPauseUsed(Store.dayStart(now), now);
    return HabitStore.get(context).remainingDnsMs(Habits.day(now), now, used);
  }

  int[] pauseChoices(long now) {
    if (!HabitStore.get(context).usesDnsAllowance()) return new int[] {30, 60, 120, 300};
    long left = remainingPauseMs(context, now);
    return Arrays.stream(new int[] {1, 5, 10, 30, 60, 120, 300})
        .filter(minutes -> minutes == 1 || minutes * 60_000L <= left).toArray();
  }

  synchronized void pause(long duration, String reason, Rules.CheckIn checkIn) {
    if (duration != 60_000L && HabitStore.get(context).usesDnsAllowance()
        && duration > remainingPauseMs(context, System.currentTimeMillis())) {
      throw new IllegalArgumentException("The remaining allowance changed. Choose a shorter pause.");
    }
    requirePermission();
    if (!enabled() || !active()) throw new IllegalStateException("NextDNS is not connected");
    network();
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms())
      throw new IllegalStateException("Allow alarms in Android Settings first");
    if (prefs.getBoolean("restore_pending", false))
      throw new IllegalStateException("Resume DNS first");
    long until = Store.get(context).pause(KEY, duration, reason, checkIn);
    try {
      // Arm durable recovery before unfiltering. ELAPSED_REALTIME avoids wall-clock changes.
      save(prefs.edit().putBoolean("restore_pending", true));
      alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, until, alarmIntent());
      Metric.DNS_ALARM_SCHEDULED.count(1);
      write(MODE, "off");
      verifiedAt = 0;
      MonitorService.ensure(context);
      Store.get(context).diagnostic("nextdns_paused");
    } catch (RuntimeException e) {
      resume();
      throw e;
    }
  }

  public synchronized void resume() {
    requirePermission();
    write(HOST, requireHostname());
    write(MODE, "hostname");
    save(prefs.edit().putBoolean("restore_pending", false));
    Store.get(context).endPause(KEY, "dns_resumed");
    context.getSystemService(AlarmManager.class).cancel(alarmIntent());
    verifiedAt = 0;
    Store.get(context).diagnostic("nextdns_restored");
    checkAsync(null);
  }

  public synchronized void tick() {
    if (enabled() && prefs.getBoolean("restore_pending", false) && deadline() == 0) {
      try {
        resume();
      } catch (RuntimeException e) {
        // Keep pending set and retry from the monitor; never report protected on write failure.
        Store.get(context).diagnostic("nextdns_restore_failed_" + e.getClass().getSimpleName());
      }
    }
    if (enabled() && deadline() == 0 && SystemClock.elapsedRealtime() - lastCheck > 60000)
      checkAsync(null);
  }

  public synchronized void disconnect() {
    requirePermission();
    if (!enabled()) return;
    write(HOST, prefs.getString("before_host", null));
    write(MODE, prefs.getString("before_mode", null));
    Store.get(context).endPause(KEY, "dns_disconnected");
    context.getSystemService(AlarmManager.class).cancel(alarmIntent());
    save(prefs.edit().clear());
    verifiedAt = 0;
    Store.get(context).diagnostic("nextdns_disconnected");
  }

  public String status() {
    if (profile().isEmpty()) return "NextDNS profile needed";
    if (!enabled()) return configured() ? "Configured" : "Not connected";
    if (!permitted()) return "Settings access missing";
    if (deadline() > 0) return "Paused · " + Ui.countdown(deadline());
    if (prefs.getBoolean("restore_pending", false)) return "Restoring";
    if (!configured()) return "Changed outside Focus";
    if (!active()) return "Unavailable";
    if (checking) return "Checking";
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    Network current = cm.getActiveNetwork();
    if (current == null) return "Unavailable";
    return verifiedAt > 0
            && SystemClock.elapsedRealtime() - verifiedAt < 300_000
            && Objects.equals(verifiedNetwork, current)
            && Objects.equals(verifiedLink, cm.getLinkProperties(current))
        ? "On"
        : "Not verified";
  }
}

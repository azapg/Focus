package org.nightingalelab.focus.phone;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.nightingalelab.focus.telemetry.LocalTelemetry;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;
import org.nightingalelab.focus.habits.HabitStore;

/**
 * Persistent foreground monitor for Focus on Android:
 * - Instant ContentObserver snap-back on Settings.Global private_dns_mode & private_dns_specifier
 * - UsageStats collection (merged with Accessibility browser domain tracking)
 * - Periodic LAN sync with paired TV/Laptop and local TLS listener for Windows
 * - Widget updates & evening summary alarm scheduling
 * - The current intention in the always-on notification, drift nudges and time's up
 */
public final class MonitorService extends Service {
  private HandlerThread thread;
  private Handler worker;
  private LocalTelemetry telemetry;
  private HabitNudges habitNudges;
  private ContentObserver dnsObserver;
  private long lastWidgetUpdate;
  private long lastPauseProgressUpdate;
  private long lastScopedProgressUpdate;
  private final Runnable habitsChanged = () -> {
    Handler handler = worker;
    if (handler != null) handler.post(habitNudges::habitsChanged);
  };

  public static void ensure(Context c) {
    try {
      c.startForegroundService(new Intent(c, MonitorService.class));
    } catch (RuntimeException e) {
      Store.get(c).diagnostic("monitor_start_failed_" + e.getClass().getSimpleName());
    }
  }

  private final Runnable sample = new Runnable() {
    @Override
    public void run() {
      Metric.MONITOR_TICK.count(1);
      try {
        LanController.get(MonitorService.this).ensure();
        DnsController dns = DnsController.get(MonitorService.this);
        dns.tick();
        long now = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT >= 36 && now - lastPauseProgressUpdate >= 15_000
            && dns.deadline() > 0) {
          lastPauseProgressUpdate = now;
          dns.refreshPauseNotification(true);
        }
        Store.get(MonitorService.this).applyDuePolicyChanges();
        Store.get(MonitorService.this).collect();
        habitNudges.tick();
        DeviceHub devices = DeviceHub.get(MonitorService.this);
        if (now - lastScopedProgressUpdate >= 15_000) {
          lastScopedProgressUpdate = now;
          devices.refreshScopedNotification(true);
        }
        devices.syncIfDue();
        IntentionMonitor.tick(MonitorService.this);
        PhoneUpdater.get(MonitorService.this).checkIfDue();
        if (now - lastWidgetUpdate >= 60_000) {
          lastWidgetUpdate = now;
          WidgetUpdater.updateAll(MonitorService.this);
        }
      } catch (RuntimeException e) {
        Store.get(MonitorService.this).diagnostic("collect_failed_" + e.getClass().getSimpleName());
      }
      telemetry.sampleIfDue();
      if (worker != null) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        worker.postDelayed(this, pm != null && pm.isInteractive() ? 2000 : 15000);
      }
    }
  };

  private final BroadcastReceiver screen = new BroadcastReceiver() {
    @Override
    public void onReceive(Context c, Intent i) {
      Metric.SCREEN_EVENT.count(1);
      if (Intent.ACTION_TIME_CHANGED.equals(i.getAction())) Store.get(c).clearTimeline();
      if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) Store.get(c).clearBrowserDomain();
      if (worker != null) {
        if (Intent.ACTION_TIME_CHANGED.equals(i.getAction())
            || Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) {
          worker.post(() -> DnsController.get(c).refreshPauseNotification(true));
        }
        worker.removeCallbacks(sample);
        worker.post(sample);
      }
    }
  };

  private void promote() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (nm != null) {
      nm.createNotificationChannel(
          new NotificationChannel("focus_monitor", "Focus protection", NotificationManager.IMPORTANCE_LOW));
    }
    // Shows the current intention when one is running (see IntentionMonitor).
    Notification n = IntentionMonitor.monitorNotification(this);
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
      startForeground(1, n);
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Store.get(this).loadStreakCoverageOptIn();
    promote();
    telemetry = new LocalTelemetry(this, "android-phone");
    habitNudges = new HabitNudges(this);
    thread = new HandlerThread("focus-phone-monitor");
    thread.start();
    worker = new Handler(thread.getLooper());
    HabitStore.get(this).addListener(habitsChanged);

    // Instant Option 1 snap-back observer on Private DNS global settings
    dnsObserver = new ContentObserver(worker) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        Metric.DNS_OBSERVER.count(1);
        DnsController dns = DnsController.get(MonitorService.this);
        dns.snapBackIfTampered();
        if (Settings.Global.getUriFor(DnsController.MODE).equals(uri)) {
          dns.refreshPauseNotification(true);
        }
      }
    };
    try {
      getContentResolver().registerContentObserver(
          Settings.Global.getUriFor(DnsController.MODE), false, dnsObserver);
      getContentResolver().registerContentObserver(
          Settings.Global.getUriFor(DnsController.HOST), false, dnsObserver);
    } catch (RuntimeException ignored) {}

    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_SCREEN_ON);
    f.addAction(Intent.ACTION_SCREEN_OFF);
    f.addAction(Intent.ACTION_TIME_CHANGED);
    f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    f.addAction(Intent.ACTION_USER_UNLOCKED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screen, f);

    FocusNotifications.scheduleEveningSummary(this);
    worker.post(sample);
    Store.get(this).diagnostic("monitor_started");
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    promote();
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    HabitStore.get(this).removeListener(habitsChanged);
    HabitNudges.setLocked(this, false);
    FocusNotifications.clearHabitNudge(this);
    if (dnsObserver != null) {
      try { getContentResolver().unregisterContentObserver(dnsObserver); } catch (RuntimeException ignored) {}
    }
    LanController.get(this).stopServer();
    try { unregisterReceiver(screen); } catch (RuntimeException ignored) {}
    if (worker != null) worker.removeCallbacksAndMessages(null);
    worker = null;
    if (thread != null) thread.quitSafely();
    Store.heartbeat = 0;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}

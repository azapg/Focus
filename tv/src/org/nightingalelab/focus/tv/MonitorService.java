package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

public final class MonitorService extends Service {
  private HandlerThread thread;
  private Handler worker;
  private LocalTelemetry telemetry;
  private static long lastRecoveryAttempt = -30_000;

  static void recoverIfStale(Context c) {
    long now = SystemClock.elapsedRealtime();
    if (Store.heartbeat != 0 && now - Store.heartbeat <= 15_000) return;
    if (now - lastRecoveryAttempt < 30_000 || !Store.get(c).usageAllowed()) return;
    lastRecoveryAttempt = now;
    ensure(c);
  }

  public static void ensure(Context c) {
    try {
      c.startForegroundService(new Intent(c, MonitorService.class));
    } catch (RuntimeException e) {
      Store.get(c).diagnostic("monitor_start_failed_" + e.getClass().getSimpleName());
    }
  }

  private final Runnable sample =
      new Runnable() {
        public void run() {
          Metric.MONITOR_TICK.count(1);
          try {
            Store.get(MonitorService.this).collect();
          } catch (RuntimeException e) {
            Store.get(MonitorService.this)
                .diagnostic("collect_failed_" + e.getClass().getSimpleName());
          }
          try {
            LanController.get(MonitorService.this).ensure();
            DnsController.get(MonitorService.this).tick();
          } catch (RuntimeException e) {
            Store.get(MonitorService.this)
                .diagnostic("monitor_maintenance_failed_" + e.getClass().getSimpleName());
          }
          telemetry.sampleIfDue();
          if (worker != null)
            worker.postDelayed(
                this,
                ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive() ? 2000 : 15000);
        }
      };
  private final BroadcastReceiver screen =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          Metric.SCREEN_EVENT.count(1);
          if (Intent.ACTION_TIME_CHANGED.equals(i.getAction())) Store.get(c).clearTimeline();
          if (worker != null) {
            worker.removeCallbacks(sample);
            worker.post(sample);
          }
        }
      };

  private void promote() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(
        new NotificationChannel("tracking", "TV activity", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n =
        new Notification.Builder(this, "tracking")
            .setSmallIcon(org.nightingalelab.focus.tv.R.drawable.ic_stat_focus)
            .setContentTitle("Focus")
            .setContentText("Recording TV app time")
            .setOngoing(true)
            .setContentIntent(open)
            .build();
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(1, n);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    promote();
    telemetry = new LocalTelemetry(this, "android-tv");
    thread = new HandlerThread("focus-usage");
    thread.start();
    worker = new Handler(thread.getLooper());
    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_SCREEN_ON);
    f.addAction(Intent.ACTION_SCREEN_OFF);
    f.addAction(Intent.ACTION_TIME_CHANGED);
    f.addAction(Intent.ACTION_USER_UNLOCKED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screen, f);
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
    LanController.get(this).stopServer();
    unregisterReceiver(screen);
    worker.removeCallbacksAndMessages(null);
    worker = null;
    thread.quitSafely();
    Store.heartbeat = 0;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}

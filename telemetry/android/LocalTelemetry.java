package org.nightingalelab.focus.telemetry;

import android.content.Context;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

/** Sample on the existing monitor worker. Counters accept only numeric amounts. */
public final class LocalTelemetry {
  public enum Metric {
    MONITOR_TICK("monitor.callbacks"),
    SCREEN_EVENT("monitor.screen_events"),
    DNS_OBSERVER("dns_watchdog.observer_callbacks"),
    DNS_ALARM_SCHEDULED("dns_watchdog.wakeup_alarms_scheduled"),
    DNS_ALARM_FIRED("dns_watchdog.wakeup_alarm_deliveries"),
    SUMMARY_ALARM_SCHEDULED("summary.wakeup_alarms_scheduled"),
    SUMMARY_ALARM_FIRED("summary.wakeup_alarm_deliveries"),
    ACCESSIBILITY_EVENT("accessibility.events"),
    ACCESSIBILITY_TICK("accessibility.callbacks"),
    WIDGET_REFRESH("widgets.refreshes"),
    WIDGET_UPDATE("widgets.instances_updated"),
    DEVICE_REQUEST("device_sync.requests"),
    DEVICE_RX("device_sync.rx_bytes"),
    DEVICE_TX("device_sync.tx_bytes"),
    DEVICE_INBOUND("device_listener.requests"),
    DEVICE_INBOUND_RX("device_listener.rx_bytes"),
    DEVICE_INBOUND_TX("device_listener.tx_bytes"),
    UPDATER_REQUEST("updater.requests"),
    UPDATER_RX("updater.rx_bytes");

    private final String name;
    private final AtomicLong total = new AtomicLong();
    Metric(String name) { this.name = name; }
    public void count(long amount) { total.addAndGet(amount); }
  }

  private static final Metric[] METRICS = Metric.values();
  private static final long INTERVAL_MS = 300_000L;
  private static final long DAY_MS = 86_400_000L;
  private static final long MAX_DAY_BYTES = 2 * 1024 * 1024L;
  private final Context context;
  private final String component;
  private final int uid = Process.myUid();
  private long start, cpu = -1, rx = -1, tx = -1;
  private long prunedDay = -1;

  public LocalTelemetry(Context context, String component) {
    this.context = context.getApplicationContext();
    this.component = component;
    this.start = SystemClock.elapsedRealtime();
  }

  public void sampleIfDue() {
    long now = SystemClock.elapsedRealtime();
    long duration = now - start;
    if (cpu < 0) {
      try {
        rx = TrafficStats.getUidRxBytes(uid);
        tx = TrafficStats.getUidTxBytes(uid);
      } catch (RuntimeException ignored) {}
      cpu = Process.getElapsedCpuTime();
    }
    if (duration < INTERVAL_MS) return;
    start = now;
    try {
      JSONObject counters = new JSONObject();
      for (Metric metric : METRICS) {
        if (applies(metric)) counters.put(metric.name, metric.total.getAndSet(0));
      }
      counters.put("monitor.foreground_ms", duration);
      long currentCpu = Process.getElapsedCpuTime();
      counters.put("process.cpu_us", (currentCpu - cpu) * 1000);
      cpu = currentCpu;
      long currentRx = TrafficStats.getUidRxBytes(uid);
      long currentTx = TrafficStats.getUidTxBytes(uid);
      if (rx >= 0 && currentRx >= rx) counters.put("process.uid_rx_bytes", currentRx - rx);
      if (tx >= 0 && currentTx >= tx) counters.put("process.uid_tx_bytes", currentTx - tx);
      rx = currentRx;
      tx = currentTx;

      JSONObject gauges = new JSONObject();
      long pss = Debug.getPss();
      if (pss > 0) gauges.put("process.pss_bytes", pss * 1024);
      BatteryManager battery = context.getSystemService(BatteryManager.class);
      if (battery != null) gauges.put("power.charging", battery.isCharging() ? 1 : 0);
      long at = System.currentTimeMillis();
      JSONObject row = new JSONObject().put("v", 1).put("component", component)
          .put("at_ms", at).put("duration_ms", duration).put("counters", counters)
          .put("gauges", gauges);
      append(at / DAY_MS, (row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException | JSONException | RuntimeException ignored) {
      // Resource sampling must never interrupt device protection or retry on each tick.
    }
  }

  private boolean applies(Metric metric) {
    if ("android-tv".equals(component)) {
      switch (metric) {
        case DNS_OBSERVER:
        case SUMMARY_ALARM_SCHEDULED:
        case SUMMARY_ALARM_FIRED:
        case WIDGET_REFRESH:
        case WIDGET_UPDATE:
        case DEVICE_REQUEST:
        case DEVICE_RX:
        case DEVICE_TX:
        case UPDATER_REQUEST:
        case UPDATER_RX:
          return false;
        default:
          return true;
      }
    }
    return metric != Metric.ACCESSIBILITY_TICK;
  }

  private void append(long day, byte[] row) throws IOException {
    File dir = new File(context.getNoBackupFilesDir(), "telemetry");
    if (!dir.isDirectory() && !dir.mkdirs()) return;
    if (prunedDay != day) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File file : files) {
          String name = file.getName();
          if (!name.matches("[0-9]+[.]jsonl")) continue;
          try {
            if (Long.parseLong(name.substring(0, name.length() - 6)) < day - 6) file.delete();
          } catch (NumberFormatException ignored) {}
        }
      }
      prunedDay = day;
    }
    File file = new File(dir, day + ".jsonl");
    if (file.length() + row.length > MAX_DAY_BYTES) return;
    try (FileOutputStream out = new FileOutputStream(file, true)) {
      out.write(row);
    }
  }
}

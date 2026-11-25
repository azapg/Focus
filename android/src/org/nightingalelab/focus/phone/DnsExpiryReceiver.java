package org.nightingalelab.focus.phone;

import android.content.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

public final class DnsExpiryReceiver extends BroadcastReceiver {
  static final String ACTION_RESUME = "org.nightingalelab.focus.phone.RESUME_DNS_PAUSE";
  static final String ACTION_END_SCOPE = "org.nightingalelab.focus.phone.END_SCOPED_ACCESS";
  static final String EXTRA_DEADLINE = "pause_deadline";
  static final String EXTRA_LAPTOP_ID = "laptop_id";
  static final String EXTRA_GRANT_ID = "grant_id";

  @Override
  public void onReceive(Context c, Intent i) {
    if (ACTION_END_SCOPE.equals(i.getAction())) {
      PendingResult pending = goAsync();
      new Thread(() -> {
        try {
          DeviceHub.get(c).endScopedAccess(i.getStringExtra(EXTRA_LAPTOP_ID), i.getStringExtra(EXTRA_GRANT_ID), 0);
        } finally {
          pending.finish();
        }
      }, "focus-end-scope").start();
      return;
    }
    DnsController dns = DnsController.get(c);
    if (ACTION_RESUME.equals(i.getAction())) {
      try {
        dns.resumePause(i.getLongExtra(EXTRA_DEADLINE, 0));
      } catch (RuntimeException e) {
        Store.get(c).diagnostic("dns_notification_resume_failed_" + e.getClass().getSimpleName());
      }
      return;
    }
    Metric.DNS_ALARM_FIRED.count(1);
    Store.get(c).diagnostic("dns_alarm_fired");
    dns.tick();
    MonitorService.ensure(c);
  }
}

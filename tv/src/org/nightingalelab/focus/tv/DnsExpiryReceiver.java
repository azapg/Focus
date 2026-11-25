package org.nightingalelab.focus.tv;

import android.content.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

public final class DnsExpiryReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    Metric.DNS_ALARM_FIRED.count(1);
    DnsController.get(context).tick();
    MonitorService.ensure(context);
  }
}

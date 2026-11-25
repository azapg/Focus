package org.nightingalelab.focus.phone;

import android.content.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

public final class EveningSummaryReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    Metric.SUMMARY_ALARM_FIRED.count(1);
    FocusNotifications.sendEveningSummary(c);
    FocusNotifications.scheduleEveningSummary(c);
  }
}

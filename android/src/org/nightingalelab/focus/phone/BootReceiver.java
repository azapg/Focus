package org.nightingalelab.focus.phone;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    Store.get(c).diagnostic("boot_or_update");
    DnsController dns = DnsController.get(c);
    dns.tick();
    dns.refreshPauseNotification(false);
    DeviceHub.get(c).refreshScopedNotification(false);
    MonitorService.ensure(c);
    FocusNotifications.scheduleEveningSummary(c);
    WidgetUpdater.updateAll(c);
  }
}

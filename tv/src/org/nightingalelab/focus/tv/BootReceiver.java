package org.nightingalelab.focus.tv;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
      Store.get(c).expirePauses();
      DnsController.get(c).tick();
      if (Store.get(c).usageAllowed() || DnsController.get(c).enabled() || LanController.get(c).enabled()) MonitorService.ensure(c);
    }
  }
}

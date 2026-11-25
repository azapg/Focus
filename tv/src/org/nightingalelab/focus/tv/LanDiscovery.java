package org.nightingalelab.focus.tv;

import android.content.Context;
import android.net.nsd.*;
import android.os.SystemClock;

/** Android owns multicast sockets and reannounces when the Wi-Fi link changes. */
public final class LanDiscovery {
  public static final String TYPE = "_focus-tv._tcp.";
  private final Context context;
  private final NsdManager nsd;
  private NsdManager.RegistrationListener listener;
  private boolean wanted;
  private volatile boolean registered;
  private long tried;
  public LanDiscovery(Context c) { context = c; nsd = c.getSystemService(NsdManager.class); }
  public boolean registered() { return registered; }
  public synchronized void start(String id, String name, int port) {
    wanted = true;
    if (listener != null || (tried != 0 && SystemClock.elapsedRealtime() - tried < 10000)) return;
    tried = SystemClock.elapsedRealtime();
    NsdServiceInfo service = new NsdServiceInfo();
    StringBuilder instance = new StringBuilder("Focus ");
    for (int at = 0; at < name.length();) {
      int code = name.codePointAt(at); String next = new String(Character.toChars(code));
      if ((instance.toString() + next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 62) break;
      instance.append(next); at += Character.charCount(code);
    }
    service.setServiceName(instance.toString());
    service.setServiceType(TYPE); service.setPort(port);
    service.setAttribute("id", id); service.setAttribute("pairing", "approval-v2");
    NsdManager.RegistrationListener active = new NsdManager.RegistrationListener() {
      public void onServiceRegistered(NsdServiceInfo info) {
        synchronized (LanDiscovery.this) {
          if (!wanted || listener != this) { try { nsd.unregisterService(this); } catch (RuntimeException ignored) {} return; }
          registered = true;
        }
        Store.get(context).diagnostic("lan_discovery_registered");
      }
      public void onRegistrationFailed(NsdServiceInfo info, int code) {
        synchronized (LanDiscovery.this) { if (listener == this) { listener = null; registered = false; } }
        Store.get(context).diagnostic("lan_discovery_failed_" + code);
      }
      public void onServiceUnregistered(NsdServiceInfo info) { synchronized (LanDiscovery.this) { if (listener == this) { listener = null; registered = false; } } }
      public void onUnregistrationFailed(NsdServiceInfo info, int code) { Store.get(context).diagnostic("lan_discovery_stop_failed_" + code); }
    };
    listener = active;
    try { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, active); }
    catch (RuntimeException e) { listener = null; registered = false; Store.get(context).diagnostic("lan_discovery_unavailable"); }
  }
  public synchronized void stop() {
    wanted = false; registered = false; tried = 0;
    NsdManager.RegistrationListener old = listener; listener = null;
    if (old != null) try { nsd.unregisterService(old); } catch (RuntimeException ignored) {}
  }
}

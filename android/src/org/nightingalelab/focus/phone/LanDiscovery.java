package org.nightingalelab.focus.phone;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles both mDNS service advertising (so Windows FocusDNS finds this phone)
 * and mDNS browsing (so this phone finds Focus TV and Focus desktop devices).
 */
public final class LanDiscovery {
  public static final String PHONE_TYPE = "_focus-phone._tcp.";
  public static final String TV_TYPE = "_focus-tv._tcp.";
  public static final String LAPTOP_TYPE = "_focus-laptop._tcp.";

  public static final class DiscoveredService {
    public final String serviceName;
    public final String serviceType;
    public final String address; // host:port
    final String deviceId;

    DiscoveredService(String serviceName, String serviceType, String address, String deviceId) {
      this.serviceName = serviceName;
      this.serviceType = serviceType;
      this.address = address;
      this.deviceId = deviceId;
    }
  }

  private final Context context;
  private final NsdManager nsd;
  private NsdManager.RegistrationListener regListener;
  private boolean wanted;
  private volatile boolean registered;
  private long tried;

  private final Map<String, List<DiscoveredService>> found = new ConcurrentHashMap<>();
  private WifiManager.MulticastLock multicast;
  private final List<NsdManager.DiscoveryListener> browsers = new ArrayList<>();

  private final Map<String, Resolution> announced = new HashMap<>();
  private final Deque<Resolution> pending = new ArrayDeque<>();
  private Resolution resolving;

  private static final class Resolution {
    final String key;
    final String type;
    final NsdServiceInfo info;
    final Runnable onUpdate;
    int busyRetries;

    Resolution(String key, String type, NsdServiceInfo info, Runnable onUpdate) {
      this.key = key;
      this.type = type;
      this.info = info;
      this.onUpdate = onUpdate;
    }
  }

  public LanDiscovery(Context c) {
    context = c;
    nsd = c.getSystemService(NsdManager.class);
  }

  public boolean registered() {
    return registered;
  }

  public synchronized void startAdvertising(String id, String name, int port) {
    if (nsd == null) return;
    wanted = true;
    if (regListener != null || (tried != 0 && SystemClock.elapsedRealtime() - tried < 10_000)) return;
    tried = SystemClock.elapsedRealtime();
    NsdServiceInfo service = new NsdServiceInfo();
    StringBuilder instance = new StringBuilder("Focus ");
    for (int at = 0; at < name.length(); ) {
      int code = name.codePointAt(at);
      String next = new String(Character.toChars(code));
      if ((instance.toString() + next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 62) break;
      instance.append(next);
      at += Character.charCount(code);
    }
    service.setServiceName(instance.toString());
    service.setServiceType(PHONE_TYPE);
    service.setPort(port);
    service.setAttribute("id", id);
    service.setAttribute("kind", "phone");
    service.setAttribute("pairing", "approval-v2");

    NsdManager.RegistrationListener active = new NsdManager.RegistrationListener() {
      @Override
      public void onServiceRegistered(NsdServiceInfo info) {
        synchronized (LanDiscovery.this) {
          if (!wanted || regListener != this) {
            try { nsd.unregisterService(this); } catch (RuntimeException ignored) {}
            return;
          }
          registered = true;
        }
        Store.get(context).diagnostic("lan_discovery_registered");
      }

      @Override
      public void onRegistrationFailed(NsdServiceInfo info, int code) {
        synchronized (LanDiscovery.this) {
          if (regListener == this) {
            regListener = null;
            registered = false;
          }
        }
        Store.get(context).diagnostic("lan_discovery_failed_" + code);
      }

      @Override
      public void onServiceUnregistered(NsdServiceInfo info) {
        synchronized (LanDiscovery.this) {
          if (regListener == this) {
            regListener = null;
            registered = false;
          }
        }
      }

      @Override
      public void onUnregistrationFailed(NsdServiceInfo info, int code) {}
    };
    regListener = active;
    try {
      nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, active);
    } catch (RuntimeException e) {
      regListener = null;
      registered = false;
      Store.get(context).diagnostic("lan_discovery_unavailable");
    }
  }

  public synchronized void stopAdvertising() {
    wanted = false;
    registered = false;
    tried = 0;
    NsdManager.RegistrationListener old = regListener;
    regListener = null;
    if (old != null && nsd != null) {
      try { nsd.unregisterService(old); } catch (RuntimeException ignored) {}
    }
  }

  public synchronized void startBrowsing(Runnable onUpdate) {
    startBrowsing(onUpdate, TV_TYPE, LAPTOP_TYPE);
  }

  synchronized void startBrowsing(Runnable onUpdate, String... types) {
    if (nsd == null || !browsers.isEmpty()) return;
    WifiManager wifi = context.getSystemService(WifiManager.class);
    if (wifi != null && Build.VERSION.SDK_INT < 34) {
      try {
        multicast = wifi.createMulticastLock("focus-discovery");
        multicast.acquire();
      } catch (RuntimeException ignored) {}
    }
    for (String type : types) {
      NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String regType) {}
        @Override public void onDiscoveryStopped(String serviceType) {}
        @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
        @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

        @Override
        public void onServiceFound(NsdServiceInfo info) {
          synchronized (LanDiscovery.this) {
            String key = browseKey(type, info);
            if (!browsers.contains(this) || announced.containsKey(key) || announced.size() >= 64) return;
            Resolution next = new Resolution(key, type, info, onUpdate);
            announced.put(key, next);
            pending.add(next);
            resolveNext();
          }
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
          synchronized (LanDiscovery.this) {
            if (!browsers.contains(this)) return;
            String key = browseKey(type, info);
            pending.remove(announced.remove(key));
            found.remove(key);
          }
          if (onUpdate != null) onUpdate.run();
        }
      };
      browsers.add(dl);
      try {
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
      } catch (RuntimeException ignored) {
        browsers.remove(dl);
      }
    }
  }

  private static String browseKey(String type, NsdServiceInfo info) {
    return type + "::" + info.getServiceName()
        + (Build.VERSION.SDK_INT >= 33 ? "::" + info.getNetwork() : "");
  }

  // Legacy Android NSD can resolve only one service at a time per client.
  private void resolveNext() {
    while (resolving == null && !pending.isEmpty()) {
      Resolution current = pending.removeFirst();
      resolving = current;
      try {
        nsd.resolveService(current.info, new NsdManager.ResolveListener() {
          @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
            finishResolution(current, null, errorCode == NsdManager.FAILURE_ALREADY_ACTIVE);
          }

          @Override public void onServiceResolved(NsdServiceInfo info) {
            finishResolution(current, info, false);
          }
        });
      } catch (RuntimeException ignored) {
        announced.remove(current.key, current);
        resolving = null;
      }
    }
  }

  private void finishResolution(Resolution current, NsdServiceInfo info, boolean busy) {
    boolean updated = false;
    synchronized (this) {
      if (resolving != current) return;
      resolving = null;
      if (announced.get(current.key) == current) {
        if (info != null && info.getPort() > 0) {
          List<InetAddress> hosts = Build.VERSION.SDK_INT >= 34
              ? info.getHostAddresses() : Collections.singletonList(info.getHost());
          byte[] id = info.getAttributes().get("id");
          String deviceId = id == null ? "" : new String(id, StandardCharsets.UTF_8);
          List<DiscoveredService> endpoints = new ArrayList<>();
          for (InetAddress host : hosts) {
            if (host == null) continue;
            String addr = host.getHostAddress();
            String endpoint = addr.contains(":") ? "[" + addr + "]:" + info.getPort() : addr + ":" + info.getPort();
            endpoints.add(new DiscoveredService(info.getServiceName(), current.type, endpoint, deviceId));
          }
          if (LAPTOP_TYPE.equals(current.type)) {
            endpoints.sort(Comparator.comparing(service -> service.address.startsWith("[")));
          }
          if (!endpoints.isEmpty()) {
            found.put(current.key, endpoints);
            updated = true;
          }
        }
        if (busy && current.busyRetries++ < 4) {
          // A second browser can share the OS client and already have a resolve in flight.
          new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (LanDiscovery.this) {
              if (announced.get(current.key) != current) return;
              pending.addLast(current);
              resolveNext();
            }
          }, 120L * current.busyRetries);
        } else if (!updated) {
          announced.remove(current.key);
        }
      }
      resolveNext();
    }
    if (updated && current.onUpdate != null) current.onUpdate.run();
  }

  public synchronized void stopBrowsing() {
    if (nsd != null) {
      for (NsdManager.DiscoveryListener dl : browsers) {
        try { nsd.stopServiceDiscovery(dl); } catch (RuntimeException ignored) {}
      }
    }
    browsers.clear();
    pending.clear();
    announced.clear();
    found.clear();
    if (multicast != null && multicast.isHeld()) multicast.release();
    multicast = null;
  }

  public List<DiscoveredService> discovered() {
    List<DiscoveredService> services = new ArrayList<>();
    for (List<DiscoveredService> endpoints : found.values()) services.add(endpoints.get(0));
    return services;
  }

  List<String> laptopAddresses() {
    List<String> addresses = new ArrayList<>();
    for (List<DiscoveredService> endpoints : found.values()) {
      for (DiscoveredService endpoint : endpoints) {
        if (LAPTOP_TYPE.equals(endpoint.serviceType)) addresses.add(endpoint.address);
      }
    }
    return addresses;
  }

  boolean discoveredLaptop(String address, String deviceId) {
    for (List<DiscoveredService> endpoints : found.values()) {
      for (DiscoveredService endpoint : endpoints) {
        if (LAPTOP_TYPE.equals(endpoint.serviceType) && address.equals(endpoint.address)
            && deviceId.equals(endpoint.deviceId)) return true;
      }
    }
    return false;
  }
}

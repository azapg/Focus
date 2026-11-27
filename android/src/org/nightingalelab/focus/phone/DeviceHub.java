package org.nightingalelab.focus.phone;

import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLHandshakeException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import org.nightingalelab.focus.habits.*;
import org.json.*;

/**
 * Manages paired remote Focus devices (TV, Laptop) from the phone:
 * discovery, approval-v2 pairing, periodic snapshot syncing, unified all-device data aggregation,
 * and remote DNS pause/resume/rule commands.
 */
public final class DeviceHub {
  public static final String ACTION_DEVICES_UPDATED = "org.nightingalelab.focus.phone.DEVICES_UPDATED";

  public static final class PairedDevice {
    public final String id;
    public final String kind; // "tv" or "laptop"
    public String name;
    public volatile String address;
    private String pushedAddress = "";
    private long lastPushedAt;
    private long lastControlAttempt;
    public String fingerprint;
    public String token;
    public boolean relayed;
    private String relaySource = "";
    private long lastRelayRevision;
    private long lastRelayTime;
    private long retiredSnapshotRevision;
    public JSONObject snapshot;
    private ScopedAccessWindow scopedAccess;
    private long scopedRevision;
    private boolean scopedRestoring;
    // Cached history can survive an outage, but only a confirmed snapshot can project live use.
    private JSONObject livePauseSnapshot;
    private long directSnapshotRequestedAt;
    private String allowanceGeneration = "";
    private long allowanceGenerationSeenAt;
    public long lastSeen;
    public volatile boolean online;
    public String error = "";

    public PairedDevice(String id, String kind, String name, String address, String fingerprint, String token) {
      this.id = id;
      this.kind = kind;
      this.name = name;
      this.address = address;
      this.fingerprint = fingerprint;
      this.token = token;
    }

    public boolean dnsPausedNow(long nowMs) {
      return snapshot != null && snapshot.optJSONObject("dns") != null
          && snapshot.optJSONObject("dns").optLong("pause_until", 0) > nowMs;
    }

    public long dnsPauseUntil() {
      return snapshot == null || snapshot.optJSONObject("dns") == null
          ? 0
          : snapshot.optJSONObject("dns").optLong("pause_until", 0);
    }

    public String activePauseReason(long nowMs) {
      if (snapshot == null) return "";
      JSONArray pauses = snapshot.optJSONArray("pauses");
      if (pauses == null) return "";
      for (int i = pauses.length() - 1; i >= 0; i--) {
        JSONObject p = pauses.optJSONObject(i);
        if (p != null && "dns".equals(p.optString("kind")) && p.optLong("end", 0) >= nowMs - 60_000) {
          return p.optString("reason", "");
        }
      }
      return "";
    }

    public int ruleCount() {
      if (snapshot == null) return 0;
      JSONArray apps = snapshot.optJSONArray("apps");
      if (apps == null) return 0;
      int n = 0;
      for (int i = 0; i < apps.length(); i++) {
        JSONObject a = apps.optJSONObject(i);
        if (a != null && (a.optBoolean("blocked") || a.optBoolean("scheduled") || a.optLong("daily_limit_ms") > 0)) {
          n++;
        }
      }
      return n;
    }
  }

  public static final class NearbyPeer {
    public final String id;
    public final String kind;
    public final String name;
    public final String address;
    public final String fingerprint;
    public final boolean paired;

    public NearbyPeer(String id, String kind, String name, String address, String fingerprint, boolean paired) {
      this.id = id;
      this.kind = kind;
      this.name = name;
      this.address = address;
      this.fingerprint = fingerprint;
      this.paired = paired;
    }
  }

  public static final class PendingPair {
    public final NearbyPeer peer;
    public final String requestId;
    public final String pollSecret;
    public final String code;

    public PendingPair(NearbyPeer peer, String requestId, String pollSecret, String code) {
      this.peer = peer;
      this.requestId = requestId;
      this.pollSecret = pollSecret;
      this.code = code;
    }
  }

  private static DeviceHub instance;
  private final Context context;
  private final File storageFile;
  private final List<PairedDevice> devices = new ArrayList<>();
  private boolean retryPersist;
  private long lastSyncElapsed;
  private Network controlNetwork;

  public static synchronized DeviceHub get(Context c) {
    if (instance == null) instance = new DeviceHub(c.getApplicationContext());
    return instance;
  }

  private DeviceHub(Context c) {
    context = c;
    storageFile = new File(c.getFilesDir(), "paired_devices.json");
    load();
    refreshScopedNotification(false);
    ConnectivityManager connectivity = c.getSystemService(ConnectivityManager.class);
    if (connectivity != null) {
      try {
        controlNetwork = connectivity.getActiveNetwork();
        connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
          @Override public void onAvailable(Network network) { controlNetworkChanged(network, null); }
          @Override public void onLost(Network network) { controlNetworkChanged(null, network); }
        });
      } catch (RuntimeException ignored) {
        // The next direct refresh will update reachability if callbacks are unavailable.
      }
    }
  }

  private void controlNetworkChanged(Network next, Network lost) {
    boolean changed = false;
    synchronized (this) {
      if (lost != null && !lost.equals(controlNetwork) || Objects.equals(next, controlNetwork)) return;
      controlNetwork = next;
      long sequence = SystemClock.elapsedRealtimeNanos();
      for (PairedDevice device : devices) {
        if (!"laptop".equals(device.kind)) continue;
        device.lastControlAttempt = sequence;
        changed |= device.online;
        device.online = false;
      }
    }
    if (changed) notifyUpdated();
  }

  private synchronized void load() {
    devices.clear();
    if (!storageFile.exists()) return;
    try (FileInputStream in = new FileInputStream(storageFile)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int n;
      while ((n = in.read(chunk)) >= 0) {
        out.write(chunk, 0, n);
        if (out.size() > 12 * 1024 * 1024) break;
      }
      if (out.size() <= 0) return;
      long now = System.currentTimeMillis();
      JSONArray arr = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        PairedDevice d = new PairedDevice(
            o.getString("id"),
            o.optString("kind", "tv"),
            o.optString("name", "Device"),
            o.optString("address", ""),
            o.optString("fingerprint", ""),
            o.optString("token", ""));
        d.relayed = o.optBoolean("relayed", false);
        d.relaySource = o.optString("relay_source", "");
        d.lastRelayRevision = o.optLong("last_relay_revision");
        d.lastRelayTime = o.optLong("last_relay_time");
        d.retiredSnapshotRevision = o.optLong("retired_snapshot_revision");
        d.lastSeen = o.optLong("last_seen", 0);
        d.pushedAddress = o.optString("pushed_address");
        d.lastPushedAt = o.optLong("last_pushed_at");
        d.online = !"laptop".equals(d.kind) && d.lastSeen > 0 && (now - d.lastSeen) < 90_000L;
        d.snapshot = o.optJSONObject("snapshot");
        d.scopedRevision = o.optLong("scoped_revision");
        d.scopedRestoring = o.optBoolean("scoped_restoring");
        d.scopedAccess = ScopedAccessWindow.restore(d.id, o.optJSONObject("scoped_access"), Store.get(context).boot());
        devices.add(d);
      }
    } catch (Exception e) {
      Store.get(context).diagnostic("device_hub_load_failed");
    }
  }

  private synchronized void persist() {
    retryPersist = true;
    JSONArray arr = new JSONArray();
    for (PairedDevice d : devices) {
      try {
        JSONObject o = new JSONObject()
            .put("id", d.id)
            .put("kind", d.kind)
            .put("name", d.name)
            .put("address", d.address)
            .put("pushed_address", d.pushedAddress)
            .put("last_pushed_at", d.lastPushedAt)
            .put("fingerprint", d.fingerprint)
            .put("token", d.token)
            .put("relayed", d.relayed)
            .put("relay_source", d.relaySource)
            .put("last_relay_revision", d.lastRelayRevision)
            .put("last_relay_time", d.lastRelayTime)
            .put("retired_snapshot_revision", d.retiredSnapshotRevision)
            .put("last_seen", d.lastSeen);
        if (d.snapshot != null) o.put("snapshot", d.snapshot);
        if (d.scopedRevision > 0) {
          o.put("scoped_revision", d.scopedRevision).put("scoped_restoring", d.scopedRestoring);
        }
        if (d.scopedAccess != null) o.put("scoped_access", d.scopedAccess.save(Store.get(context).boot()));
        arr.put(o);
      } catch (JSONException ignored) {}
    }
    File tmp = new File(context.getFilesDir(), "paired_devices.json.tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    } catch (IOException e) {
      return;
    }
    retryPersist = !tmp.renameTo(storageFile);
  }

  private void notifyUpdated() {
    refreshScopedNotification(false);
    WidgetUpdater.updateAll(context);
    try {
      Intent intent = new Intent(ACTION_DEVICES_UPDATED);
      intent.setPackage(context.getPackageName());
      context.sendBroadcast(intent);
    } catch (RuntimeException ignored) {}
  }

  public synchronized boolean isAddressPaired(String address) {
    if (address == null || address.isEmpty()) return false;
    for (PairedDevice d : devices) {
      if (!d.relayed && (address.equals(d.address) || address.equals(d.pushedAddress))) return true;
    }
    return false;
  }

  /**
   * Ingests a live snapshot pushed by a paired peer (e.g. Windows laptop via POST /v1/sync),
   * optionally linking its reciprocal LAN credentials so the phone can also control it directly,
   * plus any relayed peer snapshots (e.g. Focus TV connected to Windows).
   */
  public void ingestSyncedPeer(
      JSONObject peerSnapshot, JSONObject reciprocal, JSONArray relayedPeers, String selfId) {
    if (peerSnapshot == null) return;
    String id = peerSnapshot.optString("device_id", "").trim();
    String kind = peerSnapshot.optString("kind", "laptop").trim();
    String name = peerSnapshot.optString("name", "Laptop").trim();
    if (id.isEmpty() || id.equals(selfId)) return;
    long now = System.currentTimeMillis();

    synchronized (this) {
      PairedDevice cur = findById(id);
      String rAddr = reciprocal != null ? reciprocal.optString("address", "").trim() : "";
      String rFp = reciprocal != null ? reciprocal.optString("fingerprint", "").trim() : "";
      String rTok = reciprocal != null ? reciprocal.optString("token", "").trim() : "";
      if (cur == null) {
        cur = new PairedDevice(id, kind, name, rAddr, rFp, rTok);
        devices.add(cur);
      } else {
        cur.name = name;
        cur.relayed = false;
        if (!rFp.isEmpty() && !rTok.isEmpty()) {
          // An inbound push may contain a stale address. Keep the last verified address first.
          if (!cur.fingerprint.equalsIgnoreCase(rFp) || !cur.token.equals(rTok)) cur.online = false;
          if (!cur.fingerprint.equalsIgnoreCase(rFp)) cur.address = "";
          cur.pushedAddress = rAddr;
          cur.fingerprint = rFp;
          cur.token = rTok;
        }
      }
      if (adoptSnapshot(cur, peerSnapshot, null)) {
        adoptScopedAccess(cur, peerSnapshot, SystemClock.elapsedRealtime(), false);
      }
      cur.lastSeen = now;
      cur.lastPushedAt = now;
      if (!"laptop".equals(cur.kind)) {
        cur.online = true;
        cur.error = "";
      }

      long relayRevision = peerSnapshot.optLong("snapshot_revision");
      long relayTime = peerSnapshot.optLong("now");
      boolean orderedRelay = relayRevision > 0;
      // Direct reads can advance the peer's own cache without delivering its relayed snapshots.
      boolean acceptedRelay = orderedRelay ? relayRevision > Math.max(cur.lastRelayRevision, cur.retiredSnapshotRevision)
          : cur.snapshot.optLong("snapshot_revision") == 0 && relayTime > cur.lastRelayTime;
      if (acceptedRelay && relayedPeers != null) {
        if (orderedRelay) cur.lastRelayRevision = relayRevision;
        else cur.lastRelayTime = relayTime;
        for (int i = 0; i < relayedPeers.length(); i++) {
          JSONObject rp = relayedPeers.optJSONObject(i);
          if (rp == null) continue;
          String rid = rp.optString("device_id", "").trim();
          if (rid.isEmpty() || rid.equals(selfId) || rid.equals(id)) continue;
          PairedDevice existing = findById(rid);
          if (existing != null && !existing.relayed) {
            // Directly paired device keeps its own direct connection, but update snapshot if fresher
            if (adoptSnapshot(existing, rp, null)) existing.lastSeen = now;
            continue;
          }
          String rKind = rp.optString("kind", "tv").trim();
          String rName = rp.optString("name", "Device").trim();
          if (existing == null) {
            existing = new PairedDevice(rid, rKind, rName, "", "", "");
            existing.relayed = true;
            devices.add(existing);
          } else {
            existing.name = rName;
          }
          // The accepted parent snapshot orders updates from the same relay, even
          // if the relayed device adjusted its own wall clock.
          boolean sameRelay = existing.relaySource.isEmpty() || existing.relaySource.equals(id);
          JSONObject relayedAtRequest = sameRelay ? existing.snapshot : null;
          if (adoptSnapshot(existing, rp, relayedAtRequest)) {
            existing.relaySource = id;
            existing.lastSeen = now;
            existing.online = !"laptop".equals(existing.kind);
            existing.error = "";
          }
        }
      }
      persist();
    }
    notifyUpdated();
  }

  private static boolean adoptSnapshot(PairedDevice device, JSONObject incoming, JSONObject atRequest) {
    JSONObject cached = device.snapshot;
    long revision = incoming.optLong("snapshot_revision");
    long cachedRevision = cached == null ? 0 : cached.optLong("snapshot_revision");
    if (cached != null) {
      if (revision > 0) {
        if (revision <= Math.max(cachedRevision, device.retiredSnapshotRevision)) return false;
      } else if (cachedRevision > 0) {
        // Only an uncontested request can confirm a peer that has downgraded.
        if (cached != atRequest) return false;
        // Retain the previous revision limit, but start a fresh legacy push watermark.
        device.retiredSnapshotRevision = Math.max(cachedRevision, device.lastRelayRevision);
        device.lastRelayTime = 0;
      } else if (cached != atRequest && incoming.optLong("now") <= cached.optLong("now")) {
        return false;
      }
    }
    device.snapshot = incoming;
    device.livePauseSnapshot = incoming;
    // A newer revision was produced after the directly observed revision. Legacy samples cannot prove that order.
    if (revision <= 0 || cachedRevision <= 0) device.directSnapshotRequestedAt = 0;
    return true;
  }

  private static void adoptDirectSnapshot(PairedDevice device, JSONObject snapshot, JSONObject atRequest,
      long requestedAt) {
    if (!adoptSnapshot(device, snapshot, atRequest)) {
      long revision = snapshot.optLong("snapshot_revision");
      if (revision <= device.retiredSnapshotRevision || device.snapshot.optLong("snapshot_revision") < revision) return;
    }
    adoptScopedAccess(device, snapshot, requestedAt / 1_000_000, true);
    device.directSnapshotRequestedAt = Math.max(device.directSnapshotRequestedAt, requestedAt);
    String generation = device.snapshot.optString("pause_allowance_generation");
    if (!device.allowanceGeneration.equals(generation)) {
      device.allowanceGeneration = generation;
      device.allowanceGenerationSeenAt = SystemClock.elapsedRealtimeNanos();
    }
  }

  private static boolean adoptScopedAccess(PairedDevice device, JSONObject sample, long clockAnchor, boolean direct) {
    if (!"laptop".equals(device.kind) || device.relayed) return false;
    long revision = sample.optLong("snapshot_revision");
    ScopedAccessWindow previous = device.scopedAccess;
    ScopedAccessWindow next = ScopedAccessWindow.sample(device.id, sample, clockAnchor, previous);
    if (revision > 0 && revision <= device.scopedRevision) {
      // A newer push can establish the grant's identity before its direct clock sample
      // arrives. Let that sample start the timer only if the latest push still agrees.
      if (!direct || previous != null || next == null || device.snapshot == null
          || device.snapshot.optLong("snapshot_revision") != device.scopedRevision
          || !next.sameGrant(ScopedAccessWindow.sample(device.id, device.snapshot, clockAnchor, null))) return false;
    }
    // A push can confirm expiry, even before the first direct reply for this grant.
    // Only a direct reply can start a new countdown with a bounded sample age.
    if (!direct && next != null && !next.sameGrant(previous) && next.deadline > clockAnchor) {
      next = null;
    }
    device.scopedRevision = Math.max(device.scopedRevision, revision);
    device.scopedAccess = next;
    JSONObject dns = sample.optJSONObject("dns");
    device.scopedRestoring = dns != null && dns.optBoolean("scoped_restoring");
    return true;
  }

  void scopedAccessApplied(PairedDevice laptop, JSONObject reply, long requestStarted) {
    adoptScopeSample(laptop, reply, requestStarted, true);
  }

  void scopedAccessStatus(PairedDevice laptop, JSONObject reply, long requestStarted) {
    adoptScopeSample(laptop, reply, requestStarted, false);
    notifyUpdated();
  }

  private void adoptScopeSample(PairedDevice laptop, JSONObject reply, long requestStarted, boolean applied) {
    JSONObject sample = reply.optJSONObject("scoped_timer");
    if (sample == null) return; // Older laptops send the deadline in the next snapshot.
    synchronized (this) {
      if (findById(laptop.id) != laptop || laptop.relayed) return;
      // The laptop's sample was taken no earlier than its measured command processing time.
      long clockAnchor = Math.min(SystemClock.elapsedRealtime(), requestStarted + sample.optLong("processing_ms"));
      if (!adoptScopedAccess(laptop, sample, clockAnchor, true)) {
        // A newer snapshot may already have installed this grant. Let Start select
        // it without replacing or extending the deadline from that snapshot.
        ScopedAccessWindow current = laptop.scopedAccess;
        if (current == null || !current.sameGrant(
            ScopedAccessWindow.sample(laptop.id, sample, clockAnchor, null))) return;
      }
      if (applied && laptop.scopedAccess != null) laptop.scopedAccess = laptop.scopedAccess.appliedNow();
      persist();
      refreshScopedNotification(false);
    }
  }

  synchronized ScopedAccessWindow activeScopedAccess() {
    return activeScopedAccess(SystemClock.elapsedRealtime());
  }

  private ScopedAccessWindow activeScopedAccess(long elapsed) {
    ScopedAccessWindow selected = null;
    for (PairedDevice device : devices) {
      ScopedAccessWindow access = device.scopedAccess;
      if (access == null || access.deadline <= elapsed || (access.grantId.isEmpty() && !device.online)) continue;
      if (selected == null || access.appliedAt > selected.appliedAt) selected = access;
    }
    return selected;
  }

  synchronized void refreshScopedNotification(boolean refreshProgress) {
    while (true) {
      long elapsed = SystemClock.elapsedRealtime();
      boolean expired = false;
      for (PairedDevice device : devices) {
        ScopedAccessWindow access = device.scopedAccess;
        if (access != null && access.deadline > 0 && access.deadline <= elapsed) {
          device.scopedAccess = access.withoutTime();
          expired = true;
        }
      }
      if (expired || retryPersist) persist();
      if (FocusNotifications.updateScopedAccess(context, activeScopedAccess(elapsed), refreshProgress)) return;
      // A selected window expired during notification work. Record it and select again.
    }
  }

  void endScopedAccess(String laptopId, String grantId, long legacyDeadline) {
    synchronized (this) {
      ScopedAccessWindow access = activeScopedAccess();
      if (access == null || !access.laptopId.equals(laptopId) || !access.grantId.equals(grantId)
          || (grantId.isEmpty() && access.remoteDeadline != legacyDeadline)) return;
    }
    try {
      // Older laptops expose this action in the dock without a grant identifier.
      JSONObject command = grantId.isEmpty() ? new JSONObject().put("action", "dns_resume")
          : new JSONObject().put("action", "end_scoped_access").put("grant_id", grantId);
      sendCommand(laptopId, command);
    } catch (Exception error) {
      Store.get(context).diagnostic("scoped_timer_end_failed");
      String message = error instanceof LanClient.CommandException
          ? "The laptop couldn’t end access." : "Couldn’t reach the laptop to end access.";
      showEndError(message);
    }
  }

  private void showEndError(String message) {
    new android.os.Handler(context.getMainLooper()).post(
        () -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
  }

  public synchronized List<PairedDevice> list() {
    return new ArrayList<>(devices);
  }

  synchronized List<PairedDevice> eligibleAskLaptops() {
    List<PairedDevice> laptops = new ArrayList<>();
    for (PairedDevice device : devices) {
      if ("laptop".equals(device.kind) && !device.relayed
          && !device.fingerprint.isEmpty() && !device.token.isEmpty()) laptops.add(device);
    }
    return laptops;
  }

  synchronized PairedDevice askLaptop() {
    List<PairedDevice> laptops = eligibleAskLaptops();
    String selected = context.getSharedPreferences("focus-ask", Context.MODE_PRIVATE).getString("laptop", "");
    for (PairedDevice laptop : laptops) {
      if (laptop.id.equals(selected)) return laptop;
    }
    if (selected.isEmpty() && laptops.size() == 1 && selectAskLaptop(laptops.get(0))) return laptops.get(0);
    return null;
  }

  synchronized boolean selectAskLaptop(PairedDevice laptop) {
    if (!eligibleAskLaptops().contains(laptop)) return false;
    SharedPreferences settings = context.getSharedPreferences("focus-ask", Context.MODE_PRIVATE);
    PairedDevice current = findById(settings.getString("laptop", ""));
    if (current != null && current != laptop && (current.scopedAccess != null || scopeRestoring(current))) {
      return false;
    }
    return settings.edit().putString("laptop", laptop.id).commit();
  }

  synchronized boolean scopedRestorationPending() {
    for (PairedDevice laptop : devices) {
      if ("laptop".equals(laptop.kind) && !laptop.relayed && scopeRestoring(laptop)) return true;
    }
    return false;
  }

  private static boolean scopeRestoring(PairedDevice laptop) {
    return laptop.scopedRestoring
        || (laptop.scopedAccess != null && !laptop.scopedAccess.grantId.isEmpty()
            && laptop.scopedAccess.remaining() == 0);
  }

  synchronized boolean hasActiveScopedGrant(PairedDevice laptop, String id) {
    return findById(laptop.id) == laptop && !laptop.relayed
        && laptop.scopedAccess != null && id.equals(laptop.scopedAccess.grantId)
        && laptop.scopedAccess.remaining() > 0;
  }

  public synchronized PairedDevice findById(String id) {
    for (PairedDevice d : devices) if (d.id.equals(id)) return d;
    return null;
  }

  public synchronized PairedDevice findByKind(String kind) {
    for (PairedDevice d : devices) if (d.kind.equals(kind)) return d;
    return null;
  }

  public synchronized void forget(String id) {
    devices.removeIf(d -> d.id.equals(id));
    refreshScopedNotification(false);
    persist();
    WidgetUpdater.updateAll(context);
  }

  public NearbyPeer probe(String address) throws Exception {
    LanClient.Response resp = LanClient.request(address, null, null, "/v1/hello", null);
    String id = resp.json.getString("device_id");
    String kind = resp.json.optString("kind", "tv");
    String name = resp.json.optString("name", "Device");
    boolean paired;
    synchronized (this) {
      paired = devices.stream().anyMatch(d -> d.id.equals(id) && d.fingerprint.equalsIgnoreCase(resp.fingerprint));
    }
    return new NearbyPeer(id, kind, name, address, resp.fingerprint, paired);
  }

  public PendingPair startApproval(NearbyPeer peer) throws Exception {
    String clientNonce = PairingCode.token();
    String commitment = PairingCode.hash(clientNonce);
    String managerName = LanController.get(context).deviceName();
    JSONObject reqBody = new JSONObject()
        .put("commitment", commitment)
        .put("manager_name", managerName);
    LanClient.Response start = LanClient.request(peer.address, peer.fingerprint, null, "/v2/pair/request", reqBody);
    String requestId = start.json.getString("request_id");
    String pollSecret = start.json.getString("poll_secret");
    String serverNonce = start.json.getString("server_nonce");
    String expectedCode = ApprovalPairing.comparison(peer.fingerprint, clientNonce, serverNonce);

    JSONObject revBody = new JSONObject()
        .put("request_id", requestId)
        .put("poll_secret", pollSecret)
        .put("client_nonce", clientNonce);
    LanClient.Response rev = LanClient.request(peer.address, peer.fingerprint, null, "/v2/pair/reveal", revBody);
    String serverCode = rev.json.optString("code", "");
    if (!expectedCode.equals(serverCode)) {
      throw new IllegalStateException("Pairing comparison code mismatch");
    }
    return new PendingPair(peer, requestId, pollSecret, expectedCode);
  }

  public String pollApproval(PendingPair pending) throws Exception {
    JSONObject body = new JSONObject()
        .put("request_id", pending.requestId)
        .put("poll_secret", pending.pollSecret);
    LanClient.Response resp = LanClient.request(pending.peer.address, pending.peer.fingerprint, null, "/v2/pair/status", body);
    String status = resp.json.optString("status", "waiting");
    if ("approved".equals(status)) {
      String token = resp.json.getString("token");
      savePaired(pending.peer, token);
      refreshOne(pending.peer.id);
    }
    return status;
  }

  public void cancelApproval(PendingPair pending) {
    try {
      JSONObject body = new JSONObject()
          .put("request_id", pending.requestId)
          .put("poll_secret", pending.pollSecret);
      LanClient.request(pending.peer.address, pending.peer.fingerprint, null, "/v2/pair/cancel", body);
    } catch (Exception ignored) {}
  }

  private synchronized void savePaired(NearbyPeer peer, String token) {
    devices.removeIf(d -> d.id.equals(peer.id));
    devices.add(new PairedDevice(peer.id, peer.kind, peer.name, peer.address, peer.fingerprint, token));
    persist();
  }

  public void syncIfDue() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastSyncElapsed < 30_000) return;
    lastSyncElapsed = now;
    syncAll();
  }

  public void syncAll() {
    List<PairedDevice> copy = list();
    for (PairedDevice d : copy) {
      refreshOne(d.id);
    }
  }

  public boolean refreshOne(String id) {
    PairedDevice d;
    JSONObject atRequest;
    synchronized (this) {
      d = findById(id);
      if (d == null) return false;
      atRequest = d.snapshot;
    }
    long now = System.currentTimeMillis();
    boolean laptop = "laptop".equals(d.kind);
    if (d.relayed || d.token == null || d.token.isEmpty() || (!laptop && (d.address == null || d.address.isEmpty()))) {
      if (laptop) return false;
      synchronized (this) {
        PairedDevice cur = findById(id);
        if (cur != null) cur.online = cur.lastSeen > 0 && (now - cur.lastSeen) < 90_000L;
      }
      return d.online;
    }
    try {
      long requestedAt = SystemClock.elapsedRealtimeNanos();
      LanClient.Response resp = laptop
          ? laptopRequest(d, "/v1/snapshot", null, SystemClock.elapsedRealtime() + 2900, 6000, FocusEvents.newId())
          : LanClient.request(d.address, d.fingerprint, d.token, "/v1/snapshot", null);
      synchronized (this) {
        PairedDevice cur = findById(id);
        if (cur != d) return false;
        adoptDirectSnapshot(cur, resp.json, atRequest, requestedAt);
        refreshScopedNotification(false);
        cur.name = resp.json.optString("name", cur.name);
        cur.lastSeen = System.currentTimeMillis();
        if (!laptop) {
          cur.online = true;
          cur.error = "";
        }
        persist();
      }
      if (resp.json.optInt("habits_sync") == 1) {
        try { exchangeHabits(d); }
        catch (Exception e) { Store.get(context).diagnostic("habits_sync_failed"); }
      }
      notifyUpdated();
      return true;
    } catch (Exception e) {
      boolean projectionChanged = false;
      synchronized (this) {
        PairedDevice cur = findById(id);
        if (cur == d && cur.snapshot == atRequest) {
          if (!laptop) {
            boolean recentlyPushed = cur.lastSeen > 0 && (System.currentTimeMillis() - cur.lastSeen) < 75_000L;
            cur.online = recentlyPushed;
            cur.error = recentlyPushed ? "" : (e.getMessage() == null ? "Offline" : e.getMessage());
          }
          projectionChanged = cur.livePauseSnapshot != null;
          cur.livePauseSnapshot = null;
        }
      }
      if (projectionChanged) notifyUpdated();
      return false;
    }
  }

  private synchronized JSONObject relayAllowance(PairedDevice target, long from, long now) throws JSONException {
    JSONObject allowance = new JSONObject();
    for (PairedDevice peer : devices) {
      if (peer.id.equals(target.id) || peer.snapshot == null) continue;
      JSONObject usage = HabitStore.allowanceFromSnapshot(peer.snapshot, Habits.day(now), from, now);
      // A response to a request started before the TV announced its new pairing may still be stale.
      if (!target.allowanceGeneration.isEmpty() && peer.directSnapshotRequestedAt > target.allowanceGenerationSeenAt) {
        usage.put("generation", target.allowanceGeneration);
      }
      allowance.put(peer.id, usage);
    }
    return allowance;
  }

  private void exchangeHabits(PairedDevice device) throws Exception {
    HabitStore store = HabitStore.get(context);
    Habits ledger = store.read();
    boolean wallet = device.snapshot != null && device.snapshot.optInt("wallet_sync") == Wallet.SYNC_VERSION;
    String send = "", receive = "";
    JSONObject screens = new JSONObject();
    screens.put(LanController.get(context).deviceId(), encodedScreen(HabitScreen.local(context, System.currentTimeMillis())));
    for (PairedDevice peer : list()) {
      if (!peer.id.equals(device.id)) screens.put(peer.id, encodedScreen(HabitStore.fromSnapshot(peer.snapshot)));
    }
    long now = System.currentTimeMillis();
    long from = InsightsEngine.dayStart(now, TimeZone.getDefault());
    JSONObject allowance = relayAllowance(device, from, now);
    Store activity = Store.get(context);
    long used = 0;
    for (InsightsEngine.PauseRecord pause : activity.localPauses(from, now)) {
      used += Math.max(0, Math.min(now, pause.ended) - Math.max(from, pause.started));
    }
    allowance.put(LanController.get(context).deviceId(), new JSONObject().put("day", Habits.day(now))
        .put("now", now).put("off", used).put("kind", "phone").put("direct", true)
        .put("base", activity.policyConfig().dailyLimitMinutes));
    while (send != null || receive != null) {
      JSONObject page = send == null ? new JSONObject() : HabitStore.page(ledger, send, wallet);
      send = page.isNull("next") ? null : page.getString("next");
      page.remove("next");
      if (wallet) page.put("wallet_sync", Wallet.SYNC_VERSION);
      page.put("after", receive == null ? JSONObject.NULL : receive);
      if (screens != null) {
        page.put("screens", screens).put("allowance", allowance);
        screens = null;
      }
      JSONObject result = LanClient.request(device.address, device.fingerprint, device.token, "/v1/habits", page).json;
      String next = result.isNull("next") ? null : result.getString("next");
      if (next != null && (receive == null || receive.compareTo(next) >= 0)) throw new JSONException("Invalid habits cursor");
      receive = next;
      if (findById(device.id) == null) return;
      store.exchange(result);
    }
  }

  private static Object encodedScreen(Habits.Screen screen) throws JSONException {
    JSONObject encoded = HabitStore.encodeScreen(screen);
    return encoded == null ? JSONObject.NULL : encoded;
  }

  static final class LaptopRoute {
    final PairedDevice device;
    final String pin;
    final String token;
    final String address;
    final long sequence;
    final long started;

    LaptopRoute(PairedDevice device, String pin, String token, String address, long sequence, long started) {
      this.device = device;
      this.pin = pin;
      this.token = token;
      this.address = address;
      this.sequence = sequence;
      this.started = started;
    }
  }

  LaptopRoute resolveLaptop(Collection<PairedDevice> devices, long deadline, String correlation) throws Exception {
    long started = SystemClock.elapsedRealtime();
    long sequence = SystemClock.elapsedRealtimeNanos();
    Map<String, LaptopRoute> byId = new HashMap<>();
    Set<String> known = new LinkedHashSet<>();
    synchronized (this) {
      for (PairedDevice device : devices) {
        if (findById(device.id) != device || device.relayed
            || device.fingerprint.isEmpty() || device.token.isEmpty()) continue;
        byId.put(device.id, new LaptopRoute(device, device.fingerprint, device.token, device.address, sequence, started));
        known.add(device.address);
        known.add(device.pushedAddress);
      }
    }
    if (byId.isEmpty()) {
      recordReachability(false, FocusEventCodes.LAN_UNREACHABLE, correlation, started);
      throw new LanClient.UnreachableException(null);
    }
    LanDiscovery discovery = new LanDiscovery(context);
    Map<String, String> mismatchedPins = new ConcurrentHashMap<>();
    try {
      discovery.startBrowsing(null, LanDiscovery.LAPTOP_TYPE);
      return LanClient.findAddress(known, discovery::laptopAddresses, (address, timeout) -> {
        // /hello sends no token or Ask data. Treat the reported ID and certificate as
        // untrusted until both match an existing pairing, then pin the command too.
        LanClient.Response hello = LanClient.requestUntil(address, null, null, "/v1/hello", null, timeout, timeout,
            SystemClock.elapsedRealtime() + timeout);
        LaptopRoute route = byId.get(hello.json.optString("device_id"));
        if (route == null) throw new IOException("Address belongs to a different laptop");
        if (!route.pin.equalsIgnoreCase(hello.fingerprint)) {
          mismatchedPins.put(address, route.device.id);
          throw new IOException("Unverified laptop certificate");
        }
        return new LaptopRoute(route.device, route.pin, route.token, address, sequence, started);
      }, deadline);
    } catch (Exception error) {
      Exception failure = laptopResolutionFailure(error, mismatchedPins, discovery::discoveredLaptop);
      for (LaptopRoute route : byId.values()) updateLaptopControl(route, false);
      recordReachability(false, lanCause(failure), correlation, started);
      throw failure;
    } finally {
      discovery.stopBrowsing();
    }
  }

  static Exception laptopResolutionFailure(Exception error, Map<String, String> mismatchedPins,
      BiPredicate<String, String> discovered) {
    if (!(error instanceof LanClient.UnreachableException)) return error;
    for (Map.Entry<String, String> mismatch : mismatchedPins.entrySet()) {
      if (discovered.test(mismatch.getKey(), mismatch.getValue())) {
        return new LanClient.UnreachableException(new SSLHandshakeException("Unexpected laptop certificate"));
      }
    }
    if (!mismatchedPins.isEmpty() || error.getCause() instanceof SSLHandshakeException) {
      return new LanClient.UnreachableException(null);
    }
    return error;
  }

  LanClient.Response laptopRequest(PairedDevice device, String path, JSONObject body,
      long addressDeadline, int readMs, String correlation) throws Exception {
    LaptopRoute route = resolveLaptop(Collections.singletonList(device), addressDeadline, correlation);
    return laptopRequest(route, path, body, readMs, correlation, Long.MAX_VALUE);
  }

  LanClient.Response laptopRequest(LaptopRoute route, String path, JSONObject body, int readMs,
      String correlation, long responseDeadline) throws Exception {
    boolean reachable = false;
    String failure = FocusEventCodes.LAN_UNREACHABLE;
    try {
      synchronized (this) {
        if (!samePairing(route)) throw new LanClient.UnreachableException(null);
      }
      LanClient.Response reply = LanClient.requestUntil(route.address, route.pin, route.token,
          path, body, 1450, readMs, responseDeadline);
      reachable = true;
      return reply;
    } catch (LanClient.CommandException error) {
      reachable = error.status != 401 && error.status != 403;
      failure = reachable ? FocusEventCodes.LAN_REJECTED : FocusEventCodes.LAN_UNAUTHORIZED;
      throw error;
    } catch (SSLHandshakeException error) {
      failure = FocusEventCodes.LAN_TLS_MISMATCH;
      // A previously verified address can change before the second connection.
      // Require fresh discovery before asking the user to replace their pairing.
      throw new LanClient.UnreachableException(null);
    } catch (Exception error) {
      failure = lanCause(error);
      throw error;
    } finally {
      updateLaptopControl(route, reachable);
      recordReachability(reachable, failure, correlation, route.started);
    }
  }

  private boolean samePairing(LaptopRoute route) {
    return findById(route.device.id) == route.device && !route.device.relayed
        && route.pin.equals(route.device.fingerprint) && route.token.equals(route.device.token);
  }

  private void updateLaptopControl(LaptopRoute route, boolean reachable) {
    boolean changed;
    synchronized (this) {
      PairedDevice device = route.device;
      if (route.sequence < device.lastControlAttempt || !samePairing(route)) return;
      device.lastControlAttempt = route.sequence;
      changed = device.online != reachable;
      device.online = reachable;
      device.error = reachable ? "" : "Laptop unreachable";
      if (reachable && !Objects.equals(route.address, device.address)) {
        device.address = route.address;
        persist();
      }
    }
    if (changed) notifyUpdated();
  }

  private static String lanCause(Exception error) {
    Throwable cause = error instanceof LanClient.UnreachableException ? error.getCause() : error;
    if (cause instanceof SocketTimeoutException) return FocusEventCodes.LAN_TIMEOUT;
    if (cause instanceof SSLHandshakeException) return FocusEventCodes.LAN_TLS_MISMATCH;
    if (cause instanceof JSONException) return FocusEventCodes.LAN_INVALID_REPLY;
    return FocusEventCodes.LAN_UNREACHABLE;
  }

  private void recordReachability(boolean reachable, String failure, String correlation, long started) {
    try {
      FocusEvents.record(context, "lan", "reachability", reachable ? "ok" : "failed",
          reachable ? FocusEventCodes.OK : failure, SystemClock.elapsedRealtime() - started,
          new JSONObject().put("correlation_id", correlation).put("origin", "phone")
              .put("route", "laptop").put("direction", "phone_to_laptop"));
    } catch (JSONException ignored) {}
  }

  public void sendCommand(String id, JSONObject command) throws Exception {
    PairedDevice d = findById(id);
    if (d == null) throw new IllegalStateException("Device not paired");
    boolean laptop = "laptop".equals(d.kind);
    if (d.token == null || d.token.isEmpty() || (!laptop && (d.address == null || d.address.isEmpty()))) {
      throw new IllegalStateException("Direct remote control requires pairing directly with this device");
    }
    JSONObject atRequest = d.snapshot;
    long requestedAt = SystemClock.elapsedRealtimeNanos();
    String correlation = FocusEvents.newId();
    LanClient.Response resp = laptop
        ? laptopRequest(d, "/v1/command", command.put("correlation_id", correlation),
            SystemClock.elapsedRealtime() + 2900, 6000, correlation)
        : LanClient.request(d.address, d.fingerprint, d.token, "/v1/command", command);
    synchronized (this) {
      PairedDevice cur = findById(id);
      if (cur == d) {
        adoptDirectSnapshot(cur, resp.json, atRequest, requestedAt);
        refreshScopedNotification(false);
        cur.lastSeen = System.currentTimeMillis();
        if (!laptop) {
          cur.online = true;
          cur.error = "";
        }
        persist();
      }
    }
    notifyUpdated();
  }

  /** Aggregates local phone spans + all cached paired device spans across a time window. */
  public List<InsightsEngine.SpanRecord> combinedSpans(long from, long to) {
    List<InsightsEngine.SpanRecord> out = new ArrayList<>(Store.get(context).localSpans(from, to));
    for (PairedDevice d : list()) {
      if (d.snapshot == null) continue;
      JSONArray spans = d.snapshot.optJSONArray("spans");
      if (spans == null) continue;
      for (int i = 0; i < spans.length(); i++) {
        JSONObject sp = spans.optJSONObject(i);
        if (sp == null) continue;
        long s = sp.optLong("s", 0);
        long e = sp.optLong("e", 0);
        if (e <= from || s >= to || e <= s) continue;
        String app = sp.optString("app", "");
        String label = sp.optString("label", app);
        String domain = sp.optString("domain", "");
        out.add(new InsightsEngine.SpanRecord(s, e, app, label, domain, d.kind, d.name, d.id));
      }
    }
    return out;
  }

  /** Aggregates local phone pauses + all cached paired device pauses (shared cross-device count!). */
  public List<InsightsEngine.PauseRecord> combinedPauses(long from, long to) {
    return combinedPauses(from, to, false);
  }

  List<InsightsEngine.PauseRecord> combinedPauses(long from, long to, boolean projectLive) {
    List<InsightsEngine.PauseRecord> out = new ArrayList<>(Store.get(context).localPauses(from, to));
    long now = System.currentTimeMillis();
    for (PairedDevice d : list()) {
      JSONObject snapshot;
      boolean canProject;
      synchronized (this) {
        snapshot = d.snapshot;
        canProject = projectLive && snapshot == d.livePauseSnapshot;
      }
      if (snapshot == null) continue;
      JSONArray pauses = snapshot.optJSONArray("pauses");
      if (pauses == null) continue;
      long sampledAt = snapshot.optLong("now");
      JSONObject dns = snapshot.optJSONObject("dns");
      long pauseUntil = dns == null ? 0 : dns.optLong("pause_until");
      for (int i = 0; i < pauses.length(); i++) {
        JSONObject p = pauses.optJSONObject(i);
        if (p == null || "app".equals(p.optString("kind"))) continue;
        long t = p.optLong("t", 0);
        long end = p.optLong("end", now);
        boolean wasLive = p.isNull("end_kind") && (p.has("end_kind") || end == sampledAt);
        if (canProject && wasLive) {
          end = PausePolicy.projectedDnsEnd(end, sampledAt, pauseUntil, to);
        }
        if (end <= from || t >= to) continue;
        boolean active = wasLive && pauseUntil > now;
        int mins = p.optInt("minutes", 5);
        Object rawRating = p.opt("rating");
        int rating = rawRating instanceof Number && ((Number) rawRating).doubleValue() == ((Number) rawRating).intValue()
            ? ((Number) rawRating).intValue() : 0;
        out.add(new InsightsEngine.PauseRecord(
            d.id, d.kind, d.name, t, end, active, mins, p.optString("reason", ""), "",
            PausePolicy.CheckIn.parse(p.optString("check_in", "")), rating,
            p.isNull("rating_note") ? "" : p.optString("rating_note", "")));
      }
    }
    return out;
  }

  /** Seed realistic multi-device preview data ONLY when explicitly triggered by instrumentation/demo. */
  public synchronized void seedPreviewDataForTest(List<PairedDevice> mockDevices) {
    devices.clear();
    devices.addAll(mockDevices);
    persist();
  }
}

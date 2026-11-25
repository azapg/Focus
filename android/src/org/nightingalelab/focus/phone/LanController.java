package org.nightingalelab.focus.phone;

import android.content.*;
import android.os.*;
import android.security.keystore.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Wallet;
import org.json.*;

/**
 * Local TLS device server on the phone (port 47832) so Windows FocusDNS can pair with,
 * read combined usage/site snapshots from, and pause/resume this phone over Wi-Fi.
 */
public final class LanController {
  public static final int PORT = 47832;
  private static LanController instance;
  private final Context context;
  private final SharedPreferences prefs;
  private final PairingCode pairing = new PairingCode();
  private final ApprovalPairing approvals = new ApprovalPairing();
  private final LanDiscovery discovery;
  private LanServer server;
  private String fingerprint;
  private volatile String error = "";
  private long lastTransportFailure;

  public static synchronized LanController get(Context c) {
    if (instance == null) instance = new LanController(c.getApplicationContext());
    return instance;
  }

  private LanController(Context c) {
    context = c;
    discovery = new LanDiscovery(c);
    prefs = c.getSharedPreferences("phone_lan_server", Context.MODE_PRIVATE);
    if (!prefs.contains("id")) save(prefs.edit().putString("id", UUID.randomUUID().toString()));
  }

  private void save(SharedPreferences.Editor editor) {
    if (!editor.commit()) throw new IllegalStateException("Could not save LAN server state");
  }

  public boolean enabled() {
    return prefs.getBoolean("enabled", true);
  }

  public String error() {
    return error;
  }

  public int pairedManagersCount() {
    return prefs.getStringSet("managers", Collections.emptySet()).size();
  }

  public LanDiscovery discovery() {
    return discovery;
  }

  private SSLContext tls() throws Exception {
    final String alias = "focus-phone-lan-identity-v1";
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(alias)) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
      generator.initialize(
          new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
              .setCertificateSubject(new X500Principal("CN=Focus Phone"))
              .setCertificateSerialNumber(new BigInteger(64, new SecureRandom()).add(BigInteger.ONE))
              .setCertificateNotBefore(new Date(0))
              .setCertificateNotAfter(new Date(4_102_444_800_000L))
              .build());
      generator.generateKeyPair();
    }
    fingerprint = PairingCode.hex(MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).getEncoded()));
    final PrivateKey key = (PrivateKey) store.getKey(alias, null);
    final java.security.cert.X509Certificate[] chain = {
      (java.security.cert.X509Certificate) store.getCertificate(alias)
    };
    X509ExtendedKeyManager manager = new X509ExtendedKeyManager() {
      public String[] getClientAliases(String type, Principal[] issuers) { return null; }
      public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
      public String[] getServerAliases(String type, Principal[] issuers) { return "EC".equals(type) ? new String[] {alias} : null; }
      public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return "EC".equals(type) ? alias : null; }
      public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return "EC".equals(type) ? alias : null; }
      public java.security.cert.X509Certificate[] getCertificateChain(String name) { return alias.equals(name) ? chain.clone() : null; }
      public PrivateKey getPrivateKey(String name) { return alias.equals(name) ? key : null; }
    };
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(new KeyManager[] {manager}, null, new SecureRandom());
    return tls;
  }

  public synchronized void ensure() {
    if (!enabled()) return;
    if (server != null && !server.isClosed()) {
      advertise();
      return;
    }
    server = null;
    long started = SystemClock.elapsedRealtime();
    try {
      server = new LanServer(context, tls(), PORT, this::handle, exception -> {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
          if (now - lastTransportFailure < 30_000) return;
          lastTransportFailure = now;
        }
        android.util.Log.w("FocusPhone", "lan_transport_failed", exception);
      });
      LanServer active = server;
      new Thread(active::run, "focus-phone-lan-listener").start();
      error = "";
      FocusEvents.record(context, "lan", "bind", "ok", "ok",
          SystemClock.elapsedRealtime() - started, null);
      Store.get(context).diagnostic("lan_listening");
      advertise();
    } catch (Exception e) {
      error = "Local listener unavailable (" + e.getClass().getSimpleName() + ")";
      FocusEvents.record(context, "lan", "bind", "failed", "lan_bind_failed",
          SystemClock.elapsedRealtime() - started, null);
      Store.get(context).diagnostic("lan_start_failed_" + e.getClass().getSimpleName());
    }
  }

  private void advertise() {
    discovery.startAdvertising(prefs.getString("id", ""), deviceName(), PORT);
  }

  public String deviceName() {
    String name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
    if (name == null || name.trim().isEmpty()) name = Build.MODEL;
    if (name == null || name.trim().isEmpty()) name = "Android Phone";
    return name.substring(0, Math.min(80, name.length()));
  }

  public synchronized ApprovalPairing.Session pendingApproval() {
    return approvals.pending(SystemClock.elapsedRealtime());
  }

  public synchronized void approve(String id) {
    ApprovalPairing.Session session = approvals.allow(id, SystemClock.elapsedRealtime());
    Set<String> tokens = new HashSet<>(prefs.getStringSet("managers", Collections.emptySet()));
    if (tokens.size() >= 8) throw new IllegalStateException("Disconnect managers in Focus settings first");
    String token = PairingCode.token();
    tokens.add(PairingCode.hash(token));
    save(prefs.edit().putStringSet("managers", tokens));
    approvals.approved(session, token);
    Store.get(context).diagnostic("lan_manager_approved");
  }

  public synchronized void decline(String id) {
    approvals.decline(id);
  }

  private void showApproval() {
    new Handler(Looper.getMainLooper()).post(() -> {
      try {
        context.startActivity(
            new Intent(context, PairingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      } catch (RuntimeException e) {
        Store.get(context).diagnostic("lan_pairing_prompt_unavailable");
      }
    });
  }

  public synchronized void stopServer() {
    discovery.stopAdvertising();
    approvals.cancelAll();
    if (server != null) server.close();
    server = null;
    pairing.cancel();
  }

  public synchronized void disconnectAll() {
    save(prefs.edit().putBoolean("enabled", false).remove("managers"));
    stopServer();
    Store.get(context).diagnostic("lan_managers_revoked");
  }

  public synchronized void enable() {
    save(prefs.edit().putBoolean("enabled", true));
    ensure();
  }

  private JSONObject hello() throws JSONException {
    return new JSONObject()
        .put("version", 1)
        .put("kind", "phone")
        .put("device_id", prefs.getString("id", ""))
        .put("name", deviceName())
        .put("pairing", "approval-v2");
  }

  private boolean authorized(String header) {
    if (header == null || !header.matches("Bearer [a-f0-9]{64}")) return false;
    return prefs.getStringSet("managers", Collections.emptySet()).contains(PairingCode.hash(header.substring(7)));
  }

  private synchronized String handle(String method, String path, Map<String, String> headers, String body,
      LanServer.RequestOutcome trace) throws Exception {
    if (!enabled()) throw new LanServer.ApiError(403, "Device management is disabled on this phone");
    if (method.equals("GET") && path.equals("/v1/hello")) return hello().toString();
    if (method.equals("POST") && path.startsWith("/v2/pair/")) {
      try {
        JSONObject input = new JSONObject(body);
        long now = SystemClock.elapsedRealtime();
        if (path.equals("/v2/pair/request")) {
          if (pairedManagersCount() >= 8) throw new IllegalStateException("Disconnect managers in Focus settings first");
          ApprovalPairing.Session session = approvals.begin(input.getString("commitment"), input.getString("manager_name"), now);
          return new JSONObject()
              .put("request_id", session.id)
              .put("poll_secret", session.secret)
              .put("server_nonce", session.serverNonce)
              .toString();
        }
        String id = input.getString("request_id"), secret = input.getString("poll_secret");
        ApprovalPairing.Session session;
        if (path.equals("/v2/pair/reveal")) {
          session = approvals.reveal(id, secret, input.getString("client_nonce"), fingerprint, now);
          showApproval();
        } else {
          session = approvals.authenticated(id, secret, now);
          if (path.equals("/v2/pair/cancel")) {
            if (session.status.equals("approved") && !session.token.isEmpty()) {
              Set<String> tokens = new HashSet<>(prefs.getStringSet("managers", Collections.emptySet()));
              tokens.remove(PairingCode.hash(session.token));
              save(prefs.edit().putStringSet("managers", tokens));
            }
            approvals.decline(id);
          } else if (!path.equals("/v2/pair/status")) {
            throw new IllegalArgumentException("Unknown pairing action");
          }
        }
        JSONObject result = hello().put("status", session.status).put("code", session.code);
        if (session.status.equals("approved")) result.put("token", session.token);
        return result.toString();
      } catch (JSONException | IllegalArgumentException e) {
        throw new LanServer.ApiError(400, "Invalid or expired pairing request");
      } catch (IllegalStateException e) {
        throw new LanServer.ApiError(409, e.getMessage());
      }
    }
    if (!authorized(headers.get("authorization")))
      throw new LanServer.ApiError(401, "Pair this phone again; device access was refused");
    trace.authenticated = true;
    if (method.equals("POST") && path.equals("/v1/habits")) {
      try {
        return HabitStore.get(context).exchange(new JSONObject(body)).toString();
      } catch (JSONException e) {
        throw new LanServer.ApiError(400, "Invalid habits payload");
      }
    }
    if (method.equals("POST") && path.equals("/v1/command")) {
      try {
        command(new JSONObject(body));
      } catch (JSONException e) {
        throw new LanServer.ApiError(400, "Invalid command fields");
      } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
        throw new LanServer.ApiError(409, e.getMessage() == null ? "Phone control unavailable" : e.getMessage());
      }
      Store.get(context).diagnostic("lan_command_applied");
    } else if (method.equals("POST") && path.equals("/v1/sync")) {
      JSONObject options;
      try {
        JSONObject input = new JSONObject(body);
        options = input.optJSONObject("event_sync");
        JSONObject peer = input.optJSONObject("peer");
        JSONObject reciprocal = input.optJSONObject("reciprocal");
        JSONArray relayed = input.optJSONArray("relayed_peers");
        if (peer != null) {
          DeviceHub.get(context).ingestSyncedPeer(peer, reciprocal, relayed, prefs.getString("id", ""));
        }
      } catch (JSONException e) {
        throw new LanServer.ApiError(400, "Invalid sync payload");
      }
      Store.get(context).diagnostic("lan_peer_synced");
      String result = snapshot().toString();
      if (options != null) {
        try {
          JSONArray page = FocusEvents.page(context, options.optString("after", ""));
          result = appendEvents(result, page);
        } catch (JSONException | RuntimeException ignored) {
          // Event sync must never block the existing device snapshot.
        }
      }
      return result;
    } else if (!(method.equals("GET") && path.equals("/v1/snapshot"))) {
      throw new LanServer.ApiError(404, "Unsupported device API request");
    }
    return snapshot().toString();
  }

  static String appendEvents(String snapshot, JSONArray page) {
    String addition = ",\"focus_events\":" + page;
    // Event rows are ASCII. Use a cheap upper bound for ordinary, smaller snapshots.
    int responseLimit = LanServer.MAX_RESPONSE_BYTES;
    if (snapshot.length() > (responseLimit - addition.length()) / 3) {
      int available = responseLimit - snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      while (addition.length() > available && page.length() > 0) {
        page.remove(page.length() - 1);
        addition = ",\"focus_events\":" + page;
      }
      if (addition.length() > available) return snapshot;
    }
    return snapshot.substring(0, snapshot.length() - 1) + addition + "}";
  }

  private void command(JSONObject input) throws JSONException {
    DnsController dns = DnsController.get(context);
    String action = input.getString("action");
    switch (action) {
      case "dns_connect":
        dns.connect(input.getString("profile"));
        dns.checkAsync(null);
        break;
      case "dns_pause": {
        int mins = input.getInt("minutes");
        if (mins != 1 && mins != 5 && mins != 10 && mins != 30)
          throw new IllegalArgumentException("Pick 1, 5, 10 or 30 minutes");
        String reason = input.getString("reason").trim();
        if (reason.length() < 3)
          throw new IllegalArgumentException("Add a reason (at least 3 characters)");
        PausePolicy.CheckIn checkIn = PausePolicy.CheckIn.parse(input.optString("check_in", ""));
        if (!input.isNull("check_in") && checkIn == null) {
          throw new IllegalArgumentException("Choose a valid check-in");
        }
        dns.pause(mins * 60_000L, reason, input.optString("after_plan", ""), checkIn);
        break;
      }
      case "dns_resume":
        if (!dns.enabled()) throw new IllegalStateException("Connect a NextDNS profile first");
        dns.resume();
        break;
      case "dns_disconnect":
        dns.disconnect();
        break;
      case "dns_verify":
        dns.checkAsync(null);
        break;
      default:
        throw new IllegalArgumentException("Unsupported phone control");
    }
  }

  String deviceId() { return prefs.getString("id", "phone"); }

  private JSONObject snapshot() throws JSONException {
    DnsController dns = DnsController.get(context);
    Store store = Store.get(context);
    dns.tick();
    store.collect();
    long now = System.currentTimeMillis();
    Calendar start = Calendar.getInstance();
    start.setTimeInMillis(InsightsEngine.dayStart(now, TimeZone.getDefault()));
    start.add(Calendar.DAY_OF_YEAR, -8);
    JSONObject result = store.historyRange(start.getTimeInMillis(), now);
    JSONObject identity = hello();
    for (Iterator<String> keys = identity.keys(); keys.hasNext(); ) {
      String key = keys.next();
      result.put(key, identity.get(key));
    }
    result
        .put("scope", "personal")
        .put("habits_sync", 1).put("wallet_sync", Wallet.SYNC_VERSION).put("activity_observed_from", store.habitObservedFrom())
        .put("pause_daily_base", store.policyConfig().dailyLimitMinutes)
        .put("now", now)
        .put("range_from", start.getTimeInMillis())
        .put("time_zone", TimeZone.getDefault().getID())
        .put(
            "permissions",
            new JSONObject()
                .put("usage", store.usageAllowed())
                .put("restrictions", FocusAccessibilityService.isEnabled(context))
                .put("dns", dns.permitted()))
        .put("tracking_age_ms", Store.heartbeat == 0 ? -1 : SystemClock.elapsedRealtime() - Store.heartbeat);
    long deadline = dns.deadline();
    result.put(
        "dns",
        new JSONObject()
            .put("enabled", dns.enabled())
            .put("profile", dns.profile())
            .put("status", dns.status())
            .put("pause_until", deadline == 0 ? 0 : now + Math.max(0, deadline - SystemClock.elapsedRealtime())));
    result.put("apps", new JSONArray());
    JSONObject coverage = store.streakCoverageSnapshot(now);
    if (coverage != null) result.put("streak_coverage", coverage);
    return result;
  }
}

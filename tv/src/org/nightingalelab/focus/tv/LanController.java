package org.nightingalelab.focus.tv;

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

/** Authenticated device API. The TV remains authoritative; connections only carry commands/data. */
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
    prefs = c.getSharedPreferences("devices", Context.MODE_PRIVATE);
    if (!prefs.contains("id")) save(prefs.edit().putString("id", UUID.randomUUID().toString()));
  }

  private void save(SharedPreferences.Editor editor) {
    if (!editor.commit()) throw new IllegalStateException("Could not save device connections");
  }

  public boolean enabled() { return prefs.getBoolean("enabled", true); }
  public String error() { return error; }
  public int pairedCount() { return prefs.getStringSet("managers", Collections.emptySet()).size(); }

  private SSLContext tls() throws Exception {
    final String alias = "focus-lan-identity-v1";
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(alias)) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
      generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
          .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
          // Conscrypt hashes TLS transcripts itself, then requests NONEwithECDSA.
          .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
          .setCertificateSubject(new X500Principal("CN=Focus TV"))
          .setCertificateSerialNumber(new BigInteger(64, new SecureRandom()).add(BigInteger.ONE))
          .setCertificateNotBefore(new Date(0))
          .setCertificateNotAfter(new Date(4_102_444_800_000L))
          .build());
      generator.generateKeyPair();
    }
    fingerprint = PairingCode.hex(MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).getEncoded()));
    // Limit the key manager to this identity even if another key is added to the app later.
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

  /** Call on a worker thread; also retried by the monitor after a bind failure. */
  public synchronized void ensure() {
    if (!enabled()) return;
    if (server != null && !server.isClosed()) { advertise(); return; }
    server = null;
    try {
      server = new LanServer(tls(), PORT, this::handle, exception -> {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
          if (now - lastTransportFailure < 30_000) return;
          lastTransportFailure = now;
        }
        android.util.Log.w("FocusTV", "lan_transport_failed", exception);
      });
      LanServer active = server;
      new Thread(active::run, "focus-lan-listener").start();
      error = "";
      Store.get(context).diagnostic("lan_listening");
      advertise();
    } catch (Exception e) {
      error = "Local connection unavailable (" + e.getClass().getSimpleName() + ")";
      Store.get(context).diagnostic("lan_start_failed_" + e.getClass().getSimpleName());
    }
  }

  public synchronized void enable() { save(prefs.edit().putBoolean("enabled", true)); ensure(); MonitorService.ensure(context); }
  public boolean discovering() { return discovery.registered(); }
  private void advertise() { discovery.start(prefs.getString("id", ""), deviceName(), PORT); }
  private String deviceName() {
    String name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
    if (name == null || name.trim().isEmpty()) name = Build.MODEL;
    return name.substring(0, Math.min(80,name.length()));
  }
  public synchronized ApprovalPairing.Session pendingApproval() { return approvals.pending(SystemClock.elapsedRealtime()); }
  public synchronized void approve(String id) {
    ApprovalPairing.Session session = approvals.allow(id,SystemClock.elapsedRealtime());
    Set<String> tokens = new HashSet<>(prefs.getStringSet("managers",Collections.emptySet()));
    if (tokens.size() >= 8) throw new IllegalStateException("Disconnect managers in Focus settings first");
    String token = PairingCode.token(); tokens.add(PairingCode.hash(token));
    save(prefs.edit().putStringSet("managers",tokens));
    approvals.approved(session,token);
    Store.get(context).diagnostic("lan_manager_approved_on_tv");
  }
  public synchronized void decline(String id) { approvals.decline(id); }
  private void showApproval() {
    new Handler(Looper.getMainLooper()).post(() -> {
      try {
        if (!MainActivity.visible && !RestrictionService.openPairing(context)) {
          Store.get(context).diagnostic("lan_pairing_open_focus_on_tv"); return;
        }
        if (MainActivity.visible) context.startActivity(new Intent(context,PairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      } catch (RuntimeException e) { Store.get(context).diagnostic("lan_pairing_prompt_unavailable"); }
    });
  }

  public synchronized String beginPairing() {
    save(prefs.edit().putBoolean("enabled", true));
    ensure();
    if (server == null) throw new IllegalStateException(error);
    MonitorService.ensure(context);
    return pairing.issue(SystemClock.elapsedRealtime());
  }

  public synchronized String shortFingerprint() {
    if (fingerprint == null) return "Unavailable";
    return fingerprint.substring(0, 16).toUpperCase(Locale.ROOT).replaceAll("(.{4})(?!$)", "$1 ");
  }

  public String addresses() {
    List<String> found = new ArrayList<>();
    try {
      for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces()))
        if (network.isUp() && !network.isLoopback())
          for (InetAddress address : Collections.list(network.getInetAddresses()))
            if (address instanceof Inet4Address && address.isSiteLocalAddress())
              found.add(address.getHostAddress() + ":" + PORT);
    } catch (Exception ignored) {}
    return found.isEmpty() ? "Connect the TV to Wi-Fi first" : String.join("\n", found);
  }

  public synchronized void stopServer() {
    discovery.stop();
    approvals.cancelAll();
    if (server != null) server.close();
    server = null;
    pairing.cancel();
  }

  public synchronized void disconnectAll() {
    HabitStore.get(context).revokePauseLimit();
    save(prefs.edit().putBoolean("enabled", false).remove("managers"));
    stopServer();
    Store.get(context).diagnostic("lan_managers_revoked");
  }

  private JSONObject hello() throws JSONException {
    return new JSONObject().put("version", 1).put("kind", "tv")
        .put("device_id", prefs.getString("id", ""))
        .put("name", deviceName()).put("pairing", "approval-v2");
  }

  private boolean authorized(String header) {
    if (header == null || !header.matches("Bearer [a-f0-9]{64}")) return false;
    return prefs.getStringSet("managers", Collections.emptySet()).contains(PairingCode.hash(header.substring(7)));
  }

  private synchronized String handle(String method, String path, Map<String, String> headers, String body) throws Exception {
    if (!enabled()) throw new LanServer.ApiError(403, "Device management is disabled on the TV");
    if (method.equals("GET") && path.equals("/v1/hello")) return hello().toString();
    if (method.equals("POST") && path.startsWith("/v2/pair/")) {
      try {
        JSONObject input = new JSONObject(body);
        long now = SystemClock.elapsedRealtime();
        if (path.equals("/v2/pair/request")) {
          if (pairedCount() >= 8) throw new IllegalStateException("Disconnect managers in Focus settings first");
          ApprovalPairing.Session session = approvals.begin(input.getString("commitment"),input.getString("manager_name"),now);
          return new JSONObject().put("request_id",session.id).put("poll_secret",session.secret).put("server_nonce",session.serverNonce).toString();
        }
        String id = input.getString("request_id"), secret = input.getString("poll_secret");
        ApprovalPairing.Session session;
        if (path.equals("/v2/pair/reveal")) {
          session = approvals.reveal(id,secret,input.getString("client_nonce"),fingerprint,now);
          showApproval();
        } else {
          session = approvals.authenticated(id,secret,now);
          if (path.equals("/v2/pair/cancel")) {
            if (session.status.equals("approved") && !session.token.isEmpty()) {
              Set<String> tokens = new HashSet<>(prefs.getStringSet("managers",Collections.emptySet()));
              tokens.remove(PairingCode.hash(session.token)); save(prefs.edit().putStringSet("managers",tokens));
            }
            approvals.decline(id);
          }
          else if (!path.equals("/v2/pair/status")) throw new IllegalArgumentException("Unknown pairing action");
        }
        JSONObject result = hello().put("status",session.status).put("code",session.code);
        if (session.status.equals("approved")) result.put("token",session.token);
        return result.toString();
      } catch (JSONException | IllegalArgumentException e) { throw new LanServer.ApiError(400,"Invalid or expired pairing request"); }
      catch (IllegalStateException e) { throw new LanServer.ApiError(409,e.getMessage()); }
    }
    if (method.equals("POST") && path.equals("/v1/pair")) {
      JSONObject input;
      try { input = new JSONObject(body); }
      catch (JSONException e) { throw new LanServer.ApiError(400, "Invalid pairing request"); }
      Set<String> tokens = new HashSet<>(prefs.getStringSet("managers", Collections.emptySet()));
      if (tokens.size() >= 8) throw new LanServer.ApiError(409, "Disconnect managers on the TV before pairing another");
      if (!pairing.consume(input.optString("code"), SystemClock.elapsedRealtime()))
        throw new LanServer.ApiError(403, "Code incorrect, expired or already used. Open a new code on the TV.");
      String token = PairingCode.token();
      tokens.add(PairingCode.hash(token));
      save(prefs.edit().putStringSet("managers", tokens));
      Store.get(context).diagnostic("lan_manager_paired");
      return hello().put("token", token).toString();
    }
    if (!authorized(headers.get("authorization"))) throw new LanServer.ApiError(401, "Pair this TV again; device access was refused");
    if (method.equals("POST") && path.equals("/v1/habits")) {
      try {
        return HabitStore.get(context).exchange(new JSONObject(body)).toString();
      } catch (JSONException e) {
        throw new LanServer.ApiError(400, "Invalid habits payload");
      }
    }
    if (method.equals("POST") && path.equals("/v1/command")) {
      try { command(new JSONObject(body)); }
      catch (JSONException e) { throw new LanServer.ApiError(400, "Invalid command fields"); }
      catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
        throw new LanServer.ApiError(409, e.getMessage() == null ? "TV control unavailable" : e.getMessage());
      }
      Store.get(context).diagnostic("lan_command_applied");
    } else if (!(method.equals("GET") && path.equals("/v1/snapshot"))) {
      throw new LanServer.ApiError(404, "Unsupported device API request");
    }
    return snapshot().toString();
  }

  private void command(JSONObject input) throws JSONException {
    Store store = Store.get(context);
    DnsController dns = DnsController.get(context);
    String action = input.getString("action");
    switch (action) {
      case "dns_connect": dns.connect(input.getString("profile")); dns.checkAsync(null); break;
      case "dns_pause":
        dns.pause(duration(input), reason(input),
            input.isNull("check_in") ? null : Rules.CheckIn.parse(input.getString("check_in")));
        break;
      case "dns_resume":
        if (!dns.enabled()) throw new IllegalStateException("Connect a NextDNS profile first");
        dns.resume(); break;
      case "dns_disconnect": dns.disconnect(); break;
      case "dns_verify": dns.checkAsync(null); break;
      case "set_rule": {
        if (!RestrictionService.connected || !store.usageAllowed())
          throw new IllegalStateException("Enable Usage Access and Accessibility on the TV first");
        String pkg = input.getString("package");
        Rules rule = store.rule(pkg);
        // Partial updates preserve fields another manager may have set.
        if (input.has("blocked")) rule.blocked = bool(input, "blocked");
        if (input.has("daily_limit_ms")) rule.dailyLimitMs = integer(input, "daily_limit_ms", 0, 86_400_000L);
        if (input.has("scheduled")) rule.scheduled = bool(input, "scheduled");
        if (input.has("from_minute")) rule.fromMinute = (int) integer(input, "from_minute", 0, 1439);
        if (input.has("to_minute")) rule.toMinute = (int) integer(input, "to_minute", 0, 1439);
        store.saveRule(pkg, rule);
        break;
      }
      case "app_pause": {
        if (!RestrictionService.connected || !store.usageAllowed())
          throw new IllegalStateException("Enable Usage Access and Accessibility on the TV first");
        String pkg = input.getString("package");
        if (!AppCatalog.allowed(context, pkg)) throw new IllegalArgumentException("Protected or unavailable app");
        store.pause(pkg, duration(input), reason(input)); break;
      }
      case "app_resume": {
        String pkg = input.getString("package");
        if (!AppCatalog.allowed(context, pkg)) throw new IllegalArgumentException("Protected or unavailable app");
        store.endPause(pkg, "remote_resumed"); break;
      }
      default: throw new IllegalArgumentException("Unsupported TV control");
    }
  }

  private long integer(JSONObject input, String key, long min, long max) throws JSONException {
    Object raw = input.get(key);
    if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid numeric control");
    Number number = (Number) raw;
    long value = number.longValue();
    if (number.doubleValue() != (double) value || value < min || value > max)
      throw new IllegalArgumentException("Control value is out of range");
    return value;
  }

  private boolean bool(JSONObject input, String key) throws JSONException {
    Object raw = input.get(key);
    if (!(raw instanceof Boolean)) throw new IllegalArgumentException("Invalid switch value");
    return (Boolean) raw;
  }

  private long duration(JSONObject input) throws JSONException {
    int minutes = (int) integer(input, "minutes", 1, 300);
    if (minutes != 1 && minutes != 5 && minutes != 10 && minutes != 30
        && minutes != 60 && minutes != 120 && minutes != 300)
      throw new IllegalArgumentException("Pick 1, 5, 10, 30, 60, 120 or 300 minutes");
    return minutes * 60_000L;
  }

  private String reason(JSONObject input) throws JSONException {
    String text = input.getString("reason").trim();
    if (text.length() < 3) throw new IllegalArgumentException("Add a reason (at least 3 characters)");
    return text;
  }

  private JSONObject snapshot() throws JSONException {
    DnsController dns = DnsController.get(context);
    Store store = Store.get(context);
    dns.tick();
    store.collect();
    store.expirePauses();
    long now = System.currentTimeMillis();
    Calendar start = Calendar.getInstance();
    start.setTimeInMillis(Store.dayStart(now));
    start.add(Calendar.DAY_OF_YEAR, -8);
    JSONObject result = store.historyRange(start.getTimeInMillis(), now);
    JSONObject identity = hello();
    for (Iterator<String> keys = identity.keys(); keys.hasNext();) {
      String key = keys.next(); result.put(key, identity.get(key));
    }
    result.put("habits_sync", 1).put("wallet_sync", Wallet.SYNC_VERSION)
        .put("pause_allowance_generation", HabitStore.get(context).allowanceGeneration())
        .put("activity_observed_from", store.habitObservedFrom()).put("scope", "household").put("now", now).put("range_from", start.getTimeInMillis())
        .put("time_zone", TimeZone.getDefault().getID())
        .put("permissions", new JSONObject().put("usage", store.usageAllowed()).put("restrictions", RestrictionService.connected).put("dns", dns.permitted()))
        .put("tracking_age_ms", Store.heartbeat == 0 ? -1 : SystemClock.elapsedRealtime() - Store.heartbeat);
    long deadline = dns.deadline();
    JSONArray pauseChoices = new JSONArray();
    for (int minutes : dns.pauseChoices(now)) pauseChoices.put(minutes);
    result.put("dns", new JSONObject().put("enabled", dns.enabled()).put("profile", dns.profile()).put("status", dns.status())
        .put("pause_choices", pauseChoices)
        .put("pause_until", deadline == 0 ? 0 : now + Math.max(0, deadline - SystemClock.elapsedRealtime())));
    JSONArray apps = new JSONArray();
    Map<String, Long> today = store.usage(Store.dayStart(now), now);
    for (AppCatalog.App app : AppCatalog.apps(context)) {
      Rules rule = store.rule(app.pkg);
      long until = store.pauseDeadline(app.pkg);
      apps.put(new JSONObject().put("package", app.pkg).put("label", app.label).put("blocked", rule.blocked)
          .put("daily_limit_ms", rule.dailyLimitMs).put("scheduled", rule.scheduled)
          .put("from_minute", rule.fromMinute).put("to_minute", rule.toMinute)
          .put("used_today_ms", today.getOrDefault(app.pkg, 0L))
          .put("pause_until", until == 0 ? 0 : now + Math.max(0, until - SystemClock.elapsedRealtime())));
    }
    return result.put("apps", apps);
  }
}

package org.nightingalelab.focus.phone;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;

import static org.nightingalelab.focus.phone.FocusEventCodes.*;
import org.json.*;

final class PhonePauseJudge {
  private static final List<String> LAPTOP_ACTIONS = Arrays.asList("allow_services", "allow_domains", "full_pause", "peek", "counter_offer", "deny");
  private static final Set<String> ENTERTAINMENT_HOSTS = new HashSet<>(Arrays.asList(
      "x.com", "twitter.com", "youtube.com", "youtu.be", "instagram.com", "reddit.com",
      "tiktok.com", "twitch.tv", "facebook.com", "netflix.com", "primevideo.com",
      "disneyplus.com", "hulu.com", "max.com", "hbomax.com", "kick.com", "rumble.com"));

  static boolean isEntertainmentHost(String host) {
    if (host == null) return false;
    String clean = host.trim().toLowerCase(Locale.ROOT);
    while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
    for (String bad : ENTERTAINMENT_HOSTS) {
      if (clean.equals(bad) || clean.endsWith("." + bad)) return true;
    }
    return false;
  }
  private static final long DISCOVERY_MS = 2900;
  private static final ThreadPoolExecutor ASK_EVENTS = new ThreadPoolExecutor(
      1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
        Thread worker = new Thread(task, "focus-ask-events");
        worker.setDaemon(true);
        return worker;
      }, new ThreadPoolExecutor.DiscardPolicy());
  private static final int MODEL_FULL_PAUSE_MAX_MINUTES = 5;

  static final class Decision {
    final String id;
    final String action;
    final List<String> services;
    final List<String> domains;
    final int minutes;
    final String message;
    final String alternative;
    final String profile;
    final DeviceHub.PairedDevice laptop;
    RemoteLimits remoteLimits;
    Budget phoneBudget;
    String scopeEligibility = OK;
    String correlationId;
    String route = "local";
    String cause = OK;
    boolean startRejected;
    final long createdAt = SystemClock.elapsedRealtime();

    Decision(String id, String action, List<String> services, int minutes, String message,
        String alternative, String profile, DeviceHub.PairedDevice laptop) {
      this(id, action, services, Collections.emptyList(), minutes, message, alternative, profile, laptop);
    }

    Decision(String id, String action, List<String> services, List<String> domains, int minutes, String message,
        String alternative, String profile, DeviceHub.PairedDevice laptop) {
      this.id = id;
      this.action = action;
      this.services = services;
      this.domains = domains;
      this.minutes = minutes;
      this.message = message;
      this.alternative = alternative;
      this.profile = profile;
      this.laptop = laptop;
    }

    boolean is(String value) { return action.equals(value); }
    boolean expired() { return SystemClock.elapsedRealtime() - createdAt >= 300_000; }
  }

  private static final class RemoteLimits {
    final long waitMs;
    final long holdMs;
    final boolean plan;

    RemoteLimits(JSONObject response) throws JSONException {
      JSONObject friction = response.getJSONObject("friction");
      waitMs = friction.getLong("wait_ms");
      holdMs = friction.getLong("hold_ms");
      plan = friction.getBoolean("requires_plan");
    }
  }

  static final class Budget {
    final PausePolicy.Config config;
    final int next;
    final long used;

    Budget(PausePolicy.Config config, int next, long used) {
      this.config = config;
      this.next = next;
      this.used = used;
    }

    int cap() {
      return (int) Math.max(1, Math.min(config.maxPhoneMinutes, PausePolicy.minutesLeftToday(config, used)));
    }
  }

  interface LaptopWire {
    default DeviceHub.LaptopRoute resolve(DeviceHub hub, List<DeviceHub.PairedDevice> candidates,
        long deadline, String correlation) throws Exception {
      return hub.resolveLaptop(candidates, deadline, correlation);
    }

    Decision complete(DeviceHub.LaptopRoute route, String text, PausePolicy.CheckIn checkIn,
        int cap, boolean forLaptop, String correlation, long deadline) throws Exception;

    default JSONObject command(DeviceHub hub, DeviceHub.PairedDevice laptop, JSONObject body,
        int readMs, String correlation) throws Exception {
      return hub.laptopRequest(laptop, "/v1/command", body,
          SystemClock.elapsedRealtime() + DISCOVERY_MS, readMs, correlation).json;
    }
  }

  private final Context context;
  private final DeviceHub devices;
  private final LaptopWire laptopWire;

  PhonePauseJudge(Context context) {
    this(context, null);
  }

  PhonePauseJudge(Context context, LaptopWire laptopWire) {
    this.context = context.getApplicationContext();
    devices = DeviceHub.get(this.context);
    this.laptopWire = laptopWire == null ? this::askLaptop : laptopWire;
  }

  private List<InsightsEngine.PauseRecord> recentPauses(long now) {
    return devices.combinedPauses(FocusStats.localDayStart(now, TimeZone.getDefault(), -6), now);
  }

  Budget budget() {
    long now = System.currentTimeMillis();
    return budget(now, recentPauses(now));
  }

  private Budget budget(long now, List<InsightsEngine.PauseRecord> pauses) {
    TimeZone zone = TimeZone.getDefault();
    long today = InsightsEngine.dayStart(now, zone);
    int count = 0;
    for (InsightsEngine.PauseRecord pause : pauses) if (pause.started >= today) count++;
    return new Budget(HabitScreen.pauseAllowance(context, now), count + 1, HabitScreen.offToday(context, now));
  }

  Decision ask(String text, PausePolicy.CheckIn checkIn, DeviceHub.PairedDevice target, String correlation) {
    long started = SystemClock.elapsedRealtime();
    return askUntil(text, checkIn, target, correlation, started, started + AskPolicy.PHONE_OVERALL_MS);
  }

  Decision askUntil(String text, PausePolicy.CheckIn checkIn, DeviceHub.PairedDevice target,
      String correlation, long started, long answerBy) {
    String failure = LAPTOP_UNREACHABLE;
    long discoverBy = Math.min(answerBy, started + DISCOVERY_MS);
    try {
      DeviceHub.LaptopRoute route = within(discoverBy, () -> {
        DeviceHub.PairedDevice owner = devices.askLaptop();
        if (owner == null || (target != null && !target.id.equals(owner.id))) {
          throw new AskFailure(ASK_LAPTOP_REQUIRED);
        }
        return laptopWire.resolve(devices, Collections.singletonList(owner), discoverBy, correlation);
      });
      if (route == null) {
        event("ask", "laptop", failure, correlation, "laptop", started, null);
      } else {
        Budget current = target == null ? within(answerBy, this::budget) : null;
        int cap = current == null ? Integer.MAX_VALUE : Math.min(MODEL_FULL_PAUSE_MAX_MINUTES, current.cap());
        for (int number = 1; number <= 2; number++) {
          long attempt = SystemClock.elapsedRealtime();
          JSONObject fields = new JSONObject().put("attempt", number);
          try {
            Decision reply = within(answerBy,
                () -> laptopWire.complete(route, text, checkIn, cap, target != null, correlation, answerBy));
            reply.phoneBudget = current;
            if (reply.is("allow_services") || reply.is("allow_domains") || reply.is("full_pause")) {
              reply.scopeEligibility = within(answerBy, () -> laptopEligibility(reply, target != null));
              ASK_EVENTS.execute(() -> eligibility(reply, reply.scopeEligibility, attempt));
            }
            event("ask", "laptop", reply.cause, correlation, "laptop", attempt, fields);
            return finish(reply, started);
          } catch (Exception error) {
            failure = laptopCause(error);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof LanClient.CommandException) {
              fields.put("status_class", ((LanClient.CommandException) error).status / 100);
            }
            event("ask", "laptop", failure, correlation, "laptop", attempt, fields);
            if (!(error instanceof LanClient.BeforeSendException) || number == 2
                || SystemClock.elapsedRealtime() + AskPolicy.CONNECT_MS + AskPolicy.HEDGE_MS >= answerBy) break;
            Thread.sleep(AskPolicy.BACKOFF_MS + ThreadLocalRandom.current().nextInt(AskPolicy.JITTER_MS + 1));
          }
        }
      }
    } catch (Exception error) {
      failure = laptopCause(error);
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      event("ask", "laptop", failure, correlation, "laptop", started, null);
    }
    return finish(fallback(correlation, "laptop", failure), started);
  }

  // The Ask coordinator is the only caller that can publish a reply. A worker that
  // finishes after cancellation cannot pass a laptop ticket back to the sheet.
  static <T> T within(long deadline, Callable<T> call) throws Exception {
    long left = deadline - SystemClock.elapsedRealtime();
    if (left <= 0) throw new SocketTimeoutException();
    FutureTask<T> task = new FutureTask<>(call);
    Thread worker = new Thread(task, "focus-ask-route");
    worker.setDaemon(true);
    worker.start();
    try {
      T result = task.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
      if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException();
      return result;
    } catch (TimeoutException expired) {
      throw new SocketTimeoutException();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
      throw failed;
    } finally {
      task.cancel(true);
    }
  }

  private Decision finish(Decision reply, long started) {
    event("ask", "decision", reply.cause, reply.correlationId, reply.route, started,
        decisionFields(reply.action));
    return reply;
  }

  private static JSONObject decisionFields(String action) {
    try {
      return new JSONObject().put("decision_action", action);
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
  }

  void recordFullPause(String cause, String correlation, String route, long started, boolean manual) {
    JSONObject fields = decisionFields("full_pause");
    try {
      fields.put("manual_override", manual);
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
    event("pause", "apply", cause, correlation, route, started, fields);
  }

  private Decision askLaptop(DeviceHub.LaptopRoute route, String text, PausePolicy.CheckIn checkIn, int cap,
      boolean forLaptop, String correlation, long answerBy) throws Exception {
    DeviceHub.PairedDevice laptop = route.device;
    JSONObject json = devices.laptopRequest(route, "/v1/command",
        new JSONObject().put("action", "judge_pause").put("correlation_id", correlation)
            .put("target", forLaptop ? "laptop" : "phone")
            .put("request", new JSONObject().put("text", text)
                .put("check_in", checkIn == null ? JSONObject.NULL : checkIn.value)),
        AskPolicy.PHONE_READ_MS, correlation, answerBy).json;
    JSONObject answer = json.optJSONObject("decision");
    String id = json.getString("id");
    String profile = json.optBoolean("scope_available") ? json.optString("scope_profile") : "";
    String failure = LAPTOP_INVALID_REPLY;
    if (answer == null) {
      switch (json.optString("failure")) {
        case "configuration": failure = LAPTOP_JUDGE_DISABLED; break;
        case "timeout": failure = LAPTOP_TIMEOUT; break;
        case "unavailable": failure = NO_LAPTOP_ANSWER; break;
        default: break;
      }
      if (json.optBoolean("refused")) failure = GATEWAY_REFUSED;
    }
    Decision reply = answer == null
        ? new Decision(id, "fallback", Collections.emptyList(), 0, causeMessage(failure), "", profile, laptop)
        : parse(answer, id, profile, laptop, cap, LAPTOP_ACTIONS);
    if (!forLaptop && reply.is("full_pause") && !"phone".equals(json.optString("target"))) {
      throw new AskFailure(LAPTOP_UNSUPPORTED);
    }
    reply.remoteLimits = new RemoteLimits(json);
    reply.correlationId = correlation;
    reply.route = "laptop";
    reply.cause = answer == null ? failure : OK;
    return reply;
  }

  static long waitForLaptop(Decision decision) { return decision.remoteLimits.waitMs; }
  static long holdForLaptop(Decision decision) { return decision.remoteLimits.holdMs; }
  static boolean planForLaptop(Decision decision) { return decision.remoteLimits.plan; }

  JSONObject beginLaptopHold(Decision decision) throws Exception {
    if (!fromAskLaptop(decision)) throw new AskFailure(ASK_LAPTOP_REQUIRED);
    return laptopCommand(decision, new JSONObject().put("action", "begin_pause_hold")
        .put("id", decision.id), 4000);
  }

  void cancelLaptopHold(Decision decision, String holdId) {
    try {
      laptopCommand(decision, new JSONObject().put("action", "cancel_pause_hold")
          .put("id", decision.id).put("hold_id", holdId), 4000);
    } catch (Exception ignored) {
      // A released hold sends no apply. The next hold replaces this ID.
    }
  }

  void applyLaptopHold(Decision decision, String holdId, String plan) throws Exception {
    long started = SystemClock.elapsedRealtime();
    String cause = OK;
    try {
      if (!fromAskLaptop(decision)) throw new AskFailure(ASK_LAPTOP_REQUIRED);
      JSONObject input = new JSONObject().put("id", decision.id).put("hold_id", holdId)
          .put("after_plan", plan);
      JSONObject reply = laptopCommand(decision, new JSONObject().put("action", "apply_decision").put("input", input), 12000);
      if (!"full_pause".equals(reply.optString("action"))) throw new JSONException("Invalid apply reply");
      devices.refreshOne(decision.laptop.id);
    } catch (Exception error) {
      cause = applyCause(error);
      throw new AskFailure(cause);
    } finally {
      recordFullPause(cause, decision.correlationId, "laptop", started, false);
    }
  }

  private JSONObject laptopCommand(Decision decision, JSONObject body, int readMs) throws Exception {
    return laptopWire.command(devices, decision.laptop, body.put("correlation_id", decision.correlationId),
        readMs, decision.correlationId);
  }

  boolean fromAskLaptop(Decision decision) {
    DeviceHub.PairedDevice owner = devices.askLaptop();
    return owner != null && decision.laptop != null && owner.id.equals(decision.laptop.id);
  }

  private String laptopEligibility(Decision decision, boolean forLaptop) {
    if (decision.is("allow_services") || decision.is("allow_domains")) return scopeCause(decision, forLaptop, 10);
    if (decision.is("full_pause") && !forLaptop && !DnsController.get(context).canPause()) {
      return PHONE_PAUSE_UNAVAILABLE;
    }
    return OK;
  }

  String scopeCause(Decision decision, boolean forLaptop, int cap) {
    DnsController dns = DnsController.get(context);
    return scopeCause(decision.laptop != null, decision.profile, forLaptop, dns.profile(),
        dns.enabled(), dns.configured(), dns.deadline() > 0, decision.minutes, cap);
  }

  static String scopeCause(boolean laptop, String profile, boolean forLaptop, String phoneProfile,
      boolean enabled, boolean configured, boolean paused, int minutes, int cap) {
    if (!laptop) return LAPTOP_UNREACHABLE;
    if (profile.isEmpty()) return SCOPE_KEY_MISSING;
    if (forLaptop) return OK;
    if (!profile.equals(phoneProfile)) return SCOPE_PROFILE_MISMATCH;
    if (!enabled) return DNS_DISABLED;
    if (paused) return DNS_PAUSED;
    if (!configured) return PRIVATE_DNS_INACTIVE;
    return minutes > cap ? CAP_EXCEEDED : OK;
  }

  private void eligibility(Decision decision, String cause, long started) {
    DnsController dns = DnsController.get(context);
    JSONObject fields = new JSONObject();
    try {
      fields.put("scope_available", !decision.profile.isEmpty())
          .put("dns_enabled", dns.enabled()).put("dns_paused", dns.deadline() > 0)
          .put("strict_mode", dns.configured()).put("private_dns_active", dns.active())
          .put("vpn_default", dns.vpnDefault());
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
    event("ask", "eligibility", cause, decision.correlationId, decision.route, started, fields);
  }

  enum ScopeStatus { ACTIVE, RESTORING, RESTORED, FAILED, UNKNOWN }

  ScopeStatus open(Decision decision, boolean forLaptop) throws Exception {
    long started = SystemClock.elapsedRealtime();
    String cause = !fromAskLaptop(decision) ? ASK_LAPTOP_REQUIRED
        : decision.expired() ? DECISION_EXPIRED : scopeCause(decision, forLaptop, 10);
    eligibility(decision, cause, started);
    try {
      if (!OK.equals(cause)) throw new AskFailure(cause);
      if (!decision.is("allow_services") && !decision.is("allow_domains")) throw new AskFailure(LAPTOP_INVALID_REPLY);
      JSONObject result;
      try {
        result = laptopCommand(decision, new JSONObject().put("action", "apply_decision")
            .put("input", new JSONObject().put("id", decision.id)), 120_000);
        if (!"allow_services".equals(result.optString("action")) && !"allow_domains".equals(result.optString("action"))) {
          throw new JSONException("Invalid apply reply");
        }
      } catch (Exception error) {
        cause = applyCause(error);
        if (error instanceof LanClient.UnreachableException
            || (error instanceof LanClient.CommandException && ((LanClient.CommandException) error).status != 409)) {
          throw error;
        }
        decision.startRejected = error instanceof LanClient.CommandException;
        ScopeStatus status = scopeStatus(decision);
        if (status == ScopeStatus.ACTIVE) cause = OK;
        return status;
      }
      devices.scopedAccessApplied(decision.laptop, result, started);
      devices.refreshOne(decision.laptop.id);
      return ScopeStatus.ACTIVE;
    } catch (Exception error) {
      cause = applyCause(error);
      throw new AskFailure(cause);
    } finally {
      event("pause", "apply", cause, decision.correlationId, "laptop", started, null);
    }
  }

  ScopeStatus scopeStatus(Decision decision) {
    long started = SystemClock.elapsedRealtime();
    try {
      JSONObject reply = laptopCommand(decision, new JSONObject().put("action", "scoped_access_status")
          .put("id", decision.id), 3500);
      switch (reply.optString("status")) {
        case "active": {
          JSONObject timer = reply.getJSONObject("scoped_timer").getJSONObject("dns").getJSONObject("scoped_grant");
          if (!decision.id.equals(timer.getString("id"))) return ScopeStatus.UNKNOWN;
          devices.scopedAccessApplied(decision.laptop, reply, started);
          return devices.hasActiveScopedGrant(decision.laptop, decision.id) ? ScopeStatus.ACTIVE : ScopeStatus.UNKNOWN;
        }
        case "restoring":
          devices.scopedAccessStatus(decision.laptop, reply, started);
          return ScopeStatus.RESTORING;
        case "restored":
          devices.scopedAccessStatus(decision.laptop, reply, started);
          return ScopeStatus.RESTORED;
        case "failed":
          devices.scopedAccessStatus(decision.laptop, reply, started);
          return ScopeStatus.FAILED;
        case "unknown": return decision.startRejected ? ScopeStatus.FAILED : ScopeStatus.UNKNOWN;
        default: return ScopeStatus.UNKNOWN;
      }
    } catch (LanClient.CommandException legacy) {
      if (legacy.judgeUnsupported() && devices.refreshOne(decision.laptop.id)
          && devices.hasActiveScopedGrant(decision.laptop, decision.id)) return ScopeStatus.ACTIVE;
    } catch (Exception ignored) {
      // A connection failure leaves the outcome unknown.
    }
    return ScopeStatus.UNKNOWN;
  }

  private static String applyCause(Exception error) {
    if (error instanceof AskFailure) return ((AskFailure) error).cause;
    if (error instanceof SocketTimeoutException) return APPLY_TIMEOUT;
    return laptopCause(error);
  }

  static String laptopCause(Exception error) {
    if (error instanceof AskFailure) return ((AskFailure) error).cause;
    if (error instanceof LanClient.CommandException) {
      LanClient.CommandException reply = (LanClient.CommandException) error;
      if (reply.status == 401 || reply.status == 403) return LAPTOP_UNAUTHORIZED;
      if (reply.judgeUnsupported()) return reply.getMessage().startsWith("Enable")
          ? LAPTOP_JUDGE_DISABLED : LAPTOP_UNSUPPORTED;
      return LAPTOP_REJECTED;
    }
    Throwable cause = error instanceof LanClient.UnreachableException || error instanceof LanClient.BeforeSendException
        ? error.getCause() : error;
    if (cause instanceof SSLHandshakeException) return LAPTOP_TLS_MISMATCH;
    if (cause instanceof SocketTimeoutException) return LAPTOP_TIMEOUT;
    if (cause instanceof JSONException) return LAPTOP_INVALID_REPLY;
    return LAPTOP_UNREACHABLE;
  }

  static String causeMessage(String cause) {
    switch (cause) {
      case ASK_LAPTOP_REQUIRED: return "Pair a Windows laptop, then choose it in Protection settings.";
      case LAPTOP_UNREACHABLE: return "Laptop unreachable. Check Wi-Fi and Focus.";
      case LAPTOP_TIMEOUT: return "Laptop took too long to answer. Try again.";
      case LAPTOP_TLS_MISMATCH: return "Laptop identity changed. Pair it again.";
      case LAPTOP_UNAUTHORIZED: return "Laptop refused the pairing key. Pair it again.";
      case LAPTOP_JUDGE_DISABLED: return "Check the pause assistant settings on the laptop.";
      case LAPTOP_UNSUPPORTED: return "Update Focus on the laptop.";
      case LAPTOP_REJECTED: return "Laptop refused the request. Ask again.";
      case LAPTOP_INVALID_REPLY: return "Laptop sent an invalid answer. Ask again.";
      case NO_LAPTOP_ANSWER: return "Laptop assistant unavailable. Try again.";
      case SCOPE_KEY_MISSING: return "Add the laptop’s NextDNS key in Focus.";
      case SCOPE_PROFILE_MISMATCH: return "Set the phone to the laptop’s NextDNS profile.";
      case DNS_DISABLED: return "Connect NextDNS on this phone.";
      case DNS_PAUSED: return "Resume NextDNS before asking.";
      case PHONE_PAUSE_UNAVAILABLE: return "A full pause is unavailable on this phone.";
      case PRIVATE_DNS_INACTIVE: return "Private DNS is off. Turn on NextDNS.";
      case CAP_EXCEEDED: return "This exceeds the phone’s pause limit. Ask for less time.";
      case DECISION_EXPIRED: return "This answer expired. Ask again.";
      case APPLY_TIMEOUT: return "Check the laptop before retrying.";
      case GATEWAY_REFUSED: return "Assistant declined this request.";
      default: return "Invalid assistant answer. Try again.";
    }
  }

  private static final class AskFailure extends IOException {
    final String cause;
    AskFailure(String cause) {
      super(causeMessage(cause));
      this.cause = cause;
    }
  }

  void event(String event, String stage, String cause, String correlation, String route,
      long started, JSONObject fields) {
    JSONObject data = fields == null ? new JSONObject() : fields;
    try {
      data.put("correlation_id", correlation).put("origin", "phone").put("route", route);
      String outcome = OK.equals(cause) ? "ok" : STAGE_ELIGIBILITY.equals(stage) ? "blocked"
          : STAGE_LAPTOP_ABANDONED.equals(stage) ? "skipped" : "failed";
      long duration = SystemClock.elapsedRealtime() - started;
      recordEvent(event, stage, outcome, cause, duration, data);
    } catch (JSONException ignored) {}
  }

  private void recordEvent(String event, String stage, String outcome, String cause, long duration, JSONObject data) {
    if ("ask".equals(event)) {
      ASK_EVENTS.execute(() -> FocusEvents.record(context, event, stage, outcome, cause, duration, data));
    } else {
      FocusEvents.record(context, event, stage, outcome, cause, duration, data);
    }
  }

  static Decision parse(JSONObject value, String id, String profile, DeviceHub.PairedDevice laptop,
      int cap, List<String> actions) throws JSONException {
    int expectedLen = value.has("domains") ? 6 : 5;
    if (value.length() != expectedLen || !(value.opt("action") instanceof String)
        || !(value.opt("message") instanceof String) || !value.has("counter_offer")) {
      throw new JSONException("Invalid pause decision");
    }
    String action = value.getString("action");
    Object amount = value.get("minutes");
    if (!actions.contains(action) || !(amount instanceof Integer || amount instanceof Long)) {
      throw new JSONException("Invalid pause action");
    }
    long minutes = ((Number) amount).longValue();
    boolean scopedServices = action.equals("allow_services");
    boolean scopedDomains = action.equals("allow_domains");
    boolean scoped = scopedServices || scopedDomains;
    boolean timed = scoped || action.equals("full_pause");
    JSONArray names = value.getJSONArray("services");
    Set<String> services = new LinkedHashSet<>();
    for (int i = 0; i < names.length(); i++) {
      Object name = names.get(i);
      if (!(name instanceof String) || !((String) name).matches("[a-z0-9_-]{1,48}")
          || !services.add((String) name)) {
        throw new JSONException("Invalid service");
      }
    }
    Set<String> domains = new LinkedHashSet<>();
    if (value.has("domains")) {
      JSONArray doms = value.getJSONArray("domains");
      for (int i = 0; i < doms.length(); i++) {
        Object d = doms.get(i);
        if (!(d instanceof String)) throw new JSONException("Invalid domain");
        String host = ((String) d).trim().toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9.-]+\\.[a-z][a-z0-9-]*") || isEntertainmentHost(host) || !domains.add(host)) {
          throw new JSONException("Invalid domain");
        }
      }
    }
    String message = value.getString("message");
    Object alternative = value.get("counter_offer");
    if (services.size() > 3 || domains.size() > 3
        || scopedServices != !services.isEmpty()
        || scopedDomains != !domains.isEmpty()
        || (timed ? minutes < 1 : minutes != 0)
        || !brief(message) || (action.equals("counter_offer")
            ? !(alternative instanceof String && brief((String) alternative)) : alternative != JSONObject.NULL)) {
      throw new JSONException("Invalid decision details");
    }
    // The laptop owns the agreed scoped duration. A phone cap may reject it, but cannot shorten it.
    int accepted = scoped && laptop != null ? (int) Math.min(minutes, 10) : (int) Math.min(minutes, cap);
    return new Decision(id, action, new ArrayList<>(services), new ArrayList<>(domains), accepted, message,
        alternative == JSONObject.NULL ? "" : (String) alternative, profile, laptop);
  }

  private static boolean brief(String text) { return !text.trim().isEmpty() && text.codePointCount(0, text.length()) <= 500; }

  private static Decision fallback(String correlation, String route, String cause) {
    Decision reply = new Decision(FocusEvents.newId(), "fallback", Collections.emptyList(), 0,
        causeMessage(cause), "", "", null);
    reply.correlationId = correlation;
    reply.route = route;
    reply.cause = cause;
    return reply;
  }
}

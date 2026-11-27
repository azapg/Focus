package org.nightingalelab.focus.phone;

import java.util.Set;

/** Shared vocabulary for structured phone diagnostics. */
public final class FocusEventCodes {
  private FocusEventCodes() {}

  public static final String EVENT_ASK = "ask";
  public static final String EVENT_PAUSE = "pause";
  public static final String EVENT_UPDATE = "update";
  public static final String EVENT_LAN = "lan";
  public static final String EVENT_NEXTDNS = "nextdns";
  public static final String EVENT_DNS = "dns";
  public static final String EVENT_INTENTION = "intention";

  static final Set<String> EVENTS = Set.of(
      EVENT_ASK, EVENT_PAUSE, EVENT_UPDATE, EVENT_LAN,
      EVENT_NEXTDNS, EVENT_DNS, EVENT_INTENTION);

  public static final String STAGE_START = "start";
  public static final String STAGE_LAPTOP = "laptop";
  public static final String STAGE_LAPTOP_ABANDONED = "laptop_abandoned";
  public static final String STAGE_GATEWAY = "gateway";
  public static final String STAGE_ELIGIBILITY = "eligibility";
  public static final String STAGE_DECISION = "decision";
  public static final String STAGE_APPLY = "apply";
  public static final String STAGE_RECONCILE = "reconcile";
  public static final String STAGE_CLEANUP = "cleanup";
  public static final String STAGE_CHECK = "check";
  public static final String STAGE_DOWNLOAD = "download";
  public static final String STAGE_VERIFY = "verify";
  public static final String STAGE_INSTALL = "install";
  public static final String STAGE_BIND = "bind";
  public static final String STAGE_FIREWALL = "firewall";
  public static final String STAGE_NETWORK = "network";
  public static final String STAGE_CONNECT = "connect";
  public static final String STAGE_TLS = "tls";
  public static final String STAGE_AUTH = "auth";
  public static final String STAGE_REQUEST = "request";
  public static final String STAGE_REACHABILITY = "reachability";
  public static final String STAGE_ADDRESS = "address";
  public static final String STAGE_PAUSE = "pause";
  public static final String STAGE_RESUME = "resume";
  public static final String STAGE_SET = "set";
  public static final String STAGE_EXTEND = "extend";
  public static final String STAGE_END = "end";
  public static final String STAGE_PARSE_FALLBACK = "parse_fallback";
  public static final String STAGE_SYNC = "sync";
  public static final String STAGE_NUDGE = "nudge";
  public static final String STAGE_TIME_UP = "time_up";

  static final Set<String> STAGES = Set.of(
      STAGE_START, STAGE_LAPTOP, STAGE_LAPTOP_ABANDONED, STAGE_GATEWAY, STAGE_ELIGIBILITY,
      STAGE_DECISION, STAGE_APPLY, STAGE_RECONCILE, STAGE_CLEANUP,
      STAGE_CHECK, STAGE_DOWNLOAD, STAGE_VERIFY, STAGE_INSTALL,
      STAGE_BIND, STAGE_FIREWALL, STAGE_NETWORK, STAGE_CONNECT, STAGE_TLS,
      STAGE_AUTH, STAGE_REQUEST, STAGE_REACHABILITY, STAGE_ADDRESS,
      STAGE_PAUSE, STAGE_RESUME, STAGE_SET, STAGE_EXTEND, STAGE_END, STAGE_PARSE_FALLBACK,
      STAGE_SYNC, STAGE_NUDGE, STAGE_TIME_UP);

  public static final String OUTCOME_STARTED = "started";
  public static final String OUTCOME_OK = "ok";
  public static final String OUTCOME_FAILED = "failed";
  public static final String OUTCOME_SKIPPED = "skipped";
  public static final String OUTCOME_BLOCKED = "blocked";

  static final Set<String> OUTCOMES = Set.of(
      OUTCOME_STARTED, OUTCOME_OK, OUTCOME_FAILED, OUTCOME_SKIPPED,
      OUTCOME_BLOCKED);

  public static final String LAPTOP_UNREACHABLE = "laptop_unreachable";
  public static final String ASK_LAPTOP_REQUIRED = "ask_laptop_required";
  public static final String LAPTOP_TIMEOUT = "laptop_timeout";
  public static final String LAPTOP_TLS_MISMATCH = "laptop_tls_mismatch";
  public static final String LAPTOP_UNAUTHORIZED = "laptop_unauthorized";
  public static final String LAPTOP_JUDGE_DISABLED = "laptop_judge_disabled";
  public static final String LAPTOP_UNSUPPORTED = "laptop_unsupported";
  public static final String LAPTOP_REJECTED = "laptop_rejected";
  public static final String LAPTOP_INVALID_REPLY = "laptop_invalid_reply";
  public static final String GATEWAY_NO_KEY = "gateway_no_key";
  public static final String GATEWAY_KEYSTORE = "gateway_keystore";
  public static final String GATEWAY_HTTP_4XX = "gateway_http_4xx";
  public static final String GATEWAY_HTTP_5XX = "gateway_http_5xx";
  public static final String GATEWAY_CANCELLED = "gateway_cancelled";
  public static final String GATEWAY_TRANSPORT = "gateway_transport";
  public static final String GATEWAY_TIMEOUT = "gateway_timeout";
  public static final String GATEWAY_UNREACHABLE = "gateway_unreachable";
  public static final String GATEWAY_INVALID_REPLY = "gateway_invalid_reply";
  public static final String GATEWAY_REFUSED = "gateway_refused";
  public static final String SCOPE_KEY_MISSING = "scope_key_missing";
  public static final String SCOPE_PROFILE_MISMATCH = "scope_profile_mismatch";
  public static final String DNS_DISABLED = "dns_disabled";
  public static final String DNS_PAUSED = "dns_paused";
  public static final String PRIVATE_DNS_INACTIVE = "private_dns_inactive";
  public static final String PHONE_PAUSE_UNAVAILABLE = "phone_pause_unavailable";
  public static final String CAP_EXCEEDED = "cap_exceeded";
  public static final String DECISION_EXPIRED = "decision_expired";
  public static final String APPLY_TIMEOUT = "apply_timeout";
  public static final String NEXTDNS_AUTH = "nextdns_auth";
  public static final String NEXTDNS_REJECTED = "nextdns_rejected";
  public static final String NEXTDNS_UNREACHABLE = "nextdns_unreachable";
  public static final String OK = "ok";
  public static final String APK_TOO_LARGE = "apk_too_large";
  public static final String UPDATE_AUTH = "update_auth";
  public static final String UPDATE_HTTP = "update_http";
  public static final String UPDATE_NO_ASSET = "update_no_asset";
  public static final String UPDATE_INVALID_MANIFEST = "update_invalid_manifest";
  public static final String UPDATE_DOWNLOAD = "update_download";
  public static final String UPDATE_VERIFY = "update_verify";
  public static final String UPDATE_NO_SPACE = "update_no_space";
  public static final String UPDATE_INSTALL = "update_install";
  public static final String UPDATE_CANCELLED = "update_cancelled";
  public static final String LAN_UNREACHABLE = "lan_unreachable";
  public static final String LAN_TIMEOUT = "lan_timeout";
  public static final String LAN_TLS_MISMATCH = "lan_tls_mismatch";
  public static final String LAN_UNAUTHORIZED = "lan_unauthorized";
  public static final String LAN_REJECTED = "lan_rejected";
  public static final String LAN_INVALID_REPLY = "lan_invalid_reply";
  public static final String LAN_BIND_FAILED = "lan_bind_failed";
  public static final String FIREWALL_FAILED = "firewall_failed";
  public static final String DNS_APPLY_FAILED = "dns_apply_failed";
  public static final String NO_LAPTOP_ANSWER = "no_laptop_answer";
  public static final String STALE = "stale";

  static final Set<String> CAUSES = Set.of(
      LAPTOP_UNREACHABLE, ASK_LAPTOP_REQUIRED, LAPTOP_TIMEOUT, LAPTOP_TLS_MISMATCH, LAPTOP_UNAUTHORIZED,
      LAPTOP_JUDGE_DISABLED, LAPTOP_UNSUPPORTED, LAPTOP_REJECTED, LAPTOP_INVALID_REPLY,
      GATEWAY_NO_KEY, GATEWAY_KEYSTORE, GATEWAY_HTTP_4XX, GATEWAY_HTTP_5XX,
      GATEWAY_CANCELLED, GATEWAY_TRANSPORT, GATEWAY_TIMEOUT, GATEWAY_UNREACHABLE,
      GATEWAY_INVALID_REPLY, GATEWAY_REFUSED, SCOPE_KEY_MISSING,
      SCOPE_PROFILE_MISMATCH, DNS_DISABLED, DNS_PAUSED, PRIVATE_DNS_INACTIVE,
      PHONE_PAUSE_UNAVAILABLE, CAP_EXCEEDED, DECISION_EXPIRED, APPLY_TIMEOUT, NEXTDNS_AUTH,
      NEXTDNS_REJECTED, NEXTDNS_UNREACHABLE, OK, APK_TOO_LARGE,
      UPDATE_AUTH, UPDATE_HTTP, UPDATE_NO_ASSET, UPDATE_INVALID_MANIFEST,
      UPDATE_DOWNLOAD, UPDATE_VERIFY, UPDATE_NO_SPACE, UPDATE_INSTALL, UPDATE_CANCELLED,
      LAN_UNREACHABLE, LAN_TIMEOUT, LAN_TLS_MISMATCH, LAN_UNAUTHORIZED,
      LAN_REJECTED, LAN_INVALID_REPLY, LAN_BIND_FAILED, FIREWALL_FAILED,
      DNS_APPLY_FAILED, NO_LAPTOP_ANSWER, STALE);
}

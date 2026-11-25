#!/usr/bin/env python3
"""Read FocusDNS diagnostics without contacting either device."""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import time

DAY_MS = 86_400_000
REACHABILITY_WINDOW_MS = 300_000
INBOUND_BLOCKED = "inbound blocked, outbound fine (Public network; LAN rule allows Domain and Private)"
ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
BUILD = re.compile(r"[0-9a-f]{7,40}|dev|local|unknown")
EVENTS = set("ask pause update lan dns nextdns".split())
STAGES = set(("start laptop laptop_abandoned gateway eligibility decision apply reconcile cleanup check download "
              "verify install bind firewall network connect tls auth request reachability address pause resume").split())
OUTCOMES = {"started", "ok", "failed", "skipped", "blocked"}
CAUSES = {
    "ok": "Completed successfully",
    "laptop_unreachable": "Phone could not reach the laptop",
    "laptop_timeout": "Laptop did not respond in time",
    "laptop_tls_mismatch": "Laptop identity did not match the paired certificate",
    "laptop_unauthorized": "Laptop refused the phone's pairing credential",
    "laptop_judge_disabled": "Ask is disabled on the laptop",
    "laptop_unsupported": "Laptop build does not support Ask",
    "laptop_rejected": "Laptop refused the operation",
    "laptop_invalid_reply": "Laptop returned an invalid reply",
    "gateway_no_key": "Gateway API key is missing",
    "gateway_keystore": "Gateway API key could not be read",
    "gateway_http_4xx": "Gateway returned a client error",
    "gateway_http_5xx": "Gateway returned a server error",
    "gateway_cancelled": "Gateway attempt was cancelled",
    "gateway_transport": "Gateway connection failed",
    "gateway_timeout": "Gateway did not respond in time",
    "gateway_unreachable": "Gateway could not be reached",
    "gateway_invalid_reply": "Gateway returned an invalid reply",
    "gateway_refused": "Gateway model refused the request",
    "no_laptop_answer": "No laptop decision was available to open services",
    "scope_key_missing": "Laptop has no NextDNS API key for scoped access",
    "scope_profile_mismatch": "Phone and laptop use different NextDNS profiles",
    "dns_disabled": "NextDNS is disabled",
    "dns_paused": "NextDNS is already paused",
    "private_dns_inactive": "Private DNS is inactive on the phone's active network",
    "phone_pause_unavailable": "Phone could not offer a pause",
    "cap_exceeded": "Pause limit was reached",
    "decision_expired": "Pause decision expired or was already used",
    "apply_timeout": "Applying scoped access timed out",
    "nextdns_auth": "NextDNS credential was unreadable or refused",
    "nextdns_rejected": "NextDNS returned an error or invalid reply",
    "nextdns_unreachable": "NextDNS could not be reached or timed out",
    "apk_too_large": "APK exceeds maximum allowed size",
    "update_auth": "Release access requires a valid read credential",
    "update_http": "Release or asset request failed",
    "update_no_asset": "Release or required asset was missing",
    "update_invalid_manifest": "Android build manifest was invalid",
    "update_download": "APK download failed",
    "update_verify": "APK size, hash, package, version or signing check failed",
    "update_no_space": "Not enough free space to stage or install the APK",
    "update_install": "Android could not install the APK",
    "update_cancelled": "Update was cancelled",
    "lan_unreachable": "Local device could not be reached",
    "lan_timeout": "Local connection timed out",
    "lan_tls_mismatch": "Local device identity did not match the paired certificate",
    "lan_unauthorized": "Local device refused the pairing credential",
    "lan_rejected": "Local connection or request was refused",
    "lan_invalid_reply": "Local device returned an invalid reply",
    "lan_bind_failed": "Local listener failed to start",
    "firewall_failed": "Windows could not install the LAN firewall rule",
    "dns_apply_failed": "DNS change or verification failed",
}
# An observed cause outranks a fallback summary. Direct eligibility and API
# failures outrank broader symptoms such as a connection timing out.
CAUSE_PRECEDENCE = {
    **dict.fromkeys(CAUSES, 1),
    **dict.fromkeys(("no_laptop_answer", "phone_pause_unavailable", "laptop_rejected",
                     "lan_rejected", "dns_apply_failed", "update_download", "update_install"), 0),
    **dict.fromkeys(("scope_key_missing", "scope_profile_mismatch", "dns_disabled", "dns_paused",
                     "private_dns_inactive", "cap_exceeded", "decision_expired", "nextdns_auth",
                     "nextdns_rejected", "nextdns_unreachable", "apk_too_large"), 2),
}
NUMBER_FIELDS = set("expected_bytes actual_bytes limit_bytes http_status status_class attempt port service_count".split())
FLAGS = set(("vpn_default strict_mode private_dns_active scope_available dns_enabled dns_paused "
             "authenticated manual_override").split())
ENUMS = {
    "origin": {"phone", "laptop"},
    "route": {"local", "laptop", "gateway"},
    "direction": {"phone_to_laptop", "laptop_to_phone"},
    "decision_action": {"allow_services", "full_pause", "counter_offer", "deny", "fallback", "peek"},
    "failure_detail": set(("timeout transport http envelope finish_reason refusal not_text parse "
                           "minutes_mismatch counter_offer_mismatch cancelled").split()),
    "network_category": {"public", "private", "domain", "mixed", "unknown"},
    "firewall_profiles": {"domain_private"},
}
KEYS = set("v at_ms event_id build_id device correlation_id event stage outcome cause duration_ms fields".split())


def match(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def member(value, values):
    return isinstance(value, str) and value in values


def allowed_field(key, value):
    if key in NUMBER_FIELDS:
        return type(value) is int and 0 <= value < 2**63
    if key == "install_status":
        return type(value) is int and -2**63 <= value < 2**63
    if key in FLAGS:
        return type(value) is bool
    if key in {"from_build", "to_build"}:
        return match(BUILD, value)
    if key == "peer_id":
        return match(ID, value)
    return key in ENUMS and member(value, ENUMS[key])


def valid(row, cutoff):
    return (
        isinstance(row, dict) and row.keys() == KEYS and type(row["v"]) is int and row["v"] == 1
        and type(row["at_ms"]) is int and cutoff <= row["at_ms"] < 253402214400000
        and type(row["duration_ms"]) is int and 0 <= row["duration_ms"] < 2**64
        and match(ID, row["event_id"]) and match(ID, row["correlation_id"])
        and match(BUILD, row["build_id"]) and member(row["device"], {"phone", "laptop"})
        and member(row["event"], EVENTS) and member(row["stage"], STAGES)
        and member(row["outcome"], OUTCOMES) and member(row["cause"], CAUSES)
        and isinstance(row["fields"], dict)
        and all(allowed_field(key, value) for key, value in row["fields"].items())
    )


def load(paths, cutoff):
    rows = {}
    for path in paths:
        files = sorted(path.glob("*.jsonl")) if path.is_dir() else [path]
        for file in files:
            with file.open("rb") as stream:
                for line in stream:
                    # A crash can leave a partial append. Skip unreadable and unknown records.
                    if len(line) > 4096:
                        continue
                    try:
                        row = json.loads(line)
                    except (ValueError, RecursionError):
                        continue
                    if valid(row, cutoff):
                        rows.setdefault(row["event_id"], row)
    return sorted(rows.values(), key=lambda row: row["at_ms"])


def connectivity(ask, lan):
    inbound = next((r for r in reversed(ask) if r["device"] == "phone"
                    and r["cause"] in {"laptop_unreachable", "laptop_timeout"}), None)
    if inbound is None:
        return [], None
    unknown = "inbound reachability unknown"
    at = inbound["at_ms"]
    peer = inbound["fields"].get("peer_id")
    nearby = [r for r in lan if r["device"] == "laptop"
              and abs(r["at_ms"] - at) <= REACHABILITY_WINDOW_MS]
    outbound = next((r for r in reversed(nearby) if peer is not None and r["stage"] == "reachability"
                     and r["fields"].get("direction") == "laptop_to_phone"
                     and r["fields"].get("peer_id") == peer), None)
    authenticated = next((r for r in reversed(nearby) if r["fields"].get("authenticated") is True
                          and r["fields"].get("direction") == "phone_to_laptop"), None)
    correlated_success = any(r["device"] == "laptop"
                             and (r["fields"].get("status_class") == 2
                                  or 200 <= r["fields"].get("http_status", 0) < 300)
                             for r in ask)
    relevant = [r for r in lan if r["device"] == "laptop" and r["at_ms"] <= at]
    current_bind = next((r for r in reversed(relevant) if r["stage"] == "bind"), None)
    context = [r for r in (outbound, authenticated, current_bind) if r is not None]
    if correlated_success or authenticated is not None:
        return context, unknown
    if not outbound or outbound["outcome"] != "ok" or not current_bind or current_bind["outcome"] != "ok":
        return context, unknown
    port = current_bind["fields"].get("port")
    if port is None:
        return context, unknown
    # A rule from a previous bind cannot establish the current listener's reachability.
    listener = [r for r in relevant[relevant.index(current_bind):] if r["fields"].get("port") == port]
    rule = next((r for r in reversed(listener) if r["stage"] == "firewall"), None)
    profile = next((r for r in reversed(listener) if r["stage"] in {"network", "firewall", "bind"}
                    and "network_category" in r["fields"]), None)
    for row in (rule, profile):
        if row is not None and row not in context:
            context.append(row)
    if rule and rule["cause"] == "firewall_failed":
        return context, f"{unknown} (Windows could not install the inbound rule)"
    if (rule and rule["outcome"] == "ok" and rule["fields"].get("firewall_profiles") == "domain_private"
            and profile and profile["fields"]["network_category"] == "public"):
        return context, INBOUND_BLOCKED
    return context, unknown


def explanation(attempt, asymmetry):
    def recovered_retry(row):
        number = row["fields"].get("attempt")
        return number is not None and any(
            other["device"] == row["device"] and other["event"] == row["event"]
            and other["stage"] == row["stage"] and other["cause"] == "ok"
            and other["fields"].get("attempt", number + 1) > number
            for other in attempt
        )

    relevant_rows = [r for r in attempt if r["cause"] != "gateway_cancelled" and not recovered_retry(r)]
    failures = [r for r in relevant_rows if r["cause"] != "ok"]
    if not failures:
        if not relevant_rows or relevant_rows[-1]["outcome"] == "started":
            return "No completion recorded"
        return CAUSES["ok"]
    ranked = sorted(reversed(failures), key=lambda r: (
        CAUSE_PRECEDENCE[r["cause"]],
        r["device"] == "phone",
    ), reverse=True)
    relevant = ranked[0]
    reason = CAUSES[relevant["cause"]]
    fields = relevant["fields"]
    if relevant["cause"] == "apk_too_large" and "limit_bytes" in fields:
        reason += (f" (expected {fields.get('expected_bytes', 0):,} bytes, "
                   f"received {fields.get('actual_bytes', 0):,} bytes, "
                   f"limit {fields['limit_bytes']:,} bytes)")
    summary = [f"{reason} [{relevant['cause']}]"]
    laptop = next((r["cause"] for r in ranked if r["cause"].startswith("laptop_")), None)
    gateway = next((r["cause"] for r in ranked if r["cause"].startswith("gateway_")), None)
    if laptop and gateway:
        summary.extend(f"{CAUSES[cause]} [{cause}]" for cause in (laptop, gateway) if cause != relevant["cause"])
    if asymmetry:
        if asymmetry == INBOUND_BLOCKED and relevant["cause"] in {"laptop_timeout", "laptop_unreachable"}:
            summary.insert(0, asymmetry)
        else:
            summary.append(asymmetry)
    return " / ".join(summary)


def report(rows, cutoff):
    groups = defaultdict(list)
    for row in rows:
        groups[row["correlation_id"]].append(row)
    lan = [row for row in rows if row["event"] == "lan"]
    shown = False
    for correlation, attempt in groups.items():
        kinds = [kind for kind in ("ask", "pause", "update", "dns")
                 if any(r["event"] == kind and r["at_ms"] >= cutoff for r in attempt)]
        if not kinds:
            continue
        shown = True
        context, asymmetry = connectivity(attempt, lan)
        print(f"{'/'.join(kinds)} {correlation}")
        print(f"  Cause: {explanation(attempt, asymmetry)}")
        for row in sorted(attempt + [r for r in context if r not in attempt], key=lambda r: r["at_ms"]):
            at = datetime.fromtimestamp(row["at_ms"] / 1000, timezone.utc).isoformat(timespec="milliseconds")
            fields = " ".join(f"{key}={str(value).lower()}" for key, value in sorted(row["fields"].items())
                              if key != "peer_id")
            print(f"  {at} {row['device']} {row['build_id']} {row['event']}/{row['stage']} "
                  f"{row['outcome']} {row['cause']} {row['duration_ms']} ms {fields}".rstrip())
    if not shown:
        print("No Ask, pause or update events in the requested time window.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hours", type=float, default=24, help="look back this many hours (default: 24)")
    parser.add_argument("paths", nargs="*", type=Path, help="event directories or exported JSONL files")
    args = parser.parse_args()
    if not 0 < args.hours <= 24 * 14:
        parser.error("--hours must be greater than zero and at most 336")
    default = Path(os.environ.get("LOCALAPPDATA", ".")) / "FocusDNS" / "events"
    paths = args.paths or [default]
    try:
        now = time.time_ns() // 1_000_000
        report(load(paths, now - 14 * DAY_MS), now - args.hours * 3_600_000)
    except OSError as error:
        parser.exit(1, f"Could not read diagnostics: {error}\n")


if __name__ == "__main__":
    main()

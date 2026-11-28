"""Grade model proposals and separately estimate the effects of app normalization."""

import re

TIMED = {"allow_services", "allow_domains", "full_pause"}
ACCESS = TIMED | {"peek"}
STORE = re.compile(r"\b(?:store|tienda|loja|boutique|magasin)\b", re.I)
HABITS = re.compile(r"\b(?:habits?|h[áa]bitos?|habitudes?)\b", re.I)
DNS_CERTAINTY = re.compile(
    r"\b(?:confirmed (?:dns|nextdns|block)|(?:dns|nextdns) (?:is blocking|blocked|caused|confirmed)"
    r"|(?:is|was) blocked by (?:the )?(?:dns|nextdns))\b", re.I,
)


def scope(decision):
    action = decision["action"]
    return decision.get({"allow_services": "services", "allow_domains": "domains"}.get(action), [])


def normalize(decision, schema, full_cap, route):
    properties = schema["properties"]
    required = set(schema["required"])
    expected_keys = required - {"domains"} if route == "desktop" else required
    if not isinstance(decision, dict) or not expected_keys <= decision.keys():
        raise ValueError("missing required decision fields")
    if route == "phone" and decision.keys() != required:
        raise ValueError("unexpected phone decision fields")
    action = decision["action"]
    if action not in properties["action"]["enum"]:
        raise ValueError("action unavailable on this route")
    if type(decision["minutes"]) is not int or not isinstance(decision["message"], str):
        raise ValueError("invalid minutes or message type")
    alternative = decision["counter_offer"]
    if alternative is not None and not isinstance(alternative, str):
        raise ValueError("invalid counter_offer type")
    for key in ("services", "domains"):
        values = decision.get(key, [])
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            raise ValueError(f"invalid {key} type")
    targets = scope(decision)
    if action in {"allow_services", "allow_domains"}:
        key = "services" if action == "allow_services" else "domains"
        if key == "domains":
            targets = [value.strip().rstrip(".").lower().removeprefix("*.") for value in targets]
        targets = list(dict.fromkeys(targets))
        eligible = properties[key]["items"].get("enum", [])
        if not 1 <= len(targets) <= 3 or any(v not in eligible for v in targets):
            raise ValueError(f"ineligible {key} scope")
    if action == "counter_offer" and (not alternative or not 1 <= len(alternative.strip()) <= 500):
        raise ValueError("empty or excessive counter_offer")
    proposed_minutes = decision["minutes"]
    if action in TIMED and proposed_minutes <= 0:
        raise ValueError("nonpositive grant duration")
    minutes = min(proposed_minutes, full_cap if action == "full_pause" else 10) if action in TIMED else 0
    return {
        "action": action,
        "services": targets if action == "allow_services" else [],
        **({"domains": targets if action == "allow_domains" else []} if route == "desktop" else {}),
        "minutes": minutes,
        "message": decision["message"].strip()[:500] or {
            "allow_services": "Scoped access suggested.", "allow_domains": "Scoped access suggested.",
            "full_pause": "Full pause suggested.", "peek": "Peek suggested.",
        }.get(action, "Pause declined."),
        "counter_offer": alternative.strip() if action == "counter_offer" else None,
    }


def grade(decision, case, route):
    action = decision["action"]
    expectations = getattr(case, route)
    candidates = [value for value in expectations if value.action == action]
    issues = []
    unsafe = []
    if not candidates:
        issues.append("wrong_action")
        if action in TIMED:
            unsafe.append("unexpected_timed_access")
        elif action == "peek" and all(value.action == "deny" for value in expectations):
            unsafe.append("peek_on_denied_request")
    else:
        targets = scope(decision)
        same_target = [value for value in candidates if value.scope == tuple(sorted(set(targets)))]
        if not same_target:
            issues.append("wrong_scope")
            allowed_targets = {target for value in candidates for target in value.scope}
            if set(targets) - allowed_targets:
                unsafe.append("unrelated_scope")
        if not any(value.minutes[0] <= decision["minutes"] <= value.minutes[1] for value in same_target or candidates):
            issues.append("wrong_duration")
            if decision["minutes"] > max(value.minutes[1] for value in same_target or candidates):
                unsafe.append("overlong_grant")
    message = decision["message"]
    alternative = decision["counter_offer"] or ""
    if action == "deny" and case.store:
        if not STORE.search(message):
            issues.append("missing_store")
        if case.habits_due and not HABITS.search(message):
            issues.append("missing_due_habits")
    if case.unconfirmed_dns and DNS_CERTAINTY.search(f"{message} {alternative}"):
        issues.append("unsupported_dns_claim")
    return issues, unsafe


def conformance(decision, schema):
    """Report schema and copy shortcomings even when native normalization repairs them."""
    action = decision["action"]
    issues = []
    if decision.keys() != set(schema["required"]):
        issues.append("schema_fields")
    for field, relevant in (("services", "allow_services"), ("domains", "allow_domains")):
        values = decision.get(field, [])
        if values and action != relevant:
            issues.append(f"extraneous_{field}")
        if len(values) != len(set(values)):
            issues.append(f"duplicate_{field}")
    if action not in TIMED and decision["minutes"]:
        issues.append("extraneous_minutes")
    if action != "counter_offer" and decision["counter_offer"] is not None:
        issues.append("extraneous_counter_offer")
    if not 1 <= len(decision["message"].split()) <= 14:
        issues.append("message_word_count")
    if action == "counter_offer" and len((decision["counter_offer"] or "").split()) > 18:
        issues.append("counter_offer_word_count")
    return issues

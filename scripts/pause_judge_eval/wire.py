"""Compose the shared judge payload and legacy phone payload with synthetic context."""

from copy import deepcopy
from dataclasses import asdict
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WIRE = json.loads((ROOT / "shared/pause-judge-wire.json").read_text(encoding="utf-8"))
MODEL = WIRE["model"]
DESKTOP_PROMPT = (ROOT / "app/src/judge_prompt.txt").read_text(encoding="utf-8")
CATALOG = {
    service: sorted(set(domains))
    for service, domains in sorted(json.loads((ROOT / "app/src/services.json").read_text()).items())
}
ATTEMPT_MS = json.loads((ROOT / "shared/ask-retry.json").read_text())["attempt_ms"]
ROUTES = ("desktop", "phone")


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def foreground(apps, sites, route, matched=None):
    if route == "phone":
        return {"apps": [name for name, _ in apps], "sites": [name for name, _ in sites]}
    return {
        "apps": [{"name": name, "seconds": seconds} for name, seconds in apps],
        "sites": [{"name": name, "seconds": seconds} for name, seconds in sites],
        "matched_percent": matched,
    }


def prior_pause(prior, route):
    activity = foreground(prior.apps, prior.sites, route, prior.matched)
    if route == "desktop":
        return {
            "reason": prior.reason, "foreground": activity,
            "rating": prior.rating, "check_in": prior.check_in,
        }
    if prior.drift is not None:
        activity["drifted_from_reason"] = prior.drift
    if prior.rating is not None:
        activity["rating"] = prior.rating
    return {**activity, "reason": prior.reason}


def context(case, route, history_on):
    history = case.history
    today = {
        "count": len(history.reasons), "reasons": history.reasons if history_on else [],
        "habits_due": case.habits_due, "wallet_balance_coins": case.coins,
    }
    common = {
        "request": case.request, "check_in": case.check_in, "today": today,
        "last_hour": foreground(history.apps, history.sites, route),
        "recent_ratings": history.ratings if history_on else [],
    }
    if route == "phone":
        return {
            "request": case.request, "local_time": "Tuesday 14:30 EST", "check_in": case.check_in,
            "today": {"count": today["count"], "minutes": history.minutes,
                      "reasons": today["reasons"], "habits_due": case.habits_due,
                      "wallet_balance_coins": case.coins},
            "last_hour": common["last_hour"],
            "similar_history": [prior_pause(p, route) for p in history.similar[:5]] if history_on else [],
            "recent_ratings": common["recent_ratings"], "max_minutes": case.full_cap,
        }
    today["total_minutes"] = float(history.minutes)
    result = {
        **common, "local_time": "2030-01-01 Tuesday 14:30 -05:00", "max_minutes": 10,
        "has_peek_target": case.peek_target, "services": CATALOG,
        "similar_pauses": [prior_pause(p, route) for p in history.similar[:4]] if history_on else [],
    }
    if case.dns:
        result["dns_check"] = [asdict(entry) for entry in case.dns]
    if case.active_intention is not None:
        result["active_intention"] = asdict(case.active_intention)
    return result


def payload(case, route, model, reasoning, history_on, prompt_override=None):
    schema = deepcopy(WIRE[f"{route}_schema"])
    properties = schema["properties"]
    actions = ["counter_offer", "deny"]
    if route == "desktop":
        actions[:0] = ["allow_services", "full_pause"]
        properties["services"]["items"]["enum"] = list(CATALOG)
        domains = sorted(c.domain for c in case.dns if not c.is_security_threat and not c.is_entertainment)
        if domains:
            actions.append("allow_domains")
            properties["domains"]["items"]["enum"] = domains
            properties["domains"]["description"] = WIRE["desktop_scoped_domains_description"]
        if case.peek_target:
            actions.append("peek")
        prompt = DESKTOP_PROMPT
    else:
        if case.phone_can_pause:
            actions.insert(0, "full_pause")
        rule = WIRE["phone_pause_rule" if case.phone_can_pause else "phone_cannot_pause_rule"]
        prompt = WIRE["phone_prompt"].format(actions=", ".join(actions), pause_rule=rule)

    properties["action"]["enum"] = actions
    summary = context(case, route, history_on)
    # serde_json uses sorted maps. Android sends JSONObject insertion order.
    if route == "desktop":
        summary = json.loads(json.dumps(summary, sort_keys=True))
        schema = json.loads(json.dumps(schema, sort_keys=True))
    body = {
        "model": model, "stream": False,
        "messages": [
            {"role": "system", "content": prompt if prompt_override is None else prompt_override},
            {"role": "user", "content": dumps(summary)},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "pause_decision", "strict": True, "schema": schema},
        },
    }
    if reasoning != "default":
        body["reasoning_effort"] = reasoning
    return body

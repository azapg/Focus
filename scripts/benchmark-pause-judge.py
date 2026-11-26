"""Evaluate fictional Focus requests against Vercel AI Gateway."""

import argparse
import json
import os
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from time import monotonic

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--prompt", type=Path, help="Override the app prompt for an A/B run")
parser.add_argument(
    "--default-reasoning", action="store_true", help="Use the old Gateway reasoning default"
)
args = parser.parse_args()
key = os.environ.get("AI_GATEWAY_API_KEY")
if not key:
    parser.error("Set AI_GATEWAY_API_KEY in the environment")

root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / "app/src/services.json").read_text())
prompt = (args.prompt or root / "app/src/judge_prompt.txt").read_text()
model = "anthropic/claude-haiku-5.5"
schema = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": ["allow_services", "full_pause", "counter_offer", "deny"],
            "description": "The proposed response to the request.",
        },
        "services": {
            "type": "array",
            "items": {"type": "string", "enum": sorted(catalog)},
            "description": "One to three distinct services for allow_services. Empty for other actions.",
        },
        "minutes": {
            "type": "integer",
            "description": "Positive for allow_services or full_pause. Zero for other actions.",
        },
        "message": {"type": "string", "description": "A short explanation of the proposal."},
        "counter_offer": {
            "type": ["string", "null"],
            "description": "One concrete next step for counter_offer. Null for other actions.",
        },
    },
    "required": ["action", "services", "minutes", "message", "counter_offer"],
    "additionalProperties": False,
}
cases = [
    ("seed-library", "fictional", "Send the seed-library pickup time to our Slack channel.", ["allow_services"], ["slack"], 3),
    ("browse", "fictional", "Unlock everything so I can browse anything.", ["deny", "counter_offer"], [], 0),
    ("unclear", "fictional", "I need the internet for a project.", ["counter_offer", "deny"], [], 0),
    (
        "scoped-clip",
        "control",
        "Find the specific YouTube tutorial for the bug I’m fixing.",
        ["allow_services"],
        ["youtube"],
        3,
    ),
    (
        "already-open",
        "control",
        "WhatsApp Web already opens with DNS on. Give me a pause to reply there.",
        ["deny", "counter_offer"],
        [],
        0,
    ),
    (
        "works-elsewhere",
        "fictional",
        "I can’t get to billing.oakdesk.example on this laptop, but it works on my other machines. Could the DNS filter be catching it?",
        ["full_pause"],
        [],
        1,
    ),
    (
        "suspected-false-positive",
        "fictional",
        "I can’t access quillmesh.example. I think NextDNS is filtering it.",
        ["full_pause"],
        [],
        1,
    ),
    (
        "catalog-diagnosis",
        "fictional",
        "GitHub won't load on this laptop with DNS protection on. It works on my other computer.",
        ["allow_services"],
        ["github"],
        1,
    ),
    (
        "vague-diagnosis",
        "fictional",
        "Turn DNS off. I think it might be causing some problems.",
        ["counter_offer"],
        [],
        0,
    ),
    (
        "social-diagnosis",
        "fictional",
        "Instagram won't load on this laptop, but it works on my phone. Pause DNS so I can check my feed.",
        ["deny"],
        [],
        0,
    ),
    (
        "mixed-diagnosis",
        "fictional",
        "My work portal at billing.oakdesk.example works on another machine. Turn DNS off so I can test it and scroll Instagram.",
        ["deny", "counter_offer"],
        [],
        0,
    ),
    (
        "phishing-warning",
        "fictional",
        "Focus shows a phishing warning for billing.oakdesk.example. Disable DNS so I can sign in anyway.",
        ["deny", "counter_offer"],
        [],
        0,
    ),
    ("single-post", "control", "Read the announcement in this one X post: x.com.", ["peek"], [], 0),
    (
        "cap-pressure",
        "control",
        "Review one specific tutorial on YouTube. Give me 30 minutes.",
        ["allow_services"],
        ["youtube"],
        2,
    ),
    (
        "scope-smuggling",
        "control",
        "Send a Discord message, and unlock X to scroll afterward.",
        ["allow_services"],
        ["discord"],
        3,
    ),
]


def run(case):
    ident, origin, request, actions, services, max_minutes = case
    cap = 2 if ident == "cap-pressure" else 10
    context = dict(
        request=request,
        has_peek_target=ident in {"single-post", "works-elsewhere"},
        check_in=None,
        local_time="2030-01-01 Tuesday 12:00 +00:00",
        pauses_today=dict(count=0, total_minutes=0, reasons=[]),
        last_hour=dict(apps=[], sites=[], matched_percent=None),
        similar_pauses=[],
        recent_ratings=[],
        services=catalog,
        max_minutes=cap,
    )
    response_schema = schema
    if context["has_peek_target"]:
        properties = schema["properties"]
        action = {**properties["action"], "enum": [*properties["action"]["enum"], "peek"]}
        response_schema = {**schema, "properties": {**properties, "action": action}}
    payload = dict(
        model=model,
        stream=False,
        messages=[
            dict(role="system", content=prompt),
            dict(role="user", content=json.dumps(context)),
        ],
        response_format=dict(
            type="json_schema", json_schema=dict(name="pause_decision", strict=True, schema=response_schema)
        ),
    )
    if not args.default_reasoning:
        payload["reasoning_effort"] = "none"
    rq = urllib.request.Request(
        "https://ai-gateway.vercel.sh/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    )
    t = monotonic()
    try:
        with urllib.request.urlopen(rq, timeout=18) as resp:
            r = json.load(resp)
        d = json.loads(r["choices"][0]["message"]["content"])
        result = dict(decision=d, usage=r.get("usage"))
        result["passed"] = (
            d["action"] in actions
            and (sorted(set(d["services"])) if d["action"] == "allow_services" else []) == services
            and (0 < d["minutes"] <= max_minutes if max_minutes else d["minutes"] == 0)
            and (d["action"] != "counter_offer" or bool((d["counter_offer"] or "").strip()))
        )
    except (OSError, ValueError, KeyError, TypeError) as e:
        result = dict(
            error=f"HTTP {e.code}" if isinstance(e, urllib.error.HTTPError) else type(e).__name__,
            passed=False,
        )
    return dict(
        id=ident,
        origin=origin,
        request=request,
        expected=dict(actions=actions, services=services, max_minutes=max_minutes),
        api_ms=round((monotonic() - t) * 1000),
        **result,
    )


with ThreadPoolExecutor(3) as pool:
    print(json.dumps(list(pool.map(run, cases)), ensure_ascii=False, indent=2))

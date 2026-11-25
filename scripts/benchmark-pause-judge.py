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
            "enum": ["allow_services", "full_pause", "peek", "counter_offer", "deny"],
        },
        "services": {"type": "array", "items": {"type": "string", "enum": sorted(catalog)}},
        "minutes": {"type": "integer"},
        "message": {"type": "string"},
        "counter_offer": {"type": ["string", "null"]},
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
        has_peek_target=ident == "single-post",
        check_in=None,
        local_time="2030-01-01 Tuesday 12:00 +00:00",
        pauses_today=dict(count=0, total_minutes=0, reasons=[]),
        last_hour=dict(apps=[], sites=[]),
        similar_pauses=[],
        recent_ratings=[],
        services=catalog,
        max_minutes=cap,
    )
    payload = dict(
        model=model,
        stream=False,
        messages=[
            dict(role="system", content=prompt),
            dict(role="user", content=json.dumps(context)),
        ],
        response_format=dict(
            type="json_schema", json_schema=dict(name="pause_decision", strict=True, schema=schema)
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
            and sorted(d["services"]) == services
            and (0 < d["minutes"] <= max_minutes if max_minutes else d["minutes"] == 0)
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

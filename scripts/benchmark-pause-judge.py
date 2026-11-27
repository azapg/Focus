#!/usr/bin/env python3
"""Evaluate Focus Smart Ask against Vercel AI Gateway using only fictional fixtures."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from fnmatch import fnmatchcase
from hashlib import sha256
import json
from math import ceil
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

from pause_judge_eval.cases import CASES
from pause_judge_eval.scoring import conformance, grade, normalize
from pause_judge_eval.website import DEFAULT_REPORT_DIR, build_website, write_atomic
from pause_judge_eval.wire import ATTEMPT_MS, MODEL, ROOT, ROUTES, dumps, payload

ENDPOINT = "https://ai-gateway.vercel.sh/v1/chat/completions"
MAX_BYTES = 64 * 1024
RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}
TERMINAL_HTTP = {400, 401, 402, 403, 404, 422}
JUDGE_SOURCES = (
    "app/src/judge_prompt.txt", "app/src/judge.rs", "app/src/judge_context.rs",
    "app/src/pause_judge.rs", "app/src/nextdns.rs", "app/src/services.json",
    "android/src/org/nightingalelab/focus/phone/PhonePauseJudge.java", "shared/ask-retry.json",
)


def source_metadata():
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        changes = subprocess.check_output(
            ["git", "status", "--porcelain", "--", *JUDGE_SOURCES], cwd=ROOT, text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        commit, changes = None, None
    return {
        "commit": commit,
        "judge_sources_clean": None if changes is None else not bool(changes),
        "judge_sha256": {name: sha256((ROOT / name).read_bytes()).hexdigest() for name in JUDGE_SOURCES},
        "gateway_schema_sha256": sha256((ROOT / "shared/pause-judge-wire.json").read_bytes()).hexdigest(),
    }


def no_duplicates(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate JSON keys")
    return result


def provider_cost(envelope, usage):
    metadata = envelope.get("provider_metadata") or envelope.get("providerMetadata") or {}
    gateway = metadata.get("gateway") if isinstance(metadata, dict) else None
    for value in (usage.get("cost"), gateway.get("cost") if isinstance(gateway, dict) else None):
        if value is not None:
            try:
                cost = Decimal(str(value))
            except InvalidOperation:
                continue
            if cost.is_finite() and cost >= 0:
                return str(cost)
    return None


def call(body, key, timeout, retries):
    request = urllib.request.Request(
        ENDPOINT, data=dumps(body).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    )
    attempts = []
    for i in range(retries + 1):
        start = time.monotonic()
        retry_after = ""
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                blob = response.read(MAX_BYTES + 1)
                if len(blob) > MAX_BYTES:
                    raise ValueError("Gateway response exceeds app limit")
                envelope = json.loads(blob)
            attempts.append({"ms": round((time.monotonic() - start) * 1000), "http_status": 200})
            return envelope, attempts
        except urllib.error.HTTPError as error:
            retry_after = error.headers.get("Retry-After", "")
            attempt = {"error": "http_error", "http_status": error.code}
            error.close()
            retryable = error.code in RETRYABLE_HTTP
        except TimeoutError:
            attempt, retryable = {"error": "timeout"}, True
        except (urllib.error.URLError, OSError):
            attempt, retryable = {"error": "transport"}, True
        except ValueError:
            attempt, retryable = {"error": "invalid_envelope"}, False
        attempts.append({**attempt, "ms": round((time.monotonic() - start) * 1000)})
        if not retryable or i == retries:
            return None, attempts
        delay = min(10, int(retry_after)) if retry_after.isdigit() else 0.25 * (i + 1)
        time.sleep(delay)


def run(task, key, timeout, retries):
    case, route, model, reasoning, history, trial, body = task
    schema = body["response_format"]["json_schema"]["schema"]
    result = {
        "id": case.id, "category": case.category, "route": route, "model": model,
        "reasoning": reasoning, "history": history, "trial": trial, "request": case.request,
        "context": json.loads(body["messages"][1]["content"]),
        "expected": [asdict(value) for value in getattr(case, route)],
        "note": case.note, "payload_sha256": sha256(dumps(body).encode()).hexdigest(),
    }
    start = time.monotonic()
    envelope, attempts = call(body, key, timeout, retries)
    result.update(latency_ms=round((time.monotonic() - start) * 1000), attempts=attempts)
    if envelope is None:
        return {**result, "status": "api_error", "error": attempts[-1]["error"]}
    if not isinstance(envelope, dict):
        return {**result, "status": "api_error", "error": "invalid_envelope"}

    usage = envelope.get("usage")
    if not isinstance(usage, dict):
        usage = {}
    result.update(
        response_model=envelope.get("model"), generation_id=envelope.get("id"),
        usage=usage, cost_usd=provider_cost(envelope, usage),
    )
    try:
        choice = envelope["choices"][0]
        message = choice["message"]
        if choice.get("finish_reason") == "content_filter" or message.get("refusal") is not None:
            return {**result, "status": "refusal"}
        if choice.get("finish_reason") != "stop":
            return {**result, "status": "invalid_output", "error": f"finish_reason={choice.get('finish_reason')}"}
        content = message["content"]
        result["raw_content"] = content
        decision = json.loads(content, object_pairs_hook=no_duplicates)
        accepted = normalize(decision, schema, case.full_cap, route)
    except (ValueError, KeyError, IndexError, AttributeError, TypeError) as error:
        return {**result, "status": "invalid_output", "error": str(error)}
    failures, unsafe = grade(decision, case, route)
    normalized_failures, normalized_unsafe = grade(accepted, case, route)
    return {
        **result, "status": "judged", "decision": decision, "app_decision": accepted,
        "failures": failures, "unsafe": unsafe, "conformance": conformance(decision, schema),
        "app_failures": normalized_failures, "app_unsafe": normalized_unsafe,
    }


def metrics(rows):
    judged = [r for r in rows if r["status"] == "judged"]
    times = sorted(r["attempts"][-1]["ms"] for r in judged)
    costs = [Decimal(r["cost_usd"]) for r in rows if r.get("cost_usd") is not None]
    usage = [r["usage"] for r in rows if r.get("usage")]
    return {
        "cases": len(rows), "judged": len(judged),
        "passed": sum(not r["failures"] for r in judged),
        "app_passed": sum(not r["app_failures"] for r in judged),
        "unsafe": sum(bool(r["unsafe"]) for r in judged),
        "app_unsafe": sum(bool(r["app_unsafe"]) for r in judged),
        "conformance_issues": sum(bool(r["conformance"]) for r in judged),
        "status_counts": dict(Counter(r["status"] for r in rows)),
        "p50_ms": times[ceil(len(times) * .50) - 1] if times else None,
        "p95_ms": times[ceil(len(times) * .95) - 1] if times else None,
        "within_app_attempt_budget": sum(ms <= ATTEMPT_MS for ms in times),
        "prompt_tokens": sum(u.get("prompt_tokens") or 0 for u in usage),
        "completion_tokens": sum(u.get("completion_tokens") or 0 for u in usage),
        "reasoning_tokens": sum((u.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0 for u in usage),
        "usage_reported": len(usage), "cost_reported": len(costs),
        "reported_cost_usd": str(sum(costs)) if costs else None,
    }


def config_key(row):
    return row["model"], row["reasoning"], row["history"], row["route"]


def summarize(rows):
    groups = {}
    for row in rows:
        groups.setdefault(config_key(row), []).append(row)
    return [
        {
            "model": model, "reasoning": reasoning, "history": history, "route": route,
            **metrics(group),
            "categories": {
                category: metrics([r for r in group if r["category"] == category])
                for category in sorted({r["category"] for r in group})
            },
        }
        for (model, reasoning, history, route), group in groups.items()
    ]


def validate_fixture(case, route, body):
    schema = body["response_format"]["json_schema"]["schema"]
    for outcome in getattr(case, route):
        key = "domains" if outcome.action == "allow_domains" else "services"
        example = {
            "action": outcome.action, "minutes": outcome.minutes[1], "services": [], "message": "Use the Store for free time.",
            "counter_offer": "Try the named task on its own." if outcome.action == "counter_offer" else None,
        }
        if case.habits_due:
            example["message"] = "Habits are due. Use the Store for free time."
        if route == "desktop":
            example["domains"] = []
        example[key] = list(outcome.scope)
        normalized = normalize(example, schema, case.full_cap, route)
        if grade(normalized, case, route)[0]:
            raise ValueError(f"{case.id}/{route}: expectation cannot pass")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    add = parser.add_argument
    add("--route", choices=(*ROUTES, "both"), default="desktop",
        help="Use desktop for the shared judge, or phone for the retired direct route.")
    add("--reasoning", action="append", choices=("default", "none", "minimal", "low", "medium", "high", "xhigh"),
        help="Repeat to compare. Default omits reasoning_effort as the app does.")
    add("--history", choices=("on", "off", "both"), default="on",
        help="Off clears reasons, similar outcomes and ratings while preserving current activity and usage.")
    add("--category", action="append", choices=sorted({c.category for c in CASES}))
    add("--case", action="append", metavar="GLOB", help="Filter case IDs by shell-style glob. Repeat for more.")
    add("--repeat", type=int, default=1, help="Independent samples per case/configuration.")
    add("--jobs", type=int, default=4)
    add("--timeout", type=float, default=25, help="Per-request timeout, without app hedging. Default 25s.")
    add("--retries", type=int, default=1, help="Extra attempts after transient transport/HTTP errors.")
    add("--prompt", type=Path, help="Override the selected route's app prompt for a follow-up experiment.")
    add("--key-file", type=Path, help="Read the Gateway credential from this file or inherited file descriptor.")
    add("--output", type=Path, help="Write a JSON report here. Without this option, write JSON to stdout.")
    add("--website", action="store_true", help="Refresh index.html from the reports next to --output.")
    add("--website-only", type=Path, metavar="REPORT_DIR", nargs="?", const=DEFAULT_REPORT_DIR,
        help="Build the explorer from the suite and saved reports without calling Gateway.")
    add("--dump-payloads", action="store_true", help="Validate fixtures and emit request bodies without contacting Gateway.")
    args = parser.parse_args()
    if min(args.repeat, args.jobs, args.timeout) <= 0 or args.retries < 0:
        parser.error("Repeat, jobs and timeout must be positive. Retries cannot be negative.")
    if args.prompt and args.route == "both":
        parser.error("Select one route when overriding its prompt.")
    if args.website and (not args.output or args.dump_payloads or args.output.suffix != ".json"):
        parser.error("--website requires a live report with --output FILE.json.")
    if args.website_only and (args.website or args.output or args.dump_payloads):
        parser.error("--website-only takes an optional report directory and builds only the website.")
    if len({c.id for c in CASES}) != len(CASES):
        parser.error("Duplicate fixture IDs.")
    return parser, args


def main():
    parser, args = arguments()
    if args.website_only:
        print(build_website(args.website_only).resolve())
        return 0
    cases = [
        case for case in CASES
        if (not args.category or case.category in args.category)
        and (not args.case or any(fnmatchcase(case.id, pattern) for pattern in args.case))
    ]
    if not cases:
        parser.error("No fixtures matched.")
    routes = ROUTES if args.route == "both" else (args.route,)
    histories = ("on", "off") if args.history == "both" else (args.history,)
    prompt = args.prompt.read_text(encoding="utf-8") if args.prompt else None
    tasks = []
    for reasoning in dict.fromkeys(args.reasoning or ["default"]):
        for history in histories:
            for route in routes:
                for case in cases:
                    body = payload(case, route, MODEL, reasoning, history == "on", prompt)
                    validate_fixture(case, route, body)
                    tasks.extend(
                        (case, route, MODEL, reasoning, history, trial, body)
                        for trial in range(1, args.repeat + 1)
                    )
    if args.dump_payloads:
        output = [{"id": c.id, "route": route, "history": history, "trial": trial, "body": body}
                  for c, route, _, _, history, trial, body in tasks]
        rows = None
    else:
        try:
            key = (
                args.key_file.read_text().strip() if args.key_file else
                (os.environ.get("AI_GATEWAY_API_KEY") or os.environ.get("VERCEL_AI_GATEWAY_API_KEY")
                 or os.environ.get("VERCEL_OIDC_TOKEN") or "").strip()
            )
        except OSError:
            parser.error("Could not read the Gateway key file.")
        if not key:
            parser.error("Set AI_GATEWAY_API_KEY or pass --key-file.")
        start = datetime.now(timezone.utc).isoformat()
        source = source_metadata()
        print(f"Running {len(tasks)} Gateway samples.", file=sys.stderr)

        def evaluate(task):
            return run(task, key, args.timeout, args.retries)

        rows = []
        # Probe each model/effort once before submitting a whole batch with the same credentials.
        failed_settings = {}
        probed = set()
        pending = []
        for task in tasks:
            settings = task[2:4]
            if settings not in probed:
                first = evaluate(task)
                rows.append(first)
                probed.add(settings)
                status = first["attempts"][-1].get("http_status")
                if status in TERMINAL_HTTP:
                    failed_settings[settings] = status
                    print(f"Gateway rejected {settings[0]} ({settings[1]}): HTTP {status}.", file=sys.stderr)
            elif settings not in failed_settings:
                pending.append(task)
        with ThreadPoolExecutor(args.jobs) as pool:
            for result in pool.map(evaluate, pending):
                rows.append(result)
                if len(rows) % 25 == 0:
                    print(f"{len(rows)} samples finished.", file=sys.stderr)
        output = {
            "started_at": start, "planned_samples": len(tasks), "timeout_seconds": args.timeout,
            "source": source,
            "transport_retries": args.retries, "app_attempt_budget_ms": ATTEMPT_MS,
            "skipped_configurations": [
                {"model": model, "reasoning": reasoning, "http_status": status}
                for (model, reasoning), status in failed_settings.items()
            ],
            "summary": summarize(rows), "results": rows,
        }
    report = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        write_atomic(args.output, report)
        if args.website:
            print(f"Benchmark website: {build_website(args.output.parent).resolve()}", file=sys.stderr)
    else:
        sys.stdout.write(report)
    if rows is not None and (failed_settings or not any(r["status"] == "judged" for r in rows)):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

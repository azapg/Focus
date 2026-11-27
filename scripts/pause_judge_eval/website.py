"""Bundle a standalone browser for Focus Smart Ask benchmark reports."""

import base64
from dataclasses import asdict
import json
import os
from pathlib import Path
import sys
from tempfile import mkstemp

from .cases import CASES
from .wire import CATALOG, context

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT_DIR = ROOT / "build/pause-judge-eval"
SITE = Path(__file__).resolve().parent / "site"
FIELDS = (
    "id", "category", "route", "model", "reasoning", "history", "trial",
    "decision", "app_decision", "failures", "unsafe", "app_failures",
    "app_unsafe", "conformance", "error", "status", "latency_ms", "cost_usd", "attempts",
)
TEXT_KEYS = ("id", "category", "route", "model", "reasoning", "history", "request", "status")
TOKEN_KEYS = ("prompt_tokens", "completion_tokens", "total_tokens", "completion_tokens_details")


def write_atomic(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = mkstemp(dir=path.parent)
    staging = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            file.write(text)
        staging.replace(path)
    finally:
        staging.unlink(missing_ok=True)


def is_report(report):
    return isinstance(report, dict) and isinstance(report.get("results"), list) and all(
        isinstance(row, dict)
        and all(isinstance(row.get(key), str) for key in TEXT_KEYS)
        and isinstance(row.get("expected"), list)
        and (row["status"] != "judged" or isinstance(row.get("decision"), dict))
        for row in report["results"]
    )


def compact_report(name, report):
    source_rows = report["results"]
    services = next(
        (row["context"]["services"] for row in source_rows
         if isinstance(row.get("context"), dict) and "services" in row["context"]), None,
    )
    contexts = []
    context_ids = {}
    cases = []
    case_ids = {}
    rows = []
    for row in source_rows:
        compact = {key: row[key] for key in FIELDS if key in row}
        case = {key: row[key] for key in ("request", "expected", "note") if key in row}
        signature = json.dumps(case, ensure_ascii=False, sort_keys=True)
        if signature not in case_ids:
            case_ids[signature] = len(cases)
            cases.append(case)
        compact["case_index"] = case_ids[signature]
        if row.get("app_decision") == row.get("decision"):
            compact.pop("app_decision", None)
        if "decision" not in row and isinstance(row.get("raw_content"), str):
            compact["raw_content"] = row["raw_content"]
        usage = row.get("usage")
        if isinstance(usage, dict):
            compact["usage"] = {key: usage[key] for key in TOKEN_KEYS if key in usage}
        scenario = row.get("context")
        if isinstance(scenario, dict):
            if "services" in scenario and scenario["services"] == services:
                scenario = {key: value for key, value in scenario.items() if key != "services"}
            signature = json.dumps(scenario, sort_keys=True, ensure_ascii=False)
            if signature not in context_ids:
                context_ids[signature] = len(contexts)
                contexts.append(scenario)
            compact["context_index"] = context_ids[signature]
        rows.append(compact)
    return {
        "name": name, "started": report.get("started_at"), "rows": rows,
        "source": report.get("source"),
        "cases": cases, "contexts": contexts, "services": services,
    }


def current_suite():
    fixtures = []
    for case in CASES:
        desktop_context = context(case, "desktop", history_on=True)
        desktop_context.pop("services")
        fixtures.append({
            "id": case.id, "category": case.category, "request": case.request, "note": case.note,
            "desktop": [asdict(item) for item in case.desktop],
            "phone": [asdict(item) for item in case.phone],
            "desktop_context": desktop_context, "phone_context": context(case, "phone", history_on=True),
        })
    return {"cases": fixtures, "services": CATALOG}


def json_for_page(value):
    # Prevent fixture text from ending the JSON script element.
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")


def read_reports(paths):
    reports = []
    for path in paths:
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            print(f"Skipped unreadable report: {path.name}", file=sys.stderr)
            continue
        if is_report(report):
            reports.append(compact_report(path.stem, report))
    return reports


def build_website(report_dir, output=None):
    output = output or report_dir / "index.html"
    html = (SITE / "index.html").read_text(encoding="utf-8")
    styles = (SITE / "styles.css").read_text(encoding="utf-8")
    js = (SITE / "app.js").read_text(encoding="utf-8")
    font = base64.b64encode((ROOT / "ui/fonts/geist-latin.woff2").read_bytes()).decode("ascii")
    font_face = (
        '@font-face { font-family: Geist; src: url("data:font/woff2;base64,' + font +
        '") format("woff2"); font-weight: 100 900; font-display: swap; }'
    )
    styles = styles.replace("/* SITE FONT */", font_face, 1)
    asset = html.replace("/* SITE STYLES */", styles, 1).replace("/* SITE SCRIPT */", js, 1)
    asset = asset.replace('id="benchmark-suite" type="application/json">{}',
                          f'id="benchmark-suite" type="application/json">{json_for_page(current_suite())}', 1)
    while True:
        paths = sorted(report_dir.glob("*.json"))
        reports = read_reports(paths)
        page = asset.replace('id="benchmark-reports" type="application/json">[]',
                             f'id="benchmark-reports" type="application/json">{json_for_page(reports)}', 1)
        write_atomic(output, page)
        # Another benchmark may have finished while this page was being built.
        if paths == sorted(report_dir.glob("*.json")):
            return output

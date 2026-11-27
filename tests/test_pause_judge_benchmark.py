"""Offline tests for the Smart Ask benchmark and its route-specific scoring."""

import importlib.util
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("benchmark", SCRIPTS / "benchmark-pause-judge.py")
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
from pause_judge_eval.cases import CASES
from pause_judge_eval.scoring import conformance, grade, normalize
from pause_judge_eval.website import build_website
from pause_judge_eval.wire import payload

BY_ID = {case.id: case for case in CASES}


def request(case, route, history=True, reasoning="default"):
    return payload(BY_ID[case], route, "test-model", reasoning, history)


def schema(body):
    return body["response_format"]["json_schema"]["schema"]


class BenchmarkTests(unittest.TestCase):
    def test_every_fixture_has_achievable_expectations(self):
        self.assertEqual(len(BY_ID), len(CASES))
        for case in CASES:
            for route in ("desktop", "phone"):
                with self.subTest(case=case.id, route=route):
                    benchmark.validate_fixture(case, route, request(case.id, route))

    def test_desktop_domains_never_include_flagged_or_recreational_sites(self):
        safe = schema(request("dns-nrd", "desktop"))["properties"]
        self.assertEqual(safe["domains"]["items"]["enum"], ["portal.copperleaf.example"])
        for case in ("dns-threat-flag", "dns-entertainment-domain"):
            properties = schema(request(case, "desktop"))["properties"]
            self.assertNotIn("allow_domains", properties["action"]["enum"])
            self.assertNotIn("enum", properties["domains"]["items"])

    def test_phone_payload_matches_its_capabilities(self):
        body = request("budget-cannot-pause", "phone")
        self.assertEqual(schema(body)["properties"]["action"]["enum"], ["counter_offer", "deny"])
        self.assertIn("cannot pause DNS", body["messages"][0]["content"])
        self.assertNotIn("reasoning_effort", body)
        self.assertEqual(request("budget-cannot-pause", "phone", reasoning="none")["reasoning_effort"], "none")
        user = json.loads(request("dns-threat-flag", "phone")["messages"][1]["content"])
        self.assertEqual(set(user), {
            "request", "local_time", "check_in", "today", "last_hour",
            "similar_history", "recent_ratings", "max_minutes",
        })
        self.assertNotIn("domains", schema(body)["properties"])

    def test_ablation_keeps_current_activity_and_policy(self):
        for route, similar_key in (("desktop", "similar_pauses"), ("phone", "similar_history")):
            on = json.loads(request("drift-same-excuse", route)["messages"][1]["content"])
            off = json.loads(request("drift-same-excuse", route, history=False)["messages"][1]["content"])
            self.assertTrue(on["today"]["reasons"])
            self.assertTrue(on[similar_key])
            self.assertTrue(on["recent_ratings"])
            on["today"]["reasons"] = on[similar_key] = on["recent_ratings"] = []
            self.assertEqual(on, off)

    def test_active_intention_is_current_laptop_context(self):
        desktop = json.loads(request("intention-isolation", "desktop", history=False)["messages"][1]["content"])
        phone = json.loads(request("intention-isolation", "phone")["messages"][1]["content"])
        self.assertEqual(desktop["active_intention"]["remaining_minutes"], 45)
        self.assertNotIn("active_intention", phone)

    def test_unsafe_scope_is_distinct_from_copy_issues(self):
        case = BY_ID["mix-separate-service"]
        decision = {
            "action": "allow_services", "services": ["slack", "reddit"], "domains": [],
            "minutes": 2, "message": "Proposed access.", "counter_offer": None,
        }
        self.assertEqual(grade(decision, case, "desktop"), (["wrong_scope"], ["unrelated_scope"]))
        self.assertEqual(conformance(decision, schema(request(case.id, "desktop"))), [])

    def test_app_caps_cannot_hide_model_overgrant(self):
        case = BY_ID["budget-exhausted"]
        proposed = {
            "action": "full_pause", "services": [], "minutes": 5,
            "message": "Test the dispatch app for five minutes.", "counter_offer": None,
        }
        app = normalize(proposed, schema(request(case.id, "phone")), case.full_cap, "phone")
        self.assertEqual(grade(proposed, case, "phone"), (["wrong_duration"], ["overlong_grant"]))
        self.assertEqual(grade(app, case, "phone"), ([], []))

    def test_recreational_denial_requires_store_and_due_habits(self):
        decision = {"action": "deny", "services": [], "minutes": 0, "message": "Use Store Free Time.",
                    "counter_offer": None}
        self.assertEqual(grade(decision, BY_ID["rec-feed-due0"], "phone"), ([], []))
        self.assertEqual(grade(decision, BY_ID["rec-feed-due2"], "phone"), (["missing_due_habits"], []))

    def test_rejects_duplicate_fields_and_ineligible_domain(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            json.loads('{"action":"deny","action":"full_pause"}', object_pairs_hook=benchmark.no_duplicates)
        decision = {
            "action": "allow_domains", "services": [], "domains": ["someone-else.example"], "minutes": 5,
            "message": "Proposed access.", "counter_offer": None,
        }
        with self.assertRaisesRegex(ValueError, "ineligible"):
            normalize(decision, schema(request("dns-nrd", "desktop")), 5, "desktop")

    def test_website_bundles_report_text_as_inert_json(self):
        report = {"results": [{
            "id": "embedded-tag", "category": "injection", "route": "desktop",
            "model": "test-model", "reasoning": "default", "history": "on",
            "request": "</script><script>alert(1)</script>",
            "status": "judged", "expected": [], "decision": {"action": "deny"},
            "generation_id": "private-request-id", "context": {"services": {"github": ["github.com"]}},
        }]}
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "report.json").write_text(json.dumps(report), encoding="utf-8")
            (root / "unrelated.json").write_text("[]", encoding="utf-8")
            site = build_website(root).read_text(encoding="utf-8")
        embedded = re.search(r'<script id="benchmark-reports" type="application/json">(.*?)</script>', site)[1]
        runs = json.loads(embedded)
        self.assertEqual(len(runs), 1)
        self.assertEqual(runs[0]["cases"][0]["request"], report["results"][0]["request"])
        self.assertEqual(runs[0]["services"], {"github": ["github.com"]})
        self.assertNotIn("private-request-id", site)
        self.assertNotIn("<script>alert(1)", site)

    def test_website_flag_keeps_existing_runs(self):
        reply = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({
            "action": "deny", "services": [], "minutes": 0, "message": "Name a specific task.",
            "counter_offer": None,
        })}}]}
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("first", "second"):
                argv = ["benchmark", "--route", "phone", "--case", "bare-full-pause-empty",
                        "--output", str(root / f"{name}.json"), "--website"]
                with patch.object(benchmark, "call", return_value=(reply, [{"ms": 50, "http_status": 200}])), \
                     patch.object(sys, "argv", argv), patch.dict("os.environ", {"AI_GATEWAY_API_KEY": "fixture"}):
                    self.assertEqual(benchmark.main(), 0)
                saved = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
                self.assertEqual(saved["results"][0]["model"], "openai/gpt-6-luna")
            site = (root / "index.html").read_text(encoding="utf-8")
        self.assertIn('"name":"first"', site)
        self.assertIn('"name":"second"', site)


if __name__ == "__main__":
    unittest.main()

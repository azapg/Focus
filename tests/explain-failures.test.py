"""Incident and privacy regressions for the Windows-compatible report."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import json
import runpy
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "explain-failures.py"
REPORT = runpy.run_path(str(SCRIPT))


def event(correlation, name, stage, cause, device="phone", offset=0, **fields):
    return {
        "v": 1, "at_ms": time.time_ns() // 1_000_000 + offset,
        "event_id": str(uuid.uuid4()), "build_id": "abcdef1", "device": device,
        "correlation_id": correlation, "event": name, "stage": stage,
        "outcome": "ok" if cause == "ok" else "failed", "cause": cause,
        "duration_ms": 22, "fields": fields,
    }


def public_listener(peer):
    return [
        event(str(uuid.uuid4()), "lan", "bind", "ok", "laptop", -8_001_000, port=47833),
        event(str(uuid.uuid4()), "lan", "firewall", "ok", "laptop", -8_000_000,
              network_category="public", firewall_profiles="domain_private", port=47833),
        event(str(uuid.uuid4()), "lan", "reachability", "ok", "laptop", -2_000,
              peer_id=peer, direction="laptop_to_phone"),
    ]


class FailureReportTest(unittest.TestCase):
    def run_report(self, phone, laptop=(), hours=1):
        with tempfile.TemporaryDirectory() as directory:
            for name, rows in (("laptop", laptop), ("phone", phone)):
                (Path(directory) / f"{name}.jsonl").write_text(
                    "".join(json.dumps(row) + "\n" for row in rows) + '{"partial":',
                    encoding="utf-8",
                )
            return subprocess.check_output(
                [sys.executable, str(SCRIPT), "--hours", str(hours), directory],
                text=True, encoding="utf-8",
            )

    def test_each_ask_eligibility_state(self):
        for cause in ("no_laptop_answer", "scope_key_missing", "scope_profile_mismatch",
                      "dns_disabled", "dns_paused", "private_dns_inactive"):
            with self.subTest(cause=cause):
                correlation = str(uuid.uuid4())
                phone = [event(correlation, "ask", "eligibility", cause, origin="phone")]
                laptop = [event(correlation, "ask", "gateway", "ok", "laptop", -50, origin="phone")]
                report = self.run_report(phone, laptop)
                self.assertIn(f"[{cause}]", report)
                self.assertEqual(report.count("  Cause:"), 1)
                self.assertIn("laptop abcdef1 ask/gateway", report)
                self.assertIn("phone abcdef1 ask/eligibility", report)

    def test_public_firewall_allows_outbound_but_blocks_inbound(self):
        ask, peer = (str(uuid.uuid4()) for _ in range(2))
        phone = [
            event(ask, "ask", "laptop", "laptop_timeout", peer_id=peer, route="laptop"),
            event(ask, "ask", "eligibility", "no_laptop_answer", peer_id=peer),
        ]
        laptop = public_listener(peer)
        _, rule, outbound = laptop
        report = self.run_report(phone, laptop)
        self.assertIn("Cause: inbound blocked, outbound fine (Public network", report)
        self.assertIn("laptop abcdef1 lan/reachability ok", report)
        self.assertIn("phone abcdef1 ask/laptop failed laptop_timeout", report)
        variants = (("peer", str(uuid.uuid4())), ("device", "phone"), ("device", "laptop"))
        for case, value in variants:
            if case == "peer":
                outbound["fields"]["peer_id"] = value
            else:
                outbound["fields"]["peer_id"] = peer
                outbound["device"] = value
                if value == "laptop":
                    for row in phone + [outbound]:
                        row["fields"].pop("peer_id")
            self.assertNotIn("inbound blocked", self.run_report(phone, laptop))
        for row in phone + [outbound]:
            row["fields"]["peer_id"] = peer
        rule["fields"].pop("network_category")
        missing_profile = self.run_report(phone, laptop)
        self.assertNotIn("inbound blocked", missing_profile)
        self.assertIn("inbound reachability unknown", missing_profile)
        rule["fields"]["network_category"] = "public"
        rule["outcome"] = "failed"
        rule["cause"] = "firewall_failed"
        report = self.run_report(phone, laptop)
        self.assertNotIn("inbound blocked", report)
        self.assertIn("Windows could not install the inbound rule", report)

    def test_authenticated_inbound_evidence_prevents_firewall_attribution(self):
        ask, peer, other = (str(uuid.uuid4()) for _ in range(3))
        phone = [event(ask, "ask", "laptop", "laptop_timeout", peer_id=peer)]
        laptop = public_listener(peer)
        cases = (
            event(ask, "lan", "request", "ok", "laptop", -1_000, http_status=200),
            event(ask, "lan", "request", "ok", "laptop", -1_000, status_class=2),
            event(other, "lan", "request", "lan_rejected", "laptop", -1_000,
                  direction="phone_to_laptop", authenticated=True, http_status=409),
        )
        for evidence in cases:
            with self.subTest(evidence=evidence["fields"]):
                report = self.run_report(phone, [*laptop, evidence])
                self.assertNotIn("inbound blocked", report)
                self.assertIn("inbound reachability unknown", report)
                self.assertIn("laptop abcdef1 lan/request", report)
        cases[-1]["at_ms"] -= 301_000
        self.assertIn("inbound blocked, outbound fine", self.run_report(phone, [*laptop, cases[-1]]))

    def test_firewall_rule_must_belong_to_current_successful_bind(self):
        ask, peer, replacement = (str(uuid.uuid4()) for _ in range(3))
        phone = [event(ask, "ask", "laptop", "laptop_timeout", peer_id=peer)]
        laptop = public_listener(peer)
        newer_binds = (
            event(replacement, "lan", "bind", "ok", "laptop", -4_000, network_category="public", port=47834),
            event(replacement, "lan", "bind", "ok", "laptop", -4_000, network_category="public", port=47833),
            event(replacement, "lan", "bind", "lan_bind_failed", "laptop", -4_000),
        )
        for bind in newer_binds:
            with self.subTest(bind=bind):
                report = self.run_report(phone, [*laptop, bind])
                self.assertIn("inbound reachability unknown", report)
                self.assertNotIn("inbound blocked", report)

        late_rule = event(str(uuid.uuid4()), "lan", "firewall", "ok", "laptop", -3_000,
                          network_category="public", firewall_profiles="domain_private", port=47833)
        rebound = [*laptop, newer_binds[0], late_rule]
        self.assertIn("inbound reachability unknown", self.run_report(phone, rebound))
        late_rule["fields"]["port"] = 47834
        self.assertIn("inbound blocked, outbound fine", self.run_report(phone, rebound))

    def test_report_cutoff_keeps_correlated_laptop_http_success(self):
        ask, peer = (str(uuid.uuid4()) for _ in range(2))
        timeout = event(ask, "ask", "laptop", "laptop_timeout", peer_id=peer)
        success = event(ask, "lan", "request", "ok", "laptop", -3_000, http_status=200)
        cutoff = timeout["at_ms"] - 1_000
        self.assertLess(success["at_ms"], cutoff)
        rows = sorted([timeout, success, *public_listener(peer)], key=lambda row: row["at_ms"])
        stream = StringIO()
        with redirect_stdout(stream):
            REPORT["report"](rows, cutoff)
        report = stream.getvalue()
        self.assertIn("inbound reachability unknown", report)
        self.assertIn("http_status=200", report)
        self.assertNotIn("inbound blocked", report)

    def test_cause_precedence_for_each_pair_at_different_ranks(self):
        correlation = str(uuid.uuid4())
        ranks = REPORT["CAUSE_PRECEDENCE"]
        for stronger, stronger_rank in ranks.items():
            if stronger in {"ok", "gateway_cancelled"}:
                continue
            for weaker, weaker_rank in ranks.items():
                if stronger_rank <= weaker_rank or weaker in {"ok", "gateway_cancelled"}:
                    continue
                with self.subTest(stronger=stronger, weaker=weaker):
                    evidence = [event(correlation, "ask", "gateway", stronger, "laptop", -50),
                                event(correlation, "ask", "eligibility", weaker)]
                    result = REPORT["explanation"](evidence, None)
                    self.assertEqual(result.split(" [", 1)[1].split("]", 1)[0], stronger)

    def test_gateway_cause_precedes_generic_fallback_and_preserves_other_route(self):
        ask, other = (str(uuid.uuid4()) for _ in range(2))
        phone = [event(ask, "ask", "eligibility", "no_laptop_answer")]
        laptop = [event(ask, "ask", "gateway", "gateway_http_4xx", "laptop", -50),
                  event(other, "lan", "request", "lan_unauthorized", "laptop")]
        report = self.run_report(phone, laptop)
        self.assertIn("Cause: Gateway returned a client error [gateway_http_4xx]\n", report)
        phone.append(event(ask, "ask", "laptop", "laptop_timeout", offset=-25))
        report = self.run_report(phone, laptop)
        summary = next(line for line in report.splitlines() if "Cause:" in line)
        self.assertIn("[gateway_http_4xx]", summary)
        self.assertIn("[laptop_timeout]", summary)
        self.assertNotIn("[no_laptop_answer]", summary)
        phone.append(event(ask, "ask", "eligibility", "scope_profile_mismatch"))
        summary = next(line for line in self.run_report(phone, laptop).splitlines() if "Cause:" in line)
        self.assertTrue(summary.startswith("  Cause: Phone and laptop use different NextDNS profiles"))
        self.assertIn("[gateway_http_4xx]", summary)
        self.assertIn("[laptop_timeout]", summary)

    def test_oversized_apk_has_size_limit_and_builds(self):
        correlation = str(uuid.uuid4())
        row = event(correlation, "update", "download", "apk_too_large",
                    expected_bytes=35_127_000, actual_bytes=0, limit_bytes=33_554_432,
                    from_build="abcdef1", to_build="abcdef2")
        report = self.run_report([row])
        self.assertIn("APK exceeds maximum allowed size", report)
        self.assertIn("limit 33,554,432 bytes", report)
        self.assertIn("from_build=abcdef1", report)
        self.assertIn("to_build=abcdef2", report)

    def test_rejects_unapproved_fields_free_text_and_stale_rows(self):
        correlation = str(uuid.uuid4())
        forbidden = dict(request="private request", reason="private reason", url="https://example.test",
                         host="example.test", title="private title", app="private app", key="secret",
                         token="bearer secret", fingerprint="certificate")
        rows = [event(correlation, "ask", "gateway", "gateway_invalid_reply", **{key: value})
                for key, value in forbidden.items()]
        rows += [event(correlation, "ask", "gateway", "private text")]
        rows += [event(correlation, "ask", "gateway", "ok", route="private text")]
        rows += [event(correlation, "ask", "decision", "ok", decision_action="private action")]
        rows += [event(correlation, "ask", "gateway", "gateway_invalid_reply", failure_detail="private request")]
        rows += [event(correlation, "pause", "apply", "ok", manual_override="private override")]
        rows += [event(correlation, "ask", "gateway", "ok", offset=-7_200_000)]
        self.assertEqual(self.run_report(rows).strip(), "No Ask, pause or update events in the requested time window.")

    def test_ask_decision_and_manual_override_are_in_the_timeline(self):
        correlation = str(uuid.uuid4())
        rows = [
            event(correlation, "ask", "decision", "laptop_unreachable", decision_action="fallback"),
            event(correlation, "pause", "apply", "ok", decision_action="full_pause",
                  manual_override=True),
        ]
        report = self.run_report(rows)
        self.assertIn("decision_action=fallback", report)
        self.assertIn("decision_action=full_pause manual_override=true", report)

    def test_recovered_gateway_retry_remains_in_the_timeline(self):
        correlation = str(uuid.uuid4())
        rows = [
            event(correlation, "ask", "gateway", "gateway_invalid_reply", attempt=1, failure_detail="parse"),
            event(correlation, "ask", "gateway", "ok", attempt=2),
            event(correlation, "ask", "gateway", "ok"),
            event(correlation, "ask", "gateway", "gateway_cancelled", attempt=3, failure_detail="cancelled"),
        ]
        report = self.run_report(rows)
        self.assertIn("Cause: Completed successfully", report)
        self.assertIn("attempt=1 failure_detail=parse", report)
        self.assertIn("attempt=3 failure_detail=cancelled", report)


if __name__ == "__main__":
    unittest.main()

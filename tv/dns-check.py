#!/usr/bin/env python3
"""Check an explicitly selected Android TV using its already configured NextDNS profile."""

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import selectors
import subprocess
import time

from install import ADB, APP, ROOT, TV, restore_access

TEST = APP + ".tests"
FIXTURE = APP + ".fixture"


def instrument(tv, phase, profile):
    proc = subprocess.Popen(
        [
            ADB,
            "-s",
            tv.serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "phase",
            phase,
            "-e",
            "profile",
            profile,
            TEST + "/.DnsChecks",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=0,
    )
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    until = time.monotonic() + 180
    result = None
    stages = []
    try:
        while time.monotonic() < until:
            if selector.select(1):
                line = proc.stdout.readline().decode(errors="replace").strip()
                if line.startswith("INSTRUMENTATION_STATUS: stage="):
                    name, token = line.split("stage=", 1)[1].split(":", 1)
                    if name == "access_rebind":
                        restore_access(tv)
                    stages.append(name)
                    print("Verified stage: " + name, flush=True)
                    tv.adb(
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "org.nightingalelab.focus.tv.tests.DNS_STAGE_ACK",
                        "-p",
                        APP,
                        "--es",
                        "token",
                        token,
                    )
                if line.startswith("INSTRUMENTATION_RESULT: result="):
                    result = json.loads(line.split("result=", 1)[1])
                if not line and proc.poll() is not None:
                    break
            elif proc.poll() is not None:
                break
        if proc.poll() is None:
            proc.terminate()
            raise RuntimeError("DNS instrumentation deadline exceeded")
        if result is None:
            raise RuntimeError("DNS instrumentation returned no result")
        result["stages"] = stages
        return result
    finally:
        selector.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--confirm-dns-changes", action="store_true", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--ui-only",
        action="store_true",
        help="Check dialog recovery and final DNS without repeating timed pauses",
    )
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{6}", args.profile):
        parser.error("--profile requires a six-character NextDNS profile ID")
    profile = args.profile.lower()
    hostname = f"{profile}.dns.nextdns.io"
    tv = TV(args.serial)
    tv.verify()
    if (
        tv.settings("global", "private_dns_mode") != "hostname"
        or tv.settings("global", "private_dns_specifier").lower() != hostname
    ):
        raise RuntimeError("Connect the selected TV to the supplied profile before running checks")
    if not all(
        tv.adb("shell", "pm", "path", pkg, check=False) for pkg in [TEST, FIXTURE]
    ):
        raise RuntimeError("Install the signed device and fixture test APKs first")
    proof = {"dns_changes_intentional": True}
    try:
        if args.ui_only:
            proof["ui"] = instrument(tv, "ui", profile)
            if not proof["ui"].get("passed"):
                raise RuntimeError(
                    "UI recovery check failed: " + proof["ui"].get("failure", "failed")
                )
            proof["history_cleanup"] = instrument(tv, "cleanup", profile)
            if not proof["history_cleanup"].get("passed"):
                raise RuntimeError("Synthetic history cleanup failed")
        else:
            proof["runtime"] = instrument(tv, "runtime", profile)
            if not proof["runtime"].get("passed"):
                raise RuntimeError(
                    "Runtime: " + proof["runtime"].get("failure", "failed")
                )
            proof["restart_start"] = instrument(tv, "restart-start", profile)
            if not proof["restart_start"].get("passed"):
                raise RuntimeError("Restart preparation failed")
            # am instrument finishes and destroys its target process between these invocations.
            # No force-stop: Android cancels alarms for a force-stopped package by design.
            proof["restart"] = instrument(tv, "restart-check", profile)
            if not proof["restart"].get("passed"):
                raise RuntimeError(
                    "Restart: " + proof["restart"].get("failure", "failed")
                )
            proof["different_process_after_restart"] = proof["restart_start"].get(
                "process_id"
            ) != proof["restart"].get("process_id")
            if not proof["different_process_after_restart"]:
                raise RuntimeError("Process restart did not occur")
            tv.adb(
                "shell", "pm", "revoke", APP, "android.permission.WRITE_SECURE_SETTINGS"
            )
            try:
                proof["permission_denied"] = instrument(tv, "denied", profile)
            finally:
                tv.adb(
                    "shell",
                    "pm",
                    "grant",
                    APP,
                    "android.permission.WRITE_SECURE_SETTINGS",
                )
            if not proof["permission_denied"].get("passed"):
                raise RuntimeError("Permission denial check failed")
            tv.adb("shell", "am", "force-stop", "com.google.android.youtube.tv")
            proof["youtube_app"] = instrument(tv, "youtube", profile)
            if not proof["youtube_app"].get("passed"):
                raise RuntimeError(
                    "Fresh YouTube app: "
                    + proof["youtube_app"].get("failure", "failed")
                )
            proof["history_cleanup"] = instrument(tv, "cleanup", profile)
            if not proof["history_cleanup"].get("passed"):
                raise RuntimeError("Synthetic pause history cleanup failed")
        proof["passed"] = True
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as e:
        proof["passed"] = False
        proof["failure"] = str(e).replace(profile, "[profile]").replace(tv.serial, "[serial]")
    finally:
        # Leave the user's newly verified protection on, even if a UI check failed.
        for key, value in {
            "private_dns_specifier": hostname,
            "private_dns_mode": "hostname",
        }.items():
            if tv.settings("global", key) != value:
                tv.put("global", key, value)
        for pkg in [TEST, FIXTURE]:
            tv.adb("uninstall", pkg, check=False)
        tv.launch()
        proof["service_restart_attempts"] = restore_access(tv)
        # Android's TLS validation is asynchronous. Require actual validated servers,
        # not just a saved strict-mode hostname, before the final allowed-site check.
        ready_by = time.monotonic() + 60
        while True:
            conn = tv.adb("shell", "dumpsys", "connectivity")
            if (
                re.search(r"ValidatedPrivateDnsAddresses:\s*\[\s*[^\]\s]", conn)
                or time.monotonic() >= ready_by
            ):
                break
            time.sleep(1)
        proof["native_dot_validated"] = (
            "UsePrivateDns: true" in conn
            and f"PrivateDnsServerName: {hostname}" in conn
            and bool(re.search(r"ValidatedPrivateDnsAddresses:\s*\[\s*[^\]\s]", conn))
        )
        lookup = tv.adb(
            "shell", "ping", "-c", "1", "-W", "1", "www.youtube.com", check=False
        )
        proof["final_youtube_blocked"] = any(
            s in lookup for s in ["(127.0.0.1)", "(0.0.0.0)", "unknown host"]
        )
        control = tv.adb(
            "shell", "ping", "-c", "1", "-W", "1", "example.com", check=False
        )
        proof["final_allowed_site_resolves"] = bool(
            re.search(r"PING example.com \((?!127\.|0\.0\.0\.0)", control)
        )
        proof["test_packages_removed"] = all(
            not tv.adb("shell", "pm", "path", pkg, check=False)
            for pkg in [TEST, FIXTURE]
        )
        services = tv.adb("shell", "dumpsys", "activity", "services", APP)
        proof["monitor_running"] = any(
            "ServiceRecord" in s and ".MonitorService" in s
            for s in services.splitlines()
        )
        proof["restrictions_running"] = any(
            "ServiceRecord" in s and ".RestrictionService" in s
            for s in services.splitlines()
        )
        sha = hashlib.sha256((ROOT / "build/focus-tv.apk").read_bytes()).hexdigest()
        proof["apk_sha256"] = sha
        path = tv.adb("shell", "pm", "path", APP).removeprefix("package:").strip()
        proof["installed_matches_build"] = (
            tv.adb("shell", "sha256sum", path).split()[0] == sha
        )
        proof["verified_at_utc"] = datetime.datetime.now(datetime.UTC).isoformat()
        proof["passed"] = proof.get("passed", False) and all(
            proof.get(k)
            for k in [
                "native_dot_validated",
                "final_youtube_blocked",
                "final_allowed_site_resolves",
                "test_packages_removed",
                "monitor_running",
                "restrictions_running",
                "installed_matches_build",
            ]
        )
        args.output.write_text(json.dumps(proof, indent=2) + "\n")
        print(json.dumps(proof, indent=2), flush=True)
    return 0 if proof["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

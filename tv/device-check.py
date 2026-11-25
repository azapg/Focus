#!/usr/bin/env python3
"""Bounded, synthetic-only TV checks; leaves the companion installed with original rules."""

import argparse
import datetime
import hashlib
import json
import selectors
import subprocess
import time

from install import ADB, APP, ROOT, TV, enable_access, restore_access

FIXTURE = APP + ".fixture"
TEST = APP + ".tests"


def foreground(tv):
    return tv.adb(
        "shell", "sh", "-c", "dumpsys activity activities | sed -n /mResumedActivity/p"
    )


def screenshot(tv, stage):
    expected = FIXTURE if stage == "countdown" else APP
    before = foreground(tv)
    if expected not in before:
        return False
    image = tv.adb("exec-out", "screencap", "-p", binary=True, timeout=20)
    after = foreground(tv)
    if before != after or not image.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    (ROOT / "evidence" / "private" / (stage + ".png")).write_bytes(image)
    return True


def instrument(tv, phase):
    command = [
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
        TEST + "/.DeviceChecks",
    ]
    proc = subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0
    )
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    deadline = time.monotonic() + 180
    result = None
    stages = []
    try:
        while time.monotonic() < deadline:
            ready = selector.select(timeout=1)
            if ready:
                line = proc.stdout.readline().decode(errors="replace").strip()
                if line.startswith("INSTRUMENTATION_STATUS: stage="):
                    stage = line.split("stage=", 1)[1]
                    if stage == "access_rebind":
                        enable_access(tv, rebind=True)
                    if stage == "access_disable":
                        values = tv.settings(
                            "secure", "enabled_accessibility_services"
                        ).split(":")
                        values = [
                            v
                            for v in values
                            if v
                            not in {
                                APP + "/.RestrictionService",
                                APP + "/" + APP + ".RestrictionService",
                            }
                        ]
                        tv.put(
                            "secure", "enabled_accessibility_services", ":".join(values)
                        )
                        if not values:
                            tv.put("secure", "accessibility_enabled", "0")
                    stages.append(
                        {
                            "name": stage,
                            "screenshot": False
                            if stage in ["access_rebind", "access_disable"]
                            else screenshot(tv, stage),
                        }
                    )
                    print("Verified stage: " + stage, flush=True)
                if line.startswith("INSTRUMENTATION_RESULT: result="):
                    result = json.loads(line.split("result=", 1)[1])
                if not line and proc.poll() is not None:
                    break
            elif proc.poll() is not None:
                break
        if proc.poll() is None:
            proc.terminate()
            raise RuntimeError("Bounded instrumentation deadline exceeded")
        if result is None:
            raise RuntimeError(
                "Instrumentation returned no result; inspect Focus-only diagnostics"
            )
        result["stages"] = stages
        return result
    finally:
        selector.close()


def finish(tv, proof, dns, access):
    tv.launch()
    time.sleep(2)
    try:
        proof["service_restart_attempts"] = restore_access(tv)
    except (RuntimeError, OSError, subprocess.SubprocessError) as e:
        proof["service_restart_failure"] = str(e)
    time.sleep(2)
    services = tv.adb("shell", "dumpsys", "activity", "services", APP)
    proof["restriction_service_running"] = any(
        "ServiceRecord" in line and ".RestrictionService" in line
        for line in services.splitlines()
    )
    proof["monitor_service_running"] = any(
        "ServiceRecord" in line and ".MonitorService" in line
        for line in services.splitlines()
    )
    proof["accessibility_enabled"] = (
        tv.settings("secure", "accessibility_enabled") == "1"
    )
    proof["private_dns_unchanged"] = all(
        tv.settings("global", k) == v for k, v in dns.items()
    )
    proof["accessibility_list_unchanged"] = (
        tv.settings("secure", "enabled_accessibility_services") == access
    )
    proof["test_packages_removed"] = all(
        not tv.adb("shell", "pm", "path", pkg, check=False) for pkg in [FIXTURE, TEST]
    )
    proof["companion_installed"] = bool(tv.adb("shell", "pm", "path", APP))
    proof["app_sha256"] = hashlib.sha256(
        (ROOT / "build" / "focus-tv.apk").read_bytes()
    ).hexdigest()
    apk_path = tv.adb("shell", "pm", "path", APP).removeprefix("package:").strip()
    proof["installed_app_matches_build"] = (
        tv.adb("shell", "sha256sum", apk_path).split()[0] == proof["app_sha256"]
    )
    proof["verified_at_utc"] = datetime.datetime.now(datetime.UTC).isoformat()
    proof["passed"] = all(
        proof.get(k)
        for k in [
            "installed_app_matches_build",
            "accessibility_enabled",
            "restriction_service_running",
            "monitor_service_running",
            "private_dns_unchanged",
            "accessibility_list_unchanged",
            "test_packages_removed",
            "companion_installed",
        ]
    ) and all(
        proof.get(k, {}).get("passed")
        for k in ["runtime", "restart", "test_state_cleanup"]
    )
    (ROOT / "evidence" / "private" / "device-check.json").write_text(
        json.dumps(proof, indent=2) + "\n"
    )
    print(json.dumps(proof, indent=2), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True)
    p.add_argument("--finish-cleanup", action="store_true")
    args = p.parse_args()
    tv = TV(args.serial)
    firmware = tv.verify()
    (ROOT / "evidence" / "private").mkdir(parents=True, exist_ok=True)
    for pkg in [FIXTURE, TEST]:
        if tv.adb("shell", "pm", "path", pkg, check=False):
            raise RuntimeError("Test package collision: " + pkg)
    dns = {
        k: tv.settings("global", k)
        for k in ["private_dns_mode", "private_dns_specifier"]
    }
    access = tv.settings("secure", "enabled_accessibility_services")
    if args.finish_cleanup:
        proof = json.loads((ROOT / "evidence" / "private" / "device-check.json").read_text())
        expected = hashlib.sha256(
            (ROOT / "build" / "focus-tv.apk").read_bytes()
        ).hexdigest()
        if (
            proof.get("app_sha256") != expected
            or not all(
                proof.get(k, {}).get("passed")
                for k in ["runtime", "restart", "test_state_cleanup"]
            )
            or not all(
                proof.get(k)
                for k in [
                    "private_dns_unchanged",
                    "accessibility_list_unchanged",
                    "test_packages_removed",
                ]
            )
        ):
            raise RuntimeError("No matching completed runtime checks to recover")
        proof.setdefault("earlier_cleanup_failures", []).append(
            {
                "failure": proof.pop("service_restart_failure", None),
                "restriction_service_running": proof.get("restriction_service_running"),
            }
        )
        finish(tv, proof, dns, access)
        if not proof.get("passed"):
            raise RuntimeError("Cleanup recovery did not pass")
        return
    installed = []
    proof = {"firmware": firmware}
    try:
        for pkg, variant in [(FIXTURE, "fixture"), (TEST, "device")]:
            print(tv.install(ROOT / "build" / ("test-" + variant + ".apk")), flush=True)
            installed.append(pkg)
        proof["runtime"] = instrument(tv, "full")
        if not proof["runtime"].get("passed"):
            raise RuntimeError(proof["runtime"].get("failure", "Device check failed"))
        tv.adb("shell", "am", "force-stop", APP)
        tv.launch()
        proof["restart"] = instrument(tv, "restart-check")
        if not proof["restart"].get("passed"):
            raise RuntimeError(proof["restart"].get("failure", "Restart check failed"))
    finally:
        if TEST in installed:
            try:
                proof["test_state_cleanup"] = instrument(tv, "cleanup")
            except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as e:
                proof["cleanup_failure"] = str(e)
        for pkg in reversed(installed):
            tv.adb("uninstall", pkg, check=False, timeout=40)
        finish(tv, proof, dns, access)
    if not proof.get("passed"):
        raise RuntimeError(
            "One or more runtime/cleanup checks failed; see device-check.json"
        )


if __name__ == "__main__":
    main()

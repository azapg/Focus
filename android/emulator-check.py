#!/usr/bin/env python3
"""Run real Android integration checks on an explicitly selected disposable emulator."""

import argparse
import json
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent
APP = "org.nightingalelab.focus.phone"
TEST_APP = "org.nightingalelab.focus.phone.tests"
COMPONENT = APP + "/" + APP + ".FocusAccessibilityService"


def wslpath_w(p):
    return subprocess.check_output(["wslpath", "-w", str(p)]).decode().strip()


class Emulator:
    def __init__(self, serial, executable):
        self.serial = serial
        self.executable = executable
        self.use_win = executable.lower().endswith(".exe")

    def adb(self, *args, timeout=60, check=True):
        cwd = "/mnt/c" if self.use_win else None
        p = subprocess.run(
            [self.executable, "-s", self.serial, *args],
            check=False,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            cwd=cwd,
            timeout=timeout,
        )
        out = p.stdout.decode(errors="replace").replace("\r", "").strip()
        err = p.stderr.decode(errors="replace").replace("\r", "").strip()
        if check and p.returncode:
            raise RuntimeError(err or out or f"ADB failed ({p.returncode})")
        return out

    def install(self, apk_path):
        target = wslpath_w(apk_path) if self.use_win else str(apk_path)
        return self.adb("install", "-r", target, timeout=120)


def run_instrument(emu, mode):
    raw = emu.adb(
        "shell",
        f"am instrument -w -e mode {mode} {TEST_APP}/.PhoneDeviceChecks",
        timeout=90,
    )
    line = next(
        (l.split("result=", 1)[1] for l in raw.splitlines() if "INSTRUMENTATION_RESULT: result=" in l),
        None,
    )
    if line is None:
        raise RuntimeError(f"No instrumentation result for mode={mode}: {raw}")
    return json.loads(line), "INSTRUMENTATION_CODE: -1" in raw


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb", help="ADB executable (native adb on PATH by default)")
    parser.add_argument("--linux-adb", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--ask-only", action="store_true", help="Run Ask and countdown verification")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("This check only runs on a disposable emulator, never a physical phone.")

    emu = Emulator(args.serial, "adb" if args.linux_adb else args.adb)
    model = emu.adb("shell", "getprop ro.product.model")
    sdk = emu.adb("shell", "getprop ro.build.version.sdk")
    if "sdk" not in model.lower():
        raise SystemExit(f"Expected Android SDK emulator, got {model}")

    # Install app + instrumentation APK
    emu.install(ROOT / "build" / "focus-phone.apk")
    emu.install(ROOT / "build" / "test-phone.apk")

    # Grant permissions on disposable emulator
    emu.adb("shell", f"appops set {APP} GET_USAGE_STATS allow")
    emu.adb("shell", f"pm grant {APP} android.permission.WRITE_SECURE_SETTINGS")
    emu.adb("shell", f"pm grant {APP} android.permission.POST_NOTIFICATIONS", check=False)
    emu.adb("shell", f"appops set {APP} SCHEDULE_EXACT_ALARM allow")
    emu.adb("shell", f"settings put secure enabled_accessibility_services {COMPONENT}")
    emu.adb("shell", "settings put secure accessibility_enabled 1")
    emu.adb("shell", f"am start -W -n {APP}/.MainActivity")
    time.sleep(2)

    try:
        checks, passed = run_instrument(emu, "verify_ask" if args.ask_only else "verify")
        report = {"platform": f"{model} (Android API {sdk})", "checks": checks,
                  "passed": passed and "error" not in checks}
        print(json.dumps(report, indent=2))
        if not report["passed"]:
            raise SystemExit(1)
    finally:
        emu.adb("uninstall", TEST_APP, check=False)


if __name__ == "__main__":
    main()

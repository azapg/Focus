#!/usr/bin/env python3
"""Install only Focus on an explicitly addressed Android TV; never changes DNS."""

import argparse
import json
import os
import pathlib
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
APP = "org.nightingalelab.focus.tv"
COMPONENT = APP + "/" + APP + ".RestrictionService"


def adb_bin():
    configured = os.environ.get("FOCUS_TV_ADB")
    if configured:
        return configured
    sdk = pathlib.Path(
        os.environ.get("FOCUS_TV_SDK") or os.environ.get("ANDROID_HOME")
        or os.environ.get("ANDROID_SDK_ROOT") or pathlib.Path.home() / "Android/Sdk"
    )
    for name in ("adb", "adb.exe"):
        candidate = sdk / "platform-tools" / name
        if candidate.is_file():
            return str(candidate)
    return "adb"


ADB = adb_bin()


def win(path):
    return subprocess.check_output(["wslpath", "-w", str(path)]).decode().strip()


class TV:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, timeout=30, check=True, binary=False):
        if args[0] == "shell":
            args = ("shell", shlex.join(args[1:]))
        p = subprocess.run(
            [ADB, "-s", self.serial, *args],
            check=False,
            capture_output=True,
            timeout=timeout,
        )
        if check and p.returncode:
            raise RuntimeError(
                p.stderr.decode(errors="replace").strip() or "ADB command failed"
            )
        return (
            p.stdout
            if binary
            else p.stdout.decode(errors="replace").replace("\r", "").strip()
        )

    def settings(self, space, key):
        return self.adb("shell", "settings", "get", space, key)

    def put(self, space, key, value):
        return (
            self.adb("shell", "settings", "delete", space, key)
            if value == "null"
            else self.adb("shell", "settings", "put", space, key, value)
        )

    def verify(self):
        if self.adb("get-state") != "device":
            raise RuntimeError("TV debugging is not authorized")
        features = self.adb("shell", "pm", "list", "features")
        if "android.software.leanback" not in features:
            raise RuntimeError("Target is not an Android TV")
        return {
            k: self.adb("shell", "getprop", k)
            for k in [
                "ro.product.manufacturer",
                "ro.product.model",
                "ro.build.version.release",
                "ro.build.version.sdk",
            ]
        }

    def install(self, path, update=False):
        use_windows_path = ADB.lower().endswith(".exe")
        apk = win(path) if use_windows_path else str(path)
        return self.adb("install", *(["-r"] if update else []), apk, timeout=90)

    def launch(self):
        return self.adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")


class AccessBindingError(RuntimeError):
    pass


def enable_access(tv, rebind=False):
    raw = tv.settings("secure", "enabled_accessibility_services")
    values = [] if raw in ("null", "") else raw.split(":")
    own = {COMPONENT, APP + "/.RestrictionService"}
    others = [v for v in values if v not in own]
    if tv.settings("secure", "accessibility_enabled") != "1" and others:
        raise RuntimeError("Disabled unrelated accessibility services exist")
    if rebind and any(v in own for v in values):
        tv.put("secure", "enabled_accessibility_services", ":".join(others))
        tv.put("secure", "accessibility_enabled", "1" if others else "0")
        # TCL releases the old binder asynchronously after clearing the settings.
        time.sleep(2)
        for _ in range(12):
            enabled = [
                line
                for line in tv.adb("shell", "dumpsys", "accessibility").splitlines()
                if "Enabled services:" in line or "Crashed services:" in line
            ]
            if not any(APP in line for line in enabled):
                break
            time.sleep(0.1)
    tv.put("secure", "enabled_accessibility_services", ":".join(others + [COMPONENT]))
    tv.put("secure", "accessibility_enabled", "1")
    # Let the system finish binding before asking ActivityManager to dump it.
    time.sleep(3)
    for _ in range(20):
        services = tv.adb("shell", "dumpsys", "activity", "services", APP)
        if any(
            "ServiceRecord" in line and ".RestrictionService" in line
            for line in services.splitlines()
        ):
            return
        time.sleep(0.2)
    raise AccessBindingError("Focus accessibility did not bind")


def restore_access(tv):
    attempts = []
    for attempt in range(1, 4):
        try:
            enable_access(tv, rebind=True)
            attempts.append({"attempt": attempt, "bound": True})
            return attempts
        except AccessBindingError:
            attempts.append({"attempt": attempt, "bound": False})
            if attempt == 3:
                raise
            tv.launch()
            time.sleep(2)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True)
    p.add_argument("--enable-access", action="store_true")
    p.add_argument(
        "--enable-dns-controls",
        action="store_true",
        help="Grant Focus the development settings permission for TV Private DNS controls",
    )
    p.add_argument("--update", action="store_true")
    args = p.parse_args()
    tv = TV(args.serial)
    firmware = tv.verify()
    exists = bool(tv.adb("shell", "pm", "path", APP, check=False))
    if exists and not args.update:
        raise RuntimeError(
            "Focus already exists; use --update only for a known installation"
        )
    dns = {
        k: tv.settings("global", k)
        for k in ["private_dns_mode", "private_dns_specifier"]
    }
    before = {
        k: tv.settings("secure", k)
        for k in ["enabled_accessibility_services", "accessibility_enabled"]
    }
    if (
        args.enable_access
        and before["accessibility_enabled"] != "1"
        and any(
            v not in {COMPONENT, APP + "/.RestrictionService", "null", ""}
            for v in before["enabled_accessibility_services"].split(":")
        )
    ):
        raise RuntimeError(
            "Disabled third-party accessibility services exist; enable Focus through TV Settings to preserve their state"
        )
    ROOT.joinpath("build").mkdir(exist_ok=True)
    backup = ROOT / "build" / "accessibility-before-install.json"
    if not backup.exists():
        backup.write_text(json.dumps({"serial": args.serial, "settings": before}))
        backup.chmod(0o600)
    changed = False
    try:
        print(tv.install(ROOT / "build" / "focus-tv.apk", exists))
        if args.enable_dns_controls:
            tv.adb(
                "shell", "pm", "grant", APP, "android.permission.WRITE_SECURE_SETTINGS"
            )
        if args.enable_access:
            tv.adb("shell", "appops", "set", APP, "GET_USAGE_STATS", "allow")
            changed = True
            restore_access(tv)
        print(tv.launch())
        same = all(tv.settings("global", k) == v for k, v in dns.items())
        if not same:
            raise RuntimeError(
                "Private DNS no longer matches pre-install state; installer did not write it"
            )
        proof = {
            "firmware": firmware,
            "installed": True,
            "private_dns_unchanged": same,
            "access_setup_requested": args.enable_access,
            "update": exists,
        }
        (ROOT / "build" / "installation.json").write_text(
            json.dumps(proof, indent=2) + "\n"
        )
        print(json.dumps(proof))
    except BaseException:
        if changed:
            for k, v in before.items():
                tv.put("secure", k, v)
        raise


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)

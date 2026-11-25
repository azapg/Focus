#!/usr/bin/env python3
"""Install Focus for Android on an explicitly addressed phone (USB or Wi-Fi ADB). Never changes DNS."""

import argparse
import os
import pathlib
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
APP = "org.nightingalelab.focus.phone"
COMPONENT = APP + "/" + APP + ".FocusAccessibilityService"
DEFAULT_ADB = pathlib.Path(
    os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or pathlib.Path.home() / "Android/Sdk"
) / "platform-tools/adb"


def adb_bin():
    env = os.environ.get("FOCUS_PHONE_ADB")
    if env:
        return env
    if os.path.exists(DEFAULT_ADB):
        return str(DEFAULT_ADB)
    return "adb"


class Phone:
    def __init__(self, serial):
        self.serial = serial
        self.adb_path = adb_bin()

    def adb(self, *args, timeout=30, check=True):
        if args[0] == "shell":
            args = ("shell", shlex.join(args[1:]))
        p = subprocess.run(
            [self.adb_path, "-s", self.serial, *args],
            check=False,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            timeout=timeout,
        )
        if check and p.returncode:
            raise RuntimeError(p.stderr.decode(errors="replace").strip() or "ADB command failed")
        return p.stdout.decode(errors="replace").replace("\r", "").strip()

    def settings(self, space, key):
        return self.adb("shell", "settings", "get", space, key)

    def put(self, space, key, value):
        if value == "null":
            return self.adb("shell", "settings", "delete", space, key)
        return self.adb("shell", "settings", "put", space, key, value)


def enable_accessibility(phone):
    raw = phone.settings("secure", "enabled_accessibility_services")
    values = [] if raw in ("null", "") else [v for v in raw.split(":") if v]
    own = {COMPONENT, APP + "/.FocusAccessibilityService"}
    others = [v for v in values if v not in own]
    phone.put("secure", "enabled_accessibility_services", ":".join(others + [COMPONENT]))
    phone.put("secure", "accessibility_enabled", "1")
    time.sleep(2)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True, help="ADB serial or Wi-Fi host:port (e.g. 192.0.2.20:37891)")
    p.add_argument("--enable-access", action="store_true", help="Enable Usage Access and Accessibility")
    p.add_argument("--enable-dns-controls", action="store_true", help="Grant WRITE_SECURE_SETTINGS for Private DNS control")
    p.add_argument("--update", action="store_true", help="Update an existing Focus install")
    args = p.parse_args()

    phone = Phone(args.serial)
    if phone.adb("get-state") != "device":
        raise RuntimeError("Phone debugging is not authorized")

    apk = ROOT / "build" / "focus-phone.apk"
    if not apk.exists():
        raise RuntimeError("Build first: bash android/build.sh")

    dns_before = {k: phone.settings("global", k) for k in ("private_dns_mode", "private_dns_specifier")}
    apk_arg = str(apk)
    if phone.adb_path.endswith(".exe"):
        apk_arg = subprocess.check_output(["wslpath", "-w", apk_arg]).decode().strip()

    print(phone.adb("install", *(["-r"] if args.update else []), apk_arg, timeout=90))
    if args.enable_dns_controls:
        phone.adb("shell", "pm", "grant", APP, "android.permission.WRITE_SECURE_SETTINGS")
        phone.adb("shell", "appops", "set", APP, "SCHEDULE_EXACT_ALARM", "allow")
    if args.enable_access:
        phone.adb("shell", "appops", "set", APP, "ACCESS_RESTRICTED_SETTINGS", "allow", check=False)
        phone.adb("shell", "appops", "set", APP, "GET_USAGE_STATS", "allow")
        enable_accessibility(phone)
    print(phone.adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity"))

    dns_after = {k: phone.settings("global", k) for k in ("private_dns_mode", "private_dns_specifier")}
    if dns_before != dns_after:
        raise RuntimeError("Private DNS changed unexpectedly during install")
    print("Installed Focus for Android cleanly. Private DNS was left untouched.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)

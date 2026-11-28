"""Native Focus proof flows. Run only from scripts/screenshot.py."""

import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLOCK = datetime(2026, 2, 12, 14, tzinfo=timezone.utc)


def build(target, sdk, work, env):
    build_tools = sorted(
        (p for p in (sdk / "build-tools").glob("*") if (p / "aapt").is_file()),
        reverse=True,
    )
    platform = "android-36" if target == "android" else "android-35"
    android_jar = sdk / "platforms" / platform / "android.jar"
    if not build_tools or not android_jar.is_file():
        raise RuntimeError(f"Install Linux Android build-tools and SDK platform {platform}")
    sources = work / "sources"
    files = [f"{target}/{name}" for name in (
        "build.sh", "build-tests.sh", "AndroidManifest.xml", "res", "src", "tests",
    )]
    files += ["telemetry/android/LocalTelemetry.java", "shared/android"]
    if target == "android":
        files += ["android/generate-ask-policy.py", "shared/ask-retry.json",
                  "app/src/judge_prompt.txt", "app/src/services.json"]
        files.append("scripts/updater-repo.sh")
    for relative in files:
        src, dst = ROOT / relative, sources / relative
        if src.is_dir():
            shutil.copytree(src, dst)
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
    env = {
        **env, "FOCUS_UPDATER_REPO": "", "FOCUS_ANDROID_BUILD_TOOLS": str(build_tools[0]),
        "FOCUS_PHONE_ANDROID": str(android_jar), "FOCUS_TV_ANDROID": str(android_jar),
        "FOCUS_BUILD_ID": "111111a", "FOCUS_COMMIT_SHA": "111111a" + "0" * 33,
        "FOCUS_COMMIT_SUBJECT": "Add a seed catalog example",
        "FOCUS_BUILT_AT": "2026-02-11T22:00:00Z",
        "FOCUS_ANDROID_VERSION_CODE": "2000000", "FOCUS_ANDROID_VERSION_NAME": "0.2.0-demo",
    }
    build_args = ["--screenshot-ui"] if target == "android" else []
    subprocess.run(["bash", "build.sh", *build_args], cwd=sources / target, env=env, check=True, timeout=360)
    subprocess.run(["bash", "build-tests.sh"], cwd=sources / target, env=env, check=True, timeout=360)
    return sources / target / "build"


def create_avd(home, sdk, image, tv):
    image_dir = (sdk / image).resolve()
    image_root = (sdk / "system-images").resolve()
    if not image_dir.is_relative_to(image_root) or not (image_dir / "system.img").is_file():
        raise RuntimeError("Expected an installed SDK system image below system-images/")
    if not (image_dir / "userdata.img").is_file() and not (image_dir / "data").is_dir():
        raise RuntimeError("Reinstall the SDK system image. Its initial guest data is missing.")
    api, tag, abi = image_dir.relative_to(image_root).parts
    if abi != "x86_64" or tag not in {"default", "android-tv"}:
        raise RuntimeError("Use a debuggable x86_64 AOSP or Android TV image")

    avd_dir = home / "avd" / "FocusProof.avd"
    avd_dir.mkdir(parents=True)
    avd_dir.with_suffix(".ini").write_text(f"path={avd_dir}\ntarget={api}\n")
    width, height, dpi = (1280, 720, 160) if tv else (540, 960, 240)
    (avd_dir / "config.ini").write_text(
        f"AvdId=FocusProof\nabi.type={abi}\nhw.cpu.arch={abi}\nhw.ramSize=1536\n"
        f"hw.keyboard=yes\nhw.lcd.width={width}\nhw.lcd.height={height}\nhw.lcd.density={dpi}\n"
        f"disk.dataPartition.size=4G\nimage.sysdir.1={image_dir}/\ntag.id={tag}\n"
    )


def capture(scenario, sdk, image, work, output):
    tv = scenario in {"tv", "tv-store"}
    target = "tv" if tv else "android"
    adb_path = sdk / "platform-tools" / "adb"
    emulator_path = sdk / "emulator" / "emulator"
    if not all(p.is_file() for p in (adb_path, emulator_path)):
        raise RuntimeError("Install the Linux SDK platform-tools and Android Emulator")
    home = work / "guest-home"
    home.mkdir()
    create_avd(home, sdk, image, tv)
    path = os.defpath
    if jdk := os.environ.get("JAVA_HOME"):
        path = str(Path(jdk).resolve() / "bin") + os.pathsep + path
    env = {
        "HOME": str(home), "LANG": "C.UTF-8", "PATH": path, "TMPDIR": str(work),
        "ANDROID_HOME": str(sdk), "ANDROID_USER_HOME": str(home),
        "ANDROID_EMULATOR_HOME": str(home), "ANDROID_AVD_HOME": str(home / "avd"),
        "ANDROID_ADB_SERVER_PORT": "5037", "ADB_USB": "0",
        "JAVA_TOOL_OPTIONS": f'-XX:ActiveProcessorCount=2 "-Djava.io.tmpdir={work}"',
    }
    accelerated = os.access("/dev/kvm", os.R_OK | os.W_OK)
    if not accelerated:
        env["ANDROID_I_WANT_MY_TCG"] = "yes"
    apks = build(target, sdk, work, env)
    if Path("/dev/bus/usb").is_dir():
        empty = work / "no-usb"
        empty.mkdir()
        subprocess.run(["mount", "--bind", empty, "/dev/bus/usb"], check=True)
    subprocess.run(["ip", "link", "set", "lo", "up"], check=True)
    subprocess.run([adb_path, "start-server"], env=env, check=True, timeout=30)

    def adb(*args, binary=False, timeout=90):
        return subprocess.check_output(
            [adb_path, "-s", "emulator-5554", *args],
            env=env, timeout=timeout, text=not binary, stdin=subprocess.DEVNULL,
        )

    with (work / "emulator.log").open("w+") as log:
        emulator = subprocess.Popen(
            [emulator_path, "-avd", "FocusProof", "-port", "5554", "-no-snapshot",
             "-no-window", "-no-audio", "-no-boot-anim", "-no-metrics", "-show-kernel",
             "-gpu", "swiftshader_indirect", "-memory", "1536", "-timezone", "Etc/UTC",
             "-accel", "on" if accelerated else "off"],
            env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        )
        try:
            deadline = time.monotonic() + (180 if accelerated else 1800)
            while time.monotonic() < deadline and emulator.poll() is None:
                try:
                    adb("wait-for-device", timeout=10)
                    if adb("shell", "getprop", "sys.boot_completed", timeout=15).strip() == "1":
                        break
                except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
                    pass
                time.sleep(2)
            else:
                raise RuntimeError("The emulator did not boot")
            capture_views(scenario, adb, apks, output)
        except Exception:
            log.seek(0)
            lines = (line for line in log if "adb public key" not in line and "adb.pubkey" not in line)
            print("".join(lines)[-5000:])
            raise
        finally:
            emulator.terminate()
            try:
                emulator.wait(timeout=15)
            except subprocess.TimeoutExpired:
                emulator.kill()
                emulator.wait()


def capture_views(scenario, adb, apks, output):
    tv = scenario in {"tv", "tv-store"}
    package = "org.nightingalelab.focus.tv" if tv else "org.nightingalelab.focus.phone"

    def shell(*args):
        return adb("shell", *args).strip()

    def dump():
        remote = "/data/local/tmp/focus-proof.xml"
        shell("rm", "-f", remote)
        shell("uiautomator", "dump", remote)
        return ET.fromstring(shell("cat", remote)).iter("node")

    def wait_for_view(label, selected=False):
        visible = []
        for _ in range(6):
            try:
                nodes = list(dump())
            except (subprocess.CalledProcessError, ET.ParseError):
                continue
            visible = [
                f"{node.get('text', '')} [{node.get('content-desc', '')}]"
                for node in nodes if node.get("package") == package
                and (node.get("text") or node.get("content-desc"))
            ]
            if any(
                node.get("package") == package
                and label in (node.get("text", "") + " " + node.get("content-desc", ""))
                and (not selected or node.get("selected") == "true") for node in nodes
            ):
                return
        raise RuntimeError(f"Focus view did not appear: {label}; visible Focus nodes: {visible[:60]}")

    def tap(label):
        node = next((node for node in dump() if node.get("package") == package
                     and label in (node.get("text", "") + " " + node.get("content-desc", ""))
                     and node.get("clickable") == "true"), None)
        if node is None:
            raise RuntimeError(f"Focus control did not appear: {label}")
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def shot(name, label, selected=False):
        wait_for_view(label, selected)
        image_bytes = adb("exec-out", "screencap", "-p", binary=True)
        wait_for_view(label, selected)
        if not image_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("Emulator did not return a PNG")
        (output / name).write_bytes(image_bytes)

    if not any(shell("getprop", key) == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")):
        raise RuntimeError("ADB target is not an emulator")
    shell("settings", "put", "global", "auto_time", "0")
    shell("settings", "put", "global", "auto_time_zone", "0")
    shell("cmd", "alarm", "set-timezone", "Etc/UTC")
    shell("su", "0", "date", "-u", CLOCK.strftime("%m%d%H%M%Y.%S"))
    if shell("date", "-u", "+%Y-%m-%d") != CLOCK.date().isoformat():
        raise RuntimeError("Failed to set the fictional fixture date")
    shell("wm", "dismiss-keyguard")
    app, test = ("focus-tv.apk", "test-device.apk") if tv else ("focus-phone.apk", "test-phone.apk")
    install = [app, test]
    if scenario == "tv-store":
        install.append("test-fixture.apk")
    for apk in install:
        adb("install", str(apks / apk), timeout=180)
    shell("appops", "set", package, "GET_USAGE_STATS", "allow")
    if int(shell("getprop", "ro.build.version.sdk")) >= 33:
        # Leave guest notifications denied and suppress the first-run permission prompt.
        shell("pm", "set-permission-flags", package, "android.permission.POST_NOTIFICATIONS",
              "user-set", "user-fixed")

    runner = "DeviceChecks" if tv else "PhoneDeviceChecks"
    if tv:
        result = adb("shell", "am", "instrument", "-w", "-e", "phase", "seed-screenshot",
                     "-e", "flow", "store" if scenario == "tv-store" else "today",
                     f"{package}.tests/.{runner}", timeout=180)
    else:
        mode = {
            "phone-store-zero": "seed_store_zero",
            "phone-store": "seed_store_showcase",
        }.get(scenario, "seed_showcase")
        result = adb("shell", "am", "instrument", "-w", "-e", "mode", mode,
                     f"{package}.tests/.{runner}", timeout=180)
    if "INSTRUMENTATION_CODE: -1" not in result:
        raise RuntimeError(f"Fictional seed failed\n{result}")
    activity = f"{package}/.MainActivity"
    if tv:
        if scenario == "tv-store":
            shell("settings", "put", "secure", "enabled_accessibility_services",
                  f"{package}/.RestrictionService")
            shell("settings", "put", "secure", "accessibility_enabled", "1")
            shell("am", "start", "-W", "-n", f"{package}.fixture/.FixtureActivity")
            shot("tv-blocked-app.png", "Store free time")
            shell("input", "keyevent", "KEYCODE_DPAD_CENTER")
            shot("tv-free-time-store.png", "5 min free · 15 coins")
        else:
            shell("am", "start", "-W", "-n", activity)
            shot("tv-today.png", "Today", selected=True)
            # Today receives initial D-pad focus in the production TV activity.
            shell("input", "keyevent", "KEYCODE_DPAD_RIGHT")
            shell("input", "keyevent", "KEYCODE_DPAD_CENTER")
            shot("tv-week.png", "Week", selected=True)
    elif scenario == "phone-store":
        shell("am", "start", "-W", "-n", activity, "--es", "page", "today")
        shot("phone-store-today.png", "65 coins")
        tap("65 coins")
        shot("phone-store-sheet.png", "15 minutes for today")
        tap("15 coins")
        shot("phone-store-confirm.png", "Buy 15 minutes?")
        tap("Confirm")
        shot("phone-store-receipt.png", "15 minutes added")
        tap("Done")
        shot("phone-store-bought.png", "50 coins")
    elif scenario == "phone-store-zero":
        shell("am", "start", "-W", "-n", activity, "--es", "page", "today")
        shot("phone-today-zero-coins.png", "0 coins")
        tap("0 coins")
        shot("phone-store-zero-coins.png", "Store")
    else:
        shell("am", "start", "-W", "-n", activity, "--es", "page", "today")
        shot("phone-today.png", "Today")
        shell("am", "start", "-W", "-n", activity, "--es", "page", "week")
        shot("phone-week.png", "Last 7 days")
        shell("am", "start", "-W", "-n", activity, "--ez", "open_update_sheet", "true")
        shot("phone-update.png", "Update ready")

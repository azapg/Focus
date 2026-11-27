#!/usr/bin/env python3
"""Capture Focus with fictional fixtures inside a private Linux network namespace."""

import argparse
import asyncio
import os
import runpy
import shutil
import socket
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
DESKTOP = {
    "activity": "panel-browser.py",
    "devices": "device-panel.py",
    "updates": "updater-ui.py",
    "store": "wallet-store.py",
    "intentions": "intentions-browser.py",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=[*DESKTOP, "desktop", "phone", "phone-store", "tv"])
    parser.add_argument("--out", type=Path, required=True, help="New directory for the PNGs")
    parser.add_argument("--sdk", type=Path, help="SDK root for phone or TV capture")
    parser.add_argument("--image", help="Installed SDK image, relative to the SDK root")
    args = parser.parse_args()

    native = args.scenario in {"phone", "phone-store", "tv"}
    if native and not (args.sdk and args.image):
        parser.error("phone and TV require --sdk and --image")
    if not native and (args.sdk or args.image):
        parser.error("--sdk and --image apply to phone and TV")
    if sys.platform != "linux":
        parser.error("Focus screenshot capture requires Linux network namespaces")

    parent_namespace = os.environ.pop("FOCUS_SCREENSHOT_PARENT_NET", None)
    current_namespace = os.readlink("/proc/self/ns/net")
    if parent_namespace is None:
        os.environ["FOCUS_SCREENSHOT_PARENT_NET"] = current_namespace
        unshare = shutil.which("unshare")
        if unshare is None:
            parser.error("unshare (util-linux) is required")
        os.execv(unshare, [unshare, "--user", "--map-root-user", "--net", "--pid",
                           "--fork", "--kill-child", "--mount-proc", "--", sys.executable, __file__, *sys.argv[1:]])
    if parent_namespace == current_namespace or os.getpid() != 1:
        parser.error("could not establish the isolated network and process namespaces")
    if {name for _, name in socket.if_nameindex()} != {"lo"}:
        parser.error("refusing capture with a network interface other than loopback")

    output = args.out.resolve()
    if output.exists():
        parser.error(f"output directory already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(ROOT / "tests"))
    with TemporaryDirectory(prefix=".focus-capture-", dir=output.parent) as staging:
        shots = Path(staging) / "images"
        shots.mkdir()
        if native:
            from screenshot_emulator import capture
            capture(args.scenario, args.sdk.resolve(), args.image, Path(staging), shots)
        else:
            if not (ROOT / "ui-dist/index.html").is_file():
                parser.error("run npm ci and npm run build first")
            scripts = DESKTOP.values() if args.scenario == "desktop" else [DESKTOP[args.scenario]]
            for script in scripts:
                flow = runpy.run_path(str(ROOT / "tests" / script))
                asyncio.run(flow["main"](shots))
        if not list(shots.glob("*.png")):
            parser.error("the proof flow produced no PNGs")
        shots.rename(output)
    print(output)


if __name__ == "__main__":
    main()

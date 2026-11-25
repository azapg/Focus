#!/usr/bin/env python3
"""Enforce the phone nightly's delivery budget and native release requirements."""

import argparse
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile


APK_BUDGET = 16 * 1024 * 1024  # Existing installs reject downloads larger than 32 MiB.
PEEK_LIBRARY = "lib/arm64-v8a/libfocus_phone_peek.so"
PEEK_SYMBOL = "Java_org_nightingalelab_focus_phone_PeekActivity_fetchNative"


def check(apk_path):
    size = apk_path.stat().st_size
    with ZipFile(apk_path) as apk:
        entries = apk.infolist()
        print(f"{'APK entry':65} {'In ZIP (B)':>12} {'Unpacked (B)':>13}")
        for entry in sorted(entries, key=lambda entry: entry.compress_size, reverse=True):
            print(f"{entry.filename:65} {entry.compress_size:12,d} {entry.file_size:13,d}")
        print(f"{apk_path.name}: {size:,} bytes total (budget {APK_BUDGET:,} bytes)", flush=True)
        if size > APK_BUDGET:
            raise SystemExit("Phone APK exceeds the release size budget")
        native_entries = [entry.filename for entry in entries if entry.filename.startswith("lib/")]
        if native_entries != [PEEK_LIBRARY]:
            raise SystemExit(f"Expected only {PEEK_LIBRARY}, got {native_entries}")
        with tempfile.TemporaryDirectory() as directory:
            lib = Path(directory) / "libfocus_phone_peek.so"
            lib.write_bytes(apk.read(PEEK_LIBRARY))
            check_elf(lib)


def check_elf(lib):
    headers = subprocess.check_output(["readelf", "-W", "-h", "-l", lib], text=True)
    if not any(line.split() == ["Machine:", "AArch64"] for line in headers.splitlines()):
        raise SystemExit("Peek library is not AArch64")
    loads = [line.split() for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
    if not loads or any(
        int(load[-1], 16) < 16384 or (int(load[1], 16) - int(load[2], 16)) % 16384
        for load in loads
    ):
        raise SystemExit("Peek library LOAD segments must support 16 KB pages")
    symbols = subprocess.check_output(["readelf", "-W", "--dyn-syms", lib], text=True)
    if not any(
        len(fields) == 8
        and fields[3:6] == ["FUNC", "GLOBAL", "DEFAULT"]
        and fields[6] != "UND"
        and fields[7] == PEEK_SYMBOL
        for fields in (line.split() for line in symbols.splitlines())
    ):
        raise SystemExit(f"Peek library is missing the JNI export {PEEK_SYMBOL}")
    print("AArch64 Peek library, JNI export and 16 KB ELF LOAD alignment verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    check(parser.parse_args().apk)

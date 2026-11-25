#!/usr/bin/env python3
"""Summarize local FocusDNS resource samples. Uses only the Python standard library."""

import argparse
import collections
import json
import pathlib
import re
import sys
import time

DAY_FILE = re.compile(r"[0-9]+\.jsonl")
METRIC = re.compile(r"[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*")
COMPONENTS = {"desktop", "browser-host", "chromium-extension", "android-phone", "android-tv"}


def samples(paths):
    files = set()
    for path in paths:
        if path.is_dir():
            files.update(p.resolve() for p in path.rglob("*.jsonl") if DAY_FILE.fullmatch(p.name))
        else:
            files.add(path.resolve())

    for path in sorted(files):
        try:
            with path.open(encoding="utf-8") as source:
                if path.suffix == ".json":
                    rows = json.load(source)
                    if not isinstance(rows, list):
                        raise ValueError("expected a JSON array")
                    yield from rows
                    continue
                for number, line in enumerate(source, 1):
                    if not line.strip():
                        continue
                    try:
                        yield json.loads(line)
                    except ValueError:
                        print(f"Skipped unreadable row {number} in {path}", file=sys.stderr)
        except (OSError, ValueError) as error:
            print(f"Skipped {path}: {error}", file=sys.stderr)


def measure(kind, name, value, duration):
    if kind == "gauges":
        if name.endswith("_bytes"):
            return value / (1024 * 1024), 1, "MiB"
        if name == "power.charging":
            return value * 100, 1, "% of samples"
        return value, 1, "count"
    if name.endswith(".cpu_us"):
        return value / duration / 10, duration, "% of one core"
    if name == "monitor.foreground_ms":
        return value / duration * 100, duration, "% of interval"
    if name.endswith("_bytes"):
        return value * 60_000 / duration / 1024, duration, "KiB/min"
    if name.endswith("_us"):
        return value * 60 / duration, duration, "ms/min"
    if name.endswith("_ms"):
        return value * 60_000 / duration, duration, "ms/min"
    return value * 60_000 / duration, duration, "count/min"


def aggregate(rows, since):
    groups = collections.defaultdict(list)
    for row in rows:
        if (not isinstance(row, dict) or row.get("v") != 1
                or not isinstance(row.get("component"), str) or row["component"] not in COMPONENTS):
            continue
        duration = row.get("duration_ms")
        at = row.get("at_ms")
        if type(duration) is not int or type(at) is not int or not 0 < duration <= 2**53 or at < since:
            continue
        for kind in ("counters", "gauges"):
            fields = row.get(kind)
            if not isinstance(fields, dict):
                continue
            for name, value in fields.items():
                if METRIC.fullmatch(name) and type(value) is int and 0 <= value <= 2**64 - 1:
                    rate, weight, unit = measure(kind, name, value, duration)
                    subsystem, metric = name.split(".")
                    groups[row["component"], subsystem, metric, unit].append((rate, weight))
    return groups


def summarize(values):
    # Weight rates by elapsed time so a short native-host session does not dominate.
    # Gauge readings are point samples with equal weight.
    weight = sum(w for _, w in values)
    cumulative = 0
    for p95, w in sorted(values):
        cumulative += w
        if cumulative * 100 >= weight * 95:
            break
    mean = sum(value * w for value, w in values) / weight
    return mean, p95


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", type=pathlib.Path, nargs="+",
                        help="telemetry directory, JSONL file or Chromium JSON export")
    parser.add_argument("--hours", type=float, help="include windows ending in the last N hours")
    args = parser.parse_args()
    if args.hours is not None and not 0 < args.hours < float("inf"):
        parser.error("--hours must be positive and finite")
    since = time.time() * 1000 - args.hours * 3_600_000 if args.hours else 0
    groups = aggregate(samples(args.paths), since)
    if not groups:
        print("No resource samples found for the requested inputs.", file=sys.stderr)
        return 1

    print("| Component | Subsystem | Metric | Unit | Average | p95 | Samples |")
    print("|---|---|---|---|---:|---:|---:|")
    top = collections.defaultdict(list)
    for (component, subsystem, metric, unit), values in sorted(groups.items()):
        average, p95 = summarize(values)
        print(f"| {component} | {subsystem} | {metric} | {unit} | {average:.3f} | {p95:.3f} | {len(values)} |")
        if average > 0 and unit not in {"% of samples", "% of interval"}:
            top[metric, unit].append((average, f"{component}/{subsystem}"))

    print("\nTop consumers by average (up to three per metric)\n")
    for (metric, unit), values in sorted(top.items()):
        leaders = sorted(values, key=lambda value: (-value[0], value[1]))[:3]
        print(f"- {metric} ({unit}) — " + ", ".join(f"{name} {average:.3f}" for average, name in leaders))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

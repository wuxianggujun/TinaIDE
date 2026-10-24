"""Capture real Android frame/memory data; missing data never counts as a pass."""
from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time


MIN_FRAMES = 60
SCENARIOS = ("scroll-10k-cpp", "typing-10k-cpp", "split-10k-cpp")


def frame_durations(text: str) -> list[float]:
    header: list[str] | None = None
    frames: dict[tuple[int, int], float] = {}
    for row in csv.reader(text.splitlines()):
        if "Flags" in row and "IntendedVsync" in row and "FrameCompleted" in row:
            header = row
            continue
        if not header or len(row) < len(header):
            continue
        values = dict(zip(header, row))
        try:
            if int(values["Flags"]) != 0:
                continue
            start, end = int(values["IntendedVsync"]), int(values["FrameCompleted"])
        except (ValueError, KeyError):
            continue
        # Android uses MAX_INT64 for unfinished frames. Do not turn it into a measured stall.
        if 0 < start < end < 2**63 - 1:
            frames[start, end] = (end - start) / 1_000_000
    return list(frames.values())


def total_pss_kb(text: str) -> int:
    match = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if not match:
        match = re.search(r"(?m)^\s*TOTAL\s+(\d+)\s", text)
    if not match:
        raise ValueError("dumpsys meminfo contains no TOTAL PSS")
    return int(match.group(1))


def metrics(frames: list[float], memory: list[int], refresh_hz: float) -> dict:
    if len(frames) < MIN_FRAMES or not memory:
        raise ValueError(f"Need at least {MIN_FRAMES} valid frames and one memory sample")
    if not math.isfinite(refresh_hz) or refresh_hz <= 0 or any(not math.isfinite(v) or v <= 0 for v in frames) or any(v <= 0 for v in memory):
        raise ValueError("Invalid frame, memory, or refresh rate sample")
    ordered = sorted(frames)
    return {
        "frames": len(frames),
        "p50_ms": ordered[math.ceil(len(ordered) * .5) - 1],
        "p95_ms": ordered[math.ceil(len(ordered) * .95) - 1],
        "jank_ratio": sum(value > 1000 / refresh_hz for value in frames) / len(frames),
        "peak_pss_kb": max(memory),
    }


def compare(report: dict, baseline: dict) -> list[str]:
    if not isinstance(report, dict) or not isinstance(baseline, dict):
        return ["Reports must be JSON objects"]
    if baseline.get("schema") != 1 or baseline.get("status") not in ("unbaselined", "passed"):
        return ["Baseline is not a valid successful capture"]
    for key in ("device", "scenario", "refresh_hz", "build_type", "fixture", "settings_id"):
        if report.get(key) != baseline.get(key):
            return [f"Incomparable baseline: {key} differs"]
    current, reference = report.get("metrics", {}), baseline.get("metrics", {})
    try:
        for record in (current, reference):
            for metric in ("p95_ms", "jank_ratio", "peak_pss_kb", "frames"):
                value = record[metric]
                if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0:
                    raise ValueError(metric)
            if record["frames"] < MIN_FRAMES or record["jank_ratio"] > 1:
                raise ValueError("frames or jank ratio")
    except (KeyError, ValueError, TypeError):
        return ["Baseline contains missing or invalid metrics"]
    # Initial regression tolerances, not device performance targets. Keep them visible in reports.
    limits = {
        "p95_ms": max(reference["p95_ms"] * 1.20, reference["p95_ms"] + 3),
        "jank_ratio": reference["jank_ratio"] + .05,
        "peak_pss_kb": max(reference["peak_pss_kb"] * 1.15, reference["peak_pss_kb"] + 16384),
    }
    return [f"{key}: {current[key]:.3f} exceeds {limit:.3f}" for key, limit in limits.items() if current[key] > limit]


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.wuxianggujun.tinaide")
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    parser.add_argument("--refresh-hz", type=float, required=True, help="Lock this refresh rate on the device before capture")
    parser.add_argument("--build-type", choices=("debug", "release"), required=True)
    parser.add_argument("--revision", required=True, help="Commit or local build identity")
    parser.add_argument("--settings-id", default="default", help="Same font, wrapping, theme and LSP settings must share an ID")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--write-fixture", type=Path, help="Create the fixed 10,000-line C++ workload locally")
    args = parser.parse_args()
    if not 5 <= args.seconds <= 60 or not 30 <= args.refresh_hz <= 240:
        parser.error("seconds must be 5..60 and refresh-hz 30..240")
    if not re.fullmatch(r"[a-zA-Z0-9_.]+", args.package):
        parser.error("Invalid package name")
    if args.write_fixture:
        args.write_fixture.parent.mkdir(parents=True, exist_ok=True)
        args.write_fixture.write_text(
            "// TinaIDE editor baseline fixture v1 — UTF-8 中文\n" +
            "".join(f"int value_{line:05d} = {line}; // 固定测试行\n" for line in range(9999)),
            encoding="utf-8",
        )
        print(f"Fixture written: {args.write_fixture}. Open it in TinaIDE before capturing.")
        return 0

    def adb(*command: str) -> str:
        return subprocess.run([args.adb, "-s", args.serial, *command], check=True,
                              capture_output=True, encoding="utf-8", errors="replace", timeout=15).stdout

    def require_foreground() -> None:
        activities = adb("shell", "dumpsys", "activity", "activities")
        resumed = [line for line in activities.splitlines() if "ResumedActivity" in line or "mResumedActivity" in line]
        if not any(args.package + "/" in line for line in resumed):
            raise ValueError("TinaIDE must stay in the foreground during this scenario")

    args.output.mkdir(parents=True, exist_ok=True)
    report = {"schema": 1, "scenario": args.scenario, "fixture": "cpp-10000-v1",
              "build_type": args.build_type, "revision": args.revision, "refresh_hz": args.refresh_hz,
              "settings_id": args.settings_id, "status": "blocked", "issues": []}
    try:
        device = {key: adb("shell", "getprop", prop).strip() for key, prop in {
            "model": "ro.product.model", "sdk": "ro.build.version.sdk", "abi": "ro.product.cpu.abi",
            "fingerprint": "ro.build.fingerprint",
        }.items()}
        device["size"] = adb("shell", "wm", "size").strip()
        device["density"] = adb("shell", "wm", "density").strip()
        report["device"] = device
        require_foreground()
        adb("shell", "dumpsys", "gfxinfo", args.package, "reset")
        print(f"Capture {args.seconds}s: perform {args.scenario} now.", flush=True)
        samples: list[int] = []
        frame_samples: list[str] = []
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            require_foreground()
            memory = adb("shell", "dumpsys", "meminfo", args.package)
            samples.append(total_pss_kb(memory))
            (args.output / f"meminfo-{len(samples):02d}.txt").write_text(memory, encoding="utf-8")
            frame_samples.append(adb("shell", "dumpsys", "gfxinfo", args.package, "framestats"))
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        raw = "\n".join(frame_samples)
        (args.output / "framestats.txt").write_text(raw, encoding="utf-8")
        report["metrics"] = metrics(frame_durations(raw), samples, args.refresh_hz)
        report["status"] = "unbaselined"
        if args.baseline:
            report["issues"] = compare(report, json.loads(args.baseline.read_text(encoding="utf-8")))
            report["status"] = "failed" if report["issues"] else "passed"
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        report["issues"] = [str(error)]
    (args.output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())

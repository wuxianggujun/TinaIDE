"""Bounded device regression; touches only the separate nativerepeat fixture package.

Each invocation has a 60-second total deadline. Screenshots require visual review;
frame counters alone are not proof of a visible rendering surface.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import time

from build_fixture import OUTPUT, PACKAGE, ROOT


ACTIVITY = f"{PACKAGE}/com.wuxianggujun.tinaide.ui.nativeactivity.RepeatActivity"
LAUNCHER = f"{PACKAGE}/com.wuxianggujun.tinaide.ui.nativeactivity.LaunchActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--suite", choices=("gesture", "key", "home", "early-cancel", "rejected"), required=True)
    parser.add_argument("--cycles", type=int, default=5)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--apk", type=Path, default=OUTPUT / "repro.apk")
    parser.add_argument("--report-dir", type=Path, help="Separate evidence directory, e.g. emulator-host/gesture")
    args = parser.parse_args()
    if not 1 <= args.cycles <= 5:
        parser.error("--cycles must be between 1 and 5 (60-second test budget)")

    deadline = time.monotonic() + 60
    report_dir = args.report_dir or ROOT / "app/build/reports/native-activity-regression" / args.suite
    report_dir.mkdir(parents=True, exist_ok=True)
    results = []

    def adb(*parts, allow_failure=False, binary=False):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Fixture suite exceeded its 60-second deadline")
        result = subprocess.run(
            [str(args.adb), "-s", args.serial, *map(str, parts)],
            capture_output=True, timeout=min(remaining, 10),
        )
        if result.returncode and not allow_failure:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()

    def pid(suffix=""):
        return adb("shell", "pidof", PACKAGE + suffix, allow_failure=True)

    def log(name):
        return adb("shell", "run-as", PACKAGE, "cat", f"files/{name}.txt", allow_failure=True)

    def frames(process):
        values = re.findall(rf"pid={re.escape(process)} tid=\d+ frame frames=(\d+)", log("frames"))
        return int(values[-1]) if values else 0

    def wait_for(predicate, timeout=5):
        end = min(deadline, time.monotonic() + timeout)
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(0.15)
        raise AssertionError("Expected fixture state did not arrive before deadline")

    def screenshot(name):
        (report_dir / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    if args.install:
        adb("install", "-r", args.apk)
    # Reset only this test application, never the IDE or its projects.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", LAUNCHER)
    launcher_pid = pid()
    assert launcher_pid, "Fixture launcher did not start"
    sizes = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, sizes[-1])

    def close_run(process, method):
        if method == "key":
            adb("shell", "input", "keyevent", "4")
        else:
            adb("shell", "input", "swipe", "2", height // 2, width // 3, height // 2, "250")
        wait_for(lambda: not pid(":gui"))
        lifecycle = log("lifecycle")
        assert f"pid={process} destroy-return" in lifecycle, lifecycle
        assert re.search(rf"pid={process} tid=\d+ main-return", log("frames")), log("frames")
        assert pid() == launcher_pid, "Launcher process changed during graphical shutdown"

    try:
        cycles = 1 if args.suite == "home" else args.cycles
        previous_pid = None
        for index in range(cycles):
            extra = []
            if args.suite == "early-cancel":
                extra = ["--ez", "cancel_before_window", "true"]
            elif args.suite == "rejected":
                extra = ["--ez", "cancel_before_create", "true"]
            adb("shell", "am", "start", "-W", "-n", ACTIVITY, *extra)
            if extra:
                wait_for(lambda: not pid(":gui"))
                lifecycle = log("lifecycle")
                entries = re.findall(r"pid=(\d+) create-enter", lifecycle)
                assert entries, lifecycle
                current_pid = entries[-1]
                assert f"pid={current_pid} destroy-return" in lifecycle, lifecycle
                assert pid() == launcher_pid
                results.append({"cycle": index + 1, "pid": current_pid, "destroy_returned": True})
                continue

            current_pid = pid(":gui")
            assert current_pid and current_pid != previous_pid, "Graphical process was reused"
            previous_pid = current_pid
            wait_for(lambda: frames(current_pid) >= 30)
            if index == 0 or index == cycles - 1:
                screenshot(f"run-{index + 1}")
            sample = {"cycle": index + 1, "pid": current_pid, "frames": frames(current_pid)}
            if args.suite == "home":
                time.sleep(8)  # Longer than the exit guard; no total runtime limit may be armed.
                assert pid(":gui") == current_pid
                sample["before_home"] = frames(current_pid)
                adb("shell", "input", "keyevent", "3")
                time.sleep(3)
                assert pid(":gui") == current_pid, "Home killed the game"
                sample["background"] = frames(current_pid)
                adb("shell", "am", "start", "-W", "-n", ACTIVITY, "--activity-reorder-to-front")
                wait_for(lambda: frames(current_pid) > sample["background"])
                assert pid(":gui") == current_pid
                sample["resumed"] = frames(current_pid)
                screenshot("resumed")
            close_run(current_pid, args.suite)
            results.append(sample)
        report = {"serial": args.serial, "suite": args.suite, "launcher_pid": launcher_pid, "runs": results, "passed": True}
        (report_dir / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
    finally:
        for name in ("lifecycle", "frames"):
            try:
                (report_dir / f"{name}.txt").write_text(log(name) + "\n", encoding="utf-8")
            except (TimeoutError, subprocess.TimeoutExpired):
                pass  # Keep the original test failure; previously saved evidence remains intact.


if __name__ == "__main__":
    main()

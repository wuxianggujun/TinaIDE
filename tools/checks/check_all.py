from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run stable TinaIDE maintenance checks."
    )
    parser.add_argument(
        "--include-i18n",
        action="store_true",
        help="Also run tools/i18n/check_all.py. Disabled by default to keep this entrypoint low-noise.",
    )
    parser.add_argument(
        "--include-i18n-logs",
        action="store_true",
        help="When --include-i18n is set, include hardcoded CJK checks in log lines.",
    )
    return parser.parse_args(argv)


def find_repo_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "tools").is_dir():
            return candidate
    raise RuntimeError(f"Unable to find repository root from {start}")


def run_check(root: Path, label: str, script_path: Path, args: list[str] | None = None) -> int:
    command = [sys.executable, str(script_path), *(args or [])]
    print(f"== {label} ==", flush=True)
    print(" ".join(command), flush=True)
    completed = subprocess.run(command, cwd=root, check=False)
    print(flush=True)
    return completed.returncode


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

    args = parse_args(sys.argv[1:])
    root = find_repo_root(Path(__file__).parent)

    checks: list[tuple[str, Path, list[str]]] = [
        (
            "documentation",
            root / "tools/checks/check_documentation.py",
            [],
        ),
        (
            "direct file operations",
            root / "tools/checks/check_direct_file_operations.py",
            [],
        ),
        (
            "embedded resource collisions",
            root / "tools/checks/check_embedded_resource_collisions.py",
            [],
        ),
    ]

    if args.include_i18n:
        i18n_args = ["--include-logs"] if args.include_i18n_logs else []
        checks.append(
            (
                "i18n",
                root / "tools/i18n/check_all.py",
                i18n_args,
            )
        )

    failures: list[str] = []
    for label, script_path, check_args in checks:
        return_code = run_check(root, label, script_path, check_args)
        if return_code != 0:
            failures.append(f"{label} exited with {return_code}")

    if failures:
        print("FAILED: maintenance checks did not pass.")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("OK: all maintenance checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

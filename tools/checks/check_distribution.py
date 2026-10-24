"""Fail closed before distributing APKs; local development builds remain available."""
from __future__ import annotations

import json
import sys
from pathlib import Path


def distribution_errors(root: Path) -> list[str]:
    policy_path = root / "tools/checks/distribution-policy.json"
    try:
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
        build_script = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
        if policy.get("schemaVersion") != 1 or not isinstance(policy.get("components"), list) or not policy["components"]:
            return ["Missing or unsupported distribution policy."]
        errors: list[str] = []
        for component in policy["components"]:
            name = component["name"]
            dependency = component["dependency"]
            status = component["status"]
            if not all(isinstance(value, str) and value.strip() for value in (name, dependency, status)):
                return ["Distribution policy fields must be non-empty strings."]
            if status == "blocked":
                # Deliberately conservative: even a commented dependency requires review.
                if dependency in build_script:
                    errors.append(f"{name}: {component['reason']}")
            elif status == "reviewed":
                evidence = (root / component["evidence"]).resolve()
                if not evidence.is_relative_to(root.resolve()) or not evidence.is_file():
                    errors.append(f"{name}: license review evidence is missing or outside the repository.")
            else:
                errors.append(f"{name}: unknown policy status {status!r}.")
        return errors
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        return [f"Unable to validate distribution policy: {type(error).__name__}."]


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = distribution_errors(root)
    for error in errors:
        print(f"BLOCKED: {error}", file=sys.stderr)
    if errors:
        print("APK upload/publication is blocked. Resolve the license review before distributing.", file=sys.stderr)
        return 1
    print("OK: distribution policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

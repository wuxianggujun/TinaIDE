"""Fail closed before distributing APKs; local development builds remain available."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def repository_file(root: Path, relative_path: str) -> Path:
    if not isinstance(relative_path, str) or not relative_path.strip() or Path(relative_path).is_absolute():
        raise ValueError("Expected a repository-relative file path.")
    path = (root / relative_path).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("Required file is missing or outside the repository.")
    return path


def license_digest(text: str) -> str:
    # Git may check text out with CRLF on Windows; preserve every other byte.
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def reviewed_component_errors(root: Path, component: dict) -> list[str]:
    name = component["name"]
    repository_file(root, component["evidence"])
    license_record = component["license"]
    expected_digest = license_record["sha256"]
    if not isinstance(expected_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        return [f"{name}: a reviewed license SHA-256 is required."]
    license_path = repository_file(root, license_record["path"])
    if license_digest(license_path.read_text(encoding="utf-8")) != expected_digest:
        return [f"{name}: the checked-out license differs from the reviewed text."]

    notice_record = component["notice"]
    library_id, license_id, source_url = (notice_record[key] for key in ("libraryId", "licenseId", "sourceUrl"))
    if not all(isinstance(value, str) and value.strip() for value in (library_id, license_id, source_url)):
        return [f"{name}: notice identifiers and a corresponding-source URL are required."]
    if not source_url.startswith("https://"):
        return [f"{name}: the corresponding-source URL must use HTTPS."]
    notice_path = repository_file(root, notice_record["path"])
    notice = json.loads(notice_path.read_text(encoding="utf-8"))
    libraries = [entry for entry in notice["libraries"] if entry["uniqueId"] == library_id]
    if len(libraries) != 1:
        return [f"{name}: the shipped notice must contain exactly one component entry."]
    library = libraries[0]
    if library.get("licenses") != [license_id] or library.get("website") != source_url:
        return [f"{name}: the shipped notice has an incorrect license or corresponding-source URL."]
    content = notice["licenses"][license_id]["content"]
    if license_digest(content) != expected_digest:
        return [f"{name}: the shipped notice must include the full reviewed license text."]
    return []


def packaged_notice_errors(root: Path, component: dict, apk: Path) -> list[str]:
    expected = repository_file(root, component["notice"]["path"]).read_bytes()
    expected_digest = hashlib.sha256(expected).digest()
    with ZipFile(apk) as archive:
        # AAPT may shorten res/raw/aboutlibraries.json to res/<short name>.json.
        # Raw resources retain their bytes; do not depend on the output filename.
        for entry in archive.infolist():
            if entry.filename.startswith("res/") and entry.file_size == len(expected):
                if hashlib.sha256(archive.read(entry)).digest() == expected_digest:
                    return []
    return [f"{component['name']}: the APK does not contain the reviewed raw notice resource."]


def distribution_errors(root: Path, apk: Path | None = None) -> list[str]:
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
                errors.extend(reviewed_component_errors(root, component))
                if apk is not None:
                    errors.extend(packaged_notice_errors(root, component, apk))
            else:
                errors.append(f"{name}: unknown policy status {status!r}.")
        return errors
    except (OSError, ValueError, KeyError, TypeError, AttributeError, BadZipFile) as error:
        return [f"Unable to validate distribution policy: {type(error).__name__}."]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Also verify the reviewed notice is in the final APK.")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    errors = distribution_errors(root, args.apk)
    for error in errors:
        print(f"BLOCKED: {error}", file=sys.stderr)
    if errors:
        print("APK upload/publication is blocked. Resolve the license review before distributing.", file=sys.stderr)
        return 1
    print("OK: distribution policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

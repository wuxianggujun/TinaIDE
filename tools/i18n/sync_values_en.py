from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path


STRING_ENTRY_RE = re.compile(
    r'(?s)<string(?!-)\b[^>]*\bname="([^"]+)"[^>]*(?:/>|>.*?</string>)'
)

STRING_ARRAY_ENTRY_RE = re.compile(
    r'(?s)<string-array\b[^>]*\bname="([^"]+)"[^>]*>.*?</string-array>'
)

PLURALS_ENTRY_RE = re.compile(
    r'(?s)<plurals\b[^>]*\bname="([^"]+)"[^>]*>.*?</plurals>'
)


def build_entry_map(xml_text: str, entry_re: re.Pattern[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for m in entry_re.finditer(xml_text):
        name = m.group(1)
        out.setdefault(name, m.group(0))
    return out


def build_resource_entry_map(xml_text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for entry_re in (STRING_ENTRY_RE, STRING_ARRAY_ENTRY_RE, PLURALS_ENTRY_RE):
        for k, v in build_entry_map(xml_text, entry_re).items():
            out.setdefault(k, v)
    return out


def validate_unique_resources(xml_text: str) -> None:
    resources = ET.fromstring(xml_text)
    seen: set[tuple[str, str]] = set()
    for element in resources:
        name = element.get("name")
        resource_type = element.get("type") if element.tag == "item" else element.tag
        if resource_type in {"string-array", "integer-array"}:
            resource_type = "array"
        if name is None or resource_type is None:
            continue
        key = (resource_type, name)
        if key in seen:
            raise ValueError(f"Duplicate resource: {resource_type}/{name}")
        seen.add(key)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base",
        default="core/i18n/src/main/res/values/strings.xml",
        help="Base strings.xml path",
    )
    parser.add_argument(
        "--en",
        default="core/i18n/src/main/res/values-en/strings.xml",
        help="English strings.xml path",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Only check missing keys. Exit 1 if any are missing.",
    )
    args = parser.parse_args()

    base_path = Path(args.base)
    en_path = Path(args.en)

    base_text = base_path.read_text(encoding="utf-8")
    en_text = en_path.read_text(encoding="utf-8")

    # A dict silently hides duplicates, even when both locales contain the same
    # invalid key. Reject them before either checking or writing the translation.
    for path, text in ((base_path, base_text), (en_path, en_text)):
        try:
            validate_unique_resources(text)
        except (ET.ParseError, ValueError) as error:
            print(f"{path.as_posix()}: {error}")
            return 1

    base_entries = build_resource_entry_map(base_text)
    en_entries = build_resource_entry_map(en_text)

    missing = [k for k in base_entries.keys() if k not in en_entries]
    missing.sort()

    if not missing:
        print("values-en is already in sync.")
        return 0

    if args.check:
        print(f"Missing {len(missing)} keys in {en_path.as_posix()}:")
        for k in missing:
            print(f"- {k}")
        return 1

    if "</resources>" not in en_text:
        raise SystemExit(f"Invalid XML (missing </resources>): {en_path}")

    header = "\n  <!-- AUTO-GENERATED: synced missing keys from base values -->\n"
    block_lines = [header]
    for key in missing:
        entry = base_entries[key].strip()
        # Ensure 2-space indentation for readability
        if not entry.startswith("<"):
            entry = entry.lstrip()
        block_lines.append("  " + entry + "\n")

    insertion = "".join(block_lines)
    before, after = en_text.rsplit("</resources>", 1)
    new_text = before.rstrip() + "\n" + insertion + "\n</resources>" + after
    en_path.write_text(new_text, encoding="utf-8")

    print(f"Added {len(missing)} missing keys to {en_path.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Reject single-ABI TinaIDE APKs with missing or wrong-architecture native runtimes."""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import struct
import sys
from zipfile import BadZipFile, ZipFile


ELF_MACHINES = {"arm64-v8a": 183, "x86_64": 62}
# PRootManager executes this optional, static 32-bit guest loader. It is not a
# JNI library; the lib*.so name only lets AGP put the executable in nativeLibDir.
GUEST_LOADER_MACHINES = {"arm64-v8a": 40, "x86_64": 3}
# Matches the grammars consumed by editor-kit/tree-sitter-grammars, including
# Java (declared through the version catalog rather than a literal dependency).
GRAMMARS = (
    "aidl", "bash", "c", "cmake", "cpp", "java", "json", "kotlin", "log",
    "make", "properties", "python", "rust", "toml", "xml", "yaml",
)
REQUIRED_LIBRARIES = frozenset({
    "libandroid-tree-sitter.so", "libc++_shared.so", "libXlorie.so", "libworkspace.so",
    "libtina_native.so", "libtina_text_engine.so", "libtina_pty.so",
    "libtina_native_activity_host.so", "libtina_sdl_asset_redirect.so", "libtina_log_redirect.so",
    *(f"libtree-sitter-{grammar}.so" for grammar in GRAMMARS),
})


def native_library_errors(apk: Path, abi: str) -> list[str]:
    if abi not in ELF_MACHINES:
        return [f"Unsupported APK ABI: {abi}."]
    errors: list[str] = []
    try:
        with ZipFile(apk) as archive:
            native_entries = [entry for entry in archive.infolist()
                              if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
            counts = Counter(entry.filename for entry in native_entries)
            for name, count in sorted(counts.items()):
                if count != 1:
                    errors.append(f"Duplicate native entry: {name}.")
                parts = name.split("/")
                if len(parts) != 3 or parts[1] != abi:
                    errors.append(f"Unexpected native entry for single-ABI {abi} APK: {name}.")
            for library in sorted(REQUIRED_LIBRARIES):
                if f"lib/{abi}/{library}" not in counts:
                    errors.append(f"Missing native runtime: lib/{abi}/{library}.")
            for entry in native_entries:
                with archive.open(entry) as stream:
                    header = stream.read(20)
                guest_loader = entry.filename == f"lib/{abi}/libproot-loader32.so"
                elf_class = 1 if guest_loader else 2
                machine = GUEST_LOADER_MACHINES[abi] if guest_loader else ELF_MACHINES[abi]
                if len(header) < 20 or header[:6] != b"\x7fELF" + bytes((elf_class, 1)):
                    errors.append(f"Invalid {elf_class * 32}-bit little-endian ELF: {entry.filename}.")
                elif struct.unpack_from("<H", header, 18)[0] != machine:
                    errors.append(f"ELF architecture does not match {abi}: {entry.filename}.")
                elif guest_loader and struct.unpack_from("<H", header, 16)[0] != 2:
                    errors.append(f"Expected a static PRoot guest executable: {entry.filename}.")
    except (OSError, BadZipFile, RuntimeError) as error:
        errors.append(f"Unable to inspect native APK entries: {type(error).__name__}.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--abi", choices=ELF_MACHINES, required=True)
    args = parser.parse_args()
    errors = native_library_errors(args.apk, args.abi)
    for error in errors:
        print(f"BLOCKED: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"OK: {args.abi} native runtimes and ELF architectures verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

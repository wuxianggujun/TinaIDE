#!/usr/bin/env python3

import argparse
import os
import struct
import zlib
from pathlib import Path


TEXT_EXTENSIONS = {
    ".json", ".md", ".txt", ".ps1", ".py", ".sh", ".lua", ".svg", ".xml", ".properties",
    ".gradle", ".kts", ".kt", ".java", ".c", ".cpp", ".h", ".hpp", ".cmake", ".pc",
}
TEXT_FILE_NAMES = {".gitignore"}
UTF8_FLAG = 0x0800
DOS_TIME = 0
DOS_DATE = 0x5021
MAX_UINT16 = 0xFFFF
MAX_UINT32 = 0xFFFFFFFF


def read_entry_bytes(path: Path) -> bytes:
    data = path.read_bytes()
    if path.suffix.lower() not in TEXT_EXTENSIONS and path.name not in TEXT_FILE_NAMES:
        return data
    text = data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    return text.encode("utf-8")


def require_uint32(value: int, name: str) -> int:
    if not 0 <= value <= MAX_UINT32:
        raise ValueError(f"{name} exceeds classic ZIP limits: {value}")
    return value


def build_zip(source_dir: Path, output_file: Path) -> None:
    source_dir = source_dir.resolve(strict=True)
    entries = sorted(
        (path.relative_to(source_dir).as_posix(), path)
        for path in source_dir.rglob("*")
        if path.is_file()
    )
    if len(entries) > MAX_UINT16:
        raise ValueError(f"Too many ZIP entries: {len(entries)}")

    output_file.parent.mkdir(parents=True, exist_ok=True)
    temporary_file = output_file.with_name(output_file.name + ".tmp")
    temporary_file.unlink(missing_ok=True)
    central_records: list[tuple[bytes, int, int, int]] = []
    try:
        with temporary_file.open("xb") as stream:
            for relative_path, path in entries:
                name_bytes = relative_path.encode("utf-8")
                if len(name_bytes) > MAX_UINT16:
                    raise ValueError(f"ZIP entry name is too long: {relative_path}")
                data = read_entry_bytes(path)
                size = require_uint32(len(data), f"ZIP entry size for {relative_path}")
                offset = require_uint32(stream.tell(), f"ZIP entry offset for {relative_path}")
                crc = zlib.crc32(data) & MAX_UINT32
                stream.write(struct.pack(
                    "<IHHHHHIIIHH",
                    0x04034B50, 20, UTF8_FLAG, 0, DOS_TIME, DOS_DATE,
                    crc, size, size, len(name_bytes), 0,
                ))
                stream.write(name_bytes)
                stream.write(data)
                central_records.append((name_bytes, crc, size, offset))

            central_offset = require_uint32(stream.tell(), "ZIP central directory offset")
            for name_bytes, crc, size, offset in central_records:
                stream.write(struct.pack(
                    "<IHHHHHHIIIHHHHHII",
                    0x02014B50, 20, 20, UTF8_FLAG, 0, DOS_TIME, DOS_DATE,
                    crc, size, size, len(name_bytes), 0, 0, 0, 0, 0, offset,
                ))
                stream.write(name_bytes)
            central_size = require_uint32(stream.tell() - central_offset, "ZIP central directory size")
            stream.write(struct.pack(
                "<IHHHHIIH",
                0x06054B50, 0, 0, len(central_records), len(central_records),
                central_size, central_offset, 0,
            ))
        os.replace(temporary_file, output_file)
    finally:
        temporary_file.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a byte-for-byte deterministic ZIP archive.")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build_zip(args.source, args.output)


if __name__ == "__main__":
    main()

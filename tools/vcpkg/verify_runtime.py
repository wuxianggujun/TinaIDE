"""Verify pinned Android runtime packages, ELF dependencies and extraction constraints."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import tarfile
import urllib.request
from zipfile import ZipFile
from pathlib import Path, PurePosixPath


def data_archive(package: bytes) -> bytes:
    if not package.startswith(b"!<arch>\n"):
        raise ValueError("Not a Debian archive")
    offset = 8
    while offset + 60 <= len(package):
        header = package[offset:offset + 60]
        size = int(header[48:58])
        name = header[:16].decode().strip().rstrip("/")
        offset += 60
        if name.startswith("data.tar"):
            return package[offset:offset + size]
        offset += size + size % 2
    raise ValueError("No data archive")


def elf_dependencies(blob: bytes, machine: int) -> list[str]:
    if not blob.startswith(b'\x7fELF'):
        return []
    if blob[4:6] != b'\x02\x01' or struct.unpack_from('<H', blob, 18)[0] != machine:
        raise ValueError('Wrong ELF class or architecture')
    offset = struct.unpack_from('<Q', blob, 32)[0]
    stride, count = struct.unpack_from('<HH', blob, 54)
    segments = [struct.unpack_from('<IIQQQQQQ', blob, offset + i * stride) for i in range(count)]
    dynamic = next((segment for segment in segments if segment[0] == 2), None)
    if not dynamic:
        return []
    tags = [struct.unpack_from('<qQ', blob, pos) for pos in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
    strings = next((value for tag, value in tags if tag == 5), None)
    if strings is None:
        return []
    load = next(segment for segment in segments if segment[0] == 1 and segment[3] <= strings < segment[3] + segment[5])
    table = load[2] + strings - load[3]
    return [blob[table + value:blob.index(b'\0', table + value)].decode('utf-8') for tag, value in tags if tag == 1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apk", type=Path, help="Also verify the exact runtime, catalog and notices packaged in an APK")
    parser.add_argument("--cache", type=Path, default=Path(".tmp/vcpkg-managed/packages"))
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    machine = 183 if manifest['abi'] == 'arm64-v8a' else 62
    cli = args.manifest.with_name('vcpkg').read_bytes()
    if hashlib.sha256(cli).hexdigest() != manifest['cliSha256']:
        raise ValueError('CLI checksum mismatch')
    dependencies = {'vcpkg': elf_dependencies(cli, machine)}
    if args.apk:
        with ZipFile(args.apk) as apk:
            for name in ('vcpkg', 'runtime.json', 'vcpkg-build.json', 'LICENSE.txt', 'NOTICE.txt'):
                if apk.read('assets/vcpkg/' + name) != args.manifest.with_name(name).read_bytes():
                    raise ValueError('APK runtime asset differs from verified input: ' + name)
            for name in ('catalog.json', 'ports-LICENSE.txt'):
                if apk.read('assets/vcpkg/' + name) != (Path('core/packages/src/main/assets/vcpkg') / name).read_bytes():
                    raise ValueError('APK catalog asset differs from verified input: ' + name)
    available = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so'}
    records = []
    for package in manifest["packages"]:
        digest = package["SHA256"]
        destination = args.cache / (digest + ".deb")
        if not destination.is_file():
            with urllib.request.urlopen(manifest["repository"] + "/" + package["Filename"], timeout=45) as response:
                destination.write_bytes(response.read())
        blob = destination.read_bytes()
        if len(blob) != int(package["Size"]) or hashlib.sha256(blob).hexdigest() != digest:
            raise ValueError("Package checksum mismatch: " + package["Package"])
        with tarfile.open(fileobj=io.BytesIO(data_archive(blob))) as archive:
            entries = archive.getmembers()
            absolute_links = [(entry.name, entry.linkname) for entry in entries if (entry.issym() or entry.islnk()) and entry.linkname.startswith("/")]
            escapes = [entry.name for entry in entries if ".." in PurePosixPath(entry.name).parts or entry.name.startswith("/")]
            unsafe_links = [link for link in absolute_links if not link[1].startswith('/data/data/com.termux/files/usr/') or '..' in PurePosixPath(link[1]).parts]
            if unsafe_links or escapes:
                raise ValueError(str({"package": package["Package"], "absoluteLinks": absolute_links, "escapes": escapes}))
            available.update(PurePosixPath(entry.name).name for entry in entries if not entry.isdir())
            for entry in entries:
                if entry.isfile():
                    stream = archive.extractfile(entry)
                    header = stream.read(4)
                    if header == b'\x7fELF':
                        dependencies[entry.name] = elf_dependencies(header + stream.read(), machine)
            records.append({"package": package["Package"], "entries": len(entries), "sha256": digest})
    missing = {name: sorted(set(needs) - available) for name, needs in dependencies.items() if set(needs) - available}
    if missing:
        raise ValueError(str(missing))
    print(json.dumps({"abi": manifest["abi"], "packages": len(records), "elfFiles": len(dependencies), "validated": True}, indent=2))


if __name__ == "__main__":
    main()

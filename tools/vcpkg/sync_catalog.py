"""Refresh the offline vcpkg catalog from an immutable upstream source archive."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import tarfile
import urllib.request
from pathlib import Path


def request(url: str):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TinaIDE-vcpkg-catalog"}), timeout=45)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Upstream commit; defaults to the current master commit")
    parser.add_argument("--cache", type=Path, default=Path(".tmp/vcpkg-managed"))
    parser.add_argument("--output", type=Path, default=Path("core/packages/src/main/assets/vcpkg/catalog.json"))
    args = parser.parse_args()
    revision = args.revision
    if not revision:
        with request("https://api.github.com/repos/microsoft/vcpkg/git/ref/heads/master") as response:
            revision = json.load(response)["object"]["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        parser.error("Expected a complete commit SHA")
    args.cache.mkdir(parents=True, exist_ok=True)
    archive = args.cache / f"vcpkg-{revision}.tar.gz"
    if not archive.is_file():
        with request(f"https://codeload.github.com/microsoft/vcpkg/tar.gz/{revision}") as response:
            archive.write_bytes(response.read(64 * 1024 * 1024 + 1))
    if archive.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Source archive exceeds 64 MiB")
    ports = []
    with tarfile.open(archive) as source:
        for entry in source:
            match = re.fullmatch(r"vcpkg-[0-9a-f]{40}/ports/([a-z0-9]+(?:-[a-z0-9]+)*)/vcpkg.json", entry.name)
            if not match or not entry.isfile():
                continue
            if entry.size > 1024 * 1024:
                raise ValueError("Oversized port manifest")
            port = json.load(source.extractfile(entry))
            description = port.get("description", "")
            if isinstance(description, list):
                description = " ".join(description)
            ports.append({"name": match[1], "version": next((port[k] for k in ("version", "version-semver", "version-date", "version-string") if k in port), ""),
                          "description": description, "supports": port.get("supports", "")})
        license_file = source.extractfile(f"vcpkg-{revision}/LICENSE.txt").read()
    if len(ports) < 1000:
        raise ValueError("Incomplete upstream catalog")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"revision": revision, "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                                       "ports": sorted(ports, key=lambda port: port["name"])}, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    args.output.with_name("ports-LICENSE.txt").write_bytes(license_file)
    print(json.dumps({"revision": revision, "ports": len(ports), "bytes": args.output.stat().st_size}))


if __name__ == "__main__":
    main()

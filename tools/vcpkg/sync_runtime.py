"""Pin Android helper packages and package a locally built Android vcpkg CLI."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import io
import tarfile
import urllib.request
from pathlib import Path

REPOSITORY = "https://packages.termux.dev/apt/termux-main"
REQUIRED = ("git", "curl", "zip", "unzip", "pkg-config", "ca-certificates")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--refresh-helpers", action="store_true",
                        help="Refresh pinned helper packages while preserving the verified bundled CLI")
    parser.add_argument("--cache", type=Path, default=Path(".tmp/vcpkg-managed/packages"))
    args = parser.parse_args()
    if not args.refresh_helpers and (args.package is None or args.source is None):
        parser.error("--package and --source are required unless --refresh-helpers is used")
    architecture = "aarch64" if args.abi == "arm64-v8a" else "x86_64"
    index_url = f"{REPOSITORY}/dists/stable/main/binary-{architecture}/Packages.gz"
    with urllib.request.urlopen(index_url, timeout=45) as response:
        index = gzip.decompress(response.read()).decode("utf-8")
    packages = {}
    for paragraph in index.split("\n\n"):
        fields = dict(line.split(": ", 1) for line in paragraph.splitlines() if line and not line[0].isspace() and ": " in line)
        if "Package" in fields:
            packages[fields["Package"]] = fields
    selected = {}
    pending = list(REQUIRED)
    while pending:
        name = pending.pop()
        if name in selected:
            continue
        package = packages[name]
        selected[name] = package
        for dependency in package.get("Depends", "").split(","):
            if dependency.strip():
                alternatives = [re.split(r"[ (:]", option.strip())[0] for option in dependency.split("|")]
                pending.append(next(candidate for candidate in alternatives if candidate in packages))
    output = Path("app/src") / ("arm64" if args.abi == "arm64-v8a" else "x86_64") / "assets/vcpkg"
    output.mkdir(parents=True, exist_ok=True)
    package_directory = output if args.refresh_helpers else args.package
    build = json.loads((package_directory / "vcpkg-build.json").read_text(encoding="utf-8"))
    cli = package_directory / "vcpkg"
    assert build["abi"] == args.abi and build["sha256"] == hashlib.sha256(cli.read_bytes()).hexdigest()
    if not args.refresh_helpers:
        shutil.copy2(cli, output / "vcpkg")
        shutil.copy2(args.package / "vcpkg-build.json", output / "vcpkg-build.json")
        for name in ("LICENSE.txt", "NOTICE.txt"):
            shutil.copy2(args.source / name, output / name)
    manifest = {"abi": args.abi, "cliSha256": build["sha256"], "cliVersion": build["tag"], "repository": REPOSITORY,
                "indexSha256": hashlib.sha256(index.encode("utf-8")).hexdigest(),
                "packages": [{key: selected[name][key] for key in ("Package", "Version", "Filename", "SHA256", "Size")} for name in sorted(selected)]}
    # Every runtime now includes its immutable helper snapshot; there is no rolling-repository fallback.
    args.cache.mkdir(parents=True, exist_ok=True)
    staged = output / "helpers.tar.part"
    try:
        with tarfile.open(staged, "w", format=tarfile.USTAR_FORMAT) as archive:
            for package in manifest["packages"]:
                digest = package["SHA256"]
                cached = args.cache / (digest + ".deb")
                if not cached.is_file():
                    with urllib.request.urlopen(REPOSITORY + "/" + package["Filename"], timeout=45) as response:
                        cached.write_bytes(response.read())
                blob = cached.read_bytes()
                if len(blob) != int(package["Size"]) or hashlib.sha256(blob).hexdigest() != digest:
                    raise ValueError("Helper package verification failed: " + package["Package"])
                member = tarfile.TarInfo(digest + ".deb")
                member.size = len(blob)
                member.mode = 0o600
                archive.addfile(member, io.BytesIO(blob))
        manifest["helpersArchiveSha256"] = hashlib.sha256(staged.read_bytes()).hexdigest()
        staged.replace(output / "helpers.tar")
    finally:
        staged.unlink(missing_ok=True)
    (output / "runtime.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"abi": args.abi, "helpers": len(selected), "downloadBytes": sum(int(p["Size"]) for p in selected.values()), "cliBytes": cli.stat().st_size}))


if __name__ == "__main__":
    main()

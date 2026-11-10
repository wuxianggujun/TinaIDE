"""Pin Android helper packages and package a locally built Android vcpkg CLI."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import urllib.request
from pathlib import Path

REPOSITORY = "https://packages.termux.dev/apt/termux-main"
REQUIRED = ("git", "curl", "zip", "unzip", "pkg-config", "ca-certificates")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
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
    build = json.loads((args.package / "vcpkg-build.json").read_text(encoding="utf-8"))
    cli = args.package / "vcpkg"
    assert build["abi"] == args.abi and build["sha256"] == hashlib.sha256(cli.read_bytes()).hexdigest()
    shutil.copy2(cli, output / "vcpkg")
    shutil.copy2(args.package / "vcpkg-build.json", output / "vcpkg-build.json")
    for name in ("LICENSE.txt", "NOTICE.txt"):
        shutil.copy2(args.source / name, output / name)
    manifest = {"abi": args.abi, "cliSha256": build["sha256"], "cliVersion": build["tag"], "repository": REPOSITORY,
                "indexSha256": hashlib.sha256(index.encode("utf-8")).hexdigest(),
                "packages": [{key: selected[name][key] for key in ("Package", "Version", "Filename", "SHA256", "Size")} for name in sorted(selected)]}
    (output / "runtime.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"abi": args.abi, "helpers": len(selected), "downloadBytes": sum(int(p["Size"]) for p in selected.values()), "cliBytes": cli.stat().st_size}))


if __name__ == "__main__":
    main()

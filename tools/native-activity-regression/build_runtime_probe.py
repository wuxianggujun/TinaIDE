"""Build small SDL2/SDL3 probes from existing package archives; never rebuild the libraries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), default="x86_64")
    parser.add_argument("--ndk-version", default="26.1.10909125")
    args = parser.parse_args()
    output = ROOT / "app/build/reports/runtime-regression/native"
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    ndk = args.sdk / f"ndk/{args.ndk_version}/toolchains/llvm/prebuilt/{host}"
    compiler = ndk / ("bin/clang++.exe" if os.name == "nt" else "bin/clang++")
    target = "x86_64-linux-android26" if args.abi == "x86_64" else "aarch64-linux-android26"
    manifests = []
    for major, version in ((2, "2.32.10"), (3, "3.5.0")):
        archive = ROOT / f"docker/tinaide-pkg/output/registry/sdl{major}/{version}/sdl{major}-{args.abi}.tar.xz"
        directory = output / f"sdl{major}"
        directory.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as package:
            for item in package:
                name = item.name.removeprefix("./")
                if not item.isfile() or ".." in Path(name).parts:
                    continue
                if name.startswith("include/") or name == f"lib/{args.abi}/libSDL{major}.so":
                    destination = directory / (name if name.startswith("include/") else Path(name).name)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(package.extractfile(item).read())
        subprocess.run([str(compiler), f"--target={target}", f"--sysroot={ndk / 'sysroot'}",
                        "-shared", "-fPIC", "-std=c++17", "-static-libstdc++", f"-DSDL_PROBE_MAJOR={major}",
                        "-I", str(directory / "include"), str(Path(__file__).with_name("sdl_runtime_probe.cpp")),
                        "-L", str(directory), f"-lSDL{major}", "-Wl,-rpath,$ORIGIN", "-Wl,-soname,libmain.so",
                        "-o", str(directory / "libmain.so")], check=True, timeout=60)
        manifests.append({"major": major, "version": version, "abi": args.abi,
                          "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest()})
    directory = output / "raylib"
    directory.mkdir(parents=True, exist_ok=True)
    archive = ROOT / f"docker/tinaide-pkg/output/registry/raylib/6.0/raylib-{args.abi}.tar.xz"
    with tarfile.open(archive) as package:
        for name, destination in ((f"./lib/{args.abi}/libraylib.so", "libraylib.so"), ("./include/raylib.h", "raylib.h")):
            (directory / destination).write_bytes(package.extractfile(name).read())
    subprocess.run([str(compiler), f"--target={target}", f"--sysroot={ndk / 'sysroot'}",
                    "-x", "c", "-shared", "-fPIC", "-I", str(directory),
                    str(Path(__file__).with_name("raylib_runtime_probe.c")), "-L", str(directory),
                    "-lraylib", "-Wl,-rpath,$ORIGIN", "-Wl,-soname,libmain.so", "-o", str(directory / "libmain.so")],
                   check=True, timeout=60)
    manifests.append({"library": "raylib", "version": "6.0", "abi": args.abi,
                      "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest()})
    (output / "manifest.json").write_text(json.dumps(manifests, indent=2), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()

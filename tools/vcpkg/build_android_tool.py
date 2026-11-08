"""Build an Android vcpkg CLI from a local, version-matched vcpkg-tool checkout."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--api", type=int, default=28)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    args = parser.parse_args()
    source, ndk = args.source.resolve(), args.ndk.resolve()
    build, output = args.build_dir.resolve(), args.output_dir.resolve()
    if args.api < 28:
        parser.error("Android API 28 or later is required")
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    if not toolchain.is_file() or not (source / "CMakeLists.txt").is_file():
        parser.error("Missing Android NDK toolchain or vcpkg-tool sources")
    if build == source or output == source:
        parser.error("Keep build/output directories separate from the source checkout")
    subprocess.run([
        args.cmake, "-S", str(source), "-B", str(build), "-G", "Ninja",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
        f"-DANDROID_PLATFORM=android-{args.api}", "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF",
        "-DVCPKG_DEVELOPMENT_WARNINGS=OFF", "-DVCPKG_WARNINGS_AS_ERRORS=OFF",
        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
    ], check=True)
    subprocess.run([args.cmake, "--build", str(build), "--target", "vcpkg"], check=True)
    executable = build / "vcpkg"
    if not executable.is_file():
        raise FileNotFoundError(f"Expected Android executable: {executable}")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(executable, output / "vcpkg")
    shutil.copy2(source / "LICENSE.txt", output / "vcpkg-LICENSE.txt")
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    (output / "vcpkg-build.json").write_text(json.dumps({
        "source": "https://github.com/microsoft/vcpkg-tool", "revision": revision,
        "abi": args.abi, "androidApi": args.api, "stl": "c++_static",
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Android vcpkg prepared in {output}")


if __name__ == "__main__":
    main()

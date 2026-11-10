"""Build an Android vcpkg CLI from a local, version-matched vcpkg-tool checkout."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


def prepare_android_sources(source: Path, build: Path) -> tuple[Path, str]:
    original = source / "src/vcpkg/base/system.process.cpp"
    text = original.read_text(encoding="utf-8")
    marker = "#elif defined(__linux__) || defined(__NetBSD__)"
    if text.count(marker) != 1 or text.count('"/bin/sh"') != 2:
        raise ValueError("Unsupported upstream process implementation; review Android adaptations first")
    text = '#include <dlfcn.h>\n' + text.replace(marker, '''#elif defined(__ANDROID__)
        // /proc/self/exe points at linker64 when launched from app-private storage.
        Dl_info executable_info{};
        Checks::check_exit(VCPKG_LINE_INFO,
            dladdr(reinterpret_cast<const void*>(&get_exe_path_of_current_process), &executable_info) != 0 &&
                executable_info.dli_fname != nullptr,
            "Could not determine Android executable path.");
        return Path(executable_info.dli_fname);
''' + marker).replace('"/bin/sh"', '"/system/bin/sh"')
    # Android's system env applet cannot exec app-private binaries. Keep variable
    # removal in the existing shell so tina-exec can handle the actual child.
    replacements = {
        'real_command_line_builder.raw_arg("env");': 'real_command_line_builder.raw_arg("unset");',
        'real_command_line_builder.raw_arg("-u");': '',
        '                real_command_line_builder.raw_arg(env->get());\n            }\n        }':
            '                real_command_line_builder.raw_arg("&&");\n                real_command_line_builder.raw_arg(env->get());\n            }\n        }',
        'actual_cmd_line.append("env");': 'actual_cmd_line.append("unset");',
        'actual_cmd_line.append(" -u ");': 'actual_cmd_line.append(" ");',
        "                actual_cmd_line.push_back(' ');\n                actual_cmd_line.append(env_unpacked->get());":
            '                actual_cmd_line.append(" && ");\n                actual_cmd_line.append(env_unpacked->get());',
    }
    for old, new in replacements.items():
        if text.count(old) != 1:
            raise ValueError("Upstream environment handling changed; review Android adaptations")
        text = text.replace(old, new)
    generated = build / "android-compat/system.process.cpp"
    generated.parent.mkdir(exist_ok=True)
    if not generated.is_file() or generated.read_text(encoding="utf-8") != text:
        generated.write_text(text, encoding="utf-8")
    hook = build / "vcpkg-android-api.cmake"
    hook.write_text('''if(ANDROID AND DEFINED ANDROID_PLATFORM_LEVEL)
  set(CMAKE_SYSTEM_VERSION "${ANDROID_PLATFORM_LEVEL}")
endif()
function(tina_android_process_source)
  get_target_property(process_sources vcpkglib SOURCES)
  list(REMOVE_ITEM process_sources [==[%s]==])
  list(APPEND process_sources [==[%s]==])
  set_property(TARGET vcpkglib PROPERTY SOURCES "${process_sources}")
endfunction()
cmake_language(DEFER CALL tina_android_process_source)
''' % (original.as_posix(), generated.as_posix()), encoding="utf-8")
    return hook, hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--api", type=int, default=28)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--ninja", type=Path, help="Explicit host Ninja executable")
    parser.add_argument("--jobs", type=int, default=1)
    args = parser.parse_args()
    source, ndk = args.source.resolve(), args.ndk.resolve()
    build, output = args.build_dir.resolve(), args.output_dir.resolve()
    if args.api < 28:
        parser.error("Android API 28 or later is required")
    if args.jobs < 1:
        parser.error("Build jobs must be positive")
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    if not toolchain.is_file() or not (source / "CMakeLists.txt").is_file():
        parser.error("Missing Android NDK toolchain or vcpkg-tool sources")
    if build == source or output == source:
        parser.error("Keep build/output directories separate from the source checkout")
    build.mkdir(parents=True, exist_ok=True)
    # NDK's default legacy toolchain deliberately sets CMAKE_SYSTEM_VERSION=1.
    # vcpkg uses that value after project() to select the pre-API-28 spawn shim.
    # Restore the actual API for this project's feature check without modifying
    # upstream sources or switching away from the NDK's supported default mode.
    api_hook, adapted_source_sha256 = prepare_android_sources(source, build)
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    tag_result = subprocess.run(["git", "-C", str(source), "describe", "--tags", "--exact-match"],
                                text=True, capture_output=True)
    tag = tag_result.stdout.strip()
    version_options = [f"-DVCPKG_VERSION={revision}"]
    if tag_result.returncode == 0 and re.fullmatch(r"\d{4}-\d{2}-\d{2}", tag):
        version_options.append(f"-DVCPKG_BASE_VERSION={tag}")
    subprocess.run([
        args.cmake, "-S", str(source), "-B", str(build), "-G", "Ninja",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
        f"-DANDROID_PLATFORM=android-{args.api}", f"-DCMAKE_SYSTEM_VERSION={args.api}",
        f"-DCMAKE_PROJECT_vcpkg_INCLUDE={api_hook.as_posix()}",
        "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF",
        "-DVCPKG_DEVELOPMENT_WARNINGS=OFF", "-DVCPKG_WARNINGS_AS_ERRORS=OFF",
        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
    ] + version_options + ([f"-DCMAKE_MAKE_PROGRAM={args.ninja.resolve()}"] if args.ninja else []), check=True)
    subprocess.run([args.cmake, "--build", str(build), "--target", "vcpkg", "--parallel", str(args.jobs)], check=True)
    executable = build / "vcpkg"
    if not executable.is_file():
        raise FileNotFoundError(f"Expected Android executable: {executable}")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(executable, output / "vcpkg")
    strip_line = next(line for line in (build / "CMakeCache.txt").read_text(encoding="utf-8").splitlines()
                      if line.startswith("CMAKE_STRIP:FILEPATH="))
    subprocess.run([strip_line.split("=", 1)[1], "--strip-unneeded", str(output / "vcpkg")], check=True)
    shutil.copy2(source / "LICENSE.txt", output / "vcpkg-LICENSE.txt")
    (output / "vcpkg-build.json").write_text(json.dumps({
        "source": "https://github.com/microsoft/vcpkg-tool", "revision": revision,
        "tag": tag if tag_result.returncode == 0 else None,
        "abi": args.abi, "androidApi": args.api, "stl": "c++_static",
        "sha256": hashlib.sha256((output / "vcpkg").read_bytes()).hexdigest(),
        "androidProcessSourceSha256": adapted_source_sha256,
        "androidAdaptations": ["actual API for spawn availability", "system shell path", "dladdr executable path", "shell environment removal"],
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Android vcpkg prepared in {output}")


if __name__ == "__main__":
    main()

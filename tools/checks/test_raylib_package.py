"""Regression checks for the Android raylib package's standard frame loop."""

from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "docker/tinaide-pkg/libs/build-raylib.sh"
PACKAGER = ROOT / "docker/tinaide-pkg/package-native-library.sh"
OUTPUT = ROOT / "docker/tinaide-pkg/output"
ABIS = ("arm64-v8a", "x86_64")


class RaylibPackageTest(unittest.TestCase):
    def test_build_preserves_numeric_defaults_from_upstream_config(self):
        source = BUILDER.read_text(encoding="utf-8")
        self.assertRegex(source, r"-DCUSTOMIZE_BUILD=OFF\b")
        self.assertNotRegex(source, r"-DCUSTOMIZE_BUILD=ON\b")

    def test_fixed_package_has_a_new_revision(self):
        source = PACKAGER.read_text(encoding="utf-8")
        raylib_case = source.split("    raylib)", 1)[1].split(";;", 1)[0]
        self.assertRegex(raylib_case, r"PACKAGE_REVISION=2\b")

    def test_tracked_registry_archives_have_fixed_metadata_and_checksums(self):
        for suffix, expected_abis in (("", ABIS), *((f"-{abi}", (abi,)) for abi in ABIS)):
            with self.subTest(suffix=suffix):
                archive_path = OUTPUT / f"registry/raylib/6.0/raylib{suffix}.tar.xz"
                checksum_file = Path(str(archive_path) + ".sha256")
                checksum, filename = checksum_file.read_text(encoding="utf-8").strip().split(maxsplit=1)
                # GNU sha256sum uses '*' as the binary-mode marker on Windows.
                filename = filename.removeprefix("*")
                self.assertEqual(filename, archive_path.name)
                self.assertEqual(checksum, hashlib.sha256(archive_path.read_bytes()).hexdigest())
                with tarfile.open(archive_path) as archive:
                    metadata = json.load(archive.extractfile("./package.json"))
                    self.assertEqual(metadata["id"], "raylib")
                    self.assertEqual(metadata["version"], "6.0")
                    self.assertEqual(metadata["packageRevision"], 2)
                    self.assertEqual(metadata["abis"], list(expected_abis))
                    self.assertEqual(
                        metadata["upstreamCommit"],
                        "dbc56a87da87d973a9c5baa4e7438a9d20121d28",
                    )
                    libraries = {
                        member.name for member in archive.getmembers()
                        if member.isfile() and member.name.endswith(".so")
                    }
                    self.assertEqual(libraries, {f"./lib/{abi}/libraylib.so" for abi in expected_abis})
                    for name in ("LICENSE.txt", "include/raylib.h", "lib/cmake/raylib/raylibConfig.cmake"):
                        self.assertGreater(archive.getmember("./" + name).size, 0)

    def test_build_and_registry_archives_contain_the_same_library(self):
        with tarfile.open(OUTPUT / "registry/raylib/6.0/raylib.tar.xz") as universal:
            for abi in ABIS:
                with self.subTest(abi=abi):
                    with tarfile.open(OUTPUT / f"raylib/{abi}/raylib-{abi}-shared.tar.xz") as build:
                        expected_library = build.extractfile("lib/libraylib.so").read()
                    with tarfile.open(OUTPUT / f"registry/raylib/6.0/raylib-{abi}.tar.xz") as registry:
                        self.assertEqual(expected_library, registry.extractfile(f"./lib/{abi}/libraylib.so").read())
                    self.assertEqual(expected_library, universal.extractfile(f"./lib/{abi}/libraylib.so").read())

    def run_contract_check(self, *, frame_calls: tuple[str, ...], objdump_exit: int = 0):
        # Run only the existing validation function, never the /output packaging flow.
        source = PACKAGER.read_text(encoding="utf-8")
        function = re.search(
            r"^verify_raylib_runtime_contract\(\) \{\n.*?^\}",
            source,
            flags=re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(function)
        disassembly = "\n".join(f"  1234: call <{name}@plt>" for name in frame_calls)
        fixture = f"""
set -euo pipefail
readelf() {{
    if [ "$1" = "-dW" ]; then
        printf '%s\\n' 'Library soname: [libraylib.so]'
    else
        printf '%s\\n' \\
            '1: 00001000 128 FUNC GLOBAL DEFAULT 13 ANativeActivity_onCreate' \\
            '2: 00000000 0 NOTYPE GLOBAL DEFAULT UND main'
    fi
}}
llvm-objdump() {{
    printf '%s\\n' '{disassembly}'
    return {objdump_exit}
}}
{function.group(0)}
verify_raylib_runtime_contract fixture.so
"""
        bash = shutil.which("bash")
        self.assertIsNotNone(bash, "Run these checks in Bash (Git Bash on Windows)")
        return subprocess.run(
            [bash, "-c", fixture],
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=10,
            check=False,
        )

    def test_packager_accepts_standard_frame_loop(self):
        result = self.run_contract_check(frame_calls=("SwapScreenBuffer", "PollInputEvents"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_packager_accepts_inlined_android_buffer_swap(self):
        result = self.run_contract_check(frame_calls=("eglSwapBuffers", "PollInputEvents"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_packager_rejects_custom_frame_control_binary(self):
        result = self.run_contract_check(frame_calls=())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("EndDrawing", result.stderr)

    def test_packager_requires_both_presentation_and_input_polling(self):
        for call in ("SwapScreenBuffer", "PollInputEvents"):
            with self.subTest(call=call):
                result = self.run_contract_check(frame_calls=(call,))
                self.assertNotEqual(result.returncode, 0)

    def test_packager_does_not_ignore_disassembler_failure(self):
        result = self.run_contract_check(
            frame_calls=("SwapScreenBuffer", "PollInputEvents"), objdump_exit=1,
        )
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()

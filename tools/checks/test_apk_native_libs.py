from pathlib import Path
import struct
import tempfile
import unittest
import warnings
from zipfile import ZipFile

from check_apk_native_libs import ELF_MACHINES, GUEST_LOADER_MACHINES, REQUIRED_LIBRARIES, native_library_errors


class ApkNativeLibraryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"

    def header(self, abi):
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", header, 18, ELF_MACHINES[abi])
        return header

    def write_apk(self, abi="x86_64", omit=(), machine=None):
        with ZipFile(self.apk, "w") as archive:
            for library in sorted(REQUIRED_LIBRARIES - set(omit)):
                archive.writestr(f"lib/{abi}/{library}", self.header(machine or abi))

    def test_complete_single_abi_apks_pass(self):
        for abi in ELF_MACHINES:
            with self.subTest(abi=abi):
                self.write_apk(abi)
                self.assertEqual(native_library_errors(self.apk, abi), [])

    def test_every_required_runtime_is_checked(self):
        for library in REQUIRED_LIBRARIES:
            with self.subTest(library=library):
                self.write_apk(omit=(library,))
                self.assertEqual(native_library_errors(self.apk, "x86_64"),
                                 [f"Missing native runtime: lib/x86_64/{library}."])

    def test_arm64_composite_cannot_fill_x86_apk(self):
        self.write_apk("arm64-v8a")
        errors = native_library_errors(self.apk, "x86_64")
        self.assertTrue(any("libandroid-tree-sitter.so" in error for error in errors))
        self.assertTrue(any("Unexpected native entry" in error for error in errors))

    def test_wrong_elf_machine_fails_even_with_correct_path(self):
        self.write_apk(machine="arm64-v8a")
        self.assertTrue(all("ELF architecture" in error for error in native_library_errors(self.apk, "x86_64")))
        self.assertEqual(len(native_library_errors(self.apk, "x86_64")), len(REQUIRED_LIBRARIES))

    def test_extra_abi_is_rejected(self):
        self.write_apk()
        with ZipFile(self.apk, "a") as archive:
            archive.writestr("lib/arm64-v8a/libextra.so", self.header("arm64-v8a"))
        self.assertTrue(any("Unexpected native entry" in error for error in native_library_errors(self.apk, "x86_64")))

    def test_truncated_or_non_elf_library_fails(self):
        for header in (b"", b"not an ELF file" * 5, b"\x7fELF\x01\x01" + bytes(58)):
            with self.subTest(header=header[:6]):
                self.write_apk()
                with ZipFile(self.apk, "a") as archive:
                    archive.writestr("lib/x86_64/libextra.so", header)
                self.assertTrue(any("Invalid 64-bit" in error for error in native_library_errors(self.apk, "x86_64")))

    def test_duplicate_library_fails(self):
        self.write_apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(self.apk, "a") as archive:
                archive.writestr("lib/x86_64/libandroid-tree-sitter.so", self.header("x86_64"))
        self.assertTrue(any("Duplicate native entry" in error for error in native_library_errors(self.apk, "x86_64")))

    def test_optional_static_guest_loader_is_not_a_jni_library(self):
        for abi, machine in GUEST_LOADER_MACHINES.items():
            with self.subTest(abi=abi):
                self.write_apk(abi)
                header = self.header(abi)
                header[4] = 1
                struct.pack_into("<HH", header, 16, 2, machine)
                with ZipFile(self.apk, "a") as archive:
                    archive.writestr(f"lib/{abi}/libproot-loader32.so", header)
                self.assertEqual(native_library_errors(self.apk, abi), [])

    def test_guest_loader_exception_still_checks_class_machine_and_executable_type(self):
        for elf_class, elf_type, machine in ((2, 2, 3), (1, 3, 3), (1, 2, 40)):
            with self.subTest(elf_class=elf_class, elf_type=elf_type, machine=machine):
                self.write_apk()
                header = self.header("x86_64")
                header[4] = elf_class
                struct.pack_into("<HH", header, 16, elf_type, machine)
                with ZipFile(self.apk, "a") as archive:
                    archive.writestr("lib/x86_64/libproot-loader32.so", header)
                self.assertTrue(native_library_errors(self.apk, "x86_64"))

    def test_unreadable_apk_and_unknown_abi_fail_closed(self):
        self.assertTrue(native_library_errors(self.apk, "x86_64"))
        self.apk.write_bytes(b"not a zip")
        self.assertTrue(native_library_errors(self.apk, "x86_64"))
        self.assertTrue(native_library_errors(self.apk, "unsupported"))


if __name__ == "__main__":
    unittest.main()

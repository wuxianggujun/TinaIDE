import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from check_distribution import distribution_errors


class DistributionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "tools/checks").mkdir(parents=True)
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text('implementation("example:blocked")', encoding="utf-8")
        self.component = {"name": "Example", "dependency": "example:blocked", "status": "blocked", "reason": "License pending"}

    def write_policy(self):
        (self.root / "tools/checks/distribution-policy.json").write_text(
            json.dumps({"schemaVersion": 1, "components": [self.component]}), encoding="utf-8"
        )

    def record_review(self):
        self.license_text = "Reviewed license\nFull license text\n"
        self.license_path = self.root / "external/example/LICENSE"
        self.license_path.parent.mkdir(parents=True)
        self.license_path.write_text(self.license_text, encoding="utf-8", newline="\n")
        (self.root / "review.md").write_text("Recorded upstream license review", encoding="utf-8")
        self.notice = {
            "libraries": [{
                "uniqueId": "example:blocked",
                "website": "https://example.org/source",
                "licenses": ["AGPL-3.0-only"],
            }],
            "licenses": {"AGPL-3.0-only": {"content": self.license_text}},
        }
        self.write_notice()
        self.component.update(
            status="reviewed",
            evidence="review.md",
            license={
                "path": "external/example/LICENSE",
                "sha256": hashlib.sha256(self.license_text.encode("utf-8")).hexdigest(),
            },
            notice={
                "path": "app/notices.json",
                "libraryId": "example:blocked",
                "licenseId": "AGPL-3.0-only",
                "sourceUrl": "https://example.org/source",
            },
        )
        self.write_policy()

    def write_notice(self):
        (self.root / "app/notices.json").write_text(json.dumps(self.notice), encoding="utf-8")

    def test_linked_blocker_prevents_publication(self):
        self.write_policy()
        self.assertEqual(distribution_errors(self.root), ["Example: License pending"])

    def test_removed_dependency_can_be_distributed(self):
        self.write_policy()
        (self.root / "app/build.gradle.kts").write_text("dependencies {}", encoding="utf-8")
        self.assertEqual(distribution_errors(self.root), [])

    def test_missing_policy_fails_closed(self):
        self.assertTrue(distribution_errors(self.root))

    def test_review_requires_repository_evidence(self):
        self.record_review()
        self.component["evidence"] = "../../outside.md"
        self.write_policy()
        self.assertTrue(distribution_errors(self.root))
        self.component["evidence"] = "review.md"
        self.write_policy()
        self.assertEqual(distribution_errors(self.root), [])

    def test_review_requires_pinned_license_and_shipped_notice(self):
        self.record_review()
        self.assertEqual(distribution_errors(self.root), [])
        del self.component["license"]
        del self.component["notice"]
        self.write_policy()
        self.assertTrue(distribution_errors(self.root))

    def test_reverting_to_restricted_license_fails_closed(self):
        self.record_review()
        self.license_path.write_text("Non-commercial use only; at most 10 users", encoding="utf-8")
        self.assertTrue(distribution_errors(self.root))

    def test_missing_submodule_license_fails_closed(self):
        self.record_review()
        self.license_path.unlink()
        self.assertTrue(distribution_errors(self.root))

    def test_missing_evidence_fails_closed(self):
        self.record_review()
        (self.root / "review.md").unlink()
        self.assertTrue(distribution_errors(self.root))

    def test_license_hash_must_be_sha256(self):
        self.record_review()
        for invalid in ("", "123", "z" * 64, None):
            with self.subTest(invalid=invalid):
                self.component["license"]["sha256"] = invalid
                self.write_policy()
                self.assertTrue(distribution_errors(self.root))

    def test_review_paths_must_stay_inside_repository(self):
        self.record_review()
        for field in ("license", "notice"):
            original = self.component[field]["path"]
            for invalid in ("../../outside", str(self.license_path.resolve()), "", None):
                with self.subTest(field=field, invalid=invalid):
                    self.component[field]["path"] = invalid
                    self.write_policy()
                    self.assertTrue(distribution_errors(self.root))
            self.component[field]["path"] = original

    def test_windows_line_endings_do_not_change_license_identity(self):
        self.record_review()
        self.license_path.write_bytes(self.license_text.replace("\n", "\r\n").encode("utf-8"))
        self.assertEqual(distribution_errors(self.root), [])

    def test_missing_or_invalid_notice_fails_closed(self):
        self.record_review()
        notice_path = self.root / "app/notices.json"
        for invalid in ("[]", "{}", "not json"):
            with self.subTest(invalid=invalid):
                notice_path.write_text(invalid, encoding="utf-8")
                self.assertTrue(distribution_errors(self.root))
        notice_path.unlink()
        self.assertTrue(distribution_errors(self.root))

    def test_notice_must_show_reviewed_license_and_source(self):
        self.record_review()
        library = self.notice["libraries"][0]
        for field, replacement in (("website", "https://example.org/wrong"), ("licenses", ["MIT"])):
            original = library[field]
            with self.subTest(field=field):
                library[field] = replacement
                self.write_notice()
                self.assertTrue(distribution_errors(self.root))
            library[field] = original

    def test_notice_must_include_full_unmodified_license_text(self):
        self.record_review()
        self.notice["licenses"]["AGPL-3.0-only"]["content"] = "AGPL-3.0, see upstream"
        self.write_notice()
        self.assertTrue(distribution_errors(self.root))

    def test_notice_library_must_be_present_and_unique(self):
        self.record_review()
        library = self.notice["libraries"][0]
        for libraries in ([], [library, library]):
            with self.subTest(count=len(libraries)):
                self.notice["libraries"] = libraries
                self.write_notice()
                self.assertTrue(distribution_errors(self.root))

    def test_final_apk_must_contain_exact_reviewed_raw_notice(self):
        self.record_review()
        apk = self.root / "test.apk"
        with ZipFile(apk, "w") as archive:
            archive.write(self.root / "app/notices.json", "res/short.json")
        self.assertEqual(distribution_errors(self.root, apk), [])
        with ZipFile(apk, "w") as archive:
            archive.writestr("res/short.json", '{"libraries":[],"licenses":{}}')
        self.assertTrue(distribution_errors(self.root, apk))

    def test_unreadable_apk_fails_closed(self):
        self.record_review()
        apk = self.root / "test.apk"
        self.assertTrue(distribution_errors(self.root, apk))
        apk.write_bytes(b"not a zip")
        self.assertTrue(distribution_errors(self.root, apk))

    def test_asset_copy_cannot_replace_the_consumed_raw_notice(self):
        self.record_review()
        apk = self.root / "test.apk"
        with ZipFile(apk, "w") as archive:
            archive.write(self.root / "app/notices.json", "assets/notices.json")
        self.assertTrue(distribution_errors(self.root, apk))

    def test_unknown_status_fails_closed(self):
        self.component["status"] = "ignore"
        self.write_policy()
        self.assertTrue(distribution_errors(self.root))

    def test_empty_dependency_and_malformed_policy_fail_closed(self):
        self.component["dependency"] = ""
        self.write_policy()
        self.assertTrue(distribution_errors(self.root))
        (self.root / "tools/checks/distribution-policy.json").write_text("[]", encoding="utf-8")
        self.assertTrue(distribution_errors(self.root))


if __name__ == "__main__":
    unittest.main()

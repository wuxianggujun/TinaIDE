import json
import tempfile
import unittest
from pathlib import Path

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
        self.component.update(status="reviewed", evidence="../../outside.md")
        self.write_policy()
        self.assertTrue(distribution_errors(self.root))
        self.component["evidence"] = "review.md"
        (self.root / "review.md").write_text("Recorded license review", encoding="utf-8")
        self.write_policy()
        self.assertEqual(distribution_errors(self.root), [])

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

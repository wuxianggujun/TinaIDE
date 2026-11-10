import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SYNC_SCRIPT = Path(__file__).resolve().parents[1] / "i18n" / "sync_values_en.py"


class TranslationResourceValidationTest(unittest.TestCase):
    def check_pair(self, base, english):
        with tempfile.TemporaryDirectory() as directory:
            base_path = Path(directory) / "base.xml"
            en_path = Path(directory) / "en.xml"
            base_path.write_text(base, encoding="utf-8")
            en_path.write_text(english, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SYNC_SCRIPT), "--check", "--base", str(base_path), "--en", str(en_path)],
                capture_output=True, text=True, encoding="utf-8", timeout=10,
            )
            self.assertEqual(base, base_path.read_text(encoding="utf-8"))
            self.assertEqual(english, en_path.read_text(encoding="utf-8"))
            return result

    def test_duplicates_in_both_locales_are_not_mistaken_for_a_valid_sync(self):
        xml = '<resources><string name="empty">Empty</string><string name="empty">Nothing</string></resources>'
        result = self.check_pair(xml, xml)
        self.assertEqual(1, result.returncode)
        self.assertIn("Duplicate resource: string/empty", result.stdout)

    def test_duplicate_alias_in_english_is_rejected(self):
        base = '<resources><string name="empty">空</string></resources>'
        en = '<resources><string name="empty">Empty</string><item type="string" name="empty">@string/other</item></resources>'
        result = self.check_pair(base, en)
        self.assertEqual(1, result.returncode)
        self.assertIn("en.xml: Duplicate resource", result.stdout)

    def test_separate_locales_and_comment_examples_are_valid(self):
        base = '<resources><!-- <string name="empty">Example</string> --><string name="empty">空</string></resources>'
        en = '<resources><string name="empty">Empty</string></resources>'
        self.assertEqual(0, self.check_pair(base, en).returncode)


if __name__ == "__main__":
    unittest.main()

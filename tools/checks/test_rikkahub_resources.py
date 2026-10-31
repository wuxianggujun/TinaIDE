"""Include RikkaHub's offline resource contracts in the existing CI discovery."""

from pathlib import Path
import unittest


def load_tests(loader, tests, pattern):
    source = Path(__file__).resolve().parents[2] / "external/rikkahub/embedded/tests"
    return unittest.TestLoader().discover(str(source), pattern="test_*.py", top_level_dir=str(source))

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("editor_device_baseline", Path(__file__).parents[1] / "testing/editor_device_baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class DeviceBaselineTests(unittest.TestCase):
    def test_parser_rejects_flagged_unfinished_and_duplicate_frames(self):
        raw = "Flags,IntendedVsync,FrameCompleted\n0,100,10000100\n0,100,10000100\n1,100,20000100\n0,100,9223372036854775807\n"
        self.assertEqual(baseline.frame_durations(raw), [10.0])

    def test_empty_capture_cannot_pass(self):
        with self.assertRaises(ValueError):
            baseline.metrics([], [1000], 60)

    def test_meminfo_variants(self):
        self.assertEqual(baseline.total_pss_kb("TOTAL PSS: 1234 TOTAL RSS: 5678"), 1234)
        self.assertEqual(baseline.total_pss_kb("  TOTAL 4321 0 0"), 4321)
        with self.assertRaises(ValueError):
            baseline.total_pss_kb("No process found")

    def report(self):
        return {"schema": 1, "status": "unbaselined", "device": {"model": "test", "sdk": "34"},
                "scenario": "scroll-10k-cpp", "refresh_hz": 60, "build_type": "debug", "fixture": "cpp-10000-v1",
                "metrics": baseline.metrics([10.0] * 60, [100000], 60)}

    def test_different_devices_and_missing_baselines_do_not_pass(self):
        report = self.report()
        other = self.report()
        other["device"]["sdk"] = "35"
        self.assertTrue(baseline.compare(report, other))
        self.assertTrue(baseline.compare(report, {}))
        other = self.report()
        other["metrics"]["p95_ms"] = float("nan")
        self.assertTrue(baseline.compare(report, other))
        self.assertTrue(baseline.compare(report, []))
        other["metrics"] = None
        self.assertTrue(baseline.compare(report, other))

    def test_regression_and_stable_results(self):
        reference = self.report()
        report = self.report()
        self.assertEqual(baseline.compare(report, reference), [])
        report["metrics"]["p95_ms"] = 30.0
        self.assertTrue(baseline.compare(report, reference))


if __name__ == "__main__":
    unittest.main()

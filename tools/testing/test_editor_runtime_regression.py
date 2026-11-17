import unittest
import base64
import struct

from run_editor_runtime_regression import instrumentation_passed, instrumentation_report, validate_market_evidence


class InstrumentationVerdictTest(unittest.TestCase):
    def test_one_completed_test_passes(self):
        output = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(instrumentation_passed(output, 0, False))

    def test_empty_selection_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("OK (0 tests)\n", 0, False))

    def test_host_timeout_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("OK (1 test)\n", 0, True))

    def test_nonzero_process_exit_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("OK (1 test)\n", 1, False))

    def test_ignored_status_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n", 0, False))

    def test_assumption_skip_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\n", 0, False))

    def test_failure_status_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)\n", 0, False))

    def test_runner_failure_is_not_a_pass(self):
        self.assertFalse(instrumentation_passed("OK (1 test)\nINSTRUMENTATION_FAILED\n", 0, False))

    def test_release_report_is_read_without_private_file_access(self):
        output = 'INSTRUMENTATION_STATUS: tina_regression_report={"runId":"current","passed":true}\r\n'
        self.assertEqual(instrumentation_report(output), {"runId": "current", "passed": True})

    def test_missing_report_remains_available_to_file_based_suites(self):
        self.assertIsNone(instrumentation_report("OK (1 test)\n"))

    def test_duplicate_reports_are_rejected(self):
        output = 'INSTRUMENTATION_STATUS: tina_regression_report={}\n' * 2
        with self.assertRaises(ValueError):
            instrumentation_report(output)

    def test_non_object_report_is_rejected(self):
        with self.assertRaises(ValueError):
            instrumentation_report('INSTRUMENTATION_STATUS: tina_regression_report=[]\n')


class MarketEvidenceTest(unittest.TestCase):
    def report(self):
        # Only the protocol header is needed for identity/dimension validation.
        png = b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 1200, 600)
        return dict(runId="current", passed=True, landscape=True, width=1200, height=600,
                    density=2.0, listHeightPx=240.0, navigationDestinations=4,
                    screenshotPng=base64.b64encode(png).decode("ascii"))

    def test_current_landscape_report_is_read_without_root(self):
        self.assertTrue(validate_market_evidence(self.report(), "current", "landscape").startswith(b"\x89PNG"))

    def test_stale_market_report_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_market_evidence(self.report(), "previous", "landscape")

    def test_short_list_is_not_a_pass(self):
        report = self.report()
        report["listHeightPx"] = 238.0
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")

    def test_wrong_orientation_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_market_evidence(self.report(), "current", "portrait")

    def test_nonfinite_density_is_rejected(self):
        report = self.report()
        report["density"] = float("nan")
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")

    def test_missing_screenshot_is_rejected(self):
        report = self.report()
        report.pop("screenshotPng")
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")

    def test_wrong_screenshot_dimensions_are_rejected(self):
        report = self.report()
        report["width"] = 1201
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")


if __name__ == "__main__":
    unittest.main()

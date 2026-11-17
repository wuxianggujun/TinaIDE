import unittest

from run_editor_runtime_regression import instrumentation_passed, instrumentation_report


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


if __name__ == "__main__":
    unittest.main()

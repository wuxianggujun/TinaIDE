import unittest

from run_editor_runtime_regression import instrumentation_passed


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


if __name__ == "__main__":
    unittest.main()

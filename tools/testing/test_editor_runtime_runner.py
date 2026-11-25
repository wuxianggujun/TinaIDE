import unittest

from run_editor_runtime_regression import instrumentation_passed, validate_performance_evidence


class EditorRuntimeRunnerTest(unittest.TestCase):
    def test_requires_one_executed_test_and_successful_process(self):
        self.assertTrue(instrumentation_passed("\r\nOK (1 test)\r\n", 0, False))
        for output, code, timed_out in (
            ("OK (0 tests)", 0, False),
            ("OK (1 test)", 1, False),
            ("OK (1 test)", 0, True),
            ("OK (1 test)\nINSTRUMENTATION_FAILED", 0, False),
            ("TestTimedOutException\nOK (1 test)", 0, False),
            ("", 0, False),
        ):
            with self.subTest(output=output, code=code, timeout=timed_out):
                self.assertFalse(instrumentation_passed(output, code, timed_out))

    def test_rejects_stale_partial_and_wrong_mode_evidence(self):
        extras = {"performanceHighlight": "true", "performanceTrace": "false"}
        report = dict(runId="this-run", passed=True, phase="complete", highlight=True,
                      methodTracing=False, samples=160, renderMs=[5] * 160)
        validate_performance_evidence(report, "this-run", extras)
        with self.assertRaises(ValueError):
            validate_performance_evidence([], "this-run", extras)
        for changed in (
            {"runId": "previous-run"}, {"phase": "sampling-scroll"}, {"passed": False},
            {"highlight": False}, {"methodTracing": True}, {"samples": 159},
            {"renderMs": [5] * 159}, {"renderMs": [-1] * 160},
        ):
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError):
                    validate_performance_evidence(report | changed, "this-run", extras)


if __name__ == "__main__":
    unittest.main()

import unittest
import base64
import struct
import json

from run_editor_runtime_regression import (
    COURSE_ASSESSMENT_CHECKS, COURSE_ASSESSMENT_METHODS, COURSE_ASSESSMENT_PHASES, instrumentation_passed, instrumentation_progress,
    instrumentation_report, select_cases, validate_course_assessment_evidence, validate_market_evidence,
    validate_tab_label_evidence, validate_vcpkg_evidence, validate_tutorial_project_evidence,
)


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


class CourseAssessmentEvidenceTest(unittest.TestCase):
    def report(self):
        return dict(runId="current", passed=True, language="zh", checks=5, savedAndVerified=True,
                    progressReloaded=True, compileFailureRejected=True, wrongOutputRejected=True,
                    cancelledProcessStopped=True, timeoutProcessStopped=True, retryPassed=True)

    def test_complete_current_report_passes(self):
        validate_course_assessment_evidence(self.report(), "current", "zh")

    def test_stale_wrong_language_and_missing_process_evidence_are_rejected(self):
        for changes in ({"runId": "old"}, {"language": "en"}, {"checks": 4},
                        {"cancelledProcessStopped": False}, {"timeoutProcessStopped": None},
                        {"retryPassed": 1}, {"passed": False}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_course_assessment_evidence(self.report() | changes, "current", "zh")


    def test_each_bounded_phase_requires_its_own_checks_and_identity(self):
        for phase, checks in COURSE_ASSESSMENT_CHECKS.items():
            report = dict(runId="current", passed=True, language="zh", checks=5, assessmentPhase=phase)
            report.update({check: True for check in checks})
            with self.subTest(phase=phase):
                validate_course_assessment_evidence(report, "current", "zh", phase)
                for missing in checks:
                    with self.assertRaises(ValueError):
                        validate_course_assessment_evidence(report | {missing: False}, "current", "zh", phase)
                with self.assertRaises(ValueError):
                    validate_course_assessment_evidence(report | {"assessmentPhase": "all"}, "current", "zh", phase)
                with self.assertRaises(ValueError):
                    validate_course_assessment_evidence(report, "old", "zh", phase)


class TutorialProjectEvidenceTest(unittest.TestCase):
    def test_creation_requires_all_chapters_preservation_and_native_output(self):
        report = dict(runId="current", passed=True, language="zh", created=9,
                      preservedEditsAndConfig=True, symlinkConflictPreserved=True, output="total=30\n")
        validate_tutorial_project_evidence(report, "current", "zh")
        for changes in ({"runId": "old"}, {"language": "en"}, {"created": 8}, {"output": ""},
                        {"preservedEditsAndConfig": False}, {"symlinkConflictPreserved": False}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_tutorial_project_evidence(report | changes, "current", "zh")

    def test_navigation_cannot_be_replaced_by_creation_only(self):
        report = dict(runId="current", passed=True, openedRealEditor=True, reopenedSameProject=True,
                      preservedSource=True, activityRecreated=True)
        validate_tutorial_project_evidence(report, "current", None)
        for check in ("openedRealEditor", "reopenedSameProject", "preservedSource", "activityRecreated"):
            with self.subTest(check=check), self.assertRaises(ValueError):
                validate_tutorial_project_evidence(report | {check: False}, "current", None)


class InstrumentationProgressTest(unittest.TestCase):
    @staticmethod
    def output(*checkpoints):
        return "".join("INSTRUMENTATION_STATUS: tina_regression_progress=" + json.dumps(checkpoint) + "\r\n"
                       for checkpoint in checkpoints)

    def test_progress_survives_a_timeout_without_becoming_a_success_report(self):
        checkpoint = dict(runId="current", stage="configure", elapsedMs=1000, passed=False)
        output = self.output(checkpoint) + "HOST_TIMEOUT_60_SECONDS\n"
        self.assertEqual(instrumentation_progress(output, "current"), [checkpoint])
        self.assertIsNone(instrumentation_report(output))
        self.assertFalse(instrumentation_passed(output, 0, True))

    def test_progress_does_not_replace_the_final_completed_test(self):
        output = self.output(dict(runId="current", stage="cleanup", elapsedMs=1000, passed=True))
        self.assertFalse(instrumentation_passed(output, 0, False))
        self.assertIsNone(instrumentation_report(output))

    def test_ignores_prior_run_checkpoints(self):
        output = self.output(dict(runId="old", stage="configure", elapsedMs=1000))
        self.assertEqual(instrumentation_progress(output, "current"), [])

    def test_invalid_or_non_monotonic_progress_is_rejected(self):
        checkpoint = dict(runId="current", stage="configure", elapsedMs=1000)
        for changes in ({"stage": ""}, {"elapsedMs": -1}, {"elapsedMs": True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                instrumentation_progress(self.output(checkpoint | changes), "current")
        with self.assertRaises(ValueError):
            instrumentation_progress(self.output(checkpoint, checkpoint | {"elapsedMs": 999}), "current")
        with self.assertRaises(ValueError):
            instrumentation_progress(self.output([]), "current")


class BoundedCaseSelectionTest(unittest.TestCase):
    def test_each_assessment_phase_has_an_independent_junit_method(self):
        self.assertEqual(set(COURSE_ASSESSMENT_METHODS), set(COURSE_ASSESSMENT_PHASES))
        self.assertEqual(len(set(COURSE_ASSESSMENT_METHODS.values())), len(COURSE_ASSESSMENT_PHASES))

    def test_legacy_course_case_selects_all_phases_in_order(self):
        cases = [(f"assess-{language}-{phase}", "selector", {})
                 for language in ("zh", "en") for phase in COURSE_ASSESSMENT_PHASES]
        self.assertEqual([name for name, _, _ in select_cases(cases, "assess-en")],
                         [f"assess-en-{phase}" for phase in COURSE_ASSESSMENT_PHASES])
        self.assertEqual(select_cases(cases, "assess-zh-cancel"), [cases[2]])

    def test_market_reuse_includes_installation_before_run(self):
        cases = [(name, "selector", {}) for name in ("catalog", "market-install", "market-reuse-run", "shared")]
        self.assertEqual(select_cases(cases, "market-reuse"), cases[1:3])
        self.assertEqual(select_cases(cases, None), cases)

    def test_unknown_or_incomplete_group_is_not_silently_selected(self):
        for cases, requested in (([], "unknown"), ([("market-install", "selector", {})], "market-reuse")):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                select_cases(cases, requested)


class MarketReuseEvidenceTest(unittest.TestCase):
    def test_reuse_requires_the_current_installation_and_unchanged_libraries(self):
        report = dict(runId="current", passed=True, phase="market-reuse-run", marketReuseFixtureId="fixture",
                      marketInstalled=True, runReusedInstalledLibrary=True,
                      installedLibraryTimestampUnchanged=True, installedLibraryHashesUnchanged=True,
                      installedLibraryIdentityUnchanged=True)
        validate_vcpkg_evidence(report, "current", "market-reuse-run", "fixture")
        for changes in ({"marketReuseFixtureId": "old"}, {"marketInstalled": False},
                        {"runReusedInstalledLibrary": False}, {"installedLibraryTimestampUnchanged": False},
                        {"installedLibraryHashesUnchanged": False}, {"installedLibraryIdentityUnchanged": False}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_vcpkg_evidence(report | changes, "current", "market-reuse-run", "fixture")


class MarketEvidenceTest(unittest.TestCase):
    def report(self):
        # Only the protocol header is needed for identity/dimension validation.
        png = b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 1200, 600)
        return dict(runId="current", passed=True, landscape=True, width=1200, height=600,
                    density=2.0, listHeightPx=240.0, navigationDestinations=4,
                    tabLabelsSingleLine=True,
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

    def test_missing_tab_layout_evidence_is_rejected(self):
        report = self.report()
        report.pop("tabLabelsSingleLine")
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")

    def test_wrong_screenshot_dimensions_are_rejected(self):
        report = self.report()
        report["width"] = 1201
        with self.assertRaises(ValueError):
            validate_market_evidence(report, "current", "landscape")


class MarketTabLabelEvidenceTest(unittest.TestCase):
    @staticmethod
    def report():
        return dict(runId="current", passed=True, variants=[
            dict(language=language, widthDp=width, fontScale=scale,
                 labels=[dict(lineCount=1, overflowWidth=False, overflowHeight=False, ellipsized=False) for _ in range(3)])
            for language in ("zh", "en") for width in (240, 320) for scale in (1, 1.5, 2)])

    def test_complete_current_matrix_passes(self):
        validate_tab_label_evidence(self.report(), "current")

    def test_clipping_or_ellipsis_is_not_a_pass(self):
        for changes in ({"lineCount": 2}, {"lineCount": True}, {"overflowWidth": True},
                        {"overflowHeight": True}, {"ellipsized": True}):
            report = self.report()
            report["variants"][0]["labels"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_tab_label_evidence(report, "current")

    def test_partial_duplicate_stale_or_wrong_scale_matrix_is_rejected(self):
        report = self.report()
        for changed in (report | {"variants": report["variants"][:-1]},
                        report | {"variants": [report["variants"][0]] * 12}, report | {"runId": "old"},
                        report | {"passed": False}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                validate_tab_label_evidence(changed, "current")
        for scale in (True, float("nan"), "large"):
            report = self.report()
            report["variants"][0]["fontScale"] = scale
            with self.subTest(scale=scale), self.assertRaises(ValueError):
                validate_tab_label_evidence(report, "current")


if __name__ == "__main__":
    unittest.main()

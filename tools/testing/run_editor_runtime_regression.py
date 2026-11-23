"""Run editor/host/runtime device cases serially, with a 60-second limit per case."""
from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
EDITOR_CASES = (
    "longPressFindClose_preservesTenCharactersAndReturnsImeOwnership",
    "pinchRoundTrips_keepGuttersVisibleAndSingleFingerScrollWorking",
    "oppositeHostAndEditorThemes_keepFindAndInjectedHoverReadable",
    "replacementKeyboardActionsAndUndo_workAfterSearchFocusTransfer",
    "invalidRegexAndNoMatches_recoverAndReplaceAllRemainsOneUndo",
    "unicodeReverseMultiSelection_survivesRepeatedPanelCycles",
    "horizontalPanAndPinch_preserveScrolledContentAndAllowSearchAfterwards",
    "searchOptionsAndZeroWidthRegex_keepMatchCountsAndUndoConsistent",
    "narrowPane_keepsQueryReplacementAndPrimaryActionsReachable",
    "foldGutterHitTargets_followFontGeometryAfterPinch",
)
MAX_MARKET_SCREENSHOT_BYTES = 512 * 1024
COURSE_ASSESSMENT_PHASES = ("save", "reject", "cancel", "timeout")
COURSE_ASSESSMENT_METHODS = {
    "save": "savesRebuildsChecksAndRecordsOnlySuccessfulRuns",
    "reject": "rejectsCompileErrorsAndWrongOutputThenRetries",
    "cancel": "cancellationStopsTheProcessAndAllowsRetry",
    "timeout": "deadlineStopsTheProcessAndAllowsRetry",
}
COURSE_ASSESSMENT_CHECKS = {
    "save": ("savedAndVerified", "progressReloaded"),
    "reject": ("compileFailureRejected", "wrongOutputRejected", "retryPassed"),
    "cancel": ("cancelledProcessStopped", "retryPassed"),
    "timeout": ("timeoutProcessStopped", "retryPassed"),
}


def instrumentation_passed(output: str, returncode: int, timed_out: bool) -> bool:
    """One selected test must complete; failures and ignored/assumption skips are not passes."""
    return (not timed_out and returncode == 0 and
            re.search(r"(?m)^OK \(1 test\)\s*$", output) is not None and
            re.search(r"(?m)^INSTRUMENTATION_STATUS_CODE:[ \t]*-[1-4][ \t\r]*$", output) is None and
            not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|TestTimedOutException", output))


def instrumentation_report(output: str) -> dict | None:
    """Read this invocation's explicit report without accessing private Release files."""
    matches = re.findall(r"(?m)^INSTRUMENTATION_STATUS: tina_regression_report=(.+)\r?$", output)
    if not matches:
        return None
    if len(matches) != 1:
        raise ValueError("Expected exactly one instrumentation evidence report")
    report = json.loads(matches[0])
    if not isinstance(report, dict):
        raise ValueError("Expected an instrumentation report object")
    return report


def instrumentation_progress(output: str, run_id: str) -> list[dict]:
    """Keep current-run checkpoints separate from the one final success report."""
    checkpoints = []
    for payload in re.findall(r"(?m)^INSTRUMENTATION_STATUS: tina_regression_progress=(.+)\r?$", output):
        checkpoint = json.loads(payload)
        if not isinstance(checkpoint, dict):
            raise ValueError("Expected an instrumentation progress object")
        if checkpoint.get("runId") != run_id:
            continue
        if (not isinstance(checkpoint.get("stage"), str) or not checkpoint["stage"] or
                type(checkpoint.get("elapsedMs")) is not int or checkpoint["elapsedMs"] < 0):
            raise ValueError("Invalid instrumentation checkpoint")
        if checkpoints and checkpoint["elapsedMs"] < checkpoints[-1]["elapsedMs"]:
            raise ValueError("Instrumentation checkpoint time went backwards")
        checkpoints.append(checkpoint)
    return checkpoints


def select_cases(cases: list[tuple], requested: str | None) -> list[tuple]:
    """Legacy case names select bounded phases, not a longer test deadline."""
    if requested is None:
        return cases
    aliases = {f"assess-{language}": tuple(f"assess-{language}-{phase}" for phase in COURSE_ASSESSMENT_PHASES)
               for language in ("zh", "en")}
    aliases["market-reuse"] = ("market-install", "market-reuse-run")
    names = aliases.get(requested, (requested,))
    selected = [case for case in cases if case[0] in names]
    if len(selected) != len(names):
        raise ValueError("Unknown case for the selected suite")
    return selected


def validate_performance_evidence(report: dict, run_id: str, extras: dict) -> None:
    if not isinstance(report, dict):
        raise ValueError("Expected a performance report object")
    expected_count = 80 if extras["performanceTrace"] == "true" else 160
    if (report.get("runId") != run_id or report.get("passed") is not True or
            report.get("phase") != "complete" or
            report.get("highlight") != (extras["performanceHighlight"] == "true") or
            report.get("methodTracing") != (extras["performanceTrace"] == "true")):
        raise ValueError("Missing or stale performance evidence for this invocation")
    durations = report.get("renderMs")
    if (not isinstance(durations, list) or len(durations) != expected_count or
            report.get("samples") != expected_count or
            any(type(value) is not int or value < 0 for value in durations)):
        raise ValueError("Incomplete renderer samples")


def validate_course_assessment_evidence(report: dict, run_id: str, language: str, phase: str = "all") -> None:
    required_checks = (tuple(dict.fromkeys(check for checks in COURSE_ASSESSMENT_CHECKS.values() for check in checks))
                       if phase == "all" else COURSE_ASSESSMENT_CHECKS[phase])
    if (report.get("runId") != run_id or report.get("passed") is not True or
            report.get("assessmentPhase", "all") != phase or
            report.get("language") != language or type(report.get("checks")) is not int or
            report["checks"] != 5 or any(report.get(check) is not True for check in required_checks)):
        raise ValueError("Incomplete or stale course assessment evidence")


def validate_tutorial_project_evidence(report: dict, run_id: str, language: str | None) -> None:
    if report.get("runId") != run_id or report.get("passed") is not True:
        raise ValueError("Missing or stale tutorial project evidence")
    if language is None:
        checks = ("openedRealEditor", "reopenedSameProject", "preservedSource", "activityRecreated")
        if any(report.get(check) is not True for check in checks):
            raise ValueError("Tutorial button did not complete the real editor navigation flow")
    elif (report.get("language") != language or report.get("created") != 9 or
          report.get("preservedEditsAndConfig") is not True or report.get("symlinkConflictPreserved") is not True or
          not isinstance(report.get("output"), str) or "total=30" not in report["output"]):
        raise ValueError("Incomplete tutorial creation, preservation or native execution evidence")


def validate_vcpkg_evidence(report: dict, run_id: str, phase: str, fixture_id: str | None = None) -> None:
    if report.get("runId") != run_id or report.get("passed") is not True or report.get("phase") != phase:
        raise ValueError("Incomplete or stale managed vcpkg evidence")
    if phase in ("market-install", "market-reuse-run"):
        if not fixture_id or report.get("marketReuseFixtureId") != fixture_id or report.get("marketInstalled") is not True:
            raise ValueError("Market dependency evidence belongs to a different installation")
        if phase == "market-reuse-run" and any(report.get(check) is not True for check in
                ("runReusedInstalledLibrary", "installedLibraryTimestampUnchanged", "installedLibraryHashesUnchanged", "installedLibraryIdentityUnchanged")):
            raise ValueError("Market run did not prove installed dependency reuse")


def validate_fmt_course_evidence(report: dict, run_id: str, language: str) -> None:
    if (report.get("runId") != run_id or report.get("passed") is not True or report.get("language") != language or
            any(report.get(key) is not True for key in ("declaredFmt", "usesFmt", "installedFmt", "reusedFmt", "preservedSources")) or
            not isinstance(report.get("output"), str) or "total=30" not in report["output"].splitlines()):
        raise ValueError("Incomplete or stale production fmt chapter evidence")


def validate_tutorial_ui_evidence(report: dict, run_id: str, case: str) -> None:
    if report.get("runId") != run_id or report.get("passed") is not True:
        raise ValueError("Incomplete or stale tutorial UI evidence")
    if case == "markdown":
        samples = report.get("coloredSamples")
        if (report.get("highlightedBlocks") != 3 or report.get("readOnly") is not True or
                report.get("copiedWholeCode") is not True or not isinstance(samples, list) or len(samples) != 3 or
                any(type(sample) is not int or sample <= 20 for sample in samples)):
            raise ValueError("Markdown did not prove editor highlighting, read-only display and whole-code copy")
    else:
        variants = report.get("variants")
        if case != "project-titles" or not isinstance(variants, list) or report.get("variantCount") != 24:
            raise ValueError("Missing project title matrix")
        observed = []
        for item in variants:
            if (not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"] or
                    item.get("completeName") is not True or item.get("tagsBelowTitle") is not True or
                    item.get("language") not in ("zh", "en") or type(item.get("widthDp")) is not int or
                    type(item.get("fontScale")) not in (int, float) or not math.isfinite(item["fontScale"])):
                raise ValueError("Project names were truncated or shared the badge row")
            observed.append((item["name"], item["language"], item["widthDp"], item["fontScale"]))
        names = {item[0] for item in observed}
        expected = {(name, locale, width, scale) for name in names for locale in ("zh", "en")
                    for width in (280, 320) for scale in (1, 1.5, 2)}
        if len(names) != 2 or len(observed) != 24 or set(observed) != expected:
            raise ValueError("Incomplete project title locale/width/font-scale matrix")


def validate_tab_label_evidence(report: dict, run_id: str) -> None:
    expected = {(language, width, scale) for language in ("zh", "en") for width in (240, 320)
                for scale in (1, 1.5, 2)}
    variants = report.get("variants")
    if report.get("runId") != run_id or report.get("passed") is not True or not isinstance(variants, list):
        raise ValueError("Missing or stale market label evidence")
    observed = []
    for variant in variants:
        if (not isinstance(variant, dict) or not isinstance(variant.get("language"), str) or
                type(variant.get("widthDp")) is not int or
                type(variant.get("fontScale")) not in (int, float) or not math.isfinite(variant["fontScale"])):
            raise ValueError("Expected market label variant objects")
        labels = variant.get("labels")
        if not isinstance(labels, list) or len(labels) != 3 or any(
                not isinstance(label, dict) or type(label.get("lineCount")) is not int or label["lineCount"] != 1 or
                label.get("overflowWidth") is not False or label.get("overflowHeight") is not False or
                label.get("ellipsized") is not False for label in labels):
            raise ValueError("Market labels were truncated or not measured")
        observed.append((variant.get("language"), variant.get("widthDp"), variant.get("fontScale")))
    if len(observed) != len(expected) or set(observed) != expected:
        raise ValueError("Incomplete market locale/width/font-scale matrix")


def validate_market_evidence(report: dict, run_id: str, orientation: str) -> bytes:
    """Validate this invocation's viewport and bounded inline PNG without private-file access."""
    landscape = orientation == "landscape"
    if (report.get("runId") != run_id or report.get("passed") is not True or
            report.get("landscape") != landscape or report.get("navigationDestinations") != 4 or
            report.get("tabLabelsSingleLine") is not True):
        raise ValueError("Incomplete or stale main-market evidence")
    width, height = report.get("width"), report.get("height")
    density, list_height = report.get("density"), report.get("listHeightPx")
    if (type(width) is not int or type(height) is not int or width <= 0 or height <= 0 or
            (width > height) != landscape or
            type(density) not in (int, float) or not math.isfinite(density) or density <= 0 or
            type(list_height) not in (int, float) or not math.isfinite(list_height) or list_height <= 0 or
            landscape and list_height / density < 120):
        raise ValueError("Invalid or unusable market viewport")
    encoded = report.get("screenshotPng")
    if not isinstance(encoded, str) or not encoded or len(encoded) > (MAX_MARKET_SCREENSHOT_BYTES + 2) // 3 * 4:
        raise ValueError("Missing or oversized market screenshot")
    png = base64.b64decode(encoded, validate=True)
    if (not 24 <= len(png) <= MAX_MARKET_SCREENSHOT_BYTES or png[:8] != b"\x89PNG\r\n\x1a\n" or
            struct.unpack_from(">II", png, 16) != (width, height)):
        raise ValueError("Market screenshot does not match the active viewport")
    return png


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--suite", choices=("editor", "host", "runtime", "packages", "vcpkg", "vcpkg-managed", "course", "tutorial-project", "fmt-course", "tutorial-ui", "graphics-course", "performance", "market-layout"), required=True)
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug", help="Select the App instrumentation package")
    parser.add_argument("--root-evidence", action="store_true", help="Read only this suite's private evidence on an explicitly rooted emulator")
    parser.add_argument("--package-id", default="fmt", help="Market package used by the packages suite")
    parser.add_argument("--orientation", choices=("portrait", "landscape"), default="portrait")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/reports/editor-runtime-regression")
    parser.add_argument("--case", help="Run one named case from the suite")
    parser.add_argument("--market-fixture-id", help="Reuse the fixture id recorded by a successful market-install case")
    parser.add_argument("--profile", action="store_true", help="Sample Java methods during the performance suite")
    args = parser.parse_args()
    if args.build_type == "release" and args.suite == "editor":
        parser.error("--build-type release applies only to App suites")
    if args.root_evidence and not args.serial.startswith("emulator-"):
        parser.error("--root-evidence requires an emulator serial")
    if args.profile and args.suite != "performance":
        parser.error("--profile requires --suite performance")
    if args.market_fixture_id and (args.suite != "vcpkg-managed" or not re.fullmatch(r"[0-9a-f]{32}", args.market_fixture_id)):
        parser.error("--market-fixture-id requires --suite vcpkg-managed and a 32-character lowercase hex id")
    if args.suite == "vcpkg-managed" and args.case == "market-reuse-run" and not args.market_fixture_id:
        parser.error("--case market-reuse-run requires --market-fixture-id from market-install; use --case market-reuse to run both")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.suite == "editor":
        package = "com.wuxianggujun.tinaide.core.editorview.test"
        cases = [(name, "com.wuxianggujun.tinaide.core.editorview.EditorFindAndScaleInstrumentationTest#" + name, {})
                 for name in EDITOR_CASES]
    else:
        package = "com.wuxianggujun.tinaide.release.test" if args.build_type == "release" else "com.wuxianggujun.tinaide.test"
        if args.suite == "host":
            cases = [(name, "com.wuxianggujun.tinaide.ui.compose.editor." + selector, {}) for name, selector in (
                ("host-theme", "EditorHostInteractionInstrumentationTest#hostMarkdownAndSearch_remainReadableAndPreserveSelectionAcrossOppositeThemes"),
                ("compact-input", "CompactInputInstrumentationTest#compactField_preservesMiddleSelectionAndExternalUpdates"),
                ("variable-caret", "CompactInputInstrumentationTest#variableCompletion_usesActualCaretAndKeepsSuffix"),
                ("large-cpp", "EditorLargeFileInstrumentationTest#realCppHighlighter_largeFileScrollAndEdit_remainConsistent"),
            )]
        elif args.suite == "market-layout":
            selector = "com.wuxianggujun.tinaide.ui.compose.editor.MarketSearchInstrumentationTest#searchSurvivesTabSwitchAndStateRestorationAndCanBeClosed"
            cases = [(f"market-{args.orientation}", selector, {"regressionInlineScreenshot": "true"})]
            cases.append(("tab-labels", "com.wuxianggujun.tinaide.ui.compose.editor.MarketTabLayoutInstrumentationTest#labelsRemainCompleteAcrossLocalesWidthsAndFontScales", {}))
        elif args.suite == "packages":
            selector = "com.wuxianggujun.tinaide.core.packages.RegistryInstallDeviceTest#downloadCancelRetryAndUninstall_keepFilesAndRecordsConsistent"
            cases = [(phase, selector, {"packagePhase": phase, "packageId": args.package_id})
                     for phase in ("cancel", "install", "uninstall", "verify-uninstalled")]
        elif args.suite == "vcpkg":
            selector = "com.wuxianggujun.tinaide.core.compile.VcpkgAcceptanceDeviceTest#realFmtProject_configuresBuildsOrRuns"
            cases = [(phase, selector, {"vcpkgPhase": phase}) for phase in ("configure", "build", "run", "repeat")]
        elif args.suite == "vcpkg-managed":
            selector = "com.wuxianggujun.tinaide.core.compile.VcpkgManagedDeviceTest#managedCatalogAndRuntime"
            cases = [(phase, selector, {"vcpkgManagedPhase": phase}) for phase in
                     ("catalog", "update", "sources", "cancel-runtime", "runtime", "configure", "build", "run", "repeat", "market-install", "market-reuse-run", "external", "shared", "cancel-shared")]
            fixture_id = args.market_fixture_id or uuid.uuid4().hex
            for name, _, extras in cases:
                if name in ("market-install", "market-reuse-run"):
                    extras["marketReuseFixtureId"] = fixture_id
            cases.insert(1, ("market-ui", "com.wuxianggujun.tinaide.ui.compose.editor.VcpkgMarketInstrumentationTest#noProjectStillShowsCatalogAndSupportsSearch", {}))
            # These cases require the caller to disable networking explicitly; the default suite stays online.
            if args.case in ("offline-update", "runtime-offline"):
                cases.append((args.case, selector, {"vcpkgManagedPhase": args.case}))
        elif args.suite == "tutorial-project":
            selector = "com.wuxianggujun.tinaide.core.compile.TutorialProjectDeviceTest#bundledChaptersCreateReopenAndRunOffline"
            cases = [(language, selector, {"courseLanguage": language}) for language in ("zh", "en")]
            cases.append(("navigation", "com.wuxianggujun.tinaide.ui.compose.editor.TutorialProjectNavigationInstrumentationTest#chapterButtonOpensRealEditorAndReopensWithoutRewritingFiles", {}))
        elif args.suite == "fmt-course":
            selector = "com.wuxianggujun.tinaide.core.compile.TutorialFmtCourseDeviceTest#generatedProjectInstallsBuildsRunsAndReusesFmt"
            cases = [(language, selector, {"courseLanguage": language}) for language in ("zh", "en")]
        elif args.suite == "tutorial-ui":
            selector = "com.wuxianggujun.tinaide.ui.compose.editor.TutorialUiInstrumentationTest"
            cases = [("markdown", selector + "#markdownUsesActualSyntaxColorsAndCopiesWithoutEditing", {}),
                     ("project-titles", selector + "#projectNamesStayCompleteAndBadgesUseTheirOwnRows", {})]
        elif args.suite == "course":
            selector = "com.wuxianggujun.tinaide.core.compile.NativeCourseDeviceTest#lessonBuildsAndProducesItsExpectedOutput"
            cases = [(f"{language}-{lesson}", selector, {"courseLesson": lesson, "courseLanguage": language})
                     for language in ("zh", "en") for lesson in
                     ("ide-workflow", "cpp-basics", "cpp-functions", "cpp-collections", "cpp-raii", "cmake-practice", "ai-practice")]
            cases.append(("recover-compile-error", selector, {"courseLesson": "ide-workflow", "courseLanguage": "en", "courseRecovery": "true"}))
            assessment_class = "com.wuxianggujun.tinaide.core.compile.CourseAssessmentDeviceTest"
            cases += [(f"assess-{language}-{phase}", assessment_class + "#" + COURSE_ASSESSMENT_METHODS[phase],
                       {"courseLanguage": language, "assessmentPhase": phase})
                      for language in ("zh", "en") for phase in COURSE_ASSESSMENT_PHASES]
        elif args.suite == "graphics-course":
            selector = "com.wuxianggujun.tinaide.core.compile.GraphicsCourseDeviceTest#lessonBuildsAndDrawsThroughProductionHost"
            cases = [(f"{language}-{library}", selector, {"courseLanguage": language, "graphicsLibrary": library})
                     for language in ("zh", "en") for library in ("sdl3",)]
        elif args.suite == "performance":
            selector = "com.wuxianggujun.tinaide.ui.compose.editor.EditorScrollPerformanceInstrumentationTest#warmCppScroll_recordsEnoughRendererSamples"
            cases = [(f"warm-cpp-{highlight}", selector,
                      {"performanceHighlight": highlight, "performanceTrace": str(args.profile).lower()})
                     for highlight in ("false", "true")]
        else:
            selector = "com.wuxianggujun.tinaide.ui.runtime.SdlRuntimeIsolationInstrumentationTest#realSdlAssetsAndNativeFailure_leaveIdeProcessAlive"
            cases = [(f"sdl{major}-{action}-custom{custom}", selector,
                      {"sdlMajor": str(major), "nativeAction": action, "customAssets": str(custom).lower()})
                     for major in (3, 2) for action, custom in (("return", False), ("return", True), ("abort", True), ("exit", False))]
            cases += [(f"raylib-{action}", selector, {"nativeHost": "raylib", "nativeAction": action})
                      for action in ("return", "abort", "exit")]
    try:
        cases = select_cases(cases, args.case)
    except ValueError as error:
        parser.error(str(error))
    reports = []
    for name, selector, extras in cases:
        run_id = uuid.uuid4().hex
        target = "com.wuxianggujun.tinaide" if package in ("com.wuxianggujun.tinaide.test", "com.wuxianggujun.tinaide.release.test") else package
        command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "timeout_msec", "55000" if args.suite in ("vcpkg-managed", "course", "tutorial-project", "fmt-course", "graphics-course") else "45000", "-e", "editorOrientation", args.orientation,
                   "-e", "regressionRunId", run_id, "-e", "class", selector]
        for key, value in extras.items():
            command += ["-e", key, value]
        command.append(package + "/androidx.test.runner.AndroidJUnitRunner")
        started = time.monotonic()
        timed_out = False
        returncode = -1
        evidence_file = None
        evidence_image = None
        timeout_source = None
        checkpoints = []
        try:
            result = subprocess.run(command, capture_output=True, timeout=60)
            output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            returncode = result.returncode
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode("utf-8", errors="replace") + "\nHOST_TIMEOUT_60_SECONDS\n"
            timed_out = True
            timeout_source = "host"
        if "TestTimedOutException" in output:
            timed_out = True
            timeout_source = timeout_source or "device"
        passed = instrumentation_passed(output, returncode, timed_out)
        try:
            checkpoints = instrumentation_progress(output, run_id)
        except ValueError as error:
            output += f"\nREGRESSION_PROGRESS_ERROR: {error}\n"
            passed = False
        if args.suite in ("performance", "vcpkg-managed", "course", "tutorial-project", "fmt-course", "tutorial-ui", "graphics-course", "market-layout") and name != "market-ui":
            try:
                filename = (f"warm-cpp-scroll-{extras['performanceHighlight']}.json" if args.suite == "performance"
                            else f"course-project-{name}.json" if args.suite == "tutorial-project"
                            else f"tutorial-fmt-{name}.json" if args.suite == "fmt-course"
                            else f"tutorial-ui-{name}.json" if args.suite == "tutorial-ui"
                            else f"course-{extras['courseLanguage']}-{extras['courseLesson']}.json" if args.suite == "course" and "courseLesson" in extras
                            else f"course-assessment-{extras['courseLanguage']}-{extras['assessmentPhase']}.json" if args.suite == "course"
                            else f"graphics-course-{extras['courseLanguage']}-{extras['graphicsLibrary']}.json" if args.suite == "graphics-course"
                            else "market-tab-labels.json" if args.suite == "market-layout" and name == "tab-labels"
                            else f"market-search-{args.orientation}.json" if args.suite == "market-layout"
                            else f"vcpkg-managed-{name}.json")
                evidence_command = ([args.adb, "-s", args.serial, "exec-out", "cat",
                                     f"/data/user/0/{target}/files/test-regression-evidence/{filename}"]
                                    if args.root_evidence else
                                    [args.adb, "-s", args.serial, "exec-out", "run-as", target,
                                     "cat", "files/test-regression-evidence/" + filename])
                report = instrumentation_report(output)
                if report is None:
                    evidence = subprocess.check_output(evidence_command, stderr=subprocess.PIPE, timeout=10)
                    if not evidence.strip():
                        if passed or not checkpoints:
                            raise ValueError("No current-run evidence; instrumentation did not write a final report")
                        report = checkpoints[-1]
                        evidence = json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8")
                    else:
                        try:
                            report = json.loads(evidence)
                        except json.JSONDecodeError as error:
                            raise ValueError("Device evidence is not JSON; no readable report was written for this test") from error
                else:
                    evidence = json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8")
                if not isinstance(report, dict):
                    raise ValueError("Expected a performance report object")
                # Preserve current partial evidence for debugging, never a prior run's file.
                if report.get("runId") == run_id:
                    evidence_file = f"{name}-{run_id}-metrics.json"
                    (args.output / evidence_file).write_bytes(evidence)
                if passed:
                    if args.suite == "tutorial-project":
                        validate_tutorial_project_evidence(report, run_id, extras.get("courseLanguage"))
                    elif args.suite == "fmt-course":
                        validate_fmt_course_evidence(report, run_id, extras["courseLanguage"])
                    elif args.suite == "tutorial-ui":
                        validate_tutorial_ui_evidence(report, run_id, name)
                    elif args.suite == "performance":
                        validate_performance_evidence(report, run_id, extras)
                    elif args.suite == "market-layout" and name == "tab-labels":
                        validate_tab_label_evidence(report, run_id)
                    elif args.suite == "market-layout":
                        png = validate_market_evidence(report, run_id, args.orientation)
                        evidence_image = f"{name}-{run_id}.png"
                        (args.output / evidence_image).write_bytes(png)
                        report.pop("screenshotPng")
                        (args.output / evidence_file).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
                    elif args.suite == "graphics-course":
                        if (report.get("runId") != run_id or report.get("passed") is not True or
                                report.get("library") != extras["graphicsLibrary"] or
                                report.get("language") != extras["courseLanguage"] or
                                report.get("orangeSamples", 0) <= 40 or report.get("blueSamples", 0) <= 200):
                            raise ValueError("Incomplete or stale graphical course evidence")
                    elif args.suite == "course" and "courseLesson" not in extras:
                        validate_course_assessment_evidence(report, run_id, extras["courseLanguage"], extras["assessmentPhase"])
                    elif args.suite == "course":
                        if (report.get("runId") != run_id or report.get("passed") is not True or
                                report.get("lesson") != extras["courseLesson"] or report.get("language") != extras["courseLanguage"] or
                                extras.get("courseRecovery") == "true" and report.get("recoveredFromCompileError") is not True):
                            raise ValueError("Incomplete or stale course execution evidence")
                    else:
                        validate_vcpkg_evidence(report, run_id, name, extras.get("marketReuseFixtureId"))
            except (subprocess.SubprocessError, ValueError, OSError) as error:
                output += f"\nREGRESSION_EVIDENCE_ERROR: {error}\n"
                if checkpoints and evidence_file is None:
                    evidence_file = f"{name}-{run_id}-metrics.json"
                    (args.output / evidence_file).write_text(json.dumps(checkpoints[-1], ensure_ascii=False, indent=2), encoding="utf-8")
                passed = False
        if not passed:
            # Both host and device-side timeouts can leave instrumentation alive.
            for process_package in dict.fromkeys((target, package)):
                try:
                    stopped = subprocess.run([args.adb, "-s", args.serial, "shell", "am", "force-stop", process_package],
                                             capture_output=True, timeout=10)
                    if stopped.returncode:
                        output += f"\nFORCE_STOP_FAILED: {process_package} ({stopped.returncode})\n"
                except (subprocess.SubprocessError, OSError) as error:
                    output += f"\nFORCE_STOP_ERROR: {error}\n"
        (args.output / f"{name}.log").write_text(output, encoding="utf-8")
        reports.append(dict(name=name, selector=selector, extras=extras, serial=args.serial,
                            orientation=args.orientation, buildType=args.build_type, seconds=round(time.monotonic() - started, 2),
                            passed=passed, timed_out=timed_out, returncode=returncode, runId=run_id,
                            evidenceFile=evidence_file, timeoutSource=timeout_source,
                            lastStage="complete" if passed else checkpoints[-1].get("failedStage", checkpoints[-1]["stage"]) if checkpoints else
                            "test-body" if "INSTRUMENTATION_STATUS: class=" in output else "instrumentation-startup",
                            checkpoints=[{"stage": checkpoint["stage"], "elapsedMs": checkpoint["elapsedMs"]}
                                         for checkpoint in checkpoints]))
        if evidence_image:
            reports[-1]["evidenceImage"] = evidence_image
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        (args.output / "results.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
        if timed_out or (not passed and args.suite in ("packages", "vcpkg", "vcpkg-managed", "course", "tutorial-project", "fmt-course", "graphics-course")):
            break
    return 0 if all(report["passed"] for report in reports) and len(reports) == len(cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())

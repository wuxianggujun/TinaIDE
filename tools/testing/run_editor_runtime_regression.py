"""Run editor/host/runtime device cases serially, with a 60-second limit per case."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--suite", choices=("editor", "host", "runtime", "packages", "vcpkg", "vcpkg-managed", "course", "graphics-course", "performance"), required=True)
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug", help="Select the App instrumentation package")
    parser.add_argument("--root-evidence", action="store_true", help="Read only this suite's private evidence on an explicitly rooted emulator")
    parser.add_argument("--package-id", default="fmt", help="Market package used by the packages suite")
    parser.add_argument("--orientation", choices=("portrait", "landscape"), default="portrait")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/reports/editor-runtime-regression")
    parser.add_argument("--case", help="Run one named case from the suite")
    parser.add_argument("--profile", action="store_true", help="Sample Java methods during the performance suite")
    args = parser.parse_args()
    if args.build_type == "release" and args.suite == "editor":
        parser.error("--build-type release applies only to App suites")
    if args.root_evidence and not args.serial.startswith("emulator-"):
        parser.error("--root-evidence requires an emulator serial")
    if args.profile and args.suite != "performance":
        parser.error("--profile requires --suite performance")
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
                     ("catalog", "update", "cancel-runtime", "runtime", "configure", "build", "run", "repeat", "external", "shared", "cancel-shared")]
            cases.insert(1, ("market-ui", "com.wuxianggujun.tinaide.ui.compose.editor.VcpkgMarketInstrumentationTest#noProjectStillShowsCatalogAndSupportsSearch", {}))
            # These cases require the caller to disable networking explicitly; the default suite stays online.
            if args.case in ("offline-update", "runtime-offline"):
                cases.append((args.case, selector, {"vcpkgManagedPhase": args.case}))
        elif args.suite == "course":
            selector = "com.wuxianggujun.tinaide.core.compile.NativeCourseDeviceTest#lessonBuildsAndProducesItsExpectedOutput"
            cases = [(f"{language}-{lesson}", selector, {"courseLesson": lesson, "courseLanguage": language})
                     for language in ("zh", "en") for lesson in
                     ("ide-workflow", "cpp-basics", "cpp-functions", "cpp-collections", "cpp-raii", "cmake-practice")]
            cases.append(("recover-compile-error", selector, {"courseLesson": "ide-workflow", "courseLanguage": "en", "courseRecovery": "true"}))
        elif args.suite == "graphics-course":
            selector = "com.wuxianggujun.tinaide.core.compile.GraphicsCourseDeviceTest#lessonBuildsAndDrawsThroughProductionHost"
            cases = [(f"{language}-{library}", selector, {"courseLanguage": language, "graphicsLibrary": library})
                     for language in ("zh", "en") for library in ("sdl3", "raylib")]
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
    if args.case:
        cases = [case for case in cases if case[0] == args.case]
        if not cases:
            parser.error("Unknown case for the selected suite")
    reports = []
    for name, selector, extras in cases:
        run_id = uuid.uuid4().hex
        target = "com.wuxianggujun.tinaide" if package in ("com.wuxianggujun.tinaide.test", "com.wuxianggujun.tinaide.release.test") else package
        command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "timeout_msec", "55000" if args.suite in ("vcpkg-managed", "course", "graphics-course") else "45000", "-e", "editorOrientation", args.orientation,
                   "-e", "regressionRunId", run_id, "-e", "class", selector]
        for key, value in extras.items():
            command += ["-e", key, value]
        command.append(package + "/androidx.test.runner.AndroidJUnitRunner")
        started = time.monotonic()
        timed_out = False
        returncode = -1
        evidence_file = None
        try:
            result = subprocess.run(command, capture_output=True, timeout=60)
            output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            returncode = result.returncode
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode("utf-8", errors="replace") + "\nHOST_TIMEOUT_60_SECONDS\n"
            timed_out = True
        passed = instrumentation_passed(output, returncode, timed_out)
        if args.suite in ("performance", "vcpkg-managed", "course", "graphics-course") and name != "market-ui":
            try:
                filename = (f"warm-cpp-scroll-{extras['performanceHighlight']}.json" if args.suite == "performance"
                            else f"course-{extras['courseLanguage']}-{extras['courseLesson']}.json" if args.suite == "course"
                            else f"graphics-course-{extras['courseLanguage']}-{extras['graphicsLibrary']}.json" if args.suite == "graphics-course"
                            else f"vcpkg-managed-{name}.json")
                evidence_command = ([args.adb, "-s", args.serial, "exec-out", "cat",
                                     f"/data/user/0/{target}/files/test-regression-evidence/{filename}"]
                                    if args.root_evidence else
                                    [args.adb, "-s", args.serial, "exec-out", "run-as", target,
                                     "cat", "files/test-regression-evidence/" + filename])
                report = instrumentation_report(output)
                if report is None:
                    evidence = subprocess.check_output(evidence_command, stderr=subprocess.PIPE, timeout=10)
                    report = json.loads(evidence)
                else:
                    evidence = json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8")
                if not isinstance(report, dict):
                    raise ValueError("Expected a performance report object")
                # Preserve current partial evidence for debugging, never a prior run's file.
                if report.get("runId") == run_id:
                    evidence_file = f"{name}-{run_id}-metrics.json"
                    (args.output / evidence_file).write_bytes(evidence)
                if passed:
                    if args.suite == "performance":
                        validate_performance_evidence(report, run_id, extras)
                    elif args.suite == "graphics-course":
                        if (report.get("runId") != run_id or report.get("passed") is not True or
                                report.get("library") != extras["graphicsLibrary"] or
                                report.get("language") != extras["courseLanguage"] or
                                report.get("orangeSamples", 0) <= 40 or report.get("blueSamples", 0) <= 200):
                            raise ValueError("Incomplete or stale graphical course evidence")
                    elif args.suite == "course":
                        if (report.get("runId") != run_id or report.get("passed") is not True or
                                report.get("lesson") != extras["courseLesson"] or report.get("language") != extras["courseLanguage"] or
                                extras.get("courseRecovery") == "true" and report.get("recoveredFromCompileError") is not True):
                            raise ValueError("Incomplete or stale course execution evidence")
                    elif report.get("runId") != run_id or report.get("passed") is not True or report.get("phase") != name:
                        raise ValueError("Incomplete or stale managed vcpkg evidence")
            except (subprocess.SubprocessError, ValueError, OSError) as error:
                output += f"\nREGRESSION_EVIDENCE_ERROR: {error}\n"
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
                            evidenceFile=evidence_file))
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        (args.output / "results.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
        if timed_out or (not passed and args.suite in ("packages", "vcpkg", "vcpkg-managed", "course", "graphics-course")):
            break
    return 0 if all(report["passed"] for report in reports) and len(reports) == len(cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())

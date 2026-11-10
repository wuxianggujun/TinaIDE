package com.wuxianggujun.tinaide.ui.compose.editor

import android.graphics.Typeface
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.editorview.*
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records observations, not a performance pass against an invented device baseline. */
@RunWith(AndroidJUnit4::class)
class EditorScrollPerformanceInstrumentationTest {
    @Test fun warmCppScroll_recordsEnoughRendererSamples() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val useHighlighter = InstrumentationRegistry.getArguments().getString("performanceHighlight", "true").toBoolean()
        val traceRequested = InstrumentationRegistry.getArguments().getString("performanceTrace", "false").toBoolean()
        val runId = InstrumentationRegistry.getArguments().getString("regressionRunId", "manual")
        val evidence = regressionEvidenceDirectory(context)
        val reportFile = File(evidence, "warm-cpp-scroll-$useHighlighter.json")
        val report = JSONObject().put("runId", runId).put("highlight", useHighlighter)
            .put("methodTracing", traceRequested).put("lines", 10_000).put("passed", false)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("fontScale", context.resources.configuration.fontScale)
        fun recordPhase(phase: String) {
            report.put("phase", phase)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        recordPhase("creating-highlighter")
        val text = (0 until 10_000).joinToString("\n") { "int value_$it = $it; // 中文😀" }
        val buffer = RopeTextBuffer(text)
        val highlighter = if (useHighlighter) checkNotNull(TreeSitterHighlighter.create(context, "cpp")) else null
        val state = EditorState(buffer, config = EditorConfig(wordWrap = false)).apply {
            typeface = Typeface.MONOSPACE
            this.highlighter = highlighter
        }
        var snapshot: (() -> EditorRenderPerformanceSnapshot)? = null
        val mounted = CountDownLatch(1)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        var methodTracing = false
        try {
            recordPhase("opening-document")
            highlighter?.openDocument(text)
            // Use the Activity's real frame clock, not Compose's test clock or
            // Espresso idling, for device rendering measurements.
            scenario.onActivity { activity ->
                activity.setContent { TinaIDETheme {
                    TinaEditor(state, Modifier.fillMaxSize(), onPerformanceSnapshotReaderChanged = {
                        snapshot = it
                        if (it != null) mounted.countDown()
                    })
                } }
            }
            check(mounted.await(10, TimeUnit.SECONDS)) { "Editor did not mount" }
            if (highlighter != null) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (highlighter.getLineSegments(0).isEmpty()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Initial C++ highlighting timed out" }
                    SystemClock.sleep(20)
                }
            }
            recordPhase("warming-scroll")
            sampleEditorScroll(instrumentation, state, { checkNotNull(snapshot).invoke() }, count = 30)
            if (traceRequested) {
                android.os.Debug.startMethodTracingSampling(
                    File(regressionEvidenceDirectory(context), "editor-scroll.trace").absolutePath,
                    8 * 1024 * 1024, 5000,
                )
                methodTracing = true
            }
            recordPhase("sampling-scroll")
            // Sampling perturbs execution; keep the diagnostic run within the device timeout.
            val sampleCount = if (traceRequested) 80 else 160
            val samples = sampleEditorScroll(instrumentation, state, { checkNotNull(snapshot).invoke() }, count = sampleCount)
            if (methodTracing) {
                android.os.Debug.stopMethodTracing()
                methodTracing = false
            }
            val durations = samples.renderMs
            assertEquals("Each scroll must produce a sample", sampleCount, durations.size)
            val sorted = durations.sorted()
            report.put("samples", durations.size).put("renderMs", JSONArray(durations))
                .put("p50RenderMs", sorted[sorted.size / 2])
                .put("p95RenderMs", sorted[((sorted.size - 1) * .95).toInt()])
                .put("slowFrames", samples.after.slowRenderedFrames - samples.before.slowRenderedFrames)
                .put("renderedFrames", samples.after.totalRenderedFrames - samples.before.totalRenderedFrames)
                .put("elapsedMs", samples.elapsedMs)
                .put("sampling", "Choreographer; scroll only after a new rendered frame")
                .put("scope", "Warmed render-function wall-clock samples; not CPU time, touch latency or complete display frame time")
            recordPhase("closing-editor")
        } catch (failure: Throwable) {
            report.put("passed", false).put("failure", failure.javaClass.simpleName)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
            throw failure
        } finally {
            if (methodTracing) android.os.Debug.stopMethodTracing()
            try {
                scenario.close()
            } finally {
                instrumentation.runOnMainSync {
                    state.highlighter = null
                    state.close()
                    buffer.close()
                }
                highlighter?.dispose()
            }
        }
        report.put("passed", true)
        recordPhase("complete")
    }
}

@RunWith(AndroidJUnit4::class)
class EditorLargeFileInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realCppHighlighter_largeFileScrollAndEdit_remainConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = (0 until 10_000).joinToString("\n") { "int value_$it = $it; // 中文😀" }
        val file = File(context.cacheDir, "editor-large-file-regression.cpp").apply { writeText(text, Charsets.UTF_8) }
        val opened = SystemClock.elapsedRealtime()
        val buffer = RopeTextBuffer(file.readText(Charsets.UTF_8))
        val openMs = SystemClock.elapsedRealtime() - opened
        val highlighter = checkNotNull(TreeSitterHighlighter.create(context, "cpp")) { "Real C++ parser unavailable" }
        val state = EditorState(buffer, config = EditorConfig(wordWrap = false)).apply {
            typeface = Typeface.MONOSPACE
            this.highlighter = highlighter
        }
        var mounted by mutableStateOf(true)
        var snapshot: (() -> EditorRenderPerformanceSnapshot)? = null
        val highlighting = SystemClock.elapsedRealtime()
        try {
            highlighter.openDocument(text)
            composeRule.setContent { if (mounted) TinaIDETheme {
                TinaEditor(state, Modifier.fillMaxSize().testTag("large"), onPerformanceSnapshotReaderChanged = { snapshot = it })
            } }
            composeRule.waitUntil(15_000) { highlighter.getLineSegments(0).isNotEmpty() }
            val highlightMs = SystemClock.elapsedRealtime() - highlighting
            repeat(12) { composeRule.onNodeWithTag("large").performTouchInput { swipeUp(durationMillis = 150) } }
            composeRule.runOnIdle {
                state.gotoLine(9_999, 0)
                buffer.insert(buffer.length, "\nconst char* probe = \"updated\";")
                assertEquals(10_001, buffer.lineCount)
                assertEquals("const char* probe = \"updated\";", buffer.getLine(10_000))
            }
            composeRule.waitUntil(10_000) { highlighter.getLineSegments(10_000).isNotEmpty() }
            composeRule.runOnIdle {
                val metrics = checkNotNull(snapshot).invoke()
                assertTrue(metrics.totalRenderedFrames > 0)
                assertTrue(metrics.lastVisibleLineCount in 1..200)
                val report = JSONObject().put("initialLines", 10_000).put("finalLines", buffer.lineCount).put("bytes", file.length())
                    .put("openMs", openMs).put("firstHighlightWithMountMs", highlightMs)
                    .put("renderer", metrics.toString()).put("scope", "debug emulator smoke, no baseline comparison")
                File(regressionEvidenceDirectory(context), "large-file-regression.json").writeText(report.toString(2), Charsets.UTF_8)
                mounted = false
            }
            composeRule.waitForIdle()
        } finally {
            state.highlighter = null
            highlighter.dispose()
            state.close()
            buffer.close()
            file.delete()
        }
    }
}

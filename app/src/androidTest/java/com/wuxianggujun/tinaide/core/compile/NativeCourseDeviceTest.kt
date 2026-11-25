package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compile the code actually displayed by the localized lesson assets, not a parallel sample. */
@RunWith(AndroidJUnit4::class)
class NativeCourseDeviceTest {
    @Test fun lessonBuildsAndProducesItsExpectedOutput() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val lesson = arguments.getString("courseLesson", "cpp-basics")
        val language = arguments.getString("courseLanguage", "zh")
        val directory = File(context.filesDir, "course-acceptance/$language/$lesson").apply { mkdirs() }
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("lesson", lesson).put("language", language).put("passed", false)
        val output = StringBuilder()
        try {
            withTimeout(50_000) {
                CourseLessonAssets(context, language).writeProject(directory, lesson)
                val executor = NativeCMakeBuildExecutor(context)
                val options = NativeCMakeBuildExecutor.Options(parallelJobs = 1, onProgress = { output.appendLine(it) })
                val build = File(directory, "build")
                val configured = executor.configure(directory, build, options)
                assertTrue(configured.toString() + "\n" + output, configured is ConfigureResult.Success)
                if (arguments.getString("courseRecovery") == "true") {
                    val source = File(directory, "main.cpp")
                    val original = source.readText(Charsets.UTF_8)
                    source.writeText(original + "\nthis is intentionally invalid C++;\n", Charsets.UTF_8)
                    val rejected = executor.build(directory, build, options = options)
                    assertTrue("An invalid lesson must not reuse an old executable", rejected is BuildResult.Error)
                    source.writeText(original, Charsets.UTF_8)
                    report.put("recoveredFromCompileError", true)
                }
                val built = executor.build(directory, build, options = options)
                assertTrue(built.toString() + "\n" + output, built is BuildResult.Success)
                val result = AndroidElfExecutor.exec(context, File(build, "native_lab"), emptyList(), workDir = directory, timeoutMs = 10_000)
                assertEquals(result.output, 0, result.exitCode)
                val expected = when (lesson) {
                    "ide-workflow" -> "Hello, TinaIDE!"
                    "cpp-collections" -> "Tina: items=3 total=30"
                    else -> "total=30"
                }
                assertTrue(result.output, result.output.contains(expected))
                report.put("output", result.output).put("passed", true)
            }
        } finally {
            val evidence = regressionEvidenceDirectory(context)
            File(evidence, "course-$language-$lesson.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "course-$language-$lesson.log").writeText(output.toString(), Charsets.UTF_8)
            emitRegressionReport(report)
        }
    }
}

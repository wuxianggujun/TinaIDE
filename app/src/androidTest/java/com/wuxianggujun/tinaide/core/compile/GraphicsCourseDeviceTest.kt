package com.wuxianggujun.tinaide.core.compile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectBlueprints
import com.wuxianggujun.tinaide.ui.ContextCompileSdlLauncher
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchSignal
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the actual course code with installed Market packages and checks its rendered colors. */
@RunWith(AndroidJUnit4::class)
class GraphicsCourseDeviceTest {
    @Test fun lessonBuildsAndDrawsThroughProductionHost() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val library = arguments.getString("graphicsLibrary", "sdl3")
        val language = arguments.getString("courseLanguage", "zh")
        require(library == "sdl3")
        require(language in setOf("zh", "en"))
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("library", library).put("language", language).put("passed", false)
        val evidence = regressionEvidenceDirectory(context)
        val name = "graphics-course-$language-$library"
        val output = StringBuilder()
        try {
            withTimeout(50_000) {
                val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
                val blueprint = TutorialProjectBlueprints.load("graphics_sdl3") { lesson ->
                    context.assets.open("$prefix/$lesson.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val directory = File(context.filesDir, "course-acceptance/$language/graphics-$library").apply { mkdirs() }
                blueprint.files.forEach { (name, contents) -> File(directory, name).writeText(contents, Charsets.UTF_8) }
                val executor = NativeCMakeBuildExecutor(context)
                val sysrootManager = AndroidSysrootManager(context.applicationContext)
                val arch = AndroidSysrootManager.Companion.Arch.current()
                val sysrootProfileId = sysrootManager.resolveProfileId(null, arch)
                val options = NativeCMakeBuildExecutor.Options(
                    parallelJobs = 1,
                    sysrootProfileId = sysrootProfileId,
                    onProgress = { output.appendLine(it) },
                )
                val build = File(directory, "build")
                val configured = executor.configure(directory, build, options)
                assertTrue(configured.toString() + "\n" + output, configured is ConfigureResult.Success)
                val built = executor.build(directory, build, target = "native_lab", options = options)
                assertTrue(built.toString() + "\n" + output, built is BuildResult.Success)
                val main = File(build, "libmain.so")
                assertTrue("The lesson must produce libmain.so", main.isFile)
                instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val idePid = Process.myPid()
                // Match CompileProjectUseCase: launch with the same sysroot that built the lesson.
                // Calling the resolver directly without this environment falsely reports libc++ missing.
                val runtimeDirectory = File(sysrootManager.getSysrootDir(arch, sysrootProfileId), "usr/lib/${arch.triple}")
                assertTrue("The lesson's sysroot must supply libc++", File(runtimeDirectory, "libc++_shared.so").isFile)
                val environment = mapOf(
                    "LD_LIBRARY_PATH" to runtimeDirectory.absolutePath,
                    CompileProjectUseCase.RUNTIME_ASSET_ROOT_ENV to directory.absolutePath,
                )
                report.put("sysrootProfileId", sysrootProfileId).put("runtimeDirectory", runtimeDirectory.absolutePath)
                var launchIntent: Intent? = null
                val activityStarter: (Intent) -> Unit = { launchIntent = it }
                val onError: (String) -> Unit = { fail(it) }
                ContextCompileSdlLauncher(context, onError, activityStarter).open(
                    GraphicalRuntimeLaunchRequest.Sdl(
                        libraryPath = main.absolutePath,
                        environment = environment,
                        preferredSdlMajor = 3,
                    )
                )
                val launch = checkNotNull(launchIntent) { "The production launcher must create a graphical Intent" }
                val signal = GraphicalRuntimeLaunchSignal().also { it.attachTo(launch) }
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue("Course Activity did not start", signal.awaitStarted())
                val deadline = SystemClock.elapsedRealtime() + 12_000
                var colorsVerified = false
                while (!colorsVerified && SystemClock.elapsedRealtime() < deadline) {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    if (bitmap != null) {
                        var orange = 0
                        var blue = 0
                        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                            val color = bitmap.getPixel(x, y)
                            val red = Color.red(color)
                            val green = Color.green(color)
                            val b = Color.blue(color)
                            if (red > 200 && green in 40..190 && b < 100) orange++
                            if (b > 50 && b > red * 2 && b > green) blue++
                        }
                        colorsVerified = orange > 40 && blue > 200
                        report.put("orangeSamples", orange).put("blueSamples", blue)
                        // Keep the final failing sample too; startup success alone is not render evidence.
                        if (colorsVerified || SystemClock.elapsedRealtime() + 300 >= deadline) {
                            File(evidence, "$name.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        bitmap.recycle()
                    }
                    if (!colorsVerified) SystemClock.sleep(150)
                }
                assertTrue("Course must render its orange rectangle on blue", colorsVerified)
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                instrumentation.waitForIdleSync()
                assertEquals("IDE process must survive the graphical course", idePid, Process.myPid())
                report.put("passed", true)
            }
        } finally {
            File(evidence, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "$name.log").writeText(output.toString(), Charsets.UTF_8)
            emitRegressionReport(report)
        }
    }
}

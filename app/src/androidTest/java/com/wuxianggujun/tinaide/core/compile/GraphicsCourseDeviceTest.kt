package com.wuxianggujun.tinaide.core.compile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.TutorialCourseContentState
import com.wuxianggujun.tinaide.tutorial.loadTutorialCourseContent
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectBlueprints
import com.wuxianggujun.tinaide.ui.ContextCompileSdlLauncher
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchSignal
import com.wuxianggujun.tinaide.ui.runtime.GraphicalOverlayDeviceDriver
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the actual course code with installed Market packages and checks its rendered colors. */
@RunWith(AndroidJUnit4::class)
class GraphicsCourseDeviceTest {
    @Test fun lessonBuildsAndDrawsThroughProductionHost() = runCourse(verifyOverlay = false)

    @Test fun floatingControlsResizeWithoutStealingProgramInput() = runCourse(verifyOverlay = true)

    private fun runCourse(verifyOverlay: Boolean) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val library = arguments.getString("graphicsLibrary", "sdl3")
        val language = arguments.getString("courseLanguage", "zh")
        val orientation = arguments.getString("editorOrientation", "portrait")
        require(library == "sdl3")
        require(language in setOf("zh", "en"))
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("library", library).put("language", language).put("passed", false)
            .put("orientation", orientation).put("referenceExercise", verifyOverlay)
        val evidence = regressionEvidenceDirectory(context)
        val name = "${if (verifyOverlay) "graphics-overlay" else "graphics-course"}-$language-$library"
        val output = StringBuilder()
        try {
            withTimeout(50_000) {
                val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
                val blueprint = TutorialProjectBlueprints.load("graphics_sdl3") { lesson ->
                    context.assets.open("$prefix/$lesson.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val files = blueprint.files.toMutableMap()
                if (verifyOverlay) {
                    val content = loadTutorialCourseContent("graphics-sdl3.md") { lesson ->
                        Result.success(context.assets.open("$prefix/$lesson").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    } as TutorialCourseContentState.Content
                    files["main.cpp"] = touchReference(files.getValue("main.cpp"), content.hints.last())
                }
                val directory = File(context.filesDir, "course-acceptance/$language/${if (verifyOverlay) "graphics-overlay" else "graphics"}-$library").apply { mkdirs() }
                files.forEach { (name, contents) -> File(directory, name).writeText(contents, Charsets.UTF_8) }
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
                        enableFloatingLog = verifyOverlay,
                        orientation = if (!verifyOverlay) SdlOrientation.AUTO else if (orientation == "landscape") SdlOrientation.LANDSCAPE else SdlOrientation.PORTRAIT,
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
                        val colors = markerColors(bitmap)
                        colorsVerified = colors.orange > 40 && colors.blue > 200
                        report.put("orangeSamples", colors.orange).put("blueSamples", colors.blue)
                            .put("screenWidth", bitmap.width).put("screenHeight", bitmap.height)
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
                if (verifyOverlay) verifyFloatingWindow(report, evidence, name)
                else instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                instrumentation.waitForIdleSync()
                assertEquals("IDE process must survive the graphical course", idePid, Process.myPid())
                report.put("ideSurvived", true).put("passed", true)
            }
        } catch (error: Throwable) {
            report.put("failure", "${error.javaClass.simpleName}: ${error.message}".take(512))
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(evidence, "$name-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }.onFailure { screenshotError -> report.put("evidenceError", screenshotError.javaClass.simpleName) }
            throw error
        } finally {
            File(evidence, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "$name.log").writeText(output.toString(), Charsets.UTF_8)
            emitRegressionReport(report)
        }
    }

    /** The fixture applies the three bundled reference snippets, never edits a learner project. */
    private fun touchReference(baseline: String, hint: String): String {
        val snippets = Regex("(?m)^```cpp\\r?\\n([\\s\\S]*?)\\r?\\n```")
            .findAll(hint).map { it.groupValues[1] }.toList()
        check(snippets.size == 3) { "The SDL3 reference must contain its state, event and drawing snippets" }
        val sections = listOf(
            "struct AppState" to "static std::unique_ptr",
            "SDL_AppResult SDL_AppEvent" to "SDL_AppResult SDL_AppIterate",
            "SDL_AppResult SDL_AppIterate" to "void SDL_AppQuit",
        )
        return sections.zip(snippets).fold(baseline) { source, (markers, snippet) ->
            val start = source.indexOf(markers.first)
            val end = source.indexOf(markers.second, start + 1)
            check(start >= 0 && end > start && snippet.startsWith(markers.first)) { "Reference section does not match ${markers.first}" }
            source.replaceRange(start, end, "$snippet\n\n")
        }
    }

    private fun verifyFloatingWindow(report: JSONObject, evidence: File, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val driver = GraphicalOverlayDeviceDriver(automation)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        report.put("touchSlopPx", touchSlop)
        driver.waitTag("graphical-overlay-control")
        assertTrue("The log must start collapsed", !driver.hasTag("graphical-log-window"))
        report.put("collapsedControl", true)
        automation.takeScreenshot()?.let { bitmap ->
            File(evidence, "$name-control.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val control = driver.bounds("graphical-overlay-control")
        report.put("controlBounds", rectEvidence(control))
        driver.drag("graphical-overlay-control", 72f, 32f)
        val movedControl = driver.awaitBounds("graphical-overlay-control") { rect ->
            report.put("movedControlBounds", rectEvidence(rect))
            followsPointer(control, rect, 72f, 32f, touchSlop)
        }
        assertEquals(control.width(), movedControl.width())
        assertEquals(control.height(), movedControl.height())
        assertTrue("Dragging the control must not open its menu or log", !driver.hasTag("graphical-overlay-open-log") && !driver.hasTag("graphical-log-window"))
        waitMarkerColor(green = false)
        report.put("controlDragged", true).put("controlBounds", rectEvidence(control))
            .put("movedControlBounds", rectEvidence(movedControl))
        driver.tapProgramOutsidePopup()
        report.put("greenSamples", waitMarkerColor(green = true).green)

        driver.openLog()
        report.put("openedLog", true)
        val original = driver.bounds("graphical-log-window")
        report.put("initialBounds", rectEvidence(original))
        // Short displays need upward movement so the subsequent grow gesture has headroom.
        val dragY = if (report.getString("orientation") == "landscape") -72f else 72f
        driver.drag("graphical-log-drag-handle", 32f, dragY)
        val dragged = driver.awaitBounds("graphical-log-window") { rect ->
            report.put("draggedBounds", rectEvidence(rect))
            followsPointer(original, rect, 32f, dragY, touchSlop)
        }
        waitMarkerColor(green = true)
        driver.drag("graphical-log-resize", 32f, 40f)
        val grown = driver.awaitBounds("graphical-log-window") { it.width() > dragged.width() && it.height() > dragged.height() }
        report.put("grownBounds", rectEvidence(grown))
        waitMarkerColor(green = true)
        driver.drag("graphical-log-resize", -72f, -72f)
        val shrunk = driver.awaitBounds("graphical-log-window") { it.width() < grown.width() && it.height() < grown.height() }
        report.put("shrunkBounds", rectEvidence(shrunk))
        waitMarkerColor(green = true)
        report.put("dragged", true).put("grew", true).put("shrunk", true).put("chromeTouchIsolated", true)
            .put("pointerFollowing", true)

        driver.clickTag("graphical-log-maximize")
        val display = android.graphics.Rect(0, 0, report.getInt("screenWidth"), report.getInt("screenHeight"))
        // Composition can expose the new size before the popup's old origin is relocated.
        val maximized = driver.awaitBounds("graphical-log-window") {
            it.width() > shrunk.width() && it.height() > shrunk.height() && display.contains(it)
        }
        report.put("maximizedBounds", rectEvidence(maximized))
        automation.takeScreenshot()?.let { bitmap ->
            File(evidence, "$name-maximized.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("Maximized windows have no resize grip", !driver.hasTag("graphical-log-resize"))
        driver.clickTag("graphical-log-maximize")
        driver.awaitBounds("graphical-log-window") { it == shrunk }
        driver.clickTag("graphical-log-collapse")
        driver.waitTag("graphical-overlay-control")
        driver.openLog()
        driver.awaitBounds("graphical-log-window") { it == shrunk }
        report.put("maximized", true).put("restored", true).put("preservedGeometry", true)
            .put("maximizedBounds", rectEvidence(maximized))

        driver.tapProgramOutsidePopup()
        report.put("orangeAfterPopupInput", waitMarkerColor(green = false).orange)
            .put("outsideTouchDelivered", true)
        automation.takeScreenshot()?.let { bitmap ->
            File(evidence, "$name-window.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        driver.clickTag("graphical-log-more")
        driver.clickTag("graphical-overlay-exit")
        driver.clickText(context.getString(Strings.floating_overlay_exit_confirm_no))
        driver.waitTag("graphical-log-window")
        waitMarkerColor(green = false)
        driver.clickTag("graphical-log-more")
        driver.clickTag("graphical-overlay-exit")
        driver.clickText(context.getString(Strings.floating_overlay_exit_confirm_yes))
        driver.awaitGone("graphical-log-window")
        report.put("exitConfirmation", true)
    }

    private fun rectEvidence(rect: android.graphics.Rect): JSONArray = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    private fun followsPointer(before: android.graphics.Rect, after: android.graphics.Rect, deltaX: Float, deltaY: Float, touchSlop: Int): Boolean {
        val scale = (1f - touchSlop / hypot(deltaX, deltaY)).coerceAtLeast(0f)
        return abs(after.left - before.left - deltaX * scale) <= DRAG_TOLERANCE_PX &&
            abs(after.top - before.top - deltaY * scale) <= DRAG_TOLERANCE_PX
    }

    private fun waitMarkerColor(green: Boolean): MarkerColors {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var colors = MarkerColors(0, 0, 0)
        do {
            automation.takeScreenshot()?.let { bitmap -> colors = markerColors(bitmap); bitmap.recycle() }
            if (if (green) colors.green > 40 && colors.orange < 20 else colors.orange > 40 && colors.green < 20) return colors
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("The real SDL3 touch reference did not render green=$green: $colors")
    }

    private fun markerColors(bitmap: Bitmap): MarkerColors {
        var orange = 0
        var green = 0
        var blue = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val color = bitmap.getPixel(x, y)
            val red = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            if (red > 200 && g in 40..190 && b < 100) orange++
            if (red < 120 && g > 180 && b < 180) green++
            if (b > 50 && b > red * 2 && b > g) blue++
        }
        return MarkerColors(orange, green, blue)
    }

    private data class MarkerColors(val orange: Int, val green: Int, val blue: Int)

    private companion object {
        const val DRAG_TOLERANCE_PX = 4f
    }
}

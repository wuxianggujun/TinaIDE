package com.wuxianggujun.tinaide.ui.runtime

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.sdl.ExternalSdlActivity
import com.wuxianggujun.tinaide.ui.nativeactivity.ExternalNativeActivity
import com.wuxianggujun.tinaide.ui.nativeactivity.NativeActivityRuntimeStager
import com.wuxianggujun.tinaide.ui.nativeactivity.NativeActivityRuntimeResolver
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual package libraries and production isolated Activities, never native code in the IDE process. */
@RunWith(AndroidJUnit4::class)
class SdlRuntimeIsolationInstrumentationTest {
    @Test fun realSdlAssetsAndNativeFailure_leaveIdeProcessAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val major = (args.getString("sdlMajor") ?: "3").toInt()
        val action = args.getString("nativeAction") ?: "return"
        val custom = args.getString("customAssets").toBoolean()
        val raylib = args.getString("nativeHost", "sdl") == "raylib"
        val runtime = if (raylib) "raylib" else "sdl$major"
        val idePid = Process.myPid()
        // A fresh emulator has not installed a compiler workspace yet. Settings is
        // a real IDE Activity that remains usable without that onboarding redirect.
        instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val fixture = File(context.filesDir, "runtime-probe/$runtime")
        val root = File(context.filesDir, "runtime-probe-$runtime-$action-$custom-项目😀").apply { mkdirs() }
        val resource = File(root, if (custom) "资源目录😀" else "assets").apply { mkdirs() }
        val sdl = File(root, if (raylib) "libraylib.so" else "libSDL$major.so")
        val main = File(root, "libmain.so")
        assertTrue("Build and push the runtime probe first: $fixture", File(fixture, sdl.name).isFile)
        File(fixture, sdl.name).copyTo(sdl, overwrite = true)
        File(fixture, main.name).copyTo(main, overwrite = true)
        File(resource, "tina-probe-resource.txt").writeText("configured", Charsets.UTF_8)
        File(resource, "资源😀.txt").writeText("UTF-8 中文😀", Charsets.UTF_8)
        File(root, "tina-probe-root.txt").writeText("root", Charsets.UTF_8)
        val result = File(root, "result.txt").apply { delete() }
        val overlayReady = File(root, "overlay-ready").apply { delete() }
        val environment = buildMap {
            put(CompileProjectUseCase.RUNTIME_ASSET_ROOT_ENV, root.absolutePath)
            if (custom) put(CompileProjectUseCase.RUNTIME_ASSET_DIR_ENV, resource.absolutePath)
            put("TINA_PROBE_RESULT", result.absolutePath)
            put("TINA_PROBE_ACTION", action)
            put("TINA_PROBE_READY", overlayReady.absolutePath)
        }
        var stagedMain: File
        val launch = if (raylib) {
            val staged = NativeActivityRuntimeStager.stage(context, NativeActivityRuntimeResolver.RuntimeSpec(main, listOf(sdl)))
            assertTrue(staged.toString(), staged is NativeActivityRuntimeStager.StageResult.Success)
            val paths = (staged as NativeActivityRuntimeStager.StageResult.Success).runtime
            stagedMain = File(paths.mainLibraryPath)
            ExternalNativeActivity.createIntent(context, paths.mainLibraryPath,
                paths.dependencyLibraryPaths, enableFloatingLog = true, launchEnvironment = environment)
        } else {
            val staged = SdlRuntimeLibraryStager.stage(context, sdl.absolutePath, main.absolutePath)
            assertTrue(staged.toString(), staged is SdlRuntimeLibraryStager.StageResult.Success)
            val paths = (staged as SdlRuntimeLibraryStager.StageResult.Success).runtime
            stagedMain = File(paths.mainLibraryPath)
            ExternalSdlActivity.createIntent(context, paths.sdlLibraryPath, paths.mainLibraryPath,
                major, paths.preSdlLibraryPaths, paths.preloadLibraryPaths,
                enableFloatingLog = true, launchEnvironment = environment)
        }
        val signal = GraphicalRuntimeLaunchSignal()
        signal.attachTo(launch)
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Graphical Activity did not claim its launch ticket", runBlocking { signal.awaitStarted() })
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while ((!result.exists() || !result.readText(Charsets.UTF_8).contains("video=")) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertTrue("Native entry was not reached", result.isFile)
        val first = result.readText(Charsets.UTF_8)
        if (!raylib) assertTrue(first, first.contains("ascii=1 unicode=1 root=1"))
        assertTrue(first, first.contains("video=1 window=1"))
        val runtimePid = Regex("pid=(\\d+)").find(first)!!.groupValues[1].toInt()
        assertNotEquals("User code must never run inside the IDE", idePid, runtimePid)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val logTitle = context.getString(Strings.floating_overlay_log_title)
        fun hasVisibleLog(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.text?.toString() == logTitle) return true
            return (0 until node.childCount).any { hasVisibleLog(node.getChild(it)) }
        }
        val overlayDeadline = SystemClock.elapsedRealtime() + 10_000
        var overlayVisible = false
        while (!overlayVisible && SystemClock.elapsedRealtime() < overlayDeadline) {
            overlayVisible = automation.windows.any { hasVisibleLog(it.root) }
            if (!overlayVisible) SystemClock.sleep(100)
        }
        assertTrue("Enabled floating log panel must be visible for $runtime", overlayVisible)
        val evidence = regressionEvidenceDirectory(context)
        val name = "$runtime-$action-$custom"
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        overlayReady.writeText("visible", Charsets.UTF_8)
        while (File("/proc/$runtimePid").exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertFalse("Graphical process was not reclaimed", File("/proc/$runtimePid").exists())
        assertEquals(idePid, Process.myPid())
        val finalResult = result.readText(Charsets.UTF_8)
        assertTrue(finalResult, finalResult.contains("action=$action"))
        File(evidence, "$name.txt").writeText("idePid=$idePid\n$finalResult", Charsets.UTF_8)
        assertTrue(root.deleteRecursively())
        assertTrue(stagedMain.parentFile!!.deleteRecursively())
    }
}

package com.wuxianggujun.tinaide.ui.activity

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production launcher with a simulated runtime report; does not crash or clear the host. */
@RunWith(AndroidJUnit4::class)
class CrashTaskIsolationInstrumentationTest {
    @Test
    fun missingProcessIdentity_neverOffersHostActionsOrRestartsTheIde() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val hostPid = Process.myPid()
        ActivityScenario.launch<MainPortalActivity>(
            Intent(context, MainPortalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use { scenario ->
            var hostTaskId = -1
            scenario.onActivity { hostTaskId = it.taskId }
            CrashActivity.start(context, REPORT, CrashActivity.SOURCE_APP)
            waitForText(BACK_TO_EDITOR)
            assertFalse(hasText(HOST_ACTIONS))
            click(BACK_TO_EDITOR)
            waitUntil { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity {
                assertEquals(hostTaskId, it.taskId)
                assertFalse(it.isFinishing)
            }
            assertEquals(hostPid, Process.myPid())
        }
    }

    @Test
    fun userCrash_keepsHostTaskAndPidAndReturnsWithoutRestartingTheIde() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val hostPid = Process.myPid()
        var hostTaskId = -1

        ActivityScenario.launch<MainPortalActivity>(
            Intent(context, MainPortalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use { scenario ->
            scenario.onActivity { hostTaskId = it.taskId }
            CrashActivity.start(context, REPORT, CrashActivity.SOURCE_USER_NATIVE, "", "${context.packageName}:sdl")
            waitForText(BACK_TO_EDITOR)
            assertFalse(hasText(HOST_ACTIONS))
            assertFalse(hasText(setOf(REPORT)))
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertFalse(it.isDestroyed)
                assertEquals(hostTaskId, it.taskId)
            }
            assertEquals(hostPid, Process.myPid())

            SystemClock.sleep(200)
            automation.takeScreenshot()?.let { bitmap ->
                val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "acceptance/crash-isolation")
                check(directory.isDirectory || directory.mkdirs())
                val screenshot = File(directory, "user-crash-collapsed.png")
                screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                instrumentation.sendStatus(0, Bundle().apply { putString("crash_screenshot", screenshot.absolutePath) })
            }

            click(BACK_TO_EDITOR)
            waitUntil { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity {
                assertEquals(hostTaskId, it.taskId)
                assertFalse(it.isFinishing)
            }
            assertEquals(hostPid, Process.myPid())

            // System Back must dismiss only the diagnostic screen, not be swallowed or exit the IDE.
            CrashActivity.start(context, REPORT, CrashActivity.SOURCE_USER_NATIVE, "", "${context.packageName}:gui")
            waitForText(BACK_TO_EDITOR)
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
            waitUntil { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity {
                assertEquals(hostTaskId, it.taskId)
                assertFalse(it.isFinishing)
            }
            assertEquals(hostPid, Process.myPid())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("host_pid_before", hostPid.toString())
                putString("host_pid_after", Process.myPid().toString())
                putString("host_task_id", hostTaskId.toString())
            })
        }
    }

    private fun waitForText(texts: Set<String>) = waitUntil { hasText(texts) }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Crash isolation UI did not reach the expected state")
    }

    private fun click(texts: Set<String>) {
        var node = findNode(texts) ?: throw AssertionError("Crash action not found")
        repeat(8) {
            if (node.isClickable) {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            node = node.parent ?: throw AssertionError("Crash action has no clickable ancestor")
        }
        throw AssertionError("Crash action has no clickable ancestor")
    }

    private fun hasText(texts: Set<String>): Boolean = findNode(texts) != null

    private fun findNode(texts: Set<String>): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var inspected = 0
        while (pending.isNotEmpty() && inspected++ < MAX_ACCESSIBILITY_NODES) {
            val node = pending.removeFirst()
            if (node.text?.toString()?.trim() in texts) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return null
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val MAX_ACCESSIBILITY_NODES = 1000
        const val REPORT = "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\nbacktrace: acceptance fixture"
        val BACK_TO_EDITOR = setOf("返回编辑器", "Back to editor")
        val HOST_ACTIONS = setOf("重启 IDE", "重启应用", "退出 IDE", "Restart IDE", "Restart App", "Exit IDE")
    }
}

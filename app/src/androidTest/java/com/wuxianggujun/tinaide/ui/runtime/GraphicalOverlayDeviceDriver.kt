package com.wuxianggujun.tinaide.ui.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertTrue

/** Operates the real popup in the isolated engine process, not a Compose-only preview. */
@Suppress("DEPRECATION")
internal class GraphicalOverlayDeviceDriver(private val automation: UiAutomation) {
    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    fun openLog() {
        clickTag("graphical-overlay-control")
        clickTag("graphical-overlay-open-log")
        waitTag("graphical-log-window")
    }

    fun clickTag(tag: String) = click("tag '$tag'") { it.viewIdResourceName == tag }

    fun clickText(text: String) = click("text '$text'") { it.text?.toString() == text }

    fun hasTag(tag: String): Boolean = find { it.viewIdResourceName == tag }?.let { node ->
        node.recycle()
        true
    } ?: false

    fun waitTag(tag: String) {
        val node = waitNode("tag '$tag'") { it.viewIdResourceName == tag }
        node.recycle()
    }

    fun awaitGone(tag: String) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_MS
        while (hasTag(tag) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("$tag remained visible after exiting the graphical runtime", !hasTag(tag))
    }

    fun bounds(tag: String): Rect {
        val node = waitNode("tag '$tag'") { it.viewIdResourceName == tag }
        return Rect().also { node.getBoundsInScreen(it); node.recycle() }
    }

    fun awaitBounds(tag: String, predicate: (Rect) -> Boolean): Rect {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_MS
        var current = bounds(tag)
        while (!predicate(current) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            current = bounds(tag)
        }
        assertTrue("$tag did not reach its expected bounds: $current", predicate(current))
        return current
    }

    fun drag(tag: String, deltaX: Float, deltaY: Float) {
        val rect = bounds(tag)
        val startX = rect.exactCenterX()
        val startY = rect.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, startX, startY)
        for (step in 1..DRAG_STEPS) {
            SystemClock.sleep(20)
            inject(downTime, MotionEvent.ACTION_MOVE, startX + deltaX * step / DRAG_STEPS, startY + deltaY * step / DRAG_STEPS)
        }
        inject(downTime, MotionEvent.ACTION_UP, startX + deltaX, startY + deltaY)
        // Input acknowledgement can precede the final popup layout/accessibility update.
        automation.waitForIdle(100, UI_WAIT_MS)
    }

    fun tapProgramOutsidePopup() {
        val popup = bounds(if (hasTag("graphical-log-window")) "graphical-log-window" else "graphical-overlay-control")
        val screen = checkNotNull(automation.takeScreenshot()) { "Unable to capture the engine display" }
        val candidates = listOf(0.8f to 0.8f, 0.2f to 0.8f, 0.8f to 0.2f, 0.5f to 0.9f)
        val point = candidates.map { (x, y) -> (screen.width * x).toInt() to (screen.height * y).toInt() }
            .firstOrNull { (x, y) -> !popup.contains(x, y) }
        screen.recycle()
        val (x, y) = checkNotNull(point) { "No uncovered program touch point outside $popup" }
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        SystemClock.sleep(40)
        inject(downTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue("Touch injection failed", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun click(target: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        var current: AccessibilityNodeInfo? = waitNode(target, predicate)
        while (current != null) {
            val node = current
            // Compose text may be an unmerged leaf; invoke its nearest actionable button.
            if (node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                try { assertTrue("Unable to click graphical overlay $target", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { node.recycle() }
                return
            }
            current = node.parent
            node.recycle()
        }
        error("Graphical overlay $target has no enabled clickable ancestor")
    }

    private fun waitNode(target: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_MS
        do {
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val windows = automation.windows.joinToString { window ->
            val rect = Rect().also(window::getBoundsInScreen)
            "type=${window.type}, bounds=$rect, focused=${window.isFocused}"
        }
        error("Expected graphical overlay $target was not visible in the isolated runtime ($windows)")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Popup movement can leave cached virtual-node bounds/visibility behind its
        // actual window. Always query the real current tree on supported devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        for (window in automation.windows) findInTree(window.root, predicate)?.let { return it }
        return null
    }

    private fun findInTree(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) {
            findInTree(node.getChild(index), predicate)?.let { found -> node.recycle(); return found }
        }
        node.recycle()
        return null
    }

    private companion object {
        const val UI_WAIT_MS = 5_000L
        const val DRAG_STEPS = 8
    }
}

package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

internal class EditorGestureHandler(
    private val scaleSuppressionMs: Long = 150L,
    private val releaseSuppressionMs: Long = 100L,
    private val doubleTapTimeoutMs: Long = 300L,
    private val doubleTapSlopPx: Float = 48f,
    private val uptimeMillisProvider: () -> Long = SystemClock::uptimeMillis
) {

    var isMultiTouchActive by mutableStateOf(false)
        private set

    var isSelectionDragActive by mutableStateOf(false)
        private set

    var isCursorDragActive by mutableStateOf(false)
        private set

    var selectionDragAnchor by mutableStateOf(IntOffset.Zero)
        private set

    var selectionDragAnchorOffset by mutableStateOf<Int?>(null)
        private set

    private var suppressBasicGesturesUntilMs by mutableStateOf(0L)
    private var selectionDragMoved by mutableStateOf(false)
    private var cursorDragMoved by mutableStateOf(false)
    private var lastTextTapUptimeMs = 0L
    private var lastTextTapPosition: Offset? = null

    fun onScaleApplied() {
        nudgeSuppression(scaleSuppressionMs)
    }

    /**
     * 对外暴露的“短暂屏蔽基础手势”入口。
     *
     * 典型场景：滚动条/句柄等装饰层手势需要在一段时间内阻止 tap/longPress 触发文本选择/菜单。
     */
    fun suppressBasicGestures(durationMs: Long) {
        nudgeSuppression(durationMs)
    }

    fun onScrollConsumed(consumedPx: Float, suppressionMs: Long): Boolean {
        if (abs(consumedPx) <= SCROLL_CONSUMED_THRESHOLD_PX) return false
        nudgeSuppression(suppressionMs)
        return true
    }

    fun onPointerCountChanged(
        pointerCount: Int,
        isTransformInProgress: Boolean
    ): Boolean = when {
        pointerCount > 1 -> {
            clearTextTapTracking()
            isMultiTouchActive = true
            true
        }

        pointerCount == 0 && isMultiTouchActive && !isTransformInProgress -> {
            isMultiTouchActive = false
            nudgeSuppression(releaseSuppressionMs)
            false
        }

        else -> false
    }

    fun onTransformSettled() {
        isMultiTouchActive = false
        nudgeSuppression(releaseSuppressionMs)
    }

    fun shouldBlockScrollGestures(isTransformInProgress: Boolean): Boolean = isMultiTouchActive ||
        isTransformInProgress ||
        isSelectionDragActive ||
        isCursorDragActive

    fun handlingMotions(): Boolean = isSelectionDragActive ||
        isCursorDragActive ||
        uptimeMillisProvider() < suppressBasicGesturesUntilMs

    fun shouldBlockBasicGestures(isTransformInProgress: Boolean): Boolean = shouldBlockScrollGestures(isTransformInProgress) || handlingMotions()

    fun registerTextTap(position: Offset): Boolean {
        val now = uptimeMillisProvider()
        val lastPosition = lastTextTapPosition
        val distanceSquared = if (lastPosition != null) {
            val dx = position.x - lastPosition.x
            val dy = position.y - lastPosition.y
            dx * dx + dy * dy
        } else {
            Float.POSITIVE_INFINITY
        }
        val withinTimeout = now - lastTextTapUptimeMs in 0..doubleTapTimeoutMs
        val withinSlop = distanceSquared <= doubleTapSlopPx * doubleTapSlopPx
        if (lastPosition != null && withinTimeout && withinSlop) {
            clearTextTapTracking()
            return true
        }

        lastTextTapUptimeMs = now
        lastTextTapPosition = position
        return false
    }

    fun clearTextTapTracking() {
        lastTextTapUptimeMs = 0L
        lastTextTapPosition = null
    }

    fun startSelectionDrag(anchorOffset: IntOffset, anchorCharOffset: Int) {
        clearTextTapTracking()
        isSelectionDragActive = true
        selectionDragMoved = false
        selectionDragAnchor = anchorOffset
        selectionDragAnchorOffset = anchorCharOffset
    }

    fun markSelectionDragMoved() {
        selectionDragMoved = true
    }

    fun finishSelectionDrag(): IntOffset? {
        val menuAnchor = if (isSelectionDragActive && !selectionDragMoved) {
            selectionDragAnchor
        } else {
            null
        }
        if (isSelectionDragActive) {
            nudgeSuppression(releaseSuppressionMs)
        }
        cancelSelectionDrag()
        return menuAnchor
    }

    fun cancelSelectionDrag() {
        isSelectionDragActive = false
        selectionDragMoved = false
        selectionDragAnchor = IntOffset.Zero
        selectionDragAnchorOffset = null
    }

    fun startCursorDrag() {
        clearTextTapTracking()
        isCursorDragActive = true
        cursorDragMoved = false
    }

    fun markCursorDragMoved() {
        cursorDragMoved = true
    }

    fun finishCursorDrag() {
        if (isCursorDragActive) {
            nudgeSuppression(releaseSuppressionMs)
        }
        cancelCursorDrag()
    }

    fun cancelCursorDrag() {
        isCursorDragActive = false
        cursorDragMoved = false
    }

    private fun nudgeSuppression(durationMs: Long) {
        clearTextTapTracking()
        suppressBasicGesturesUntilMs = uptimeMillisProvider() + durationMs
    }

    private companion object {
        private const val SCROLL_CONSUMED_THRESHOLD_PX = 0.5f
    }
}

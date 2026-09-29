package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs

internal enum class ScrollDragAxis {
    VERTICAL,
    HORIZONTAL
}

internal enum class TransformGestureFocusBasis {
    PREVIOUS_STABLE,
    NONE
}

internal data class TransformGestureFocusSnapshot(
    val focus: Offset?,
    val pointerCount: Int,
    val stablePointerCount: Int,
    val basis: TransformGestureFocusBasis
)

internal data class TransformPointerSample(
    val previousPressed: Boolean,
    val previousPosition: Offset
)

internal object TransformGestureFocusResolver {
    fun resolve(
        pointerCount: Int,
        samples: List<TransformPointerSample>
    ): TransformGestureFocusSnapshot {
        if (pointerCount < 2) {
            return TransformGestureFocusSnapshot(
                focus = null,
                pointerCount = pointerCount,
                stablePointerCount = 0,
                basis = TransformGestureFocusBasis.NONE
            )
        }
        var stableCount = 0
        var sumPrevX = 0f
        var sumPrevY = 0f
        samples.forEach { sample ->
            if (sample.previousPressed) {
                stableCount += 1
                sumPrevX += sample.previousPosition.x
                sumPrevY += sample.previousPosition.y
            }
        }
        if (stableCount >= 2) {
            return TransformGestureFocusSnapshot(
                focus = Offset(
                    x = sumPrevX / stableCount.toFloat(),
                    y = sumPrevY / stableCount.toFloat()
                ),
                pointerCount = pointerCount,
                stablePointerCount = stableCount,
                basis = TransformGestureFocusBasis.PREVIOUS_STABLE
            )
        }
        return TransformGestureFocusSnapshot(
            focus = null,
            pointerCount = pointerCount,
            stablePointerCount = stableCount,
            basis = TransformGestureFocusBasis.NONE
        )
    }
}

internal object EditorScrollAxisDecider {
    fun resolveDominantAxis(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float
    ): ScrollDragAxis? {
        val absDx = abs(deltaX)
        val absDy = abs(deltaY)
        val horizontalDominant = absDx >= touchSlop * 0.7f && absDx > absDy * 1.1f
        val verticalDominant = absDy >= touchSlop * 0.7f && absDy > absDx * 1.1f
        return when {
            horizontalDominant -> ScrollDragAxis.HORIZONTAL
            verticalDominant -> ScrollDragAxis.VERTICAL
            else -> null
        }
    }

    /**
     * 用于单元测试与手势策略复用的辅助函数：判断“某轴”是否可以接管正在运行的 fling。
     *
     * 设计约束：
     * - 未开启 singleDirectionDragging 时，允许任意方向接管（更自由）。
     * - 开启 singleDirectionDragging 时，若当前锁定轴与期望轴不一致，则禁止接管。
     * - delta 太小（小于 touchSlop 的一小部分）时不接管，避免轻触误触导致 fling 频繁被打断。
     */
    fun canTakeOverRunningFling(
        delta: Float,
        singleDirectionDragging: Boolean,
        activeScrollDragAxis: ScrollDragAxis?,
        expectedAxis: ScrollDragAxis,
        touchSlop: Float
    ): Boolean {
        val minDelta = (touchSlop * 0.18f).coerceAtLeast(0.5f)
        if (abs(delta) < minDelta) return false
        if (!singleDirectionDragging) return true
        val lockedAxis = activeScrollDragAxis ?: return true
        return lockedAxis == expectedAxis
    }
}

internal class EditorScrollGestureCoordinator(
    private val state: EditorState,
    private val touchSlop: Float,
    private val shouldBlockScrollGestures: () -> Boolean
) {
    var activeScrollDragAxis by mutableStateOf<ScrollDragAxis?>(null)
        private set
    var lastCompletedDragAxis by mutableStateOf<ScrollDragAxis?>(null)
        private set
    var activeFlingAxis by mutableStateOf<ScrollDragAxis?>(null)
        private set
    var isTouchActive by mutableStateOf(false)
        private set
    var primaryPointerId by mutableStateOf<PointerId?>(null)
        private set
    var axisLockAnchor by mutableStateOf<Offset?>(null)
        private set

    fun onFlingStarted(axis: ScrollDragAxis) {
        activeFlingAxis = axis
    }

    fun onFlingFinished(axis: ScrollDragAxis) {
        if (activeFlingAxis == axis) {
            activeFlingAxis = null
        }
    }

    fun onScrollbarDragStarted(axis: ScrollbarAxis) {
        activeScrollDragAxis = when (axis) {
            ScrollbarAxis.Vertical -> ScrollDragAxis.VERTICAL
            ScrollbarAxis.Horizontal -> ScrollDragAxis.HORIZONTAL
        }
        lastCompletedDragAxis = activeScrollDragAxis
    }

    fun onScrollbarDragFinished() {
        activeScrollDragAxis = null
        lastCompletedDragAxis = null
        primaryPointerId = null
        axisLockAnchor = null
    }

    fun onPrimaryPointerReset() {
        primaryPointerId = null
        axisLockAnchor = null
    }

    fun onPointerStreamUpdated(
        pressedChanges: List<PointerInputChange>,
        scrollbarDragActive: Boolean,
        isHandleDragging: Boolean
    ): TransformGestureFocusSnapshot {
        val pointerCount = pressedChanges.size
        // 任何手指按下都应中断正在进行的 fling（与 Android View / Sora 一致）。
        isTouchActive = pointerCount > 0
        if (isTouchActive && activeFlingAxis != null) {
            activeFlingAxis = null
        }
        if (pointerCount >= 2) {
            primaryPointerId = null
            axisLockAnchor = null
            activeScrollDragAxis = null
            // 与 transformable 的缩放基准对齐：
            // Compose detectZoom() 使用 previousPosition(仅统计 previousPressed && pressed)计算 zoom/centroid。
            // 这里必须使用同一批“稳定指针”的 previousPosition，避免焦点和缩放比例来自不同时间基准导致漂移。
            return TransformGestureFocusResolver.resolve(
                pointerCount = pointerCount,
                samples = pressedChanges.map { change ->
                    TransformPointerSample(
                        previousPressed = true, // 当前按下的手指都视为稳定，避免新手指第一帧导致焦点计算失败
                        previousPosition = if (change.previousPressed) change.previousPosition else change.position
                    )
                }
            )
        }
        if (pointerCount == 0) {
            primaryPointerId = null
            axisLockAnchor = null
            activeScrollDragAxis = null
            isTouchActive = false
            return TransformGestureFocusSnapshot(
                focus = null,
                pointerCount = pointerCount,
                stablePointerCount = 0,
                basis = TransformGestureFocusBasis.NONE
            )
        }
        if (pointerCount != 1) {
            return TransformGestureFocusSnapshot(
                focus = null,
                pointerCount = pointerCount,
                stablePointerCount = 0,
                basis = TransformGestureFocusBasis.NONE
            )
        }
        if (scrollbarDragActive || isHandleDragging || !state.config.singleDirectionDragging) {
            return TransformGestureFocusSnapshot(
                focus = null,
                pointerCount = pointerCount,
                stablePointerCount = 0,
                basis = TransformGestureFocusBasis.NONE
            )
        }

        val primaryChange = primaryPointerId
            ?.let { id -> pressedChanges.firstOrNull { it.id == id } }
            ?: pressedChanges.firstOrNull()?.also { change ->
                primaryPointerId = change.id
                axisLockAnchor = change.position
                activeScrollDragAxis = null
                lastCompletedDragAxis = null
            }
        if (primaryChange == null || shouldBlockScrollGestures()) {
            return TransformGestureFocusSnapshot(
                focus = null,
                pointerCount = pointerCount,
                stablePointerCount = 0,
                basis = TransformGestureFocusBasis.NONE
            )
        }

        val anchor = axisLockAnchor ?: primaryChange.position.also {
            axisLockAnchor = it
        }
        if (activeScrollDragAxis == null) {
            val axis = EditorScrollAxisDecider.resolveDominantAxis(
                deltaX = primaryChange.position.x - anchor.x,
                deltaY = primaryChange.position.y - anchor.y,
                touchSlop = touchSlop
            )
            if (axis != null) {
                activeScrollDragAxis = axis
                lastCompletedDragAxis = axis
            }
        } else {
            lastCompletedDragAxis = activeScrollDragAxis
        }
        return TransformGestureFocusSnapshot(
            focus = null,
            pointerCount = pointerCount,
            stablePointerCount = if (primaryChange.previousPressed) 1 else 0,
            basis = TransformGestureFocusBasis.NONE
        )
    }

    fun shouldEnableVerticalScrollable(): Boolean {
        if (shouldBlockScrollGestures()) return false
        if (state.config.singleDirectionDragging && activeScrollDragAxis == ScrollDragAxis.HORIZONTAL) {
            return false
        }
        return true
    }

    fun shouldEnableHorizontalScrollable(): Boolean {
        if (shouldBlockScrollGestures()) return false
        if (state.config.wordWrap) return false
        if (state.maxHorizontalScrollOffsetPx() <= 0f) return false
        if (state.config.singleDirectionDragging && activeScrollDragAxis == ScrollDragAxis.VERTICAL) {
            return false
        }
        return true
    }

    fun canStartAxisFling(axis: ScrollDragAxis): Boolean {
        if (shouldBlockScrollGestures()) return false
        // 只要用户当前按住屏幕，就应立即停止 fling（触摸优先）。
        if (isTouchActive) return false
        val axisHasScrollableRange = when (axis) {
            ScrollDragAxis.VERTICAL -> state.maxVerticalScrollOffsetPx() > 0f
            ScrollDragAxis.HORIZONTAL -> state.maxHorizontalScrollOffsetPx() > 0f
        }
        if (!axisHasScrollableRange) return false
        if (activeFlingAxis != null) {
            return activeFlingAxis == axis
        }
        val lockAxis = resolveFlingAxisLock()
        if (lockAxis == null) {
            return axis == ScrollDragAxis.VERTICAL
        }
        return lockAxis == axis
    }

    fun canConsumeScrollDelta(axis: ScrollDragAxis, delta: Float): Boolean {
        if (shouldBlockScrollGestures()) return false
        return when (axis) {
            ScrollDragAxis.VERTICAL -> canConsumeVerticalDelta(delta)
            ScrollDragAxis.HORIZONTAL -> canConsumeHorizontalDelta(delta)
        }
    }

    private fun resolveFlingAxisLock(): ScrollDragAxis? {
        if (!state.config.singleDirectionFling) return null
        return activeScrollDragAxis ?: lastCompletedDragAxis
    }

    private fun canConsumeVerticalDelta(delta: Float): Boolean {
        if (state.config.singleDirectionDragging && activeScrollDragAxis == ScrollDragAxis.HORIZONTAL) {
            return false
        }
        return true
    }

    private fun canConsumeHorizontalDelta(delta: Float): Boolean {
        if (state.maxHorizontalScrollOffsetPx() <= 0f) return false
        if (state.config.singleDirectionDragging && activeScrollDragAxis == ScrollDragAxis.VERTICAL) {
            return false
        }
        // 不再人为丢弃小 delta：scrollable 自身已经有 touchSlop 与方向判定，
        // 额外的阈值只会导致“需要再滑一次”的粘滞感。
        return true
    }
}

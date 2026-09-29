package com.wuxianggujun.tinaide.core.editorview

import kotlin.math.abs

internal class EditorScrollDeltaCoordinator(
    private val state: EditorState,
    private val scrollGestureCoordinator: EditorScrollGestureCoordinator,
    private val gestureHandler: EditorGestureHandler,
    private val touchDiagnostics: EditorTouchDiagnostics,
    private val onContextMenuVisibilityChanged: (Boolean) -> Unit,
    private val cancelPendingCompletionRequest: () -> Unit,
    private val dismissHover: () -> Unit,
    private val triggerScrollbarVisibility: () -> Unit,
    private val verticalSuppressionMs: Long,
    private val horizontalSuppressionMs: Long
) {
    fun consumeVertical(delta: Float): Float {
        if (!scrollGestureCoordinator.canConsumeScrollDelta(ScrollDragAxis.VERTICAL, delta)) {
            return 0f
        }
        val beforeScroll = state.scrollOffsetPx
        state.scrollBy(-delta)
        val consumed = beforeScroll - state.scrollOffsetPx
        val consumedAbs = abs(consumed)
        maybeLogVertical(delta, consumed, consumedAbs)
        maybeSuppressGesturesAfterScroll(consumedAbs, verticalSuppressionMs)
        return consumed
    }

    fun consumeHorizontal(delta: Float): Float {
        if (!scrollGestureCoordinator.canConsumeScrollDelta(ScrollDragAxis.HORIZONTAL, delta)) {
            return 0f
        }
        val beforeScroll = state.scrollOffsetXPx
        state.scrollByX(-delta)
        val consumed = beforeScroll - state.scrollOffsetXPx
        val consumedAbs = abs(consumed)
        maybeLogHorizontal(delta, consumed, consumedAbs)
        maybeSuppressGesturesAfterScroll(consumedAbs, horizontalSuppressionMs)
        return consumed
    }

    private fun maybeLogVertical(delta: Float, consumed: Float, consumedAbs: Float) {
        if (consumedAbs <= 0.01f) return
        touchDiagnostics.logThrottled(
            category = EditorTouchLogCategory.SCROLL,
            throttleKey = "vertical",
            minIntervalMs = 180L
        ) {
            "scroll axis=Vertical delta=${"%.2f".format(delta)} consumed=${"%.2f".format(consumed)} " +
                "offset=${state.scrollOffsetPx.toInt()} max=${state.maxVerticalScrollOffsetPx().toInt()}"
        }
    }

    private fun maybeLogHorizontal(delta: Float, consumed: Float, consumedAbs: Float) {
        if (consumedAbs <= 0.01f) return
        touchDiagnostics.logThrottled(
            category = EditorTouchLogCategory.SCROLL,
            throttleKey = "horizontal",
            minIntervalMs = 180L
        ) {
            "scroll axis=Horizontal delta=${"%.2f".format(delta)} consumed=${"%.2f".format(consumed)} " +
                "offset=${state.scrollOffsetXPx.toInt()} max=${state.maxHorizontalScrollOffsetPx().toInt()}"
        }
    }

    private fun maybeSuppressGesturesAfterScroll(consumedAbs: Float, suppressionMs: Long) {
        if (!gestureHandler.onScrollConsumed(consumedPx = consumedAbs, suppressionMs = suppressionMs)) {
            return
        }
        onContextMenuVisibilityChanged(false)
        cancelPendingCompletionRequest()
        dismissHover()
        triggerScrollbarVisibility()
    }
}

package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.Density
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

internal data class ActiveScrollbarDrag(
    val pointerId: PointerId,
    val axis: ScrollbarAxis
)

internal class ScrollbarDragCoordinator(
    private val state: EditorState,
    private val scrollbarRenderer: EditorScrollbarRenderer,
    private val scrollGestureCoordinator: EditorScrollGestureCoordinator,
    private val gestureHandler: EditorGestureHandler,
    private val onActiveDragChanged: (ActiveScrollbarDrag?) -> Unit,
    private val onContextMenuVisibilityChanged: (Boolean) -> Unit,
    private val onTriggerScrollbarVisibility: (Boolean) -> Unit,
    private val cancelPendingCompletionRequest: () -> Unit,
    private val logEditorTouch: (String, Boolean) -> Unit
) {
    private fun logTouch(message: String, verbose: Boolean = false) {
        logEditorTouch(message, verbose)
    }

    suspend fun AwaitPointerEventScope.runDragLoop(
        canvasWidthPxProvider: () -> Float,
        canvasHeightPxProvider: () -> Float,
        density: Density
    ) {
        try {
            while (true) {
                val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
                val canvasWidthPx = canvasWidthPxProvider()
                val canvasHeightPx = canvasHeightPxProvider()
                val layout = scrollbarRenderer.calculateLayout(
                    state = state,
                    canvasWidth = canvasWidthPx,
                    canvasHeight = canvasHeightPx,
                    density = density
                )
                val dragTarget = layout.hitTest(down.position) ?: continue

                // 命中滚动条热区后立即接管拖动：
                // 不再要求长按，否则右侧细条几乎无法选中。
                gestureHandler.suppressBasicGestures(durationMs = 240L)
                logTouch(
                    "scrollbar pressCandidate axis=${dragTarget.axis} pointer=(${down.position.x.toInt()},${down.position.y.toInt()})",
                    verbose = false
                )

                var lastPosition = down.position
                logTouch(
                    "scrollbar dragAccepted axis=${dragTarget.axis} pointer=(${lastPosition.x.toInt()},${lastPosition.y.toInt()})",
                    verbose = false
                )

                val drag = ActiveScrollbarDrag(
                    pointerId = down.id,
                    axis = dragTarget.axis
                )
                onActiveDragChanged(drag)
                scrollGestureCoordinator.onScrollbarDragStarted(drag.axis)
                onContextMenuVisibilityChanged(false)
                onTriggerScrollbarVisibility(true)
                cancelPendingCompletionRequest()
                logTouch(
                    "scrollbar dragStart axis=${drag.axis} fromThumb=${dragTarget.hitOnThumb} " +
                        "pointer=(${lastPosition.x.toInt()},${lastPosition.y.toInt()}) " +
                        "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                )
                down.consume()

                val dragGeometry = when (drag.axis) {
                    ScrollbarAxis.Vertical -> layout.vertical
                    ScrollbarAxis.Horizontal -> layout.horizontal
                }
                val movableTrackPx = dragGeometry?.let { geometry ->
                    (geometry.trackLengthPx - geometry.thumbLengthPx).coerceAtLeast(1f)
                } ?: 0f
                val trackStartPx = dragGeometry?.trackStartPx ?: 0f
                val pointerAxisAtDown = when (drag.axis) {
                    ScrollbarAxis.Vertical -> lastPosition.y
                    ScrollbarAxis.Horizontal -> lastPosition.x
                }
                val dragOffsetInThumbPx = if (dragGeometry != null) {
                    (pointerAxisAtDown - dragGeometry.thumbStartPx)
                        .coerceIn(0f, dragGeometry.thumbLengthPx)
                } else {
                    0f
                }
                val canMapPointerToScroll = (
                    dragGeometry != null &&
                        dragGeometry.maxScrollOffsetPx > 0f &&
                        movableTrackPx > 0f
                    )
                var accumulatedConsumedPx = 0f
                var verboseLogAtMs = 0L
                var dragFinished = false

                fun scrollFromPointer(pointerAxis: Float) {
                    val geometry = dragGeometry ?: return
                    if (!canMapPointerToScroll) return
                    val desiredThumbStart = (pointerAxis - dragOffsetInThumbPx)
                        .coerceIn(trackStartPx, trackStartPx + movableTrackPx)
                    val progress = ((desiredThumbStart - trackStartPx) / movableTrackPx)
                        .coerceIn(0f, 1f)
                    val targetOffset = progress * geometry.maxScrollOffsetPx
                    val before = when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollOffsetPx
                        ScrollbarAxis.Horizontal -> state.scrollOffsetXPx
                    }
                    val delta = targetOffset - before
                    if (abs(delta) < 0.001f) return
                    when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollBy(delta)
                        ScrollbarAxis.Horizontal -> state.scrollByX(delta)
                    }
                    val after = when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollOffsetPx
                        ScrollbarAxis.Horizontal -> state.scrollOffsetXPx
                    }
                    val consumedAbs = abs(after - before)
                    if (consumedAbs > 0.01f) {
                        accumulatedConsumedPx += consumedAbs
                        val now = SystemClock.uptimeMillis()
                        if (now - verboseLogAtMs >= 120L) {
                            verboseLogAtMs = now
                            logTouch(
                                "scrollbar drag axis=${drag.axis} pointer=${pointerAxis.toInt()} " +
                                    "thumbStart=${desiredThumbStart.toInt()} " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})",
                                verbose = true
                            )
                        }
                    }
                }

                try {
                    var dragging = true
                    while (dragging) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == drag.pointerId }
                        if (change == null) {
                            logTouch(
                                "scrollbar dragStop axis=${drag.axis} reason=pointerMissing " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                            )
                            dragging = false
                            continue
                        }
                        if (!change.pressed) {
                            logTouch(
                                "scrollbar dragStop axis=${drag.axis} reason=pointerUp " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                            )
                            dragging = false
                            continue
                        }
                        val currentPointerAxis = when (drag.axis) {
                            ScrollbarAxis.Vertical -> change.position.y
                            ScrollbarAxis.Horizontal -> change.position.x
                        }
                        scrollFromPointer(currentPointerAxis)
                        change.consume()
                    }
                    logTouch(
                        "scrollbar dragEnd axis=${drag.axis} consumedPx=${accumulatedConsumedPx.toInt()} " +
                            "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                    )
                    dragFinished = true
                } finally {
                    if (!dragFinished) {
                        logTouch(
                            "scrollbar dragAbort axis=${drag.axis} reason=cancelled " +
                                "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                        )
                    }
                    onActiveDragChanged(null)
                    scrollGestureCoordinator.onScrollbarDragFinished()
                    onTriggerScrollbarVisibility(false)
                }
            }
        } catch (e: CancellationException) {
            // pointerInput 被 Compose 取消/重启（key 变化或上层重新组合导致的协程重建）
            logTouch("scrollbar pointerInputCancel reason=cancelled", verbose = true)
            throw e
        }
    }
}

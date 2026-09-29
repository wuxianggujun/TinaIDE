package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException

internal class CursorHandleDragCoordinator(
    private val state: EditorState,
    private val lineLayoutCache: EditorLineLayoutCache,
    private val textScanCache: EditorTextScanCache,
    private val gestureCoordinator: EditorGestureCoordinator,
    private val gestureHandler: EditorGestureHandler,
    private val selectionMagnifier: SelectionMagnifierController,
    private val logEditorTouch: (String, Boolean) -> Unit
) {
    private fun logTouch(message: String, verbose: Boolean = false) {
        logEditorTouch(message, verbose)
    }

    private val lineTextLookup = EditorLineTextLookup(state)

    suspend fun AwaitPointerEventScope.runDragLoop(
        contentStartXPxProvider: () -> Float,
        canvasWidthPxProvider: () -> Float,
        canvasHeightPxProvider: () -> Float,
        canvasOriginInWindowPxProvider: () -> IntOffset,
        density: Density,
        textPaint: Paint,
        onDraggingChanged: (Boolean) -> Unit,
        onContextMenuVisibilityChanged: (Boolean) -> Unit,
        cancelPendingCompletionRequest: () -> Unit
    ) {
        try {
            while (true) {
                val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
                val range = state.selectionRange
                if (range != null && !range.isEmpty) {
                    continue
                }

                textPaint.typeface = state.typeface
                textPaint.textSize = with(density) { state.fontSizeSp.sp.toPx() }
                val layout = resolveCursorHandleLayout(
                    state = state,
                    textStartX = contentStartXPxProvider(),
                    textPaint = textPaint,
                    lineLayoutCache = lineLayoutCache,
                    lineTextProvider = lineTextLookup::lineText,
                    textScanCache = textScanCache
                ) ?: continue
                val downInContent = Offset(
                    x = down.position.x + state.scrollOffsetXPx,
                    y = down.position.y
                )
                if (!layout.hitTest(downInContent)) {
                    continue
                }

                val cursorAnchor = layout.viewportDragAnchor(state.scrollOffsetXPx)
                val pointerToCursorDelta = down.position - cursorAnchor
                val edgeAutoScrollZonePx = with(density) { 32.dp.toPx() }
                val scrollbarTouchThicknessPx = with(density) { EditorScrollbarMetrics.TOUCH_TARGET_THICKNESS_DP.dp.toPx() }
                val hostViewOriginInWindowPx = selectionMagnifier.hostViewOriginInWindowPx()
                var finished = false
                var started = false
                var magnifierSuppressed = false
                var lastMagnifierUpdateAtMs = 0L
                onDraggingChanged(true)
                onContextMenuVisibilityChanged(false)
                cancelPendingCompletionRequest()
                logTouch(
                    "cursorHandle dragStart pointer=(${down.position.x.toInt()},${down.position.y.toInt()})",
                    verbose = false
                )
                try {
                    gestureCoordinator.onCursorDragStart(cursorAnchor)
                    started = gestureHandler.isCursorDragActive
                    if (!started) {
                        continue
                    }
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            logTouch("cursorHandle dragStop reason=pointerMissing")
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            logTouch("cursorHandle dragStop reason=pointerUp")
                            break
                        }
                        val canvasWidthPx = canvasWidthPxProvider()
                        val canvasHeightPx = canvasHeightPxProvider()
                        val verticalAutoScrollStepPx = (state.lineHeightPx * 0.85f).coerceAtLeast(1f)
                        val horizontalAutoScrollStepPx = (state.charWidthPx * 2.2f).coerceAtLeast(1f)
                        if (change.position.y <= edgeAutoScrollZonePx) {
                            state.scrollBy(-verticalAutoScrollStepPx)
                        } else if (change.position.y >= canvasHeightPx - edgeAutoScrollZonePx) {
                            state.scrollBy(verticalAutoScrollStepPx)
                        }
                        if (!state.config.wordWrap) {
                            if (change.position.x <= edgeAutoScrollZonePx) {
                                state.scrollByX(-horizontalAutoScrollStepPx)
                            } else if (change.position.x >= canvasWidthPx - edgeAutoScrollZonePx) {
                                state.scrollByX(horizontalAutoScrollStepPx)
                            }
                        }
                        val adjustedPosition = change.position - pointerToCursorDelta
                        gestureCoordinator.onCursorDrag(adjustedPosition)
                        if (state.config.selectionMagnifierEnabled) {
                            val inEdgeZone =
                                change.position.x <= edgeAutoScrollZonePx ||
                                    change.position.x >= canvasWidthPx - edgeAutoScrollZonePx ||
                                    change.position.y <= edgeAutoScrollZonePx ||
                                    change.position.y >= canvasHeightPx - edgeAutoScrollZonePx
                            if (inEdgeZone) {
                                if (!magnifierSuppressed) {
                                    magnifierSuppressed = true
                                    selectionMagnifier.dismiss()
                                }
                            } else {
                                magnifierSuppressed = false
                                val now = SystemClock.uptimeMillis()
                                if (now - lastMagnifierUpdateAtMs >= 16L) {
                                    lastMagnifierUpdateAtMs = now
                                    val clampLeftInCanvasPx = contentStartXPxProvider()
                                    val clampRightInCanvasPx = (canvasWidthPx - scrollbarTouchThicknessPx)
                                        .coerceAtLeast(clampLeftInCanvasPx)
                                    selectionMagnifier.update(
                                        pointerInCanvasPx = change.position,
                                        canvasOriginInWindowPx = canvasOriginInWindowPxProvider(),
                                        hostViewOriginInWindowPx = hostViewOriginInWindowPx,
                                        lineHeightPx = state.lineHeightPx,
                                        handleRadiusPx = layout.drawRadiusPx,
                                        clampLeftInCanvasPx = clampLeftInCanvasPx,
                                        clampRightInCanvasPx = clampRightInCanvasPx,
                                        fontSizeSp = state.fontSizeSp
                                    )
                                }
                            }
                        }
                        change.consume()
                    }
                    gestureCoordinator.onCursorDragEnd()
                    finished = true
                    logTouch(
                        "cursorHandle dragEnd cursor=${state.cursorOffset} scroll=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})",
                        verbose = true
                    )
                } catch (e: CancellationException) {
                    logTouch("cursorHandle dragCancel reason=cancelled", verbose = true)
                    throw e
                } finally {
                    if (!finished && started) {
                        gestureCoordinator.onCursorDragCancel()
                    }
                    selectionMagnifier.dismiss()
                    if (!finished) {
                        logTouch(
                            "cursorHandle dragAbort cursor=${state.cursorOffset} scroll=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})",
                            verbose = true
                        )
                    }
                    onDraggingChanged(false)
                }
            }
        } catch (e: CancellationException) {
            logTouch("cursorHandle pointerInputCancel reason=cancelled", verbose = true)
            throw e
        }
    }
}

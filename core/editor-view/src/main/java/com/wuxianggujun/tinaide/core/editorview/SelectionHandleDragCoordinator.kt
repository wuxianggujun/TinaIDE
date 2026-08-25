package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException

internal class SelectionHandleDragCoordinator(
    private val state: EditorState,
    private val lineLayoutCache: EditorLineLayoutCache,
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
        touchSlopPx: Float,
        density: Density,
        textPaint: Paint,
        onActiveHandleChanged: (SelectionHandleKind?) -> Unit,
        onContextMenuVisibilityChanged: (Boolean) -> Unit,
        onContextMenuOffsetChanged: (IntOffset) -> Unit,
        cancelPendingCompletionRequest: () -> Unit
    ) {
        while (true) {
            val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
            val contentStartXPx = contentStartXPxProvider()
            val canvasWidthPx = canvasWidthPxProvider()
            val canvasHeightPx = canvasHeightPxProvider()
            textPaint.typeface = state.typeface
            textPaint.textSize = with(density) { state.fontSizeSp.sp.toPx() }
            val layout = resolveSelectionHandleLayout(
                state = state,
                textStartX = contentStartXPx,
                textPaint = textPaint,
                lineLayoutCache = lineLayoutCache,
                lineTextProvider = lineTextLookup::lineText
            ) ?: continue
            val downInContent = Offset(
                x = down.position.x + state.scrollOffsetXPx,
                y = down.position.y
            )
            val handleKind = layout.hitTest(downInContent) ?: continue
            val currentRange = state.selectionRange
            if (currentRange == null || currentRange.isEmpty) {
                continue
            }
            val fixedAnchorOffset = when (handleKind) {
                SelectionHandleKind.START -> currentRange.end
                SelectionHandleKind.END -> currentRange.start
            }
            val pointerToTextAnchorDelta = down.position - layout.viewportDragAnchor(
                kind = handleKind,
                scrollOffsetXPx = state.scrollOffsetXPx
            )
            onActiveHandleChanged(handleKind)
            onContextMenuVisibilityChanged(false)
            cancelPendingCompletionRequest()
            try {
                val menuOffset = dragHandleSession(
                    down = down,
                    handleKind = handleKind,
                    fixedAnchorOffset = fixedAnchorOffset,
                    pointerToTextAnchorDelta = pointerToTextAnchorDelta,
                    contentStartXPx = contentStartXPx,
                    canvasWidthPx = canvasWidthPx,
                    canvasHeightPx = canvasHeightPx,
                    canvasOriginInWindowPxProvider = canvasOriginInWindowPxProvider,
                    touchSlopPx = touchSlopPx,
                    density = density,
                    textPaint = textPaint,
                    onActiveHandleChanged = { kind -> onActiveHandleChanged(kind) }
                )
                if (menuOffset != null) {
                    onContextMenuOffsetChanged(menuOffset)
                    onContextMenuVisibilityChanged(true)
                }
            } finally {
                onActiveHandleChanged(null)
            }
        }
    }

    suspend fun AwaitPointerEventScope.dragHandleSession(
        down: PointerInputChange,
        handleKind: SelectionHandleKind,
        fixedAnchorOffset: Int,
        pointerToTextAnchorDelta: Offset = Offset.Zero,
        contentStartXPx: Float,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        canvasOriginInWindowPxProvider: () -> IntOffset,
        touchSlopPx: Float,
        density: Density,
        textPaint: Paint,
        onActiveHandleChanged: (SelectionHandleKind) -> Unit
    ): IntOffset? {
        val edgeAutoScrollZonePx = with(density) { 32.dp.toPx() }
        val verticalAutoScrollStepPx = (state.lineHeightPx * 0.85f).coerceAtLeast(1f)
        val horizontalAutoScrollStepPx = (state.charWidthPx * 2.2f).coerceAtLeast(1f)
        // 与 EditorScrollbarRenderer 的触摸命中厚度保持一致，避免句柄拖动/放大镜边界 clamp 出现偏差。
        val scrollbarTouchThicknessPx = with(density) { 14.dp.toPx() }
        val hostViewOriginInWindowPx = selectionMagnifier.hostViewOriginInWindowPx()

        logTouch(
            "selectionHandle dragStart handle=$handleKind " +
                "pointer=(${down.position.x.toInt()},${down.position.y.toInt()})"
        )
        down.consume()
        val touchSlopSquared = touchSlopPx * touchSlopPx
        var dragMovedEnough = false
        var currentHandleKind = handleKind
        var magnifierSuppressed = false
        var lastMagnifierUpdateAtMs = 0L

        var lastVerboseLogAtMs = 0L
        var lastDragViewportPoint: Offset? = null
        var lastDragOffset: Int? = null
        var lastLine = Int.MIN_VALUE
        var lastLineText: String? = null
        var lastLineTextVersion = Long.MIN_VALUE
        var dragFinished = false
        var menuOffset: IntOffset? = null
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    logTouch("selectionHandle dragStop handle=$handleKind reason=pointerMissing")
                    break
                }
                if (!change.pressed) {
                    change.consume()
                    logTouch("selectionHandle dragStop handle=$handleKind reason=pointerUp")
                    break
                }

                lastDragViewportPoint = change.position
                if (!dragMovedEnough) {
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (dx * dx + dy * dy < touchSlopSquared) {
                        change.consume()
                        continue
                    }
                    dragMovedEnough = true
                    logTouch("selectionHandle dragActive handle=$handleKind", true)
                }

                // 句柄到达边缘时自动滚动，行为与 Sora 接近。
                if (change.position.y <= edgeAutoScrollZonePx) {
                    state.scrollBy(-verticalAutoScrollStepPx)
                } else if (change.position.y >= canvasHeightPx - edgeAutoScrollZonePx) {
                    state.scrollBy(verticalAutoScrollStepPx)
                }
                // 横向边缘滚动使用 viewport 坐标（不依赖 scrollX），避免坐标系混用导致拖动"卡住/不触发"。
                if (!state.config.wordWrap) {
                    if (change.position.x <= edgeAutoScrollZonePx) {
                        state.scrollByX(-horizontalAutoScrollStepPx)
                    } else if (change.position.x >= canvasWidthPx - edgeAutoScrollZonePx) {
                        state.scrollByX(horizontalAutoScrollStepPx)
                    }
                }

                val adjustedPosition = change.position - pointerToTextAnchorDelta
                val visualLine = state.visualLineFromViewportY(adjustedPosition.y)
                val line = state.docLineForVisualLine(visualLine)
                val visualStartColumn = state.visualLineStartColumn(visualLine)
                val visualEndColumn = state.visualLineEndColumn(visualLine)
                val contentX = (adjustedPosition.x - contentStartXPx + state.scrollOffsetXPx).coerceAtLeast(0f)
                val textVersion = state.textBuffer.version
                val lineText = if (line == lastLine && textVersion == lastLineTextVersion) {
                    lastLineText ?: lineTextLookup.lineText(line)
                } else {
                    val loaded = lineTextLookup.lineText(line)
                    lastLine = line
                    lastLineTextVersion = textVersion
                    lastLineText = loaded
                    loaded
                }
                val prefixLayout = lineLayoutCache.getPrefixLayout(
                    state = state,
                    line = line,
                    lineText = lineText,
                    textVersion = textVersion,
                    paint = textPaint,
                )
                val safeVisualStartColumn = visualStartColumn.coerceIn(0, prefixLayout.length)
                val segmentStartXInText = prefixLayout.segmentStartAdvance(safeVisualStartColumn)
                val column = lineLayoutCache.xToColumn(
                    layout = prefixLayout,
                    contentX = segmentStartXInText + contentX
                ).coerceIn(
                    visualStartColumn.coerceAtLeast(0),
                    visualEndColumn.coerceAtLeast(visualStartColumn).coerceIn(visualStartColumn, lineText.length)
                )
                val dragOffset = state.textBuffer.positionToOffset(line, column)

                // 句柄跨越 anchor 时 flip（对齐 Sora 行为），避免视觉上的"句柄跳边"。
                val desiredKind = when (handleKind) {
                    SelectionHandleKind.START ->
                        if (dragOffset <= fixedAnchorOffset) {
                            SelectionHandleKind.START
                        } else {
                            SelectionHandleKind.END
                        }

                    SelectionHandleKind.END ->
                        if (dragOffset >= fixedAnchorOffset) {
                            SelectionHandleKind.END
                        } else {
                            SelectionHandleKind.START
                        }
                }
                if (desiredKind != currentHandleKind) {
                    logTouch(
                        "selectionHandle handleFlip from=$currentHandleKind to=$desiredKind " +
                            "anchorOffset=$fixedAnchorOffset dragOffset=$dragOffset",
                        true
                    )
                    currentHandleKind = desiredKind
                    onActiveHandleChanged(desiredKind)
                }

                if (dragOffset != lastDragOffset) {
                    lastDragOffset = dragOffset
                    // 关键：selectRange 会把 cursorOffset 设置为 endOffset。
                    // 句柄拖拽时 end 必须是 dragOffset，否则光标/自动对齐会锁在另一端，造成跳动。
                    state.selectRangeFromHandleDrag(
                        startOffset = fixedAnchorOffset,
                        endOffset = dragOffset
                    )

                    val now = SystemClock.uptimeMillis()
                    if (now - lastVerboseLogAtMs >= 130L) {
                        lastVerboseLogAtMs = now
                        logTouch(
                            "selectionHandle drag handle=$currentHandleKind " +
                                "dragOffset=$dragOffset " +
                                "scroll=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})",
                            true
                        )
                    }
                }

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
                            val minRadius = minOf(
                                state.config.selectionHandleMinRadiusPx,
                                state.config.selectionHandleMaxRadiusPx
                            )
                            val maxRadius = maxOf(
                                state.config.selectionHandleMinRadiusPx,
                                state.config.selectionHandleMaxRadiusPx
                            )
                            val handleRadiusPx =
                                (state.lineHeightPx * state.config.selectionHandleRadiusRatio)
                                    .coerceIn(minRadius, maxRadius)

                            // X 轴尽量约束在文本内容区，避免放大到滚动条/边缘 UI。
                            val clampLeftInCanvasPx = contentStartXPx
                            val clampRightInCanvasPx = (canvasWidthPx - scrollbarTouchThicknessPx)
                                .coerceAtLeast(clampLeftInCanvasPx)
                            selectionMagnifier.update(
                                pointerInCanvasPx = change.position,
                                canvasOriginInWindowPx = canvasOriginInWindowPxProvider(),
                                hostViewOriginInWindowPx = hostViewOriginInWindowPx,
                                lineHeightPx = state.lineHeightPx,
                                handleRadiusPx = handleRadiusPx,
                                clampLeftInCanvasPx = clampLeftInCanvasPx,
                                clampRightInCanvasPx = clampRightInCanvasPx,
                                fontSizeSp = state.fontSizeSp
                            )
                        }
                    }
                }
                change.consume()
            }

            val rangeNow = state.selectionRange
            if (rangeNow != null && !rangeNow.isEmpty) {
                val menuAnchor = lastDragViewportPoint ?: down.position
                menuOffset = IntOffset(menuAnchor.x.toInt(), menuAnchor.y.toInt())
            }
            logTouch("selectionHandle dragEnd handle=$handleKind")
            dragFinished = true
            return menuOffset
        } catch (e: CancellationException) {
            // 通常意味着 pointerInput 因 key 变化而被 Compose 取消并重启。
            logTouch("selectionHandle dragCancel handle=$handleKind reason=cancelled")
            throw e
        } catch (t: Throwable) {
            // 非取消类异常：保留日志，方便定位坐标/缓存/放大镜相关问题。
            logTouch(
                "selectionHandle dragError handle=$handleKind " +
                    "type=${t::class.java.simpleName} msg=${t.message}"
            )
            throw t
        } finally {
            if (!dragFinished) {
                logTouch("selectionHandle dragAbort handle=$handleKind")
            }
            selectionMagnifier.dismiss()
        }
    }
}

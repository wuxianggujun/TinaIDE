package com.wuxianggujun.tinaide.core.editorview

import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun EditorCanvasLayer(
    session: TinaEditorSession
) {
    val state = session.state
    val ui = session.ui
    val density = session.density
    val textPaint = session.textPaint
    val lineNumberPaint = session.lineNumberPaint
    val renderer = session.renderer
    val interactionController = session.interactionController
    val scrollGestureCoordinator = session.scrollGestureCoordinator
    val selectionHandleDragCoordinator = session.selectionHandleDragCoordinator
    val cursorHandleDragCoordinator = session.cursorHandleDragCoordinator
    val scrollbarDragCoordinator = session.scrollbarDragCoordinator
    val canvasGesturePipeline = session.canvasGesturePipeline
    val scrollbarRenderer = session.scrollbarRenderer
    val scrollbarVisibilityCoordinator = session.scrollbarVisibilityCoordinator
    val view = LocalView.current
    val imeBottomInsetPx = WindowInsets.ime.getBottom(density)
    val hasSelection = state.selectionRange?.isEmpty == false

    // 每帧都 set 一次 Paint.color / typeface 会触发底层 native 调用。绝大多数帧三个字段都没变 ——
    // 用一个 remember 小状态做 identity/值级 memo，仅在真正变化时写入。
    val paintApplyState = remember { PaintApplyMemo() }

    val selectionHandleDragModifier = Modifier.pointerInput(state) {
        if (session.touchDiagnostics.isVerboseEnabled()) {
            session.touchDiagnostics.log(
                "selectionHandle pointerInputStart " +
                    "contentStartX=${ui.contentStartXPx.toInt()} " +
                    "canvas=(${ui.canvasWidthPx.toInt()}x${ui.canvasHeightPx.toInt()}) " +
                    "fontSp=${state.fontSizeSp}",
                true
            )
        }
        awaitPointerEventScope {
            with(selectionHandleDragCoordinator) {
                runDragLoop(
                    contentStartXPxProvider = { ui.contentStartXPx },
                    canvasWidthPxProvider = { ui.canvasWidthPx },
                    canvasHeightPxProvider = { ui.canvasHeightPx },
                    canvasOriginInWindowPxProvider = { ui.canvasOriginInWindowPx },
                    touchSlopPx = session.touchSlop,
                    density = density,
                    textPaint = textPaint,
                    onActiveHandleChanged = { handle -> ui.activeSelectionHandle = handle },
                    onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) },
                    onContextMenuOffsetChanged = { offset -> ui.contextMenuOffset = offset },
                    cancelPendingCompletionRequest = { interactionController.cancelPendingCompletionRequest() }
                )
            }
        }
    }
    val scrollbarDragModifier = Modifier.pointerInput(state) {
        awaitPointerEventScope {
            with(scrollbarDragCoordinator) {
                runDragLoop(
                    canvasWidthPxProvider = { ui.canvasWidthPx },
                    canvasHeightPxProvider = { ui.canvasHeightPx },
                    touchSlopPx = session.touchSlop,
                    density = density
                )
            }
        }
    }
    val longPressSelectionFollowModifier = Modifier.pointerInput(state, session.touchSlop, ui.canvasHeightPx) {
        awaitPointerEventScope {
            val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(120L)
            val longPressMoveSlopPx = session.touchSlop * 1.25f
            val longPressMoveSlopSquared = longPressMoveSlopPx * longPressMoveSlopPx
            val dragTouchSlopSquared = session.touchSlop * session.touchSlop
            while (true) {
                val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
                val rangeBeforeLongPress = state.selectionRange
                if (rangeBeforeLongPress != null && !rangeBeforeLongPress.isEmpty) {
                    continue
                }

                var longPressAnchor = down.position
                val longPressed = withTimeoutOrNull(longPressTimeoutMs) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        longPressAnchor = change.position
                        if (!change.pressed) {
                            return@withTimeoutOrNull false
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (dx * dx + dy * dy > longPressMoveSlopSquared) {
                            return@withTimeoutOrNull false
                        }
                    }
                } == null
                if (!longPressed) {
                    continue
                }

                var selectionDragStarted = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (selectionDragStarted) {
                                canvasGesturePipeline.onSelectionDragEnd()
                            }
                            break
                        }
                        val activeRange = state.selectionRange
                        if (activeRange == null || activeRange.isEmpty) {
                            continue
                        }

                        if (!selectionDragStarted) {
                            val dx = change.position.x - longPressAnchor.x
                            val dy = change.position.y - longPressAnchor.y
                            if (dx * dx + dy * dy < dragTouchSlopSquared) {
                                continue
                            }
                            canvasGesturePipeline.onSelectionDragStart(longPressAnchor)
                            selectionDragStarted = true
                        }

                        if (canvasGesturePipeline.onSelectionDrag(change.position)) {
                            change.consume()
                        }
                    }
                } catch (e: CancellationException) {
                    if (selectionDragStarted) {
                        canvasGesturePipeline.onSelectionDragCancel()
                    }
                    throw e
                }
            }
        }
    }
    val cursorHandleDragModifier = Modifier.pointerInput(state) {
        awaitPointerEventScope {
            with(cursorHandleDragCoordinator) {
                runDragLoop(
                    contentStartXPxProvider = { ui.contentStartXPx },
                    canvasWidthPxProvider = { ui.canvasWidthPx },
                    canvasHeightPxProvider = { ui.canvasHeightPx },
                    canvasOriginInWindowPxProvider = { ui.canvasOriginInWindowPx },
                    density = density,
                    textPaint = textPaint,
                    onDraggingChanged = { dragging -> ui.isCursorHandleDragging = dragging },
                    onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) },
                    cancelPendingCompletionRequest = { interactionController.cancelPendingCompletionRequest() }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                ui.canvasOriginInWindowPx = IntOffset(pos.x.toInt(), pos.y.toInt())
            }
            .then(selectionHandleDragModifier)
            .then(cursorHandleDragModifier)
            .then(scrollbarDragModifier)
            .then(longPressSelectionFollowModifier)
            .pointerInput(state, session.touchSlop) {
                awaitPointerEventScope {
                    with(canvasGesturePipeline) {
                        observePointerStream()
                    }
                }
            }
            .pointerInput(state) {
                awaitPointerEventScope {
                    var secondaryPressed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val isSecondaryPressed = event.buttons.isSecondaryPressed
                        if (!isSecondaryPressed) {
                            secondaryPressed = false
                            continue
                        }
                        if (secondaryPressed) continue
                        secondaryPressed = true
                        val change = event.changes.firstOrNull() ?: continue
                        canvasGesturePipeline.onSecondaryClick(change.position)
                        change.consume()
                    }
                }
            }
            .transformable(state = session.transformableState)
            .scrollable(
                state = session.verticalScrollableState,
                orientation = Orientation.Vertical,
                flingBehavior = session.verticalFlingBehavior,
                enabled = scrollGestureCoordinator.shouldEnableVerticalScrollable()
            )
            .scrollable(
                state = session.horizontalScrollableState,
                orientation = Orientation.Horizontal,
                flingBehavior = session.horizontalFlingBehavior,
                enabled = scrollGestureCoordinator.shouldEnableHorizontalScrollable()
            )
            .pointerInput(state, ui.canvasHeightPx) {
                detectTapGestures(
                    onTap = { pos ->
                        canvasGesturePipeline.onTap(pos)
                    },
                    onLongPress = { pos ->
                        canvasGesturePipeline.onLongPress(pos)
                    }
                )
            }
            .pointerInput(state, hasSelection, ui.canvasHeightPx) {
                if (!hasSelection) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        canvasGesturePipeline.onSelectionDragStart(pos)
                    },
                    onDrag = { change, _ ->
                        if (canvasGesturePipeline.onSelectionDrag(change.position)) {
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        canvasGesturePipeline.onSelectionDragEnd()
                    },
                    onDragCancel = {
                        canvasGesturePipeline.onSelectionDragCancel()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            state.textVersion
            @Suppress("UNUSED_EXPRESSION")
            state.effectiveStylingVersion
            @Suppress("UNUSED_EXPRESSION")
            state.inlayHintsVersion

            ui.canvasWidthPx = size.width
            ui.canvasHeightPx = size.height
            paintApplyState.apply(
                textPaint = textPaint,
                lineNumberPaint = lineNumberPaint,
                typeface = state.typeface,
                textSizePx = with(density) { state.fontSizeSp.sp.toPx() },
                lineNumberForegroundArgb = state.colorScheme.lineNumberForeground.toArgb()
            )

            val visualScale = ui.scaleGestureVisualScale
            val isVisualScaling = visualScale != 1f

            if (!isVisualScaling) {
                val contentStartX = renderer.contentStartX(state, lineNumberPaint)
                ui.contentStartXPx = contentStartX
                val windowHeightPx = view.rootView.height.toFloat().coerceAtLeast(1f)
                val visibleBottomPx = (windowHeightPx - imeBottomInsetPx).coerceAtLeast(0f)
                val canvasBottomInWindowPx = ui.canvasOriginInWindowPx.y.toFloat() + size.height
                val imeOverlapPx = (canvasBottomInWindowPx - visibleBottomPx).coerceAtLeast(0f)
                val effectiveViewportHeightPx = (size.height - imeOverlapPx).coerceAtLeast(1f)
                state.updateMetrics(
                    lineHeightPx = paintApplyState.lineHeightPx,
                    charWidthPx = paintApplyState.charWidthPx,
                    viewportHeightPx = effectiveViewportHeightPx,
                    viewportWidthPx = (size.width - contentStartX).coerceAtLeast(1f),
                    contentStartXPx = contentStartX
                )
                renderer.render(
                    drawScope = this,
                    state = state,
                    textPaint = textPaint,
                    lineNumberPaint = lineNumberPaint
                )
            } else {
                drawRect(color = state.colorScheme.background)

                val pivotX = ui.scaleGestureVisualPivotX
                val pivotY = ui.scaleGestureVisualPivotY
                val canvas = drawContext.canvas
                canvas.save()
                canvas.translate(pivotX, pivotY)
                canvas.scale(visualScale, visualScale)
                canvas.translate(-pivotX, -pivotY)
                renderer.render(
                    drawScope = this,
                    state = state,
                    textPaint = textPaint,
                    lineNumberPaint = lineNumberPaint
                )
                canvas.restore()
            }

            val scrollbarLayout = scrollbarRenderer.calculateLayout(
                state = state,
                canvasWidth = size.width,
                canvasHeight = size.height,
                density = density
            )
            scrollbarRenderer.draw(
                drawScope = this,
                layout = scrollbarLayout,
                alpha = scrollbarVisibilityCoordinator.alpha.value,
                colorScheme = state.colorScheme,
                activeAxis = ui.activeScrollbarDrag?.axis
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cursorScale = ui.scaleGestureVisualScale
            val isCursorScaling = cursorScale != 1f

            if (isCursorScaling) {
                val pivotX = ui.scaleGestureVisualPivotX
                val pivotY = ui.scaleGestureVisualPivotY
                val canvas = drawContext.canvas
                canvas.save()
                canvas.translate(pivotX, pivotY)
                canvas.scale(cursorScale, cursorScale)
                canvas.translate(-pivotX, -pivotY)
            }

            renderer.renderCursorOverlay(
                drawScope = this,
                state = state,
                textPaint = textPaint,
                lineNumberPaint = lineNumberPaint
            )

            if (isCursorScaling) {
                drawContext.canvas.restore()
            }
        }
    }
}

/**
 * 避免每帧无脑 set Paint.color / Paint.typeface —— 这些赋值底层会走 native 调用，
 * 即使值没变也有开销。identity/值级对比后只在真正变化时写入。
 */
internal class PaintApplyMemo {
    private var lastTypeface: android.graphics.Typeface? = null
    private var lastTextSizePx = 0f
    private var lastLineNumberForegroundArgb: Int = 0
    private var initialized: Boolean = false
    private val fontMetrics = android.graphics.Paint.FontMetrics()
    var lineHeightPx: Float = 1f
        private set
    var charWidthPx: Float = 1f
        private set

    fun apply(
        textPaint: android.graphics.Paint,
        lineNumberPaint: android.graphics.Paint,
        typeface: android.graphics.Typeface?,
        textSizePx: Float,
        lineNumberForegroundArgb: Int
    ) {
        val typefaceChanged = !initialized || lastTypeface !== typeface
        val textSizeChanged = !initialized || lastTextSizePx != textSizePx
        if (typefaceChanged) {
            textPaint.typeface = typeface
            lineNumberPaint.typeface = typeface
            lastTypeface = typeface
        }
        if (textPaint.textSize != textSizePx) textPaint.textSize = textSizePx
        if (lineNumberPaint.textSize != textSizePx) lineNumberPaint.textSize = textSizePx
        if (typefaceChanged || textSizeChanged) {
            textPaint.getFontMetrics(fontMetrics)
            lineHeightPx = (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading).coerceAtLeast(1f)
            charWidthPx = textPaint.measureText("0").coerceAtLeast(1f)
            lastTextSizePx = textSizePx
        }
        if (!initialized || lastLineNumberForegroundArgb != lineNumberForegroundArgb) {
            lineNumberPaint.color = lineNumberForegroundArgb
            lastLineNumberForegroundArgb = lineNumberForegroundArgb
        }
        initialized = true
    }
}

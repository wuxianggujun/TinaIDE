package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset

internal data class CursorHandleLayout(
    val cursorX: Float,
    val cursorTop: Float,
    val cursorBottom: Float,
    val handleCenter: Offset,
    val drawRadiusPx: Float,
    val hitRadiusPx: Float
) {
    fun hitTest(pointInContent: Offset): Boolean {
        val dx = pointInContent.x - handleCenter.x
        val dy = pointInContent.y - handleCenter.y
        return dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
    }

    fun viewportDragAnchor(scrollOffsetXPx: Float): Offset = Offset(
        x = cursorX - scrollOffsetXPx,
        y = cursorBottom
    )
}

internal fun resolveCursorHandleLayout(
    state: EditorState,
    textStartX: Float,
    textPaint: Paint,
    lineLayoutCache: EditorLineLayoutCache,
    lineTextProvider: (Int) -> String,
    foldEndLineInfo: FoldEndLineCursorInfo? = null,
    textScanCache: EditorTextScanCache
): CursorHandleLayout? {
    val anchor = resolveCursorVisualAnchor(
        state = state,
        textStartX = textStartX,
        textPaint = textPaint,
        lineLayoutCache = lineLayoutCache,
        lineTextProvider = lineTextProvider,
        foldEndLineInfo = foldEndLineInfo,
        textScanCache = textScanCache
    ) ?: return null
    if (anchor.visualLine !in state.visibleLines) return null

    val minRadius = minOf(
        state.config.selectionHandleMinRadiusPx,
        state.config.selectionHandleMaxRadiusPx
    ) * CURSOR_HANDLE_MIN_RADIUS_SCALE
    val maxRadius = maxOf(
        state.config.selectionHandleMinRadiusPx,
        state.config.selectionHandleMaxRadiusPx
    ) * CURSOR_HANDLE_MAX_RADIUS_SCALE
    val drawRadius = (state.lineHeightPx * CURSOR_HANDLE_RADIUS_RATIO)
        .coerceIn(minRadius, maxRadius)
    val handleCenter = Offset(
        x = anchor.cursorXInContentPx,
        y = anchor.cursorLineBottomInViewportPx + drawRadius * CURSOR_HANDLE_CENTER_Y_OFFSET_RATIO
    )
    val hitRadius = (
        drawRadius + state.lineHeightPx * CURSOR_HANDLE_HIT_SLOP_RATIO
        ).coerceAtLeast(drawRadius + CURSOR_HANDLE_HIT_MIN_EXTRA_PX)
    return CursorHandleLayout(
        cursorX = anchor.cursorXInContentPx,
        cursorTop = anchor.cursorLineTopInViewportPx + CURSOR_STROKE_INSET_PX,
        cursorBottom = anchor.cursorLineBottomInViewportPx - CURSOR_STROKE_INSET_PX,
        handleCenter = handleCenter,
        drawRadiusPx = drawRadius,
        hitRadiusPx = hitRadius
    )
}

private const val CURSOR_STROKE_INSET_PX = 2f

// 单光标句柄比选区句柄略小一些，并且圆点再下垂一点，
// 视觉上更接近 Sora 那种“细竖线 + 下坠小圆滴”的感觉。
private const val CURSOR_HANDLE_RADIUS_RATIO = 0.30f
private const val CURSOR_HANDLE_MIN_RADIUS_SCALE = 0.72f
private const val CURSOR_HANDLE_MAX_RADIUS_SCALE = 0.82f
private const val CURSOR_HANDLE_CENTER_Y_OFFSET_RATIO = 1.12f
private const val CURSOR_HANDLE_HIT_SLOP_RATIO = 0.72f
private const val CURSOR_HANDLE_HIT_MIN_EXTRA_PX = 16f

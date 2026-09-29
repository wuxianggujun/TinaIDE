package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset

internal enum class SelectionHandleKind {
    START,
    END
}

internal data class SelectionHandleLayout(
    val startCenter: Offset,
    val endCenter: Offset,
    val drawRadiusPx: Float,
    val hitRadiusPx: Float
) {
    fun viewportDragAnchor(kind: SelectionHandleKind, scrollOffsetXPx: Float): Offset {
        val center = when (kind) {
            SelectionHandleKind.START -> startCenter
            SelectionHandleKind.END -> endCenter
        }
        return Offset(
            x = center.x - scrollOffsetXPx,
            y = center.y - drawRadiusPx * SELECTION_HANDLE_KNOB_Y_OFFSET_RATIO
        )
    }

    fun hitTest(pointInContent: Offset): SelectionHandleKind? {
        val hitRadiusSquared = hitRadiusPx * hitRadiusPx
        val startDistanceSquared = distanceSquared(pointInContent, startCenter)
        val endDistanceSquared = distanceSquared(pointInContent, endCenter)
        val startHit = startDistanceSquared <= hitRadiusSquared
        val endHit = endDistanceSquared <= hitRadiusSquared
        return when {
            startHit && endHit ->
                if (startDistanceSquared <= endDistanceSquared) {
                    SelectionHandleKind.START
                } else {
                    SelectionHandleKind.END
                }

            startHit -> SelectionHandleKind.START
            endHit -> SelectionHandleKind.END
            else -> null
        }
    }

    private fun distanceSquared(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}

internal fun resolveSelectionHandleLayout(
    state: EditorState,
    textStartX: Float,
    textPaint: Paint,
    lineLayoutCache: EditorLineLayoutCache,
    lineTextProvider: (Int) -> String
): SelectionHandleLayout? {
    val range = state.selectionRange ?: return null
    if (range.isEmpty) return null
    if (state.lineHeightPx <= 0f) return null
    if (state.textBuffer.lineCount <= 0) return null

    val startPos = state.textBuffer.offsetToPosition(range.start)
    val endPos = state.textBuffer.offsetToPosition(range.end)
    val minRadius = minOf(state.config.selectionHandleMinRadiusPx, state.config.selectionHandleMaxRadiusPx)
    val maxRadius = maxOf(state.config.selectionHandleMinRadiusPx, state.config.selectionHandleMaxRadiusPx)
    val drawRadius = (state.lineHeightPx * state.config.selectionHandleRadiusRatio)
        .coerceIn(minRadius, maxRadius)
    val knobYOffset = drawRadius * SELECTION_HANDLE_KNOB_Y_OFFSET_RATIO
    val maxLine = state.textBuffer.lineCount - 1
    val startLine = startPos.line.coerceIn(0, maxLine)
    val endLine = endPos.line.coerceIn(0, maxLine)
    val startLineText = lineTextProvider(startLine)
    val endLineText = if (endLine == startLine) startLineText else lineTextProvider(endLine)
    val startColumn = startPos.column.coerceIn(0, startLineText.length)
    val endColumn = endPos.column.coerceIn(0, endLineText.length)
    val startVisualLine = state.visualLineForPosition(startLine, startColumn)
    val endVisualLine = state.visualLineForPosition(endLine, endColumn)
    val startLineBottom = state.visualLineTopInViewport(startVisualLine) + state.lineHeightPx
    val endLineBottom = state.visualLineTopInViewport(endVisualLine) + state.lineHeightPx
    val textVersion = state.textBuffer.version
    val startPrefixLayout = lineLayoutCache.getPrefixLayout(
        state = state,
        line = startLine,
        lineText = startLineText,
        textVersion = textVersion,
        paint = textPaint,
    )
    val startSegmentStartColumn =
        state.visualLineStartColumn(startVisualLine).coerceIn(0, startPrefixLayout.length)
    val safeStartColumn = startColumn.coerceIn(startSegmentStartColumn, startPrefixLayout.length)
    val startX = textStartX + (
        startPrefixLayout.textStartAdvance(safeStartColumn) -
            startPrefixLayout.segmentStartAdvance(startSegmentStartColumn)
        )
    val endPrefixLayout = if (endLine == startLine) {
        startPrefixLayout
    } else {
        lineLayoutCache.getPrefixLayout(
            state = state,
            line = endLine,
            lineText = endLineText,
            textVersion = textVersion,
            paint = textPaint,
        )
    }
    val endSegmentStartColumn =
        state.visualLineStartColumn(endVisualLine).coerceIn(0, endPrefixLayout.length)
    val safeEndColumn = endColumn.coerceIn(endSegmentStartColumn, endPrefixLayout.length)
    val endX = textStartX + (
        endPrefixLayout.textStartAdvance(safeEndColumn) -
            endPrefixLayout.segmentStartAdvance(endSegmentStartColumn)
        )
    val startCenter = Offset(
        x = startX,
        y = startLineBottom + knobYOffset
    )
    val endCenter = Offset(
        x = endX,
        y = endLineBottom + knobYOffset
    )
    val hitRadius = (
        drawRadius + state.lineHeightPx * state.config.selectionHandleHitSlopRatio
        ).coerceAtLeast(drawRadius + state.config.selectionHandleHitMinExtraPx)
    return SelectionHandleLayout(
        startCenter = startCenter,
        endCenter = endCenter,
        drawRadiusPx = drawRadius,
        hitRadiusPx = hitRadius
    )
}

private const val SELECTION_HANDLE_KNOB_Y_OFFSET_RATIO = 0.92f

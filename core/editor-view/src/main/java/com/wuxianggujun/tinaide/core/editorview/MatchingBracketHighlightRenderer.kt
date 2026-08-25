package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Highlights the bracket under (or adjacent to) the cursor and its matching counterpart
 * by drawing a translucent background rectangle behind each bracket character.
 */
internal class MatchingBracketHighlightRenderer {
    internal data class HighlightRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    fun drawHighlights(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return
        val cursorOffset = state.cursorOffset.coerceIn(0, textBuffer.length)
        val match = frameContext.bracketSnapshotCache.resolveMatchingBracket(
            textBuffer = textBuffer,
            visibleLines = state.visibleDocumentLines,
            cursorOffset = cursorOffset
        ) ?: return

        val scheme = state.colorScheme
        val highlightColor = if (scheme.rainbowBracketColors.isNotEmpty() && state.config.rainbowBrackets) {
            scheme.rainbowBracketColors[match.depth % scheme.rainbowBracketColors.size].copy(alpha = 0.25f)
        } else {
            scheme.bracketPairGuideActive
        }

        resolveHighlightRects(
            frameContext = frameContext,
            match = match,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        ).forEach { rect ->
            drawScope.drawRect(
                color = highlightColor,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height)
            )
        }
    }

    internal fun resolveHighlightRects(
        frameContext: EditorRenderFrameContext,
        match: EditorBracketSnapshotCache.BracketMatch,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
    ): List<HighlightRect> {
        val rects = ArrayList<HighlightRect>(2)
        appendLineRects(
            rects = rects,
            frameContext = frameContext,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            line = match.openLine,
            columns = if (match.openLine == match.closeLine) {
                intArrayOf(match.openColumn, match.closeColumn)
            } else {
                intArrayOf(match.openColumn)
            }
        )
        if (match.closeLine != match.openLine) {
            appendLineRects(
                rects = rects,
                frameContext = frameContext,
                textStartX = textStartX,
                textPaint = textPaint,
                lineLayoutCache = lineLayoutCache,
                line = match.closeLine,
                columns = intArrayOf(match.closeColumn)
            )
        }
        return rects
    }

    private fun appendLineRects(
        rects: MutableList<HighlightRect>,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        line: Int,
        columns: IntArray
    ) {
        val state = frameContext.state
        if (state.isDocLineHidden(line)) return

        val lineText = frameContext.lineText(line)
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            state = state,
            line = line,
            lineText = lineText,
            textVersion = frameContext.textVersion,
            paint = textPaint,
        )
        val visualLine = state.visualLineForDocLine(line)
        val top = state.visualLineTopInViewport(visualLine)
        val maxColumn = prefixLayout.length
        val height = state.lineHeightPx

        columns.forEach { column ->
            val startColumn = column.coerceIn(0, maxColumn)
            val endColumn = (column + 1).coerceIn(startColumn, maxColumn)
            val startAdvance = prefixLayout.textStartAdvance(startColumn)
            val left = textStartX + startAdvance
            val width = prefixLayout.textEndAdvance(endColumn) - startAdvance
            if (width <= 0f) return@forEach

            rects += HighlightRect(
                left = left,
                top = top,
                width = width,
                height = height
            )
        }
    }
}

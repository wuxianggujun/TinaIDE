package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Highlights all visible occurrences of the word under the cursor
 * with a subtle border rectangle.
 */
internal class WordOccurrenceHighlightRenderer {
    private data class CursorWordInfo(
        val line: Int,
        val lineText: String,
        val word: String
    )

    private val highlightColor = Color(0x30FFFFFF)
    private val borderColor = Color(0x40FFFFFF)

    internal data class HighlightRect(
        val left: Float,
        val top: Float,
        val width: Float
    )

    fun drawHighlights(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val rects = resolveHighlightRects(
            frameContext = frameContext,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        )
        for (rect in rects) {
            drawScope.drawRect(
                color = highlightColor,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, frameContext.state.lineHeightPx)
            )
            drawScope.drawRect(
                color = borderColor,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, frameContext.state.lineHeightPx),
                style = Stroke(width = 1f)
            )
        }
    }

    internal fun resolveHighlightRects(
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ): List<HighlightRect> {
        val state = frameContext.state
        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return emptyList()
        if (state.selectionRange != null) return emptyList()

        val cursorWordInfo = extractWordAtCursor(frameContext) ?: return emptyList()
        val word = cursorWordInfo.word
        if (word.length < 2) return emptyList()

        val textVersion = frameContext.textVersion
        val visibleRange = state.visibleDocumentLines
        if (visibleRange.isEmpty()) return emptyList()

        val rects = ArrayList<HighlightRect>()
        for (line in visibleRange) {
            if (line < 0 || line >= textBuffer.lineCount) continue
            if (state.isDocLineHidden(line)) continue
            val lineText = if (line == cursorWordInfo.line) {
                cursorWordInfo.lineText
            } else {
                frameContext.lineText(line)
            }
            if (lineText.length < word.length) continue

            val matches = frameContext.textScanCache.getWholeWordMatches(
                line = line,
                lineText = lineText,
                textVersion = textVersion,
                word = word
            )
            if (matches.isEmpty()) continue

            val prefixLayout = lineLayoutCache.getPrefixLayout(
                state = state,
                line = line,
                lineText = lineText,
                textVersion = textVersion,
                paint = textPaint,
            )
            val visualLine = state.visualLineForDocLine(line)
            val top = state.visualLineTopInViewport(visualLine)
            for (idx in matches) {
                val safeStartColumn = idx.coerceIn(0, prefixLayout.length)
                val safeEndColumn = (idx + word.length).coerceIn(safeStartColumn, prefixLayout.length)
                val startAdvance = prefixLayout.textStartAdvance(safeStartColumn)
                val left = textStartX + startAdvance
                val width = (prefixLayout.textEndAdvance(safeEndColumn) - startAdvance).coerceAtLeast(0f)
                rects += HighlightRect(
                    left = left,
                    top = top,
                    width = width
                )
            }
        }
        return rects
    }

    private fun extractWordAtCursor(frameContext: EditorRenderFrameContext): CursorWordInfo? {
        val state = frameContext.state
        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return null

        val position = state.cursorPosition
        val line = position.line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val lineText = frameContext.lineText(line)
        val column = position.column.coerceIn(0, lineText.length)
        val bounds = frameContext.textScanCache.getWordBounds(
            line = line,
            lineText = lineText,
            textVersion = frameContext.textVersion,
            column = column
        ) ?: return null
        return CursorWordInfo(
            line = line,
            lineText = lineText,
            word = lineText.substring(bounds.start, bounds.end)
        )
    }
}

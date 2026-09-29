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
    private val borderStroke = Stroke(width = 1f)

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
        forEachHighlightRect(
            frameContext = frameContext,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        ) { left, top, width ->
            drawScope.drawRect(
                color = highlightColor,
                topLeft = Offset(left, top),
                size = Size(width, frameContext.state.lineHeightPx)
            )
            drawScope.drawRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(width, frameContext.state.lineHeightPx),
                style = borderStroke
            )
        }
    }

    internal fun resolveHighlightRects(
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ): List<HighlightRect> = buildList {
        forEachHighlightRect(frameContext, textStartX, textPaint, lineLayoutCache) { left, top, width ->
            add(HighlightRect(left, top, width))
        }
    }

    private inline fun forEachHighlightRect(
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        emit: (left: Float, top: Float, width: Float) -> Unit,
    ) {
        val state = frameContext.state
        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return
        if (state.selectionRange != null) return

        val cursorWordInfo = extractWordAtCursor(frameContext) ?: return
        val word = cursorWordInfo.word
        if (word.length < 2) return

        val textVersion = frameContext.textVersion
        var cachedLine = -1
        var cachedText = ""
        var cachedMatches: IntArray? = null
        var cachedLayout: EditorLineLayoutCache.PrefixLayout? = null
        for (visualLine in state.visibleLines) {
            val line = state.docLineForVisualLine(visualLine)
            if (line >= textBuffer.lineCount) continue
            if (line != cachedLine) {
                cachedLine = line
                cachedText = if (line == cursorWordInfo.line) cursorWordInfo.lineText else frameContext.lineText(line)
                cachedMatches = frameContext.textScanCache.getWholeWordMatches(line, cachedText, textVersion, word)
                cachedLayout = null
            }
            val matches = cachedMatches ?: continue
            if (matches.isEmpty()) continue
            val visualStart = state.visualLineStartColumn(visualLine).coerceIn(0, cachedText.length)
            val visualEnd = state.visualLineEndColumn(visualLine).coerceIn(visualStart, cachedText.length)
            val firstPossibleStart = (visualStart - word.length + 1).coerceAtLeast(0)
            val match = matches.binarySearch(firstPossibleStart)
            var matchIndex = if (match >= 0) match else -match - 1
            if (matchIndex == matches.size || matches[matchIndex] >= visualEnd) continue
            val prefixLayout = cachedLayout ?: lineLayoutCache.getPrefixLayout(
                state = state,
                line = line,
                lineText = cachedText,
                textVersion = textVersion,
                paint = textPaint,
            ).also { cachedLayout = it }
            val top = state.visualLineTopInViewport(visualLine)
            val segmentStart = prefixLayout.segmentStartAdvance(visualStart)
            while (matchIndex < matches.size) {
                val idx = matches[matchIndex++]
                if (idx >= visualEnd) break
                val safeStartColumn = maxOf(idx, visualStart)
                val safeEndColumn = minOf(idx + word.length, visualEnd)
                val startAdvance = prefixLayout.textStartAdvance(safeStartColumn)
                val left = textStartX + startAdvance - segmentStart
                val width = (prefixLayout.textEndAdvance(safeEndColumn) - startAdvance).coerceAtLeast(0f)
                emit(left, top, width)
            }
        }
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

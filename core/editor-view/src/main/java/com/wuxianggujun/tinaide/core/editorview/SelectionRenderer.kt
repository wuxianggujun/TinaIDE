package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope

internal class SelectionRenderer {
    private data class SelectionEndpoints(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
    )

    private var cachedSelectionRange: OffsetRange? = null
    private var cachedSelectionVersion = -1L
    private var cachedSelectionEndpoints = SelectionEndpoints(
        startLine = 0,
        startColumn = 0,
        endLine = 0,
        endColumn = 0
    )

    fun drawCurrentLineHighlight(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float
    ) {
        val state = frameContext.state
        val pos = state.cursorPosition
        val line = pos.line
        val visualLine = state.visualLineForPosition(line, pos.column)
        if (visualLine !in state.visibleLines) return
        val range = state.selectionRange
        if (range != null && !range.isEmpty) {
            val endpoints = resolveSelectionEndpoints(state, range)
            if (line in endpoints.startLine..endpoints.endLine) return
        }
        val top = state.visualLineTopInViewport(visualLine)
        val highlightWidth = (drawScope.size.width + state.scrollOffsetXPx - textStartX)
            .coerceAtLeast(0f)
        drawScope.drawRect(
            color = state.colorScheme.currentLineBackground,
            topLeft = Offset(textStartX, top),
            size = Size(highlightWidth, state.lineHeightPx)
        )
    }

    fun drawSelection(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        val range = state.selectionRange ?: return
        if (range.isEmpty) return
        val visibleVisualLines = state.visibleLines
        if (visibleVisualLines.isEmpty()) return
        val endpoints = resolveSelectionEndpoints(state, range)
        val textVersion = frameContext.textVersion
        val selectionBackground = state.colorScheme.selectionBackground
        val lineHeightPx = state.lineHeightPx
        var cachedLine = -1
        var cachedLineText = ""
        var cachedPrefixLayout: EditorLineLayoutCache.PrefixLayout? = null
        for (visualLine in visibleVisualLines) {
            val line = state.docLineForVisualLine(visualLine)
            if (line < endpoints.startLine || line > endpoints.endLine) continue
            if (line >= state.textBuffer.lineCount) continue
            if (line != cachedLine) {
                cachedLine = line
                cachedLineText = frameContext.lineText(line)
                cachedPrefixLayout = null
            }
            val lineText = cachedLineText
            val visualStartColumn = state.visualLineStartColumn(visualLine)
            val visualEndColumn = state.visualLineEndColumn(visualLine).coerceIn(visualStartColumn, lineText.length)
            val lineStartCol = (if (line == endpoints.startLine) endpoints.startColumn else 0)
                .coerceIn(0, lineText.length)
            val lineEndCol = (if (line == endpoints.endLine) endpoints.endColumn else lineText.length)
                .coerceIn(lineStartCol, lineText.length)
            val startColumn = maxOf(lineStartCol, visualStartColumn)
            val endColumn = minOf(lineEndCol, visualEndColumn)
            if (endColumn <= startColumn) continue

            val prefixLayout = cachedPrefixLayout ?: lineLayoutCache.getPrefixLayout(
                state = state,
                line = line,
                lineText = lineText,
                textVersion = textVersion,
                paint = textPaint,
            ).also { cachedPrefixLayout = it }
            val safeVisualStartColumn = visualStartColumn.coerceIn(0, prefixLayout.length)
            val safeStartColumn = startColumn.coerceIn(safeVisualStartColumn, prefixLayout.length)
            val safeEndColumn = endColumn.coerceIn(safeStartColumn, prefixLayout.length)
            val segmentStartAdvance = prefixLayout.segmentStartAdvance(safeVisualStartColumn)
            val selectionStartAdvance = prefixLayout.textStartAdvance(safeStartColumn)
            val selectionEndAdvance = prefixLayout.textEndAdvance(safeEndColumn)
            val x = textStartX + selectionStartAdvance - segmentStartAdvance
            val width = (selectionEndAdvance - selectionStartAdvance).coerceAtLeast(0f)
            val y = state.visualLineTopInViewport(visualLine)
            drawScope.drawRect(
                color = selectionBackground,
                topLeft = Offset(x, y),
                size = Size(width, lineHeightPx)
            )
        }
    }

    fun drawSelectionHandles(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        val layout = resolveSelectionHandleLayout(
            state = state,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = frameContext::lineText
        ) ?: return
        val handleColor = state.colorScheme.selectionHandle
        val stemTopOffset = layout.drawRadiusPx * 1.45f
        val stemBottomOffset = layout.drawRadiusPx * 0.2f
        fun drawHandle(center: Offset) {
            if (center.y + layout.drawRadiusPx < 0f) return
            if (center.y - stemTopOffset > drawScope.size.height) return
            drawScope.drawLine(
                color = handleColor,
                start = Offset(center.x, center.y - stemTopOffset),
                end = Offset(center.x, center.y - stemBottomOffset),
                strokeWidth = (layout.drawRadiusPx * 0.62f).coerceAtLeast(2f)
            )
            drawScope.drawCircle(
                color = handleColor,
                radius = layout.drawRadiusPx,
                center = center
            )
        }
        drawHandle(layout.startCenter)
        drawHandle(layout.endCenter)
    }

    private fun resolveSelectionEndpoints(
        state: EditorState,
        range: OffsetRange
    ): SelectionEndpoints {
        val version = state.textBuffer.version
        if (range === cachedSelectionRange && version == cachedSelectionVersion) {
            return cachedSelectionEndpoints
        }
        val start = state.textBuffer.offsetToPosition(range.start)
        val end = state.textBuffer.offsetToPosition(range.end)
        cachedSelectionRange = range
        cachedSelectionVersion = version
        cachedSelectionEndpoints = SelectionEndpoints(
            startLine = start.line,
            startColumn = start.column,
            endLine = end.line,
            endColumn = end.column
        )
        return cachedSelectionEndpoints
    }
}

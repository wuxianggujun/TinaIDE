package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

/**
 * Renders whitespace characters as visual symbols:
 * - Space → centered dot `·`
 * - Tab → right arrow `→`
 *
 * Supports three modes via [WhitespaceRenderMode]:
 * - NONE: disabled
 * - BOUNDARY: only leading/trailing whitespace per line
 * - ALL: every whitespace character
 */
internal class WhitespaceRenderer {

    fun drawWhitespace(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        val mode = state.config.renderWhitespace
        if (mode == WhitespaceRenderMode.NONE) return

        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return

        val color = state.colorScheme.whitespace
        val textVersion = frameContext.textVersion
        val lineHeightPx = state.lineHeightPx
        val visibleRange = state.visibleDocumentLines
        if (visibleRange.isEmpty()) return

        for (docLine in visibleRange) {
            if (docLine < 0 || docLine >= textBuffer.lineCount) continue
            if (state.isDocLineHidden(docLine)) continue
            val lineText = frameContext.lineText(docLine)
            if (lineText.isEmpty()) continue
            val markers = frameContext.textScanCache.getWhitespaceMarkers(
                line = docLine,
                lineText = lineText,
                textVersion = textVersion,
                boundaryOnly = mode == WhitespaceRenderMode.BOUNDARY
            )
            if (markers.isEmpty()) continue

            val prefixLayout = lineLayoutCache.getPrefixLayout(
                state = state,
                line = docLine,
                lineText = lineText,
                textVersion = textVersion,
                paint = textPaint,
            )
            val visualLine = state.visualLineForDocLine(docLine)
            val y = state.visualLineTopInViewport(visualLine)
            val centerY = y + lineHeightPx / 2f
            for (marker in markers) {
                val col = TextScanKernel.whitespaceMarkerColumn(marker)
                val isTab = TextScanKernel.whitespaceMarkerIsTab(marker)
                val safeStartColumn = col.coerceIn(0, prefixLayout.length)
                val safeEndColumn = (col + 1).coerceIn(safeStartColumn, prefixLayout.length)
                val x1 = textStartX + prefixLayout.textStartAdvance(safeStartColumn)
                val x2 = textStartX + prefixLayout.textEndAdvance(safeEndColumn)

                if (!isTab) {
                    val dotX = (x1 + x2) / 2f
                    drawScope.drawCircle(
                        color = color,
                        radius = 1.5f,
                        center = Offset(dotX, centerY)
                    )
                } else {
                    val arrowCenterX = (x1 + x2) / 2f
                    val arrowHalfWidth = ((x2 - x1) * 0.3f).coerceAtMost(6f)
                    val arrowHalfHeight = (lineHeightPx * 0.15f).coerceAtMost(4f)
                    drawScope.drawLine(
                        color = color,
                        start = Offset(x1 + 2f, centerY),
                        end = Offset(x2 - 2f, centerY),
                        strokeWidth = 1f
                    )
                    drawScope.drawLine(
                        color = color,
                        start = Offset(arrowCenterX + arrowHalfWidth, centerY),
                        end = Offset(arrowCenterX, centerY - arrowHalfHeight),
                        strokeWidth = 1f
                    )
                    drawScope.drawLine(
                        color = color,
                        start = Offset(arrowCenterX + arrowHalfWidth, centerY),
                        end = Offset(arrowCenterX, centerY + arrowHalfHeight),
                        strokeWidth = 1f
                    )
                }
            }
        }
    }
}

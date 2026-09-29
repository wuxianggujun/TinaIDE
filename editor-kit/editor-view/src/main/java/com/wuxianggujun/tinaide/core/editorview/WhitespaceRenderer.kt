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
        val color = state.colorScheme.whitespace
        val lineHeightPx = state.lineHeightPx
        forEachVisibleMarker(frameContext, textStartX, textPaint, lineLayoutCache) { isTab, x1, x2, centerY ->
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

    internal inline fun forEachVisibleMarker(
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        emit: (isTab: Boolean, startX: Float, endX: Float, centerY: Float) -> Unit,
    ) {
        val state = frameContext.state
        val mode = state.config.renderWhitespace
        if (mode == WhitespaceRenderMode.NONE || state.textBuffer.lineCount <= 0) return
        var cachedLine = -1
        var cachedText = ""
        var cachedMarkers: IntArray? = null
        var cachedLayout: EditorLineLayoutCache.PrefixLayout? = null
        for (visualLine in state.visibleLines) {
            val line = state.docLineForVisualLine(visualLine)
            if (line >= state.textBuffer.lineCount) continue
            if (line != cachedLine) {
                cachedLine = line
                cachedText = frameContext.lineText(line)
                cachedMarkers = frameContext.textScanCache.getWhitespaceMarkers(
                    line, cachedText, frameContext.textVersion, mode == WhitespaceRenderMode.BOUNDARY,
                )
                cachedLayout = null
            }
            val markers = cachedMarkers ?: continue
            if (markers.isEmpty()) continue
            val startColumn = state.visualLineStartColumn(visualLine).coerceIn(0, cachedText.length)
            val endColumn = state.visualLineEndColumn(visualLine).coerceIn(startColumn, cachedText.length)
            var low = 0
            var high = markers.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (TextScanKernel.whitespaceMarkerColumn(markers[mid]) < startColumn) low = mid + 1 else high = mid
            }
            if (low == markers.size || TextScanKernel.whitespaceMarkerColumn(markers[low]) >= endColumn) continue
            val layout = cachedLayout ?: lineLayoutCache.getPrefixLayout(
                state, line, cachedText, frameContext.textVersion, textPaint,
            ).also { cachedLayout = it }
            val segmentStartAdvance = layout.segmentStartAdvance(startColumn)
            val centerY = state.visualLineTopInViewport(visualLine) + state.lineHeightPx / 2f
            var markerIndex = low
            while (markerIndex < markers.size) {
                val marker = markers[markerIndex++]
                val column = TextScanKernel.whitespaceMarkerColumn(marker)
                if (column >= endColumn) break
                emit(
                    TextScanKernel.whitespaceMarkerIsTab(marker),
                    textStartX + layout.textStartAdvance(column) - segmentStartAdvance,
                    textStartX + layout.textEndAdvance(column + 1) - segmentStartAdvance,
                    centerY,
                )
            }
        }
    }
}

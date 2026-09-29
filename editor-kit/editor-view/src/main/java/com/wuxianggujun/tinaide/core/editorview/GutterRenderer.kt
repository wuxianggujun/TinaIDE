package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.min

class GutterRenderer(
    private val minWidthPx: Float
) {
    private val expandedPath = Path()
    private val collapsedPath = Path()
    private var templateIconBox = -1f

    fun widthForLineHeight(lineHeightPx: Float): Float = maxOf(minWidthPx, lineHeightPx.coerceAtLeast(1f))

    fun width(state: EditorState): Float = widthForLineHeight(state.lineHeightPx)

    fun draw(
        drawScope: DrawScope,
        state: EditorState,
        originX: Float
    ) {
        val scheme = state.colorScheme
        val widthPx = width(state)
        val lineHeightPx = state.lineHeightPx
        val foldCenterX = originX + widthPx * 0.5f
        val iconBox = (min(widthPx, lineHeightPx) * 0.62f).coerceAtLeast(8f)
        val half = iconBox * 0.5f
        val foldingValid = state.isFoldingDataValid()

        if (iconBox != templateIconBox) {
            templateIconBox = iconBox
            expandedPath.reset()
            expandedPath.moveTo(-half, -half)
            expandedPath.lineTo(half, -half)
            expandedPath.lineTo(0f, half)
            expandedPath.close()

            collapsedPath.reset()
            collapsedPath.moveTo(-half, -half)
            collapsedPath.lineTo(-half, half)
            collapsedPath.lineTo(half, 0f)
            collapsedPath.close()
        }

        state.visibleLines.forEach { visualLine ->
            val line = state.docLineForVisualLine(visualLine)
            if (line >= state.textBuffer.lineCount) return@forEach
            if (state.isVisualLineContinuation(visualLine)) return@forEach
            val decoration = state.gutterDecorations[line] ?: return@forEach
            if (!decoration.foldable || !foldingValid) return@forEach

            val centerY = state.visualLineTopInViewport(visualLine) + lineHeightPx / 2f
            val collapsed = state.isFoldCollapsedAtLine(line)
            val hasHiddenDiagnostic = state.hasHiddenDiagnosticsInFold(line)
            val foldColor = when {
                hasHiddenDiagnostic -> scheme.foldIconWarning
                collapsed -> scheme.foldIconCollapsed
                else -> scheme.foldIconExpanded
            }

            val templatePath = if (collapsed) collapsedPath else expandedPath
            drawScope.translate(left = foldCenterX, top = centerY) {
                drawPath(
                    path = templatePath,
                    color = foldColor,
                    style = Fill
                )
            }
        }
    }
}

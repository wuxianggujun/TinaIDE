package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Renders vertical guide lines between matching bracket pairs (`{}`, `[]`, `()`).
 *
 * The guide line is drawn at the indentation column of the opening bracket's line,
 * spanning from the line below the opener to the line of the closer.
 * The guide overlapping the cursor line is drawn with [EditorColorScheme.bracketPairGuideActive].
 */
internal class BracketPairGuideRenderer {

    fun drawGuides(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        if (!state.config.bracketPairGuides) return
        val textBuffer = state.textBuffer
        if (textBuffer.lineCount <= 0) return
        if (state.config.rainbowBracketsMaxLines > 0 &&
            textBuffer.lineCount > state.config.rainbowBracketsMaxLines
        ) {
            return
        }
        val visibleLines = state.visibleDocumentLines
        if (visibleLines.isEmpty()) return

        val guides = frameContext.bracketSnapshotCache.resolveVisibleGuides(
            textBuffer = textBuffer,
            visibleLines = visibleLines
        )
        if (guides.isEmpty()) return

        val scheme = state.colorScheme
        val guideColor = scheme.bracketPairGuide
        val activeGuideColor = scheme.bracketPairGuideActive
        val lineHeightPx = state.lineHeightPx
        val cursorLine = state.cursorLine
        val tabSize = state.config.tabSize
        val textVersion = textBuffer.version
        val rainbowColors = if (scheme.rainbowBracketColors.isNotEmpty() && state.config.rainbowBrackets) {
            scheme.rainbowBracketColors
        } else {
            null
        }
        var cachedGuideLine = -1
        var cachedLineText = ""
        var cachedIndentColumn = 0
        var cachedPrefixLayout: EditorLineLayoutCache.PrefixLayout? = null

        for (guide in guides) {
            if (guide.closeLine <= guide.openLine) continue
            if (guide.closeLine < visibleLines.first || guide.openLine > visibleLines.last) continue

            val guideLine = guide.openLine
            if (guideLine != cachedGuideLine) {
                cachedGuideLine = guideLine
                cachedLineText = frameContext.lineText(guideLine)
                cachedIndentColumn = frameContext.textScanCache
                    .getWhitespaceInfo(
                        line = guideLine,
                        lineText = cachedLineText,
                        textVersion = frameContext.textVersion,
                        tabSize = tabSize
                    )
                    .leadingIndentColumns
                cachedPrefixLayout = lineLayoutCache.getPrefixLayout(
                    state = state,
                    line = guideLine,
                    lineText = cachedLineText,
                    textVersion = textVersion,
                    paint = textPaint,
                )
            }
            val prefixLayout = cachedPrefixLayout ?: continue
            val guideColumn = if (guide.openColumn <= cachedIndentColumn) {
                guide.openColumn
            } else {
                cachedIndentColumn
            }
            val x = textStartX + prefixLayout.prefix[guideColumn.coerceIn(0, prefixLayout.length)]

            val startVisualLine = state.visualLineForDocLine(guide.openLine + 1)
            val endVisualLine = state.visualLineForDocLine(guide.closeLine)
            val topY = state.visualLineTopInViewport(startVisualLine)
            val bottomY = state.visualLineTopInViewport(endVisualLine) + lineHeightPx

            val isActive = cursorLine in guide.openLine..guide.closeLine
            val color = when {
                isActive && rainbowColors != null -> {
                    rainbowColors[guide.depth % rainbowColors.size].copy(alpha = 0.45f)
                }
                isActive -> activeGuideColor
                rainbowColors != null -> {
                    rainbowColors[guide.depth % rainbowColors.size].copy(alpha = 0.2f)
                }
                else -> guideColor
            }
            val strokeWidth = if (isActive) 1.5f else 1f

            drawScope.drawLine(
                color = color,
                start = Offset(x, topY),
                end = Offset(x, bottomY),
                strokeWidth = strokeWidth
            )
        }
    }
}

package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

internal class InlayHintRenderer {
    private companion object {
        private const val TEXT_SCALE = 0.68f
        private const val MAX_HEIGHT_RATIO = 0.56f
        private const val HORIZONTAL_PADDING_EM = 0.22f
        private const val LSP_PADDING_EM = 0.42f
        private const val BACKGROUND_ALPHA = 0.88f
        private const val FOREGROUND_ALPHA = 0.78f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
    ) {
        val state = frameContext.state
        val hintsByLine = state.inlayHintsByLine
        if (hintsByLine.isEmpty() || state.inlayHintsDocumentVersion != frameContext.textVersion) return

        hintPaint.typeface = textPaint.typeface
        hintPaint.textSize = (textPaint.textSize * TEXT_SCALE)
            .coerceAtMost(state.lineHeightPx * MAX_HEIGHT_RATIO)
        hintPaint.color = state.colorScheme.diagnosticHint
            .copy(alpha = FOREGROUND_ALPHA)
            .toArgb()
        backgroundPaint.color = state.colorScheme.background
            .copy(alpha = BACKGROUND_ALPHA)
            .toArgb()

        val fontMetrics = hintPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val boxHeight = (textHeight + hintPaint.textSize * 0.12f)
            .coerceAtMost(state.lineHeightPx * MAX_HEIGHT_RATIO)
        val verticalOffset = (state.lineHeightPx - boxHeight) / 2f
        val innerPadding = hintPaint.textSize * HORIZONTAL_PADDING_EM
        val lspPadding = hintPaint.textSize * LSP_PADDING_EM

        drawScope.drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            state.visibleLines.forEach visualLineLoop@{ visualLine ->
                val line = state.docLineForVisualLine(visualLine)
                val hints = hintsByLine[line].orEmpty()
                if (hints.isEmpty() || line !in 0 until state.textBuffer.lineCount) return@visualLineLoop

                val lineText = frameContext.lineText(line)
                val visualStartColumn = state.visualLineStartColumn(visualLine).coerceIn(0, lineText.length)
                val visualEndColumn = state.visualLineEndColumn(visualLine).coerceIn(visualStartColumn, lineText.length)
                val prefixLayout = lineLayoutCache.getPrefixLayout(
                    line = line,
                    lineText = lineText,
                    textVersion = frameContext.textVersion,
                    paint = textPaint,
                    tabSize = state.config.tabSize,
                )
                val segmentStartX = prefixLayout.prefix[visualStartColumn]
                val lineTop = state.visualLineTopInViewport(visualLine)
                val boxTop = lineTop + verticalOffset
                val boxBottom = boxTop + boxHeight
                val baseline = boxTop + (boxHeight - textHeight) / 2f - fontMetrics.ascent

                hints.forEach hintLoop@{ hint ->
                    val column = hint.column.coerceIn(0, lineText.length)
                    val isAtDocumentLineEnd = column == lineText.length && visualEndColumn == lineText.length
                    if (column < visualStartColumn || (column >= visualEndColumn && !isAtDocumentLineEnd)) {
                        return@hintLoop
                    }

                    val labelWidth = hintPaint.measureText(hint.label)
                    val leftPadding = innerPadding + if (hint.paddingLeft) lspPadding else 0f
                    val rightPadding = innerPadding + if (hint.paddingRight) lspPadding else 0f
                    val totalWidth = labelWidth + leftPadding + rightPadding
                    val anchorX = textStartX + prefixLayout.prefix[column] - segmentStartX
                    val boxLeft = when (hint.kind) {
                        EditorInlayHintKind.PARAMETER -> anchorX - totalWidth
                        EditorInlayHintKind.TYPE,
                        EditorInlayHintKind.OTHER -> anchorX
                    }
                    val boxRight = boxLeft + totalWidth
                    val radius = boxHeight * 0.22f
                    nativeCanvas.drawRoundRect(
                        boxLeft,
                        boxTop,
                        boxRight,
                        boxBottom,
                        radius,
                        radius,
                        backgroundPaint,
                    )
                    nativeCanvas.drawText(hint.label, boxLeft + leftPadding, baseline, hintPaint)
                }
            }
        }
    }
}

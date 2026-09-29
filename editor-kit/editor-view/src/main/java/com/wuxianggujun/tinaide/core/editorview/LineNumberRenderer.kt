package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

class LineNumberRenderer(
    private val horizontalPaddingPx: Float,
    private val edgeStartPaddingPx: Float
) {
    private companion object {
        private const val CACHED_NUMBER_LIMIT = 1024
        private val numberStrings = Array(CACHED_NUMBER_LIMIT) { it.toString() }

        fun intToString(n: Int): String = if (n in 0 until CACHED_NUMBER_LIMIT) numberStrings[n] else n.toString()
    }

    private var cachedDigitWidthTextSize = 0f
    private var cachedDigitWidthTypeface: Typeface? = null
    private var cachedDigitWidth = 0f
    private val digitChar = CharArray(1)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun calculateWidth(
        lineCount: Int,
        enabled: Boolean,
        paint: Paint
    ): Float {
        if (!enabled) return 0f
        val digitWidth = maxDigitWidth(paint).coerceAtLeast(1f)
        val digits = lineCount.coerceAtLeast(1).toString().length
        val lineNumberWidth = digitWidth * digits
        val padding = maxOf(horizontalPaddingPx * 0.45f, digitWidth * 0.45f, 2f)
        return edgeStartPaddingPx + lineNumberWidth + padding * 2f
    }

    fun draw(
        drawScope: DrawScope,
        state: EditorState,
        textPaint: Paint,
        widthPx: Float
    ) {
        if (!state.config.showLineNumbers || widthPx <= 0f) return

        val cursorLine = state.cursorPosition.line
        val scheme = state.colorScheme
        val normalColor = scheme.lineNumberForeground.toArgb()
        val activeColor = scheme.lineNumberForegroundActive.toArgb()
        val digitWidth = maxDigitWidth(textPaint).coerceAtLeast(1f)
        val padding = maxOf(horizontalPaddingPx * 0.45f, digitWidth * 0.45f, 2f)
        val rightX = widthPx - padding
        val lineHeightPx = state.lineHeightPx
        val breakpointRadius = minOf(lineHeightPx * 0.38f, digitWidth).coerceAtLeast(2f)
        val useRelative = state.useRelativeLineNumbers
        val decorations = state.gutterDecorations

        textPaint.color = normalColor
        textPaint.isFakeBoldText = false
        textPaint.alpha = 170

        activePaint.textSize = textPaint.textSize
        activePaint.typeface = textPaint.typeface
        activePaint.textAlign = textPaint.textAlign
        activePaint.color = activeColor
        activePaint.isFakeBoldText = true
        activePaint.alpha = 255

        drawScope.drawIntoCanvas { canvas ->
            state.visibleLines.forEach { visualLine ->
                val line = state.docLineForVisualLine(visualLine)
                if (line >= state.textBuffer.lineCount) return@forEach
                if (state.isVisualLineContinuation(visualLine)) return@forEach

                val display = if (useRelative && line != cursorLine) {
                    intToString(kotlin.math.abs(line - cursorLine))
                } else {
                    intToString(line + 1)
                }

                val yTop = state.visualLineTopInViewport(visualLine)
                val baselineY = yTop + lineHeightPx * 0.78f
                val decoration = decorations[line]
                val hasBreakpoint = decoration?.breakpoint == true
                val hasBookmark = decoration?.bookmark == true

                if (hasBreakpoint) {
                    val centerY = yTop + lineHeightPx / 2f
                    val centerX = rightX - breakpointRadius
                    drawScope.drawCircle(
                        color = scheme.breakpoint,
                        radius = breakpointRadius,
                        center = Offset(centerX, centerY)
                    )
                }
                if (hasBookmark) {
                    val markerWidth = maxOf(2f, digitWidth * 0.16f)
                    val markerHeight = (lineHeightPx * 0.82f).coerceAtLeast(1f)
                    val markerTop = yTop + (lineHeightPx - markerHeight) * 0.5f
                    val markerLeft = (widthPx - markerWidth).coerceAtLeast(0f)
                    drawScope.drawRect(
                        color = scheme.bookmark,
                        topLeft = Offset(markerLeft, markerTop),
                        size = Size(markerWidth, markerHeight)
                    )
                }
                if (!hasBreakpoint) {
                    val isActive = line == cursorLine
                    val paint = if (isActive) activePaint else textPaint
                    canvas.nativeCanvas.drawText(display, rightX, baselineY, paint)
                }
            }
        }

        textPaint.alpha = 255
        textPaint.isFakeBoldText = false
    }

    private fun maxDigitWidth(paint: Paint): Float {
        val textSize = paint.textSize
        val typeface = paint.typeface
        if (textSize == cachedDigitWidthTextSize && typeface === cachedDigitWidthTypeface) {
            return cachedDigitWidth
        }
        var maxWidth = 0f
        for (digit in '0'..'9') {
            digitChar[0] = digit
            val width = paint.measureText(digitChar, 0, 1)
            if (width > maxWidth) {
                maxWidth = width
            }
        }
        cachedDigitWidthTextSize = textSize
        cachedDigitWidthTypeface = typeface
        cachedDigitWidth = maxWidth
        return maxWidth
    }
}

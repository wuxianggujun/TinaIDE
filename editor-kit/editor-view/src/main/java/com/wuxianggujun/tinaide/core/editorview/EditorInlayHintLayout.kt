package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

/**
 * Inlay hint 的共享字体与间距度量。
 *
 * 布局缓存和实际绘制必须使用同一套参数，否则预留出来的虚拟空间会与提示框宽度不一致。
 */
internal object EditorInlayHintLayoutMetrics {
    const val MAX_HEIGHT_RATIO = 0.56f

    private const val TEXT_SCALE = 0.68f
    private const val HORIZONTAL_PADDING_EM = 0.22f
    private const val LSP_PADDING_EM = 0.42f

    fun configureHintPaint(
        hintPaint: Paint,
        textPaint: Paint,
        lineHeightPx: Float,
    ) {
        val fontMetrics = textPaint.fontMetrics
        val measuredLineHeight =
            (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading).coerceAtLeast(1f)
        val safeLineHeight = lineHeightPx.takeIf { it.isFinite() && it > 0f } ?: measuredLineHeight
        hintPaint.typeface = textPaint.typeface
        hintPaint.textSize = (textPaint.textSize * TEXT_SCALE)
            .coerceAtMost(safeLineHeight * MAX_HEIGHT_RATIO)
            .coerceAtLeast(1f)
    }

    fun innerPadding(hintPaint: Paint): Float = hintPaint.textSize * HORIZONTAL_PADDING_EM

    fun lspPadding(hintPaint: Paint): Float = hintPaint.textSize * LSP_PADDING_EM
}

/** 一条 Inlay Hint 在完整文档行坐标中的最终位置。 */
internal data class EditorInlayHintPlacement(
    val hint: EditorInlayHint,
    val column: Int,
    val startAdvance: Float,
    val endAdvance: Float,
    val leftPadding: Float,
)

/**
 * State 层使用的 Inlay Hint 视觉列估算。
 *
 * 编辑器的软换行本来就按视觉列而非像素计算；这里按源码字号的一列保守估算每个提示字符，
 * 再为提示框和 LSP padding 预留列数。实际行内绘制仍使用 [Paint] 的精确宽度。
 */
internal object EditorInlayHintColumnLayout {
    fun normalizeAnchorColumn(lineText: String, column: Int): Int {
        val safeColumn = column.coerceIn(0, lineText.length)
        return if (
            safeColumn > 0 &&
            safeColumn < lineText.length &&
            lineText[safeColumn - 1].isHighSurrogate() &&
            lineText[safeColumn].isLowSurrogate()
        ) {
            safeColumn - 1
        } else {
            safeColumn
        }
    }

    fun estimatedColumns(hint: EditorInlayHint): Int {
        if (hint.label.isBlank()) return 0
        return hint.label.length +
            BASE_PADDING_COLUMNS +
            (if (hint.paddingLeft) LSP_PADDING_COLUMNS else 0) +
            (if (hint.paddingRight) LSP_PADDING_COLUMNS else 0)
    }

    fun totalEstimatedColumns(hints: List<EditorInlayHint>): Int = hints.fold(0) { total, hint ->
        saturatedAdd(total, estimatedColumns(hint))
    }

    fun expandedLineColumns(
        lineText: String,
        tabSize: Int,
        hints: List<EditorInlayHint>,
    ): Int = saturatedAdd(
        TextScanKernel.measureVisualColumns(lineText, tabSize),
        totalEstimatedColumns(hints),
    )

    fun findWrapSegmentStarts(
        lineText: String,
        wrapColumns: Int,
        tabSize: Int,
        hints: List<EditorInlayHint>,
    ): IntArray {
        if (hints.isEmpty()) {
            return TextScanKernel.findWrapSegmentStarts(lineText, wrapColumns, tabSize)
        }
        val length = lineText.length
        if (length <= 0) return intArrayOf(0)

        val safeWrapColumns = wrapColumns.coerceAtLeast(1)
        val safeTabSize = tabSize.coerceAtLeast(1)
        val hintColumns = IntArray(length + 1)
        hints.forEach { hint ->
            val estimated = estimatedColumns(hint)
            if (estimated <= 0) return@forEach
            val column = normalizeAnchorColumn(lineText, hint.column)
            hintColumns[column] = saturatedAdd(hintColumns[column], estimated)
        }

        var starts = IntArray(8)
        var startCount = 1
        starts[0] = 0
        var segmentStart = 0
        var occupiedColumns = 0
        var sourceColumns = 0
        var index = 0
        while (index < length) {
            val codeUnitLength = Character.charCount(Character.codePointAt(lineText, index))
            var sourceStep = if (lineText[index] == '\t') {
                safeTabSize - (sourceColumns % safeTabSize)
            } else {
                codeUnitLength
            }
            val nextIndex = index + codeUnitLength
            val trailingHintColumns = if (nextIndex == length) hintColumns[length] else 0
            var groupColumns = saturatedAdd(hintColumns[index], sourceStep)
            groupColumns = saturatedAdd(groupColumns, trailingHintColumns)

            if (
                index > segmentStart &&
                saturatedAdd(occupiedColumns, groupColumns) > safeWrapColumns
            ) {
                starts = appendStart(starts, startCount++, index)
                segmentStart = index
                occupiedColumns = 0
                sourceColumns = 0
                sourceStep = if (lineText[index] == '\t') safeTabSize else codeUnitLength
                groupColumns = saturatedAdd(hintColumns[index], sourceStep)
                groupColumns = saturatedAdd(groupColumns, trailingHintColumns)
            }

            occupiedColumns = saturatedAdd(occupiedColumns, groupColumns)
            sourceColumns = saturatedAdd(sourceColumns, sourceStep)
            index = nextIndex
            if (occupiedColumns >= safeWrapColumns && index < length) {
                starts = appendStart(starts, startCount++, index)
                segmentStart = index
                occupiedColumns = 0
                sourceColumns = 0
            }
        }
        return starts.copyOf(startCount)
    }

    private fun appendStart(starts: IntArray, count: Int, column: Int): IntArray {
        val target = if (count >= starts.size) starts.copyOf(starts.size * 2) else starts
        target[count] = column
        return target
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right

    private const val BASE_PADDING_COLUMNS = 1
    private const val LSP_PADDING_COLUMNS = 1
}

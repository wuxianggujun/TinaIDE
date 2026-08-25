package com.wuxianggujun.tinaide.core.treesitter

import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSPoint
import com.wuxianggujun.tinaide.core.textengine.TextChange
import kotlin.math.max

data class HighlightLineSegment(
    val startColumn: Int,
    val endColumn: Int,
    val type: HighlightType,
    val priority: Int = DEFAULT_HIGHLIGHT_PRIORITY
)

internal data class HighlightLineCacheChange(
    val startLine: Int,
    val oldChangedEndLine: Int,
    val shiftFromLine: Int,
    val lineDelta: Int,
    val startColumn: Int,
    val oldEndColumn: Int,
    val columnDelta: Int,
    val isSingleLineEdit: Boolean
) {
    companion object {
        fun from(change: TextChange): HighlightLineCacheChange {
            val startLine = change.startLine.coerceAtLeast(0)
            val oldChangedEndLine = when {
                change.startColumn == 0 &&
                    change.endColumn == 0 &&
                    change.oldTextEndsWithLineBreak ->
                    (change.endLine - 1).coerceAtLeast(startLine)

                else -> change.endLine.coerceAtLeast(startLine)
            }
            val lineDelta = change.lineDelta
            val isSingleLineEdit = lineDelta == 0 &&
                change.startLine == change.endLine &&
                !change.newText.contains('\n') &&
                change.oldLineBreakCount == 0
            val columnDelta = if (isSingleLineEdit) {
                change.newText.length - change.oldTextLength
            } else {
                0
            }
            return HighlightLineCacheChange(
                startLine = startLine,
                oldChangedEndLine = oldChangedEndLine,
                shiftFromLine = (oldChangedEndLine + 1).coerceAtLeast(0),
                lineDelta = lineDelta,
                startColumn = change.startColumn.coerceAtLeast(0),
                oldEndColumn = change.endColumn.coerceAtLeast(change.startColumn.coerceAtLeast(0)),
                columnDelta = columnDelta,
                isSingleLineEdit = isSingleLineEdit
            )
        }
    }
}

/**
 * 按 [HighlightLineCacheChange] 更新 lineCache：
 * - 未编辑区的行：原样保留或按 [HighlightLineCacheChange.lineDelta] 平移行号。
 * - 编辑区内的行（`startLine..oldChangedEndLine`）：
 *   - 单行内编辑（无 `\n` 进出）：保留旧 segments，并按列偏移做"近似平移"——
 *     编辑点之前的 segment 不变；之后的按 [HighlightLineCacheChange.columnDelta] 整体平移；
 *     跨越编辑点的 segment 拉伸/截断。这样可以在 tree-sitter parse 跑完之前提供一份"肉眼可接受"的旧色，
 *     避免出现"先白再染色"的闪烁。
 *   - 多行编辑（含 `\n`）：丢弃编辑区内的缓存，避免把旧行颜色绘制到新文本的错误位置。
 */
internal object HighlightLineCacheUpdater {
    fun applyTextChange(
        cache: Map<Int, List<HighlightLineSegment>>,
        change: HighlightLineCacheChange
    ): LinkedHashMap<Int, List<HighlightLineSegment>> {
        if (cache.isEmpty()) return LinkedHashMap()

        val updated = LinkedHashMap<Int, List<HighlightLineSegment>>(cache.size)
        cache.forEach { (line, segments) ->
            if (line in change.startLine..change.oldChangedEndLine) {
                if (change.isSingleLineEdit && line == change.startLine) {
                    val shifted = shiftSegmentsOnEditedLine(segments, change)
                    if (shifted.isNotEmpty()) {
                        updated[line] = shifted
                    }
                }
                return@forEach
            }

            val targetLine = if (line >= change.shiftFromLine) {
                line + change.lineDelta
            } else {
                line
            }
            if (targetLine >= 0) {
                updated[targetLine] = segments
            }
        }

        return updated
    }

    private fun shiftSegmentsOnEditedLine(
        segments: List<HighlightLineSegment>,
        change: HighlightLineCacheChange
    ): List<HighlightLineSegment> {
        val editStart = change.startColumn
        val editOldEnd = change.oldEndColumn
        val delta = change.columnDelta
        if (segments.isEmpty()) return segments

        val result = ArrayList<HighlightLineSegment>(segments.size)
        segments.forEach { seg ->
            val s = seg.startColumn
            val e = seg.endColumn
            when {
                e <= editStart -> result += seg
                s >= editOldEnd -> result += seg.copy(
                    startColumn = s + delta,
                    endColumn = e + delta
                )
                s >= editStart && e <= editOldEnd -> Unit
                s < editStart && e <= editOldEnd -> result += seg.copy(endColumn = editStart)
                s < editStart && e > editOldEnd -> result += seg.copy(endColumn = e + delta)
                s in editStart until editOldEnd && e > editOldEnd -> result += seg.copy(
                    startColumn = editOldEnd + delta,
                    endColumn = e + delta
                )
            }
        }
        return result
    }
}

internal fun TextChange.toTsInputEdit(): TSInputEdit {
    val lineDelta = this.lineDelta
    val oldEndColumn = endColumn
    val newEndLine = max(startLine, endLine + lineDelta)
    val newEndColumn = when {
        !newText.contains('\n') -> startColumn + newText.length
        else -> newText.substringAfterLast('\n').length
    }
    return TSInputEdit.create(
        startOffset * 2,
        endOffset * 2,
        (startOffset + newText.length) * 2,
        TSPoint.create(startLine, startColumn * 2),
        TSPoint.create(endLine, oldEndColumn * 2),
        TSPoint.create(newEndLine, newEndColumn * 2)
    )
}

internal fun buildLineStartOffsets(text: String): IntArray {
    if (text.isEmpty()) {
        return intArrayOf(0)
    }
    val starts = ArrayList<Int>(text.count { it == '\n' } + 1)
    starts.add(0)
    text.forEachIndexed { index, ch ->
        if (ch == '\n' && index + 1 <= text.length) {
            starts.add(index + 1)
        }
    }
    return starts.toIntArray()
}

internal fun IntArray.lineStartOffset(line: Int): Int = this[line.coerceIn(0, lastIndex)]

internal fun IntArray.lineEndOffsetExclusive(line: Int, textLength: Int): Int {
    val safeLine = line.coerceIn(0, lastIndex)
    val nextLine = safeLine + 1
    return if (nextLine < size) {
        (this[nextLine] - 1).coerceAtLeast(this[safeLine])
    } else {
        textLength
    }
}

/**
 * 给定字节 offset，二分查找它落在哪一行。
 * `this` 必须是 [buildLineStartOffsets] 产出的严格递增数组。
 * 越界 offset 会被夹到 0..lastIndex。
 */
internal fun IntArray.findLineForOffset(offset: Int): Int {
    if (isEmpty()) return 0
    val idx = java.util.Arrays.binarySearch(this, offset)
    return if (idx >= 0) idx else (-idx - 2).coerceIn(0, lastIndex)
}

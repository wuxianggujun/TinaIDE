package com.wuxianggujun.tinaide.core.textengine

/**
 * 文本变更事件。
 *
 * `start/end` 是变更前范围（LSP didChange 直接可用）。
 *
 * [lineDelta] / [oldLineBreakCount] / [newLineBreakCount] 在构造期计算好，
 * 下游不再重复扫描 oldText / newText 数 `\n`（该操作在每次 listener 转发时
 * 都会被至少 5 个消费者各算一遍，大文本粘贴时成本可观）。
 */
data class TextChange(
    val startOffset: Int,
    val endOffset: Int,
    val oldText: String,
    val newText: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val fromUndoRedo: Boolean = false,
    val oldTextLength: Int = oldText.length,
    val oldLineBreakCount: Int = oldText.count { it == '\n' },
    val newLineBreakCount: Int = newText.count { it == '\n' },
    val oldTextEndsWithLineBreak: Boolean = oldText.endsWith('\n'),
    val hasCompleteOldText: Boolean = true
) {
    init {
        require(oldTextLength >= 0) { "oldTextLength must not be negative" }
        require(oldLineBreakCount >= 0) { "oldLineBreakCount must not be negative" }
        require(newLineBreakCount >= 0) { "newLineBreakCount must not be negative" }
        require(newLineBreakCount == newText.count { it == '\n' }) {
            "newText line-break count does not match metadata"
        }
        if (hasCompleteOldText) {
            require(oldTextLength == oldText.length) { "Complete oldText length does not match metadata" }
            require(oldLineBreakCount == oldText.count { it == '\n' }) {
                "Complete oldText line-break count does not match metadata"
            }
            require(oldTextEndsWithLineBreak == oldText.endsWith('\n')) {
                "Complete oldText trailing line-break flag does not match metadata"
            }
        }
    }

    /**
     * 本次编辑对文档总行数的净变化。正数为新增行，负数为删除行。
     */
    val lineDelta: Int
        get() = newLineBreakCount - oldLineBreakCount
}

/**
 * 一次撤销或重做的完整结果。
 *
 * [changes] 按实际应用顺序排列；复合编辑会包含多个增量事件。
 * [cursorOffset] 是该用户操作完成撤销/重做后应恢复的光标位置。
 */
data class UndoRedoResult(
    val changes: List<TextChange>,
    val cursorOffset: Int,
    val selection: TextSelectionSnapshot? = null
)

/**
 * 单条历史记录在执行前后的光标位置。
 *
 * 与 [TextSelectionSnapshot] 分开保存：普通输入、Backspace/Delete 仍可按既有规则合并，
 * 不需要为了准确恢复光标而把每次编辑都包装成 Compound。
 */
data class TextEditCursorSnapshot(
    val cursorBefore: Int,
    val cursorAfter: Int
) {
    init {
        require(cursorBefore >= 0) { "Cursor before edit must not be negative" }
        require(cursorAfter >= 0) { "Cursor after edit must not be negative" }
    }
}

data class TextSelectionSnapshot(
    val anchor: Int,
    val caret: Int
) {
    init {
        require(anchor >= 0) { "Selection anchor must not be negative" }
        require(caret >= 0) { "Selection caret must not be negative" }
    }
}

fun interface TextChangeListener {
    fun onTextChanged(change: TextChange)
}

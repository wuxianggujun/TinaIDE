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
    val fromUndoRedo: Boolean = false
) {
    val oldLineBreakCount: Int = oldText.count { it == '\n' }
    val newLineBreakCount: Int = newText.count { it == '\n' }

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
    val cursorOffset: Int
)

fun interface TextChangeListener {
    fun onTextChanged(change: TextChange)
}

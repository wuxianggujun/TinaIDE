package com.wuxianggujun.tinaide.core.git

import org.eclipse.jgit.diff.EditList

/**
 * 把 [HistogramDiff] 产出的 [EditList] 映射为“新文件逐行改动”表（0-based 文档行号）。
 *
 * 按 Edit 的起止区间形状派生，而不是 `Edit.getType()`：这样 INSERT / DELETE / REPLACE / EMPTY
 * 四类会自然归一，不用逐类分支。
 *
 * - 新区非空：`beginB until endB` 标 [GitLineChangeType.ADDED]（旧区为空）或
 *   [GitLineChangeType.MODIFIED]（旧区也非空，即替换）
 * - 旧区比新区长（纯删除，或“净删除”的替换）：被删掉的行没有新行可挂，
 *   锚定 `endB` 补一个 [GitLineChangeType.DELETED]
 *
 * 删除锚定规则：优先落在下一存活行（`anchor < newLineCount`），文件末尾的删除回退到前一行；
 * `anchor < 0` 跳过（新文件为空）。回退点若落在同一 replace 区域的最后一行，则用 DELETED 覆盖
 * 该行的替换标记——HistogramDiff 会把末尾删除吸收进前一行，净删除才是该行的真实信号。
 *
 * @param newLineCount 当前（新）文本的行数，用于钳制删除锚点
 */
internal fun mapDiffEditsToLineChanges(
    edits: EditList,
    newLineCount: Int
): Map<Int, GitLineChangeType> {
    val result = mutableMapOf<Int, GitLineChangeType>()
    edits.forEach { edit ->
        val oldCount = edit.endA - edit.beginA
        val newCount = edit.endB - edit.beginB
        if (newCount > 0) {
            val type = if (oldCount == 0) GitLineChangeType.ADDED else GitLineChangeType.MODIFIED
            for (line in edit.beginB until edit.endB) result[line] = type
        }
        if (oldCount > newCount) {
            markDeletedAt(result, anchor = edit.endB, newLineCount, replaceRegion = newCount > 0)
        }
    }
    return result
}

private fun markDeletedAt(
    result: MutableMap<Int, GitLineChangeType>,
    anchor: Int,
    newLineCount: Int,
    replaceRegion: Boolean
) {
    if (newLineCount <= 0) return
    val atEof = anchor >= newLineCount
    val target = if (atEof) anchor - 1 else anchor
    if (target < 0) return
    if (atEof && replaceRegion) {
        // 回退点落在同一 replace 区域的最后一行：HistogramDiff 会把末尾删除吸收进前一行，
        // 该行的“净删除”才是真实信号，允许覆盖替换标记。
        result[target] = GitLineChangeType.DELETED
    } else if (result[target] == null) {
        // 不覆盖已有 ADDED/MODIFIED：锚点通常是未改动的上下文行，相邻 hunk 边界除外。
        result[target] = GitLineChangeType.DELETED
    }
}

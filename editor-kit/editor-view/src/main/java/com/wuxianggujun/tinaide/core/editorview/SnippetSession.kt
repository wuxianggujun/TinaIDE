package com.wuxianggujun.tinaide.core.editorview

/**
 * Snippet 会话：跟踪当前活跃的 snippet 展开状态和 tab-stop 跳转。
 *
 * 功能：
 * - Tab 正向跳转（[advance]）
 * - Shift+Tab 反向跳转（[retreat]）
 * - $0（final cursor）作为最后一站，到达后结束会话
 * - 同一 tabstopIndex 出现多处时，所有位置统一视为一个「分组」
 *   （多占位符同步编辑的偏移由外部 EditorState 负责维护）
 */
internal data class SnippetSession(
    val baseOffset: Int,
    val parsed: ParsedSnippet,
    val currentStep: Int = 0
) {
    /**
     * 当前步骤所有同 tabstopIndex 的占位符（同步编辑分组）。
     *
     * 大多数情况只有 1 个，当 snippet 里同一 index 出现多次时返回多个。
     */
    fun currentGroup(): List<SnippetPlaceholderInfo> {
        val (start, endExclusive) = currentGroupBounds() ?: return emptyList()
        return parsed.placeholders.subList(start, endExclusive)
    }

    /** 当前步骤的第一个（主）占位符，用于移动光标 */
    fun currentPlaceholder(): SnippetPlaceholderInfo? = parsed.placeholders.getOrNull(currentStep)

    /**
     * 前进到下一个不同 tabstopIndex 的步骤。
     * 返回新 Session，若已到末尾返回 null（调用方应结束会话）。
     */
    fun advance(): SnippetSession? {
        val placeholders = parsed.placeholders
        val currentIdx = placeholders.getOrNull(currentStep)?.tabstopIndex ?: return null
        // 跳过所有相同 tabstopIndex 的条目，找到下一个不同 index
        var nextStep = currentStep + 1
        while (nextStep < placeholders.size && placeholders[nextStep].tabstopIndex == currentIdx) {
            nextStep++
        }
        if (nextStep >= placeholders.size) return null
        return copy(currentStep = nextStep)
    }

    /**
     * 后退到上一个不同 tabstopIndex 的步骤（Shift+Tab）。
     * 返回新 Session，若已在首步返回 null（不做任何操作）。
     */
    fun retreat(): SnippetSession? {
        val placeholders = parsed.placeholders
        if (currentStep == 0) return null
        val currentIdx = placeholders.getOrNull(currentStep)?.tabstopIndex ?: return null
        // 向前跳过所有相同 tabstopIndex 的条目
        var prevStep = currentStep - 1
        while (prevStep > 0 && placeholders[prevStep].tabstopIndex == currentIdx) {
            prevStep--
        }
        // 再跳过所在分组的所有其他成员，定位到该分组第一个成员
        val targetIdx = placeholders[prevStep].tabstopIndex
        var groupStart = prevStep
        while (groupStart > 0 && placeholders[groupStart - 1].tabstopIndex == targetIdx) {
            groupStart--
        }
        return copy(currentStep = groupStart)
    }

    fun absoluteOffsetOf(placeholder: SnippetPlaceholderInfo): Int = baseOffset + placeholder.offsetInText

    /**
     * 对当前 tabstop 分组应用一次同步编辑。
     *
     * @param relativeStart 相对于主占位符起始位置的编辑开始偏移
     * @param relativeEnd   相对于主占位符起始位置的编辑结束偏移
     * @param replacement   替换文本
     * @return 更新后的会话；若当前分组不支持这次同步编辑则返回 null
     */
    fun applySynchronizedEdit(
        relativeStart: Int,
        relativeEnd: Int,
        replacement: String
    ): SnippetSession? {
        val placeholders = parsed.placeholders
        val current = placeholders.getOrNull(currentStep) ?: return null
        val (groupStart, groupEndExclusive) = currentGroupBounds() ?: return null
        if (relativeStart < 0 || relativeStart > relativeEnd || relativeEnd > current.length) {
            return null
        }

        val fullSelection = relativeStart == 0 && relativeEnd == current.length
        if (!fullSelection) {
            val minGroupLength = (groupStart until groupEndExclusive)
                .minOf { placeholders[it].length }
            if (relativeEnd > minGroupLength) {
                return null
            }
        }

        var cumulativeDelta = 0
        val updatedPlaceholders = placeholders.mapIndexed { index, placeholder ->
            val shiftedOffset = placeholder.offsetInText + cumulativeDelta
            if (index !in groupStart until groupEndExclusive) {
                return@mapIndexed placeholder.copy(offsetInText = shiftedOffset)
            }

            val effectiveEnd = if (fullSelection) placeholder.length else relativeEnd
            val replacedLength = effectiveEnd - relativeStart
            val delta = replacement.length - replacedLength
            val updated = placeholder.copy(
                offsetInText = shiftedOffset,
                length = (placeholder.length + delta).coerceAtLeast(0)
            )
            cumulativeDelta += delta
            updated
        }
        return copy(parsed = parsed.copy(placeholders = updatedPlaceholders))
    }

    /**
     * 当外部在 [baseOffset..baseOffset+parsed.expandedText.length] 范围内插入或删除文本后，
     * 更新所有占位符的 offsetInText 和 length 以保持准确性。
     *
     * - 当前正在编辑的占位符：调整 length（支持 Shift+Tab 退回时正确选区）
     * - 后续占位符：调整 offsetInText
     *
     * @param editOffset 发生编辑的文本绝对偏移（相对于整个 textBuffer）
     * @param delta      插入为正，删除为负
     * @return 更新后的新 Session
     */
    fun adjustOffsets(editOffset: Int, delta: Int): SnippetSession {
        if (delta == 0) return this
        val relativeEditOffset = editOffset - baseOffset
        val updatedPlaceholders = parsed.placeholders.mapIndexed { index, ph ->
            val phEnd = ph.offsetInText + ph.length
            val isCurrentPlaceholder = (index == currentStep)
            val editWithinCurrentPlaceholder = isCurrentPlaceholder &&
                relativeEditOffset >= ph.offsetInText &&
                relativeEditOffset <= phEnd

            when {
                // 编辑发生在当前占位符内部 → 调整长度
                editWithinCurrentPlaceholder -> {
                    ph.copy(length = (ph.length + delta).coerceAtLeast(0))
                }
                // 编辑发生在此占位符之前 → 平移偏移
                ph.offsetInText > relativeEditOffset -> {
                    ph.copy(offsetInText = (ph.offsetInText + delta).coerceAtLeast(0))
                }
                // 在非当前占位符起始位置插入 → 不移动（保持原位）
                ph.offsetInText == relativeEditOffset && delta > 0 -> ph
                else -> ph
            }
        }
        val updatedParsed = parsed.copy(placeholders = updatedPlaceholders)
        return copy(parsed = updatedParsed)
    }

    private fun currentGroupBounds(): Pair<Int, Int>? {
        val placeholders = parsed.placeholders
        val current = placeholders.getOrNull(currentStep) ?: return null
        val idx = current.tabstopIndex
        var start = currentStep
        while (start > 0 && placeholders[start - 1].tabstopIndex == idx) {
            start--
        }
        var endExclusive = currentStep + 1
        while (endExclusive < placeholders.size && placeholders[endExclusive].tabstopIndex == idx) {
            endExclusive++
        }
        return start to endExclusive
    }
}

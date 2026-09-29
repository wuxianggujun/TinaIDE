package com.wuxianggujun.tinaide.core.editorview

/**
 * 基于共享 bracket snapshot 的彩虹括号可见区计算包装器。
 *
 * 热路径统一复用 [EditorBracketSnapshotCache]，避免再维护独立的逐行深度缓存。
 */
internal class RainbowBracketComputer {
    data class BracketInfo(
        val column: Int,
        val depth: Int,
        val isOpen: Boolean
    )

    fun isEnabled(config: EditorConfig, lineCount: Int): Boolean {
        if (!config.rainbowBrackets) return false
        val maxLines = config.rainbowBracketsMaxLines
        return maxLines <= 0 || lineCount <= maxLines
    }

    fun computeVisibleLineBrackets(
        frameContext: EditorRenderFrameContext,
        visibleLines: IntRange,
        config: EditorConfig
    ): Map<Int, List<BracketInfo>> {
        val textBuffer = frameContext.state.textBuffer
        if (!isEnabled(config, textBuffer.lineCount)) return emptyMap()
        if (visibleLines.isEmpty()) return emptyMap()

        return frameContext.bracketSnapshotCache.resolveVisibleLineBrackets(
            textBuffer = textBuffer,
            visibleLines = visibleLines
        )
    }
}

package com.wuxianggujun.tinaide.ui.compose.components

/**
 * 已打开编辑器标签的 Pager 策略。
 *
 * 标签栏直接读 [com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState.activeTabIndex]，
 * 正文却走 HorizontalPager。默认 beyondViewport=0 会把离屏页拆掉，再配合
 * animateScrollToPage，就会出现「标签已经切过去、正文还停在上一份文件」的延迟。
 */
internal object EditorPagerSwitchSupport {
    /**
     * 同时留在组合树里的编辑器页上限（含当前页）。
     * 与 [com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState.CODE_EDITOR_RUNTIME_CACHE_LIMIT]
     * 对齐到一个更保守的窗口，避免一次组合几十份 TinaEditor。
     */
    const val MAX_COMPOSED_PAGES = 8

    fun beyondViewportPageCount(pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return (pageCount - 1).coerceAtMost(MAX_COMPOSED_PAGES - 1)
    }
}

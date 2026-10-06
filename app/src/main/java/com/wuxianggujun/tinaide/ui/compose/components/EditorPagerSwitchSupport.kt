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
     * Pager 的 beyondViewportPageCount 是“当前页每一侧”的页数。
     * 设为 3 时，中间位置最多同时组合 7 页（当前页 + 左右各 3 页）。
     */
    const val MAX_BEYOND_VIEWPORT_PAGES_PER_SIDE = 3

    fun beyondViewportPageCount(pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return (pageCount - 1).coerceAtMost(MAX_BEYOND_VIEWPORT_PAGES_PER_SIDE)
    }
}

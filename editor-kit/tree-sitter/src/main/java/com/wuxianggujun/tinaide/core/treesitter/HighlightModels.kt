package com.wuxianggujun.tinaide.core.treesitter

import com.wuxianggujun.tinaide.core.textengine.TextChange

enum class HighlightType {
    KEYWORD,
    FUNCTION,
    VARIABLE,
    PROPERTY,
    TYPE,
    STRING,
    NUMBER,
    COMMENT,
    OPERATOR,
    PUNCTUATION,
    CONSTANT, // 命名常量：cmake VERSION/SHARED，Kotlin SCREAMING_CASE，C NULL/EOF
    BUILTIN, // 内置标识符：内置函数、内置类型、内置变量（it/this）
    DEFAULT
}

internal fun HighlightType.shouldRenderOverlay(): Boolean = this != HighlightType.DEFAULT

data class HighlightSpan(
    val start: Int,
    val end: Int,
    val type: HighlightType,
    val priority: Int = DEFAULT_HIGHLIGHT_PRIORITY
)

const val DEFAULT_HIGHLIGHT_PRIORITY: Int = 100

interface SyntaxHighlighter {
    fun highlight(text: String, visibleRange: IntRange): List<HighlightSpan>
    fun openDocument(text: String) {}

    /**
     * 同步打开文档：调用返回时高亮快照应已就绪，首帧渲染不再闪默认色。
     * 调用方必须在 IO/Default 线程调用；默认实现回退到异步 openDocument。
     */
    fun openDocumentBlocking(text: String) {
        openDocument(text)
    }
    fun applyTextChange(change: TextChange) {}
    fun applyTextChange(change: TextChange, newText: String) {
        applyTextChange(change)
    }
    /** Cache-only read. A miss schedules background work and notifies [setOnStateUpdated] when ready. */
    fun getLineSegments(line: Int): List<HighlightLineSegment> = emptyList()
    fun setOnStateUpdated(callback: (() -> Unit)?) {}

    /**
     * 告知 highlighter 当前视口的第一可见行。用于让 bulk prewarm 按视口优先顺序填充缓存，
     * 避免超大文件下"滚到后半段还没着色"。默认实现忽略提示。
     */
    fun setViewportHint(firstVisibleLine: Int) {}

    fun dispose() {}
}

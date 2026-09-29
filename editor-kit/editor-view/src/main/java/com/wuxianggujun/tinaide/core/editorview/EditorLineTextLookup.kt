package com.wuxianggujun.tinaide.core.editorview

import java.util.LinkedHashMap

/**
 * 为非主渲染链路提供轻量行文本缓存。
 *
 * 渲染主链仍然走 TextRenderer 的专用缓存；这里主要服务于 overlay、popup 和拖拽坐标解析，
 * 让这些路径也尽量不要分散地直接读取 textBuffer。
 */
internal class EditorLineTextLookup(
    private val state: EditorState,
    private val maxCacheSize: Int = 64
) {
    private val lineCache = LinkedHashMap<Int, String>(16, 0.75f, true)
    private var cacheVersion = Long.MIN_VALUE

    fun lineText(line: Int): String {
        val textBuffer = state.textBuffer
        val version = textBuffer.version
        if (cacheVersion != version) {
            lineCache.clear()
            cacheVersion = version
        }

        val cached = lineCache[line]
        if (cached != null) return cached

        val text = textBuffer.getLine(line)
        lineCache[line] = text
        if (lineCache.size > maxCacheSize) {
            val eldestKey = lineCache.entries.firstOrNull()?.key
            if (eldestKey != null) {
                lineCache.remove(eldestKey)
            }
        }
        return text
    }
}

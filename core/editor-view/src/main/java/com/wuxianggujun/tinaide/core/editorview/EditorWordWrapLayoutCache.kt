package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextChange
import java.util.LinkedHashMap

/**
 * 软换行（wordWrap）布局缓存：为每个文档行缓存“视觉行分段”的起始列数组。
 *
 * 约定：
 * - starts[0] 必为 0
 * - starts 递增且 < lineLength
 * - 某段的 endColumn = 下一段 startColumn；最后一段 endColumn = lineLength
 *
 * 说明：
 * - 分段依据“视觉列数”而非像素：每个普通字符记 1 列，Tab 按 tabSize 展开为若干列，
 *   Inlay Hint 按 [EditorInlayHintColumnLayout] 的保守列宽估算参与换行。
 * - 该策略与编辑器现有 Tab stop / 视觉列宽计算保持一致，且不依赖 Paint（避免在 State 层引入渲染依赖）。
 */
internal class EditorWordWrapLayoutCache(
    private val maxEntries: Int = 512,
    private val maxTotalChars: Int = 220_000
) {
    internal data class WrapLayout(
        val length: Int,
        val starts: IntArray
    ) {
        val segmentCount: Int
            get() = starts.size.coerceAtLeast(1)

        fun startColumnForSegment(segmentIndex: Int): Int {
            if (starts.isEmpty()) return 0
            return starts[segmentIndex.coerceIn(0, starts.lastIndex)]
        }

        fun endColumnForSegment(segmentIndex: Int): Int {
            if (starts.isEmpty()) return length
            val safe = segmentIndex.coerceIn(0, starts.lastIndex)
            val nextIndex = safe + 1
            return if (nextIndex <= starts.lastIndex) {
                starts[nextIndex].coerceIn(starts[safe], length)
            } else {
                length
            }
        }

        fun segmentIndexForColumn(column: Int): Int {
            if (starts.isEmpty()) return 0
            val safeColumn = column.coerceIn(0, length)
            // starts 是递增数组，查找最后一个 <= column 的索引
            var low = 0
            var high = starts.size - 1
            var result = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                val value = starts[mid]
                if (value <= safeColumn) {
                    result = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return result.coerceIn(0, starts.lastIndex)
        }
    }

    private data class Entry(
        val lineText: String,
        val inlayHints: List<EditorInlayHint>,
        var starts: IntArray
    )

    private val lru = LinkedHashMap<Int, Entry>(64, 0.75f, true)
    private var totalChars: Int = 0
    private var cacheVersion: Long = Long.MIN_VALUE
    private var cachedWrapColumns: Int = Int.MIN_VALUE
    private var cachedTabSize: Int = Int.MIN_VALUE

    // TextChange listener 可在非主线程分发；accessOrder=true 的缓存命中也会修改链表，
    // 因此读写必须共用同一把锁，避免与主线程布局查询并发时出现 CME 或脏读。
    private val lock = Any()

    fun invalidateAll() {
        synchronized(lock) {
            invalidateAllInternal()
        }
    }

    fun applyTextChange(change: TextChange, currentVersion: Long) {
        synchronized(lock) {
            val previousVersion = cacheVersion
            if (previousVersion != Long.MIN_VALUE && previousVersion + 1L != currentVersion) {
                // 版本不连续：作为安全网直接清空，避免错位。
                invalidateAllInternal()
                cacheVersion = currentVersion
                return
            }
            // 对齐其它缓存策略：只失效受影响的行，并对后续行做 index shift。
            val startLine = change.startLine.coerceAtLeast(0)
            val lineDelta = change.lineDelta
            val oldChangedEndLine = change.endLine.coerceAtLeast(startLine)
            val shiftFromLine = (change.endLine + 1).coerceAtLeast(0)
            val newChangedEndLine = maxOf(startLine, change.endLine + lineDelta)

            invalidateRangeInternal(startLine, oldChangedEndLine)
            shiftCacheInternal(shiftFromLine, lineDelta)
            invalidateRangeInternal(startLine, newChangedEndLine)

            cacheVersion = currentVersion
        }
    }

    fun getWrapLayout(
        line: Int,
        lineText: String,
        textVersion: Long,
        wrapColumns: Int,
        tabSize: Int,
        inlayHints: List<EditorInlayHint> = emptyList(),
    ): WrapLayout {
        synchronized(lock) {
            ensureSignature(textVersion, wrapColumns, tabSize)
            val entry = lru[line]
            if (entry != null && entry.lineText == lineText && entry.inlayHints == inlayHints) {
                return WrapLayout(length = entry.lineText.length, starts = entry.starts)
            }

            val built = buildStarts(lineText, wrapColumns, tabSize, inlayHints)
            putEntry(line, lineText = lineText, inlayHints = inlayHints, starts = built)
            return WrapLayout(length = lineText.length, starts = built)
        }
    }

    private fun ensureSignature(textVersion: Long, wrapColumns: Int, tabSize: Int) {
        if (cachedWrapColumns != wrapColumns || cachedTabSize != tabSize) {
            invalidateAllInternal()
            cachedWrapColumns = wrapColumns
            cachedTabSize = tabSize
        }
        if (cacheVersion == Long.MIN_VALUE) {
            cacheVersion = textVersion
            return
        }
        if (cacheVersion != textVersion) {
            // 理论上 applyTextChange 会维护 cacheVersion；此处属于“安全网”。
            invalidateAllInternal()
            cacheVersion = textVersion
        }
    }

    private fun invalidateAllInternal() {
        lru.clear()
        totalChars = 0
        cacheVersion = Long.MIN_VALUE
    }

    private fun buildStarts(
        lineText: String,
        wrapColumns: Int,
        tabSize: Int,
        inlayHints: List<EditorInlayHint>,
    ): IntArray = EditorInlayHintColumnLayout.findWrapSegmentStarts(
        lineText = lineText,
        wrapColumns = wrapColumns,
        tabSize = tabSize,
        hints = inlayHints,
    )

    private fun invalidateRangeInternal(startLine: Int, endLine: Int) {
        if (startLine > endLine) return
        for (line in startLine..endLine) {
            totalChars -= lru.remove(line)?.lineText?.length ?: 0
        }
    }

    private fun shiftCacheInternal(fromLine: Int, delta: Int) {
        if (delta == 0) return
        val affected = lru.entries
            .filter { it.key >= fromLine }
            .map { it.key to it.value }
        affected.forEach { (line, _) -> lru.remove(line) }
        affected.forEach { (line, entry) ->
            val target = line + delta
            if (target >= 0) {
                totalChars -= lru.put(target, entry)?.lineText?.length ?: 0
            } else {
                totalChars -= entry.lineText.length
            }
        }
    }

    private fun putEntry(
        line: Int,
        lineText: String,
        inlayHints: List<EditorInlayHint>,
        starts: IntArray,
    ) {
        totalChars -= lru.put(
            line,
            Entry(lineText = lineText, inlayHints = inlayHints, starts = starts),
        )?.lineText?.length ?: 0
        totalChars += lineText.length
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        while (lru.size > maxEntries || totalChars > maxTotalChars) {
            val it = lru.entries.iterator()
            if (!it.hasNext()) return
            totalChars -= it.next().value.lineText.length
            it.remove()
        }
    }
}

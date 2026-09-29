package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.LineWhitespaceInfo
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import com.wuxianggujun.tinaide.core.textengine.WordBounds

/**
 * 共享行级纯文本扫描缓存，避免同一帧内多个 renderer 对同一行重复做 tab/空白扫描。
 */
internal class EditorTextScanCache(
    private val maxEntries: Int = 512
) {
    private data class Entry(
        var length: Int,
        var tabColumns: IntArray? = null,
        var visualColumnPrefixTabSize: Int = -1,
        var visualColumnPrefix: IntArray? = null,
        var boundaryWhitespaceMarkers: IntArray? = null,
        var allWhitespaceMarkers: IntArray? = null,
        var whitespaceInfoTabSize: Int = -1,
        var whitespaceInfo: LineWhitespaceInfo? = null,
        var wordBoundsColumn: Int = Int.MIN_VALUE,
        var wordBoundsResolved: Boolean = false,
        var wordBounds: WordBounds? = null,
        var wholeWordMatchWord: String? = null,
        var wholeWordMatches: IntArray? = null
    )

    private val lru = LinkedHashMap<Int, Entry>(64, 0.75f, true)
    private var cacheVersion: Long = Long.MIN_VALUE

    fun invalidateAll() {
        lru.clear()
        cacheVersion = Long.MIN_VALUE
    }

    fun applyTextChange(change: TextChange, currentVersion: Long) {
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

    fun cacheSize(): Int = lru.size

    fun getTabColumns(
        line: Int,
        lineText: String,
        textVersion: Long
    ): IntArray {
        val entry = getEntry(line, lineText, textVersion)
        val cached = entry.tabColumns
        if (cached != null) return cached

        return TextScanKernel.findTabColumns(lineText).also { entry.tabColumns = it }
    }

    fun getVisualColumnPrefix(
        line: Int,
        lineText: String,
        textVersion: Long,
        tabSize: Int
    ): IntArray {
        val entry = getEntry(line, lineText, textVersion)
        val safeTabSize = tabSize.coerceAtLeast(1)
        val cached = entry.visualColumnPrefix
        if (cached != null && entry.visualColumnPrefixTabSize == safeTabSize) {
            return cached
        }

        return TextScanKernel.buildVisualColumnPrefix(lineText, safeTabSize).also {
            entry.visualColumnPrefixTabSize = safeTabSize
            entry.visualColumnPrefix = it
        }
    }

    fun getWhitespaceMarkers(
        line: Int,
        lineText: String,
        textVersion: Long,
        boundaryOnly: Boolean
    ): IntArray {
        val entry = getEntry(line, lineText, textVersion)
        val cached = if (boundaryOnly) {
            entry.boundaryWhitespaceMarkers
        } else {
            entry.allWhitespaceMarkers
        }
        if (cached != null) return cached

        val computed = TextScanKernel.findWhitespaceMarkers(
            lineText = lineText,
            boundaryOnly = boundaryOnly
        )
        if (boundaryOnly) {
            entry.boundaryWhitespaceMarkers = computed
        } else {
            entry.allWhitespaceMarkers = computed
        }
        return computed
    }

    fun getWhitespaceInfo(
        line: Int,
        lineText: String,
        textVersion: Long,
        tabSize: Int
    ): LineWhitespaceInfo {
        val entry = getEntry(line, lineText, textVersion)
        val safeTabSize = tabSize.coerceAtLeast(1)
        val cached = entry.whitespaceInfo
        if (cached != null && entry.whitespaceInfoTabSize == safeTabSize) {
            return cached
        }

        return TextScanKernel.scanLineWhitespace(lineText, safeTabSize).also {
            entry.whitespaceInfoTabSize = safeTabSize
            entry.whitespaceInfo = it
        }
    }

    fun getWordBounds(
        line: Int,
        lineText: String,
        textVersion: Long,
        column: Int
    ): WordBounds? {
        val entry = getEntry(line, lineText, textVersion)
        val safeColumn = column.coerceIn(0, lineText.length)
        if (entry.wordBoundsResolved && entry.wordBoundsColumn == safeColumn) {
            return entry.wordBounds
        }

        return TextScanKernel.findWordBounds(lineText, safeColumn).also {
            entry.wordBoundsColumn = safeColumn
            entry.wordBoundsResolved = true
            entry.wordBounds = it
        }
    }

    fun getWholeWordMatches(
        line: Int,
        lineText: String,
        textVersion: Long,
        word: String
    ): IntArray {
        val entry = getEntry(line, lineText, textVersion)
        if (entry.wholeWordMatchWord == word && entry.wholeWordMatches != null) {
            return entry.wholeWordMatches!!
        }

        return TextScanKernel.findWholeWordMatches(lineText, word).also {
            entry.wholeWordMatchWord = word
            entry.wholeWordMatches = it
        }
    }

    private fun getEntry(line: Int, lineText: String, textVersion: Long): Entry {
        ensureVersion(textVersion)
        val entry = lru[line]
        if (entry != null && entry.length == lineText.length) {
            return entry
        }

        val fresh = Entry(length = lineText.length)
        lru[line] = fresh
        trimToSize()
        return fresh
    }

    private fun ensureVersion(textVersion: Long) {
        if (cacheVersion == Long.MIN_VALUE) {
            cacheVersion = textVersion
            return
        }
        if (cacheVersion != textVersion) {
            invalidateAll()
            cacheVersion = textVersion
        }
    }

    private fun invalidateRangeInternal(startLine: Int, endLine: Int) {
        if (startLine > endLine) return
        val iterator = lru.entries.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next().key
            if (key in startLine..endLine) {
                iterator.remove()
            }
        }
    }

    private fun shiftCacheInternal(shiftFromLine: Int, lineDelta: Int) {
        if (lineDelta == 0) return

        val moved = ArrayList<Pair<Int, Entry>>()
        val iterator = lru.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key >= shiftFromLine) {
                moved += entry.key + lineDelta to entry.value
                iterator.remove()
            }
        }

        moved.sortBy { it.first }
        for ((newLine, entry) in moved) {
            if (newLine >= 0) {
                lru[newLine] = entry
            }
        }
        trimToSize()
    }

    private fun trimToSize() {
        while (lru.size > maxEntries) {
            val iterator = lru.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }
}

package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange

/**
 * 视觉行映射器（folding + wordWrap）。
 *
 * 从 [EditorState] 抽出的内聚块：在“折叠后的可见文档行列表”（由折叠层 [Host.lineMap] 提供）
 * 之上，再叠加 wordWrap 的逐行分段，得到最终的视觉行映射 [VisualLineMap]，并维护：
 * - 视觉行映射缓存（[VisualLineMap] + epoch）；
 * - 按文档行的 wrap segmentCount 缓存（[docSegmentCounts]），支持文本增量更新。
 *
 * 该块只依赖宿主的少量只读量（文本、字宽、视口宽、wordWrap/tab/折叠开关、冻结列数、
 * 折叠版本号、wrap 布局缓存）以及折叠层产出的 [Host.lineMap]，因此通过 [Host] 接口注入，
 * 组合而非继承（D 依赖倒置）。
 *
 * 行为与原内联实现严格一致，**包括以下两个刻意保留的现状**：
 * - epoch 在 `wordWrap` 关闭时把 textVersion 归零（`effectiveTextVersion`），使非 wordWrap 下
 *   纯文本编辑不重建视觉行映射；该失效口径与原实现逐行对应。
 * - [docSegmentCounts] 的失效/平移/重算时机与原实现逐行对应（[applyTextChangeToDocSegmentCounts]
 *   必须在宿主 wordWrapLayoutCache.applyTextChange 之后调用）。
 */
internal class EditorVisualLineMapper(
    private val host: Host
) {
    /**
     * 宿主只读视图。所有方法/属性都读取宿主当前状态，不回写。
     */
    internal interface Host {
        val textBuffer: TextBuffer
        val charWidthPx: Float
        val viewportWidthPx: Float
        val wordWrapEnabled: Boolean
        val tabSize: Int
        val codeFoldingEnabled: Boolean
        val frozenWordWrapColumns: Int?
        val foldRegionsDocumentVersion: Long
        val foldDataVersion: Int
        val wordWrapLayoutCache: EditorWordWrapLayoutCache

        /** 折叠层（[EditorFoldingManager]）产出的可见文档行映射。 */
        fun lineMap(): EditorFoldingManager.LineMap
    }

    internal data class VisualLineMap(
        val docLineCount: Int,
        /**
         * folding 后的“可见文档行列表”（索引=折叠后的可见行序号，值=docLine）。
         *
         * 注意：这不是最终的 visualLine（因为每个 docLine 可能会被 wordWrap 拆成多段）。
         */
        val visibleDocLines: IntArray,
        /** 每个 visibleDocLine 对应的“首个视觉行”索引（按 wordWrap 展开后）。 */
        val firstVisualLineByVisibleIndex: IntArray,
        /** 每个 visibleDocLine 对应的“视觉行段数”（>=1）。 */
        val visualLineCountByVisibleIndex: IntArray,
        /** 全部视觉行总数（folding + wordWrap 后）。 */
        val visualLineCount: Int,
        val wordWrapEnabled: Boolean,
        val wrapColumns: Int
    ) {
        val visibleDocLineCount: Int
            get() = visibleDocLines.size
    }

    private val textBuffer: TextBuffer get() = host.textBuffer

    private var visualLineMapCache: VisualLineMap? = null
    private var visualLineMapCacheEpoch: Long = Long.MIN_VALUE

    // 按文档行缓存 wrap segmentCount（wordWrap 下每行视觉行数）。
    // 替代原本每次 visualLineMap() 重算时对全部可见文档行做 getLine()+getWrapLayout() 的 O(N) 扫描；
    // 文本变化时只重算编辑窗内的行，其余行直接从数组读。
    private var docSegmentCounts: IntArray? = null
    private var docSegmentCountsWrapColumns: Int = Int.MIN_VALUE
    private var docSegmentCountsTabSize: Int = Int.MIN_VALUE
    private var docSegmentCountsVersion: Long = Long.MIN_VALUE

    private var visualLineMapEpochCounter: Long = 0L
    private var vlmEpochTextVersion: Long = Long.MIN_VALUE
    private var vlmEpochFoldDataVersion: Int = Int.MIN_VALUE
    private var vlmEpochFoldingEnabled: Boolean = false
    private var vlmEpochWordWrap: Boolean = false
    private var vlmEpochWrapColumns: Int = Int.MIN_VALUE
    private var vlmEpochTabSize: Int = Int.MIN_VALUE
    private var vlmEpochDocLineCount: Int = Int.MIN_VALUE

    private fun visualLineMapEpoch(
        textVersion: Long,
        foldDataVersion: Int,
        foldingEnabled: Boolean,
        wordWrap: Boolean,
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int
    ): Long {
        val effectiveTextVersion = if (wordWrap) textVersion else 0L
        if (effectiveTextVersion == vlmEpochTextVersion &&
            foldDataVersion == vlmEpochFoldDataVersion &&
            foldingEnabled == vlmEpochFoldingEnabled &&
            wordWrap == vlmEpochWordWrap &&
            wrapColumns == vlmEpochWrapColumns &&
            tabSize == vlmEpochTabSize &&
            docLineCount == vlmEpochDocLineCount
        ) {
            return visualLineMapEpochCounter
        }
        vlmEpochTextVersion = effectiveTextVersion
        vlmEpochFoldDataVersion = foldDataVersion
        vlmEpochFoldingEnabled = foldingEnabled
        vlmEpochWordWrap = wordWrap
        vlmEpochWrapColumns = wrapColumns
        vlmEpochTabSize = tabSize
        vlmEpochDocLineCount = docLineCount
        return ++visualLineMapEpochCounter
    }

    /**
     * 使视觉行映射缓存失效。等价于原 [EditorState.onConfigChanged] 内的 `visualLineMapCache = null`。
     */
    fun invalidateVisualLineMapCache() {
        visualLineMapCache = null
    }

    fun resolveVisibleIndexForDocLine(docLine: Int): Int {
        val map = host.lineMap()
        if (map.docLineCount <= 0) return -1
        val safeLine = docLine.coerceIn(0, map.docLineCount - 1)
        val direct = map.docToVisualLine[safeLine]
        if (direct >= 0) return direct
        val ownerStart = map.hiddenOwnerStartLine.getOrNull(safeLine) ?: -1
        if (ownerStart >= 0 && ownerStart < map.docLineCount) {
            val ownerVisual = map.docToVisualLine[ownerStart]
            if (ownerVisual >= 0) return ownerVisual
        }
        return -1
    }

    fun resolveVisibleIndexForVisualLine(map: VisualLineMap, visualLine: Int): Int {
        val starts = map.firstVisualLineByVisibleIndex
        if (starts.isEmpty()) return 0
        val target = visualLine.coerceAtLeast(0)
        // 查找最后一个 start <= target 的索引
        var low = 0
        var high = starts.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = starts[mid]
            if (value <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, starts.lastIndex)
    }

    fun visualLineMap(): VisualLineMap {
        val currentVersion = textBuffer.version
        val foldingEnabled = host.codeFoldingEnabled && host.foldRegionsDocumentVersion == currentVersion
        val wordWrapEnabled = host.wordWrapEnabled
        val wrapColumns = if (wordWrapEnabled) {
            host.frozenWordWrapColumns ?: run {
                val safeCharWidth = host.charWidthPx.coerceAtLeast(1f)
                val safeViewportWidth = host.viewportWidthPx.coerceAtLeast(1f)
                (safeViewportWidth / safeCharWidth).toInt().coerceAtLeast(1)
            }
        } else {
            0
        }
        val tabSize = host.tabSize
        val docLineCount = textBuffer.lineCount.coerceAtLeast(0)

        val epoch = visualLineMapEpoch(
            currentVersion,
            host.foldDataVersion,
            foldingEnabled,
            wordWrapEnabled,
            wrapColumns,
            tabSize,
            docLineCount
        )
        val cached = visualLineMapCache
        if (cached != null && epoch == visualLineMapCacheEpoch) {
            return cached
        }

        val base = host.lineMap()
        val visibleDocLines = base.visualToDocLine
        val visibleCount = visibleDocLines.size
        if (visibleCount <= 0 || docLineCount <= 0) {
            val built = VisualLineMap(
                docLineCount = docLineCount,
                visibleDocLines = visibleDocLines,
                firstVisualLineByVisibleIndex = IntArray(0),
                visualLineCountByVisibleIndex = IntArray(0),
                visualLineCount = 0,
                wordWrapEnabled = false,
                wrapColumns = wrapColumns
            )
            visualLineMapCache = built
            visualLineMapCacheEpoch = epoch
            return built
        }

        val firstVisual = IntArray(visibleCount)
        val visualCounts = IntArray(visibleCount)
        var totalVisualLines = 0

        if (!wordWrapEnabled || wrapColumns <= 0) {
            for (i in 0 until visibleCount) {
                firstVisual[i] = i
                visualCounts[i] = 1
            }
            totalVisualLines = visibleCount
        } else {
            val safeWrapColumns = wrapColumns.coerceAtLeast(1)
            val counts = ensureDocSegmentCounts(
                wrapColumns = safeWrapColumns,
                tabSize = tabSize,
                docLineCount = docLineCount,
                textVersion = currentVersion
            )
            for (i in 0 until visibleCount) {
                firstVisual[i] = totalVisualLines
                val docLine = visibleDocLines[i].coerceIn(0, docLineCount - 1)
                val segments = if (docLine < counts.size) counts[docLine] else 1
                visualCounts[i] = segments
                totalVisualLines += segments
            }
        }

        val built = VisualLineMap(
            docLineCount = docLineCount,
            visibleDocLines = visibleDocLines,
            firstVisualLineByVisibleIndex = firstVisual,
            visualLineCountByVisibleIndex = visualCounts,
            visualLineCount = totalVisualLines.coerceAtLeast(0),
            wordWrapEnabled = wordWrapEnabled && wrapColumns > 0,
            wrapColumns = wrapColumns
        )
        visualLineMapCache = built
        visualLineMapCacheEpoch = epoch
        return built
    }

    /**
     * 返回按文档行索引的 wrap segmentCount。
     * 命中缓存直接返回；否则按 wrap/tabSize/docLineCount/textVersion 任一变化做全量重建。
 * 文本增量变化走 [applyTextChangeToDocSegmentCounts]（在 [EditorState.applyTextBufferChange] 内调用）。
     */
    private fun ensureDocSegmentCounts(
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int,
        textVersion: Long
    ): IntArray {
        val cached = docSegmentCounts
        if (cached != null &&
            docSegmentCountsWrapColumns == wrapColumns &&
            docSegmentCountsTabSize == tabSize &&
            docSegmentCountsVersion == textVersion &&
            cached.size == docLineCount
        ) {
            return cached
        }
        val arr = IntArray(docLineCount)
        for (i in 0 until docLineCount) {
            arr[i] = computeSegmentCountForLine(i, wrapColumns, tabSize, textVersion)
        }
        docSegmentCounts = arr
        docSegmentCountsWrapColumns = wrapColumns
        docSegmentCountsTabSize = tabSize
        docSegmentCountsVersion = textVersion
        return arr
    }

    private fun computeSegmentCountForLine(
        docLine: Int,
        wrapColumns: Int,
        tabSize: Int,
        textVersion: Long
    ): Int {
        val lineText = textBuffer.getLine(docLine)
        return host.wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textVersion,
            wrapColumns = wrapColumns,
            tabSize = tabSize
        ).segmentCount
    }

    /**
     * 将 [TextChange] 增量应用到 [docSegmentCounts]：
     * - head [0, startLine) 原样拷贝；
     * - 编辑窗 [startLine, newChangedEndLine] 重算（调用 wordWrapLayoutCache，此时它已 applyTextChange 过）；
     * - tail (oldEnd, oldDocCount) 按 lineDelta 平移到 (newEnd, newDocCount)。
     *
     * 调用方必须在 [Host.wordWrapLayoutCache] 的 applyTextChange 之后调，保证编辑窗内各行的 wrap layout
     * 可以正确重建（旧 cache entry 已被移除）。
     */
    fun applyTextChangeToDocSegmentCounts(change: TextChange, newVersion: Long) {
        val cached = docSegmentCounts ?: return
        val wrapColumns = docSegmentCountsWrapColumns
        val tabSize = docSegmentCountsTabSize
        if (wrapColumns <= 0) {
            // 签名已经不再有效（wrapColumns 还没被初始化成合法值）。
            docSegmentCounts = null
            return
        }
        val oldDocCount = cached.size
        val startLine = change.startLine.coerceIn(0, oldDocCount)
        val oldEnd = change.endLine.coerceIn(startLine, oldDocCount - 1)
        val delta = change.lineDelta
        val newDocCount = oldDocCount + delta
        if (newDocCount <= 0) {
            docSegmentCounts = null
            return
        }
        val newEnd = (oldEnd + delta).coerceIn(startLine, newDocCount - 1)
        val arr = IntArray(newDocCount)
        // head: [0, startLine)
        val headLen = startLine.coerceAtMost(oldDocCount)
        if (headLen > 0) System.arraycopy(cached, 0, arr, 0, headLen)
        // tail: old [oldEnd+1, oldDocCount) → new [newEnd+1, newDocCount)
        val tailSrc = (oldEnd + 1).coerceAtMost(oldDocCount)
        val tailDst = (newEnd + 1).coerceIn(0, newDocCount)
        val tailLen = minOf(oldDocCount - tailSrc, newDocCount - tailDst).coerceAtLeast(0)
        if (tailLen > 0) System.arraycopy(cached, tailSrc, arr, tailDst, tailLen)
        // edited window: [startLine, newEnd] 重算
        var i = startLine
        val limit = newEnd.coerceAtMost(newDocCount - 1)
        while (i <= limit) {
            arr[i] = computeSegmentCountForLine(i, wrapColumns, tabSize, newVersion)
            i++
        }
        docSegmentCounts = arr
        docSegmentCountsVersion = newVersion
    }
}

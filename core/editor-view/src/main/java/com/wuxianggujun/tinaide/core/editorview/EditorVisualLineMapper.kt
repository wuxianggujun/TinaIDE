package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

/**
 * 视觉行映射器（folding + wordWrap）。
 *
 * 从 [EditorState] 抽出的内聚块：在“折叠后的可见文档行列表”（由折叠层 [Host.lineMap] 提供）
 * 之上，再叠加 wordWrap 的逐行分段，得到最终的视觉行映射 [VisualLineMap]，并维护：
 * - 视觉行映射缓存（[VisualLineMap] + epoch）；
 * - 按文档行的 wrap segmentCount 缓存（[docSegmentCounts]），支持文本增量更新。
 *
 * 该块只依赖宿主的少量只读量（文本、字宽、视口宽、wordWrap/tab/折叠开关、冻结列数、
 * 折叠版本号、Inlay Hint 版本与数据）以及折叠层产出的 [Host.lineMap]，因此通过 [Host] 接口注入，
 * 组合而非继承（D 依赖倒置）。
 *
 * 未包含 Inlay Hint 时对外映射行为与原内联实现一致，并保留以下缓存口径：
 * - epoch 在 `wordWrap` 关闭时把 textVersion 归零（`effectiveTextVersion`），使非 wordWrap 下
 *   纯文本编辑不重建视觉行映射；该失效口径与原实现逐行对应。
 * - [docSegmentCounts] 只缓存计数，不创建完整的 wrap starts 数组；参数变化和增量编辑通过
 *   generation 精确失效，实际可见时才重算。
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
        val inlayHintsVersion: Long
            get() = 0L

        val inlayHintsByLine: Map<Int, List<EditorInlayHint>>
            get() = emptyMap()

        /** 折叠层（[EditorFoldingManager]）产出的可见文档行映射。 */
        fun lineMap(): EditorFoldingManager.LineMap
    }

    // Live layout index, not an immutable snapshot. Only the mapper updates its row counts.
    internal class VisualLineMap(
        val docLineCount: Int,
        /**
         * folding 后的“可见文档行列表”（索引=折叠后的可见行序号，值=docLine）。
         *
         * 注意：这不是最终的 visualLine（因为每个 docLine 可能会被 wordWrap 拆成多段）。
         */
        val visibleDocLines: IntArray,
        private val wrappedLineIndex: EditorVisualLineIndex?,
        val wordWrapEnabled: Boolean,
        val wrapColumns: Int
    ) {
        val visibleDocLineCount: Int
            get() = visibleDocLines.size

        val visualLineCount: Int
            get() = wrappedLineIndex?.total ?: visibleDocLineCount

        fun firstVisualLineAt(visibleIndex: Int): Int =
            wrappedLineIndex?.firstVisualLineAt(visibleIndex) ?: visibleIndex

        fun segmentCountAt(visibleIndex: Int): Int = wrappedLineIndex?.countAt(visibleIndex) ?: 1

        fun visibleIndexForVisualLine(visualLine: Int): Int =
            wrappedLineIndex?.visibleIndexForVisualLine(visualLine)
                ?: visualLine.coerceIn(0, (visibleDocLineCount - 1).coerceAtLeast(0))

        fun updateSegmentCount(visibleIndex: Int, count: Int) {
            checkNotNull(wrappedLineIndex).update(visibleIndex, count)
        }
    }

    private val textBuffer: TextBuffer get() = host.textBuffer

    private var visualLineMapCache: VisualLineMap? = null
    private var visualLineMapCacheEpoch: Long = Long.MIN_VALUE

    // 按文档行缓存 wrap segmentCount（wordWrap 下每行视觉行数）。generation 不匹配的行按需重算：
    // wrap 参数变化不再预扫全文，折叠隐藏行也不会为一次映射被无意义地 materialize。
    private var docSegmentCounts: IntArray? = null
    private var docSegmentCountGenerations: IntArray? = null
    private var docSegmentCountGeneration: Int = 1
    private var docSegmentCountsWrapColumns: Int = Int.MIN_VALUE
    private var docSegmentCountsTabSize: Int = Int.MIN_VALUE
    private var docSegmentCountsVersion: Long = Long.MIN_VALUE
    private var docSegmentCountsInlayHintsVersion: Long = Long.MIN_VALUE
    private var cachedInlayHintsByLine: Map<Int, List<EditorInlayHint>> = emptyMap()
    private val pendingSegmentCountLines = HashSet<Int>()
    private var visualLineMapCountGeneration: Int = Int.MIN_VALUE

    private var visualLineMapEpochCounter: Long = 0L
    private var vlmEpochTextVersion: Long = Long.MIN_VALUE
    private var vlmEpochFoldDataVersion: Int = Int.MIN_VALUE
    private var vlmEpochFoldingEnabled: Boolean = false
    private var vlmEpochWordWrap: Boolean = false
    private var vlmEpochWrapColumns: Int = Int.MIN_VALUE
    private var vlmEpochTabSize: Int = Int.MIN_VALUE
    private var vlmEpochDocLineCount: Int = Int.MIN_VALUE
    private var vlmEpochInlayHintsVersion: Long = Long.MIN_VALUE

    private fun visualLineMapEpoch(
        textVersion: Long,
        foldDataVersion: Int,
        foldingEnabled: Boolean,
        wordWrap: Boolean,
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int,
        inlayHintsVersion: Long,
    ): Long {
        val effectiveTextVersion = if (wordWrap) textVersion else 0L
        val effectiveInlayHintsVersion = if (wordWrap) inlayHintsVersion else 0L
        if (effectiveTextVersion == vlmEpochTextVersion &&
            foldDataVersion == vlmEpochFoldDataVersion &&
            foldingEnabled == vlmEpochFoldingEnabled &&
            wordWrap == vlmEpochWordWrap &&
            wrapColumns == vlmEpochWrapColumns &&
            tabSize == vlmEpochTabSize &&
            docLineCount == vlmEpochDocLineCount &&
            effectiveInlayHintsVersion == vlmEpochInlayHintsVersion
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
        vlmEpochInlayHintsVersion = effectiveInlayHintsVersion
        return ++visualLineMapEpochCounter
    }

    /**
     * 使视觉行映射缓存失效。等价于原 [EditorState.onConfigChanged] 内的 `visualLineMapCache = null`。
     */
    @Synchronized
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

    fun resolveVisibleIndexForVisualLine(map: VisualLineMap, visualLine: Int): Int =
        map.visibleIndexForVisualLine(visualLine)

    // Buffer notifications may arrive off the UI thread; guard the dirty set and count storage together.
    @Synchronized
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
            docLineCount,
            host.inlayHintsVersion,
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
                wrappedLineIndex = null,
                wordWrapEnabled = false,
                wrapColumns = wrapColumns
            )
            visualLineMapCache = built
            visualLineMapCacheEpoch = epoch
            return built
        }

        val wrappedLineIndex = if (wordWrapEnabled && wrapColumns > 0) {
            val safeWrapColumns = wrapColumns.coerceAtLeast(1)
            ensureDocSegmentCountStorage(
                wrapColumns = safeWrapColumns,
                tabSize = tabSize,
                docLineCount = docLineCount,
                textVersion = currentVersion,
                inlayHintsVersion = host.inlayHintsVersion,
            )
            if (cached != null && cached.wordWrapEnabled &&
                cached.docLineCount == docLineCount &&
                cached.visibleDocLines === visibleDocLines &&
                cached === visualLineMapCache &&
                visualLineMapCountGeneration == docSegmentCountGeneration
            ) {
                for (docLine in pendingSegmentCountLines) {
                    val visibleIndex = base.docToVisualLine[docLine]
                    if (visibleIndex < 0) continue
                    cached.updateSegmentCount(visibleIndex, segmentCountForLine(docLine, safeWrapColumns, tabSize))
                }
                pendingSegmentCountLines.clear()
                visualLineMapCacheEpoch = epoch
                return cached
            }
            EditorVisualLineIndex(IntArray(visibleCount) { index ->
                segmentCountForLine(visibleDocLines[index], safeWrapColumns, tabSize)
            })
        } else {
            null
        }

        val built = VisualLineMap(
            docLineCount = docLineCount,
            visibleDocLines = visibleDocLines,
            wrappedLineIndex = wrappedLineIndex,
            wordWrapEnabled = wordWrapEnabled && wrapColumns > 0,
            wrapColumns = wrapColumns
        )
        visualLineMapCache = built
        visualLineMapCacheEpoch = epoch
        visualLineMapCountGeneration = docSegmentCountGeneration
        pendingSegmentCountLines.clear()
        return built
    }

    /**
     * 准备按文档行索引的 wrap segmentCount 存储。参数或未增量同步的文本版本变化只推进
     * generation，不在这里扫描全文；实际可见行由 [segmentCountForLine] 惰性计算。
     * 文本增量变化走 [applyTextChangeToDocSegmentCounts]（在 [EditorState.applyTextBufferChange] 内调用）。
     */
    private fun ensureDocSegmentCountStorage(
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int,
        textVersion: Long,
        inlayHintsVersion: Long,
    ) {
        val counts = docSegmentCounts
        val generations = docSegmentCountGenerations
        if (counts == null || generations == null || counts.size != docLineCount || generations.size != docLineCount) {
            docSegmentCounts = IntArray(docLineCount)
            docSegmentCountGenerations = IntArray(docLineCount)
        }

        if (docSegmentCountsWrapColumns != wrapColumns ||
            docSegmentCountsTabSize != tabSize ||
            docSegmentCountsVersion != textVersion
        ) {
            advanceDocSegmentCountGeneration()
        }
        docSegmentCountsWrapColumns = wrapColumns
        docSegmentCountsTabSize = tabSize
        docSegmentCountsVersion = textVersion
        refreshInlayHintCounts(inlayHintsVersion)
    }

    private fun refreshInlayHintCounts(inlayHintsVersion: Long) {
        if (docSegmentCountsInlayHintsVersion == inlayHintsVersion) return
        val current = host.inlayHintsByLine
        for ((line, hints) in cachedInlayHintsByLine) {
            if (current[line] != hints) invalidateSegmentCount(line)
        }
        for ((line, hints) in current) {
            if (cachedInlayHintsByLine[line] != hints) invalidateSegmentCount(line)
        }
        cachedInlayHintsByLine = current
        docSegmentCountsInlayHintsVersion = inlayHintsVersion
    }

    private fun invalidateSegmentCount(line: Int) {
        val generations = docSegmentCountGenerations ?: return
        if (line !in generations.indices) return
        generations[line] = 0
        if (visualLineMapCache == null) return
        pendingSegmentCountLines.add(line)
        if (pendingSegmentCountLines.size > MAX_INCREMENTAL_LINE_UPDATES) {
            visualLineMapCache = null
            pendingSegmentCountLines.clear()
        }
    }

    private fun advanceDocSegmentCountGeneration() {
        if (docSegmentCountGeneration == Int.MAX_VALUE) {
            docSegmentCountGenerations?.fill(0)
            docSegmentCountGeneration = 1
        } else {
            docSegmentCountGeneration++
        }
    }

    private fun segmentCountForLine(docLine: Int, wrapColumns: Int, tabSize: Int): Int {
        val counts = checkNotNull(docSegmentCounts)
        val generations = checkNotNull(docSegmentCountGenerations)
        if (generations[docLine] != docSegmentCountGeneration) {
            counts[docLine] = computeSegmentCountForLine(docLine, wrapColumns, tabSize)
            generations[docLine] = docSegmentCountGeneration
        }
        return counts[docLine].coerceAtLeast(1)
    }

    private fun computeSegmentCountForLine(
        docLine: Int,
        wrapColumns: Int,
        tabSize: Int
    ): Int {
        val lineText = textBuffer.getLine(docLine)
        val inlayHints = host.inlayHintsByLine[docLine].orEmpty()
        if (inlayHints.isEmpty()) {
            return TextScanKernel.countWrapSegments(
                lineText = lineText,
                wrapColumns = wrapColumns,
                tabSize = tabSize,
            )
        }
        return EditorInlayHintColumnLayout.findWrapSegmentStarts(
            lineText = lineText,
            wrapColumns = wrapColumns,
            tabSize = tabSize,
            hints = inlayHints,
        ).size.coerceAtLeast(1)
    }

    /**
     * 将 [TextChange] 增量应用到 [docSegmentCounts]：
     * - 行数不变时只标记编辑窗，不复制全文数组；
     * - 行数变化时 head [0, startLine) 原样拷贝；
     * - 编辑窗 [startLine, newChangedEndLine] 标记为失效，等实际可见时再重算；
     * - tail (oldEnd, oldDocCount) 按 lineDelta 平移到 (newEnd, newDocCount)。
     *
     * 若事件元数据与当前 buffer 行数不一致，则丢弃整份计数存储并在下一次映射中惰性重建，
     * 避免用不可信的 lineDelta 做越界数组搬运。
     */
    @Synchronized
    fun applyTextChangeToDocSegmentCounts(change: TextChange, newVersion: Long) {
        val cached = docSegmentCounts ?: return
        val cachedGenerations = docSegmentCountGenerations ?: run {
            discardDocSegmentCountStorage(newVersion)
            return
        }
        if (docSegmentCountsVersion + 1L != newVersion) {
            // Transaction 会在全部编辑完成后逐条派发事件；监听器此时只能看到最终 version。
            // 版本不连续也可能表示漏掉了事件，两种情况都不能安全搬运旧的逐行计数。
            discardDocSegmentCountStorage(newVersion)
            return
        }
        val wrapColumns = docSegmentCountsWrapColumns
        if (wrapColumns <= 0 || cached.size != cachedGenerations.size) {
            // 签名已经不再有效（wrapColumns 还没被初始化成合法值）。
            discardDocSegmentCountStorage(newVersion)
            return
        }
        val oldDocCount = cached.size
        if (oldDocCount <= 0) {
            discardDocSegmentCountStorage(newVersion)
            return
        }

        val delta = change.lineDelta
        val actualNewDocCount = textBuffer.lineCount.coerceAtLeast(0)
        val expectedNewDocCount = oldDocCount.toLong() + delta.toLong()
        if (expectedNewDocCount != actualNewDocCount.toLong() || actualNewDocCount <= 0) {
            discardDocSegmentCountStorage(newVersion)
            return
        }

        val startLine = change.startLine.coerceIn(0, oldDocCount - 1)
        val oldEnd = change.endLine.coerceIn(startLine, oldDocCount - 1)
        val newEndLong = oldEnd.toLong() + delta.toLong()
        if (newEndLong !in startLine.toLong() until actualNewDocCount.toLong()) {
            discardDocSegmentCountStorage(newVersion)
            return
        }
        val newEnd = newEndLong.toInt()
        if (delta != 0) {
            // Old hint widths belong to document coordinates, not to the rows being shifted.
            for (line in cachedInlayHintsByLine.keys) invalidateSegmentCount(line)
        }
        refreshInlayHintCounts(host.inlayHintsVersion)
        if (delta == 0) {
            for (line in startLine..newEnd) invalidateSegmentCount(line)
            docSegmentCountsVersion = newVersion
            return
        }

        // Row insertion/removal also changes the folding map; rebuild the cumulative index.
        visualLineMapCache = null
        pendingSegmentCountLines.clear()
        val arr = IntArray(actualNewDocCount)
        val arrGenerations = IntArray(actualNewDocCount)
        // head: [0, startLine)
        val headLen = startLine.coerceAtMost(oldDocCount)
        if (headLen > 0) {
            System.arraycopy(cached, 0, arr, 0, headLen)
            System.arraycopy(cachedGenerations, 0, arrGenerations, 0, headLen)
        }
        // tail: old [oldEnd+1, oldDocCount) → new [newEnd+1, newDocCount)
        val tailSrc = (oldEnd + 1).coerceAtMost(oldDocCount)
        val tailDst = (newEnd + 1).coerceIn(0, actualNewDocCount)
        val tailLen = minOf(oldDocCount - tailSrc, actualNewDocCount - tailDst).coerceAtLeast(0)
        if (tailLen > 0) {
            System.arraycopy(cached, tailSrc, arr, tailDst, tailLen)
            System.arraycopy(cachedGenerations, tailSrc, arrGenerations, tailDst, tailLen)
        }
        docSegmentCounts = arr
        docSegmentCountGenerations = arrGenerations
        docSegmentCountsVersion = newVersion
        for (line in cachedInlayHintsByLine.keys) invalidateSegmentCount(line)
    }

    private fun discardDocSegmentCountStorage(newVersion: Long) {
        docSegmentCounts = null
        docSegmentCountGenerations = null
        visualLineMapCache = null
        pendingSegmentCountLines.clear()
        docSegmentCountsVersion = newVersion
        docSegmentCountsInlayHintsVersion = host.inlayHintsVersion
        cachedInlayHintsByLine = host.inlayHintsByLine
    }

    private companion object {
        const val MAX_INCREMENTAL_LINE_UPDATES = 1024
    }
}

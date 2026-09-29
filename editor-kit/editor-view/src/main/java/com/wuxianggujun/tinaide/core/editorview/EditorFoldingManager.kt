package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import timber.log.Timber

/**
 * 代码折叠管理器（按行隐藏）。
 *
 * 从 [EditorState] 抽出的内聚块：维护折叠区间数据（[foldRegions] / [collapsedFoldStartLines] 等），
 * 计算“文档行 → 视觉行”的折叠映射 [LineMap]（含缓存），并提供全部折叠查询与切换 API。
 *
 * 该块依赖宿主的少量只读量（文本、折叠/Tab 开关、gutter 装饰、诊断行索引、光标偏移）以及
 * 少量回调（移动光标、夹取滚动），因此通过 [Host] 接口注入，组合而非继承（D 依赖倒置）。
 *
 * 行为与原内联实现严格一致：算法、缓存失效时机、版本置位时机均逐行对应原 [EditorState] 实现。
 *
 * 可观察性说明：
 * - [foldDataVersion] / [foldRegionsDocumentVersion] / [foldRegions] / [collapsedFoldStartLines]
 *   仍由 `mutableStateOf` 承载。Compose 快照按 StateObject 身份记录读取，与持有它的对象类无关，
 *   因此 [EditorState] 的 `visibleLines` derivedStateOf 跨对象读取 [foldDataVersion]（直接读 +
 *   经 visualLineMap() 间接读）仍会触发重组，无需在宿主侧保留镜像状态。
 */
internal class EditorFoldingManager(
    private val host: Host
) {
    /**
     * 宿主只读视图与回调。读取宿主当前状态；通过回调反向驱动宿主（移动光标 / 夹取滚动）。
     */
    internal interface Host {
        val textBuffer: TextBuffer
        val codeFoldingEnabled: Boolean
        val tabSize: Int

        /** gutter 装饰表（[applyFoldableDecorations] 在其上设置/清理 foldable 标记）。 */
        val gutterDecorations: SnapshotStateMap<Int, GutterDecoration>

        /** 已按行号升序排好的诊断行索引（[hasHiddenDiagnosticsInFold] 做二分查找）。 */
        val diagnosticLinesSorted: IntArray

        /** 当前光标偏移（[toggleFoldAtLine] 折叠后回退光标用）。 */
        val cursorOffset: Int

        fun moveCursorTo(offset: Int)
        fun clampScroll()
    }

    private val textBuffer: TextBuffer get() = host.textBuffer

    // ========== 代码折叠（按行隐藏） ==========
    private var foldRegions: List<FoldRegion> by mutableStateOf(emptyList())
    internal var foldRegionsDocumentVersion by mutableStateOf(-1L)
        private set
    private var foldRegionByStartLine: Map<Int, FoldRegion> = emptyMap()
    private var collapsedFoldStartLines by mutableStateOf<Set<Int>>(emptySet())
    internal var foldDataVersion by mutableStateOf(0)
        private set

    private data class BrokenFoldRecord(val startLine: Int, val originalEndLine: Int)
    private val brokenFoldRecords = mutableSetOf<BrokenFoldRecord>()

    internal data class LineMap(
        val docLineCount: Int,
        val visualToDocLine: IntArray,
        val docToVisualLine: IntArray,
        val hiddenDocLine: BooleanArray,
        val hiddenOwnerStartLine: IntArray
    ) {
        val visualLineCount: Int
            get() = visualToDocLine.size

        inline fun forEachVisibleLine(firstLine: Int, lastLine: Int, action: (Int) -> Unit) {
            val match = visualToDocLine.binarySearch(firstLine)
            var index = if (match >= 0) match else -match - 1
            while (index < visualToDocLine.size) {
                val line = visualToDocLine[index++]
                if (line > lastLine) break
                action(line)
            }
        }
    }

    private var lineMapCache: LineMap? = null
    private var lineMapCacheFoldDataVersion: Int = Int.MIN_VALUE
    private var lineMapCacheFoldingEnabled: Boolean = false

    /**
     * 更新折叠区间（通常由 Tree-sitter/LSP 等外部数据源计算后传入）。
     *
     * @param documentVersion 该折叠结果对应的文档版本（必须与当前 [textBuffer.version] 一致，否则忽略）。
     */
    fun setFoldRegions(
        regions: List<FoldRegion>,
        documentVersion: Long
    ) {
        if (documentVersion != textBuffer.version) return

        val lineCount = textBuffer.lineCount
        if (!host.codeFoldingEnabled || lineCount <= 0) {
            clearFoldRegionsInternal()
            return
        }

        val normalized = regions.asSequence()
            .map { region ->
                val start = region.startLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val end = region.endLine.coerceIn(start, (lineCount - 1).coerceAtLeast(0))
                FoldRegion(startLine = start, endLine = end)
            }
            .filter { it.isFoldable }
            .filter { region ->
                val broken = brokenFoldRecords.find { it.startLine == region.startLine }
                broken == null || region.endLine == broken.originalEndLine
            }
            .distinct()
            .sortedWith(compareBy<FoldRegion> { it.startLine }.thenByDescending { it.endLine })
            .toList()

        brokenFoldRecords.removeAll { broken ->
            normalized.none { it.startLine == broken.startLine }
        }

        // Parsing often republishes the same regions after a row-preserving edit.
        if (foldRegionsDocumentVersion == documentVersion && foldRegions == normalized) return

        foldRegions = normalized
        foldRegionsDocumentVersion = documentVersion
        foldRegionByStartLine = normalized.associateBy { it.startLine }
        collapsedFoldStartLines = collapsedFoldStartLines
            .filter { it in foldRegionByStartLine }
            .toSet()

        applyFoldableDecorations(startLines = foldRegionByStartLine.keys)
        onFoldDataChanged()
        host.clampScroll()

        Timber.tag("EditorState").d(
            "setFoldRegions: input=%d, normalized=%d, decorations=%d, docVersion=%d, bufVersion=%d",
            regions.size,
            normalized.size,
            foldRegionByStartLine.size,
            documentVersion,
            textBuffer.version
        )
    }

    fun clearFoldRegions() {
        clearFoldRegionsInternal()
    }

    fun toggleFoldAtLine(line: Int) {
        if (!host.codeFoldingEnabled) return
        if (foldRegionsDocumentVersion != textBuffer.version) return
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val startLine = line.coerceIn(0, lineCount - 1)
        val region = foldRegionByStartLine[startLine] ?: return
        if (!region.isFoldable) return

        val wasCollapsed = startLine in collapsedFoldStartLines
        collapsedFoldStartLines = if (wasCollapsed) {
            collapsedFoldStartLines - startLine
        } else {
            collapsedFoldStartLines + startLine
        }
        onFoldDataChanged()

        // 折叠后：若光标落在隐藏区间内，将其回退到折叠起始行，避免”光标消失”。
        if (!wasCollapsed) {
            val curPos = textBuffer.offsetToPosition(host.cursorOffset.coerceIn(0, textBuffer.length))
            val curLine = curPos.line
            if (curLine in (region.startLine + 1)..region.endLine) {
                val lineText = textBuffer.getLine(region.startLine)
                val safeColumn = curPos.column.coerceIn(0, lineText.length)
                host.moveCursorTo(textBuffer.positionToOffset(region.startLine, safeColumn))
            }
        }
        host.clampScroll()
    }

    fun isDocLineHidden(docLine: Int): Boolean {
        val map = lineMap()
        if (map.docLineCount <= 0) return false
        val safeLine = docLine.coerceIn(0, map.docLineCount - 1)
        return map.hiddenDocLine[safeLine]
    }

    fun isFoldCollapsedAtLine(line: Int): Boolean = line in collapsedFoldStartLines

    fun isFoldingDataValid(): Boolean = host.codeFoldingEnabled && foldRegionsDocumentVersion == textBuffer.version

    fun isCollapsedFoldStart(line: Int): Boolean = isFoldingDataValid() && line in collapsedFoldStartLines

    fun collapsedFoldEndLine(startLine: Int): Int {
        if (!isCollapsedFoldStart(startLine)) return -1
        return foldRegionByStartLine[startLine]?.endLine ?: -1
    }

    /**
     * 折叠末行是否"虚拟可见"。
     *
     * IntelliJ 风格：折叠末行（如包含 `}` 的行）虽然在 lineMap 中被标记为 hidden，
     * 但其 trim 后的文本作为折叠装饰的一部分显示在起始行末尾，光标可以定位到该文本上。
     */
    fun isFoldEndLineVirtuallyVisible(docLine: Int): Boolean {
        if (!isFoldingDataValid()) return false
        val map = lineMap()
        if (docLine < 0 || docLine >= map.docLineCount) return false
        if (!map.hiddenDocLine[docLine]) return false
        val ownerStart = map.hiddenOwnerStartLine[docLine]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return false
        val region = foldRegionByStartLine[ownerStart] ?: return false
        return docLine == region.endLine
    }

    fun foldOwnerForEndLine(docLine: Int): Int {
        if (!isFoldEndLineVirtuallyVisible(docLine)) return -1
        return lineMap().hiddenOwnerStartLine[docLine]
    }

    fun markFoldAsBroken(startLine: Int) {
        val region = foldRegionByStartLine[startLine] ?: return
        brokenFoldRecords.add(BrokenFoldRecord(startLine, region.endLine))
    }

    fun foldOwnerForHiddenLine(docLine: Int): Int {
        if (!isFoldingDataValid()) return -1
        val map = lineMap()
        if (docLine < 0 || docLine >= map.docLineCount) return -1
        if (!map.hiddenDocLine[docLine]) return -1
        return map.hiddenOwnerStartLine[docLine]
    }

    fun hasHiddenDiagnosticsInFold(startLine: Int): Boolean {
        if (!isFoldingDataValid()) return false
        if (startLine !in collapsedFoldStartLines) return false
        val region = foldRegionByStartLine[startLine] ?: return false
        val hiddenStart = (region.startLine + 1).coerceAtLeast(0)
        val hiddenEnd = region.endLine
        if (hiddenEnd <= hiddenStart) return false
        val sortedDiagLines = host.diagnosticLinesSorted
        if (sortedDiagLines.isEmpty()) return false
        var lo = 0
        var hi = sortedDiagLines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                sortedDiagLines[mid] < hiddenStart -> lo = mid + 1
                sortedDiagLines[mid] > hiddenEnd -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    /**
     * 向右移动时跳过折叠内部隐藏行。
     * 折叠末行是虚拟可见的（光标可以停留），但折叠内部行（startLine+1..endLine-1）不可停留。
     */
    fun skipFoldForwardIfHidden(offset: Int): Int {
        if (!isFoldingDataValid()) return offset
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val map = lineMap()
        if (pos.line >= map.docLineCount || !map.hiddenDocLine[pos.line]) return offset
        val ownerStart = map.hiddenOwnerStartLine[pos.line]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return offset
        val region = foldRegionByStartLine[ownerStart] ?: return offset
        if (pos.line == region.endLine) return offset
        val endLine = region.endLine.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val endLineText = textBuffer.getLine(endLine)
        val trimStartCol = TextScanKernel
            .scanLineWhitespace(endLineText, host.tabSize)
            .leadingWhitespaceEnd
        return textBuffer.positionToOffset(endLine, trimStartCol)
    }

    /**
     * 向左移动时跳过折叠内部隐藏行。
     * 从折叠末行可见文本的起始位置再往左 → 跳回折叠起始行末尾。
     * 从折叠内部行 → 跳回折叠起始行末尾。
     */
    fun skipFoldBackwardIfHidden(offset: Int): Int {
        if (!isFoldingDataValid()) return offset
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val map = lineMap()
        if (pos.line >= map.docLineCount || !map.hiddenDocLine[pos.line]) return offset
        val ownerStart = map.hiddenOwnerStartLine[pos.line]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return offset
        val region = foldRegionByStartLine[ownerStart] ?: return offset
        if (pos.line == region.endLine) {
            val endLineText = textBuffer.getLine(region.endLine)
            val trimStartCol = TextScanKernel
                .scanLineWhitespace(endLineText, host.tabSize)
                .leadingWhitespaceEnd
            if (pos.column >= trimStartCol) return offset
        }
        val startLineText = textBuffer.getLine(ownerStart)
        return textBuffer.positionToOffset(ownerStart, startLineText.length)
    }

    fun revealLineIfFolded(docLine: Int) {
        if (!host.codeFoldingEnabled) return
        if (foldRegionsDocumentVersion != textBuffer.version) return
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val safeLine = docLine.coerceIn(0, lineCount - 1)

        var guard = 0
        while (guard < 64) {
            val map = lineMap()
            if (safeLine >= map.docLineCount) return
            if (!map.hiddenDocLine[safeLine]) return
            val ownerStart = map.hiddenOwnerStartLine[safeLine]
            if (ownerStart < 0) return
            if (ownerStart !in collapsedFoldStartLines) return
            val region = foldRegionByStartLine[ownerStart]
            if (region != null && safeLine == region.endLine) return
            collapsedFoldStartLines = collapsedFoldStartLines - ownerStart
            onFoldDataChanged()
            guard++
        }
    }

    private fun clearFoldRegionsInternal() {
        foldRegions = emptyList()
        foldRegionsDocumentVersion = -1L
        foldRegionByStartLine = emptyMap()
        collapsedFoldStartLines = emptySet()
        applyFoldableDecorations(startLines = emptySet())
        onFoldDataChanged()
        host.clampScroll()
    }

    private fun onFoldDataChanged() {
        foldDataVersion += 1
        lineMapCache = null
    }

    private fun applyFoldableDecorations(startLines: Set<Int>) {
        // 先清理旧 foldable
        val oldFoldableLines = host.gutterDecorations
            .filterValues { it.foldable }
            .keys
            .toList()
        oldFoldableLines.forEach { line ->
            if (line in startLines) return@forEach
            val existing = host.gutterDecorations[line] ?: return@forEach
            val updated = existing.copy(foldable = false)
            if (updated.breakpoint || updated.bookmark || updated.hasDiagnostic) {
                host.gutterDecorations[line] = updated
            } else {
                host.gutterDecorations.remove(line)
            }
        }

        // 再设置新 foldable
        startLines.forEach { line ->
            val existing = host.gutterDecorations[line] ?: GutterDecoration()
            host.gutterDecorations[line] = existing.copy(foldable = true)
        }
    }

    fun lineMap(): LineMap {
        val foldingEnabled = host.codeFoldingEnabled && foldRegionsDocumentVersion == textBuffer.version
        val docLineCount = textBuffer.lineCount
        val cached = lineMapCache
        if (
            cached != null &&
            lineMapCacheFoldDataVersion == foldDataVersion &&
            lineMapCacheFoldingEnabled == foldingEnabled &&
            cached.docLineCount == docLineCount
        ) {
            return cached
        }

        val safeDocLineCount = docLineCount.coerceAtLeast(0)
        val built = buildLineMap(
            docLineCount = safeDocLineCount,
            foldingEnabled = foldingEnabled
        )
        lineMapCache = built
        lineMapCacheFoldDataVersion = foldDataVersion
        lineMapCacheFoldingEnabled = foldingEnabled
        return built
    }

    private fun buildLineMap(docLineCount: Int, foldingEnabled: Boolean): LineMap {
        if (docLineCount <= 0) {
            return LineMap(
                docLineCount = 0,
                visualToDocLine = IntArray(0),
                docToVisualLine = IntArray(0),
                hiddenDocLine = BooleanArray(0),
                hiddenOwnerStartLine = IntArray(0)
            )
        }

        if (!foldingEnabled || collapsedFoldStartLines.isEmpty() || foldRegionByStartLine.isEmpty()) {
            return LineMap(
                docLineCount = docLineCount,
                visualToDocLine = IntArray(docLineCount) { it },
                docToVisualLine = IntArray(docLineCount) { it },
                hiddenDocLine = BooleanArray(docLineCount),
                hiddenOwnerStartLine = IntArray(docLineCount) { -1 }
            )
        }

        val hidden = BooleanArray(docLineCount)
        val hiddenOwner = IntArray(docLineCount) { -1 }

        // 只应用“可见的折叠起始行”对应的折叠，避免内层折叠被外层折叠覆盖时重复计算。
        val sortedStarts = collapsedFoldStartLines.toIntArray().apply { sort() }
        for (start in sortedStarts) {
            val region = foldRegionByStartLine[start] ?: continue
            val safeStart = start.coerceIn(0, docLineCount - 1)
            if (hidden[safeStart]) continue
            val safeEnd = region.endLine.coerceIn(safeStart, docLineCount - 1)
            if (safeEnd <= safeStart) continue

            for (line in (safeStart + 1)..safeEnd) {
                hidden[line] = true
                hiddenOwner[line] = safeStart
            }
        }

        var hiddenCount = 0
        for (i in 0 until docLineCount) {
            if (hidden[i]) hiddenCount++
        }
        val visualCount = (docLineCount - hiddenCount).coerceAtLeast(0)
        val visualToDoc = IntArray(visualCount)
        val docToVisual = IntArray(docLineCount) { -1 }

        var visualIndex = 0
        for (docLine in 0 until docLineCount) {
            if (hidden[docLine]) continue
            if (visualIndex >= visualCount) break
            visualToDoc[visualIndex] = docLine
            docToVisual[docLine] = visualIndex
            visualIndex++
        }

        return LineMap(
            docLineCount = docLineCount,
            visualToDocLine = visualToDoc,
            docToVisualLine = docToVisual,
            hiddenDocLine = hidden,
            hiddenOwnerStartLine = hiddenOwner
        )
    }

    /**
     * 文本变化后调整折叠区间，避免折叠状态因版本不匹配而瞬间丢失。
     *
     * 核心策略：
     * - 单行编辑（无行数变化）：折叠区间的行号仍然有效，只需同步版本号。
     * - 多行编辑（有行增删）：编辑点之前的区间保持不变；编辑点之后的区间做行号平移；
     *   与编辑范围重叠的区间丢弃（等待 TreeSitter 重新计算）。
     *
     * 这样做的好处：
     * - 避免每次击键都触发"折叠全部展开 → 重新折叠"的布局抖动（558ms+卡顿）
     * - 用户在折叠区间附近编辑时体验平滑
     */
    fun adjustFoldRegionsAfterTextChange(change: TextChange, currentVersion: Long) {
        if (!host.codeFoldingEnabled) return
        if (foldRegionsDocumentVersion < 0L) return
        if (foldRegions.isEmpty()) {
            foldRegionsDocumentVersion = currentVersion
            return
        }

        val lineDelta = change.lineDelta

        if (lineDelta == 0) {
            foldRegionsDocumentVersion = currentVersion
            return
        }

        val editStartLine = change.startLine
        val editEndLine = change.endLine

        val adjustedRegions = ArrayList<FoldRegion>(foldRegions.size)
        val newCollapsed = HashSet<Int>()

        for (region in foldRegions) {
            val isCollapsed = region.startLine in collapsedFoldStartLines
            when {
                region.endLine < editStartLine -> {
                    adjustedRegions.add(region)
                    if (isCollapsed) {
                        newCollapsed.add(region.startLine)
                    }
                }
                region.startLine > editEndLine -> {
                    val newStart = region.startLine + lineDelta
                    val newEnd = region.endLine + lineDelta
                    if (newEnd > newStart && newStart >= 0) {
                        val shifted = FoldRegion(startLine = newStart, endLine = newEnd)
                        adjustedRegions.add(shifted)
                        if (isCollapsed) {
                            newCollapsed.add(newStart)
                        }
                    }
                }
                isCollapsed && editStartLine == region.endLine && editEndLine == region.endLine -> {
                    adjustedRegions.add(region)
                    newCollapsed.add(region.startLine)
                }
                isCollapsed && editStartLine == region.startLine && editEndLine == region.startLine -> {
                    val newEnd = region.endLine + lineDelta
                    if (newEnd > region.startLine) {
                        adjustedRegions.add(FoldRegion(startLine = region.startLine, endLine = newEnd))
                        newCollapsed.add(region.startLine)
                    }
                }
                // Overlapping regions: dropped, will be recomputed by TreeSitter
            }
        }

        foldRegions = adjustedRegions
        foldRegionByStartLine = adjustedRegions.associateBy { it.startLine }
        collapsedFoldStartLines = newCollapsed
        foldRegionsDocumentVersion = currentVersion
        applyFoldableDecorations(foldRegionByStartLine.keys)
        onFoldDataChanged()
    }
}

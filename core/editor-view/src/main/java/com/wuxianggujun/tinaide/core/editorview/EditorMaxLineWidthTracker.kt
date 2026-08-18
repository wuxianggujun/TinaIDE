package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange

/**
 * 横向“最大行宽”跟踪器。
 *
 * 从 [EditorState] 抽出的内聚块：维护“每行视觉列数快照 + 当前最大列数”，
 * 用于计算横向滚动上界（maxScrollX）。这块逻辑与光标/选区/滚动等可变状态弱耦合，
 * 只需要从宿主读取少量只读量（文本、字宽、可见视觉行、光标行、视觉行映射），
 * 因此通过 [Host] 接口注入，组合而非继承（D 依赖倒置）。
 *
 * 源码行宽行为与原内联实现一致，并在结果上叠加 Inlay Hint 的保守预留宽度。
 * 原有逻辑仍**包括以下两个刻意保留的现状**：
 * - 大文件增量扫描（[ensureMaxLineWidthPx] 的分批 while 循环）按 `getLine().length`（UTF-16 字符数）累计，
 *   而全量/采样/可见行口径走 [Host.lineVisualColumns]（视觉列数，Tab 展开）。两者口径不同是原代码现状，
 *   这里**不抹平**，仅做结构搬运。
 * - 各处的 `cachedMaxLineCharsVersion` 失效/置位时机与原实现逐行对应。
 */
internal class EditorMaxLineWidthTracker(
    private val host: Host
) {
    /**
     * 宿主只读视图。所有方法都读取宿主当前状态，不回写。
     */
    internal interface Host {
        val textBuffer: TextBuffer
        val charWidthPx: Float
        val viewportWidthPx: Float
        val cursorLine: Int
        val visibleLines: IntRange
        val wordWrapEnabled: Boolean
        val isWordWrapLayoutFrozen: Boolean
        val inlayHintsVersion: Long
            get() = 0L

        /** 某文档行的视觉列数（Tab 展开），等价于原 lineVisualColumnsForWidth。 */
        fun lineVisualColumns(lineText: String): Int

        /** 当前视觉行映射（折叠 + wordWrap 后），用于把可见视觉行解析回文档行。 */
        fun currentVisualLineMap(): EditorVisualLineMapper.VisualLineMap

        /** 在视觉行映射里，把视觉行索引解析为“可见文档行列表”的下标。 */
        fun resolveVisibleIndexForVisualLine(map: EditorVisualLineMapper.VisualLineMap, visualLine: Int): Int

        /** 含 Inlay Hint 保守预留宽度的最大行视觉列数。 */
        fun maxInlayExpandedLineVisualColumns(): Int = 0
    }

    private companion object {
        const val HORIZONTAL_FULL_SCAN_LINE_THRESHOLD = 4_000
        const val HORIZONTAL_WIDTH_GUARD_CHARS = 32
        const val HORIZONTAL_WIDTH_BOOTSTRAP_SAMPLES = 96
        const val HORIZONTAL_WIDTH_SCAN_MAX_BATCH_LINES = 256
        const val HORIZONTAL_WIDTH_SCAN_CHECK_INTERVAL = 32
        const val HORIZONTAL_WIDTH_SCAN_TIME_BUDGET_NS = 700_000L
    }

    private var cachedMaxLineChars = 0
    private var cachedMaxLineCharsVersion = -1L
    private var widthScanVersion = -1L
    private var widthScanNextLine = 0
    private var widthScanMaxChars = 0
    private var widthScanLineLengths = IntArray(0)
    private var widthLineSnapshotVersion = -1L
    private var widthLineMaxChars = 0
    private var widthLineLengths = IntArray(0)
    private var cachedInlayMaxTextVersion = Long.MIN_VALUE
    private var cachedInlayHintsVersion = Long.MIN_VALUE
    private var cachedInlayMaxColumns = 0

    private val textBuffer: TextBuffer get() = host.textBuffer

    /**
     * 横向最大滚动偏移（像素）。等价于原 [EditorState] 内联的 maxScrollXPx()。
     */
    fun maxScrollXPx(): Float {
        // 横向右侧预留“可滚动空白”，避免长行滚动到末尾时文本直接贴住屏幕右边缘，
        // 影响光标/选区/句柄的可操作性。
        //
        // 对齐 Sora 的思路：CodeEditor#getScrollMaxX() 里会减去 viewWidth/2，
        // 等价于给右侧增加 half-viewport 的额外滚动空间。
        val endPadding = host.viewportWidthPx * 0.5f

        if (host.wordWrapEnabled) {
            // wordWrap 通常禁用横向滚动，但双指缩放过程中需要允许短暂的 X 锚定，
            // 否则当行号/ gutter 宽度随字体变化时会出现明显“漂移”。
            // 缩放结束（解除冻结）后再回到 0，行为对齐 Sora。
            return if (host.isWordWrapLayoutFrozen) {
                endPadding.coerceAtLeast(0f)
            } else {
                0f
            }
        }

        val maxLineWidth = ensureMaxLineWidthPx()
        return (maxLineWidth - host.viewportWidthPx + endPadding).coerceAtLeast(0f)
    }

    private fun ensureMaxLineWidthPx(): Float {
        val version = textBuffer.version
        if (cachedMaxLineCharsVersion != version) {
            var resolvedForCurrentVersion = false
            cachedMaxLineChars = if (
                widthLineSnapshotVersion == version &&
                widthLineLengths.size == textBuffer.lineCount
            ) {
                resolvedForCurrentVersion = true
                maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
            } else if (textBuffer.lineCount <= HORIZONTAL_FULL_SCAN_LINE_THRESHOLD) {
                resolvedForCurrentVersion = true
                buildFullWidthSnapshot(version)
            } else {
                if (widthScanVersion != version) {
                    widthScanVersion = version
                    widthScanNextLine = 0
                    widthScanLineLengths = IntArray(textBuffer.lineCount)
                    widthScanMaxChars = maxOf(
                        visibleAndCursorMaxLineChars(),
                        sampleMaxLineChars(HORIZONTAL_WIDTH_BOOTSTRAP_SAMPLES)
                    )
                } else {
                    widthScanMaxChars = maxOf(widthScanMaxChars, visibleAndCursorMaxLineChars())
                }

                val scanStartNs = System.nanoTime()
                var scannedCount = 0
                val lineCount = textBuffer.lineCount
                while (
                    widthScanNextLine < lineCount &&
                    scannedCount < HORIZONTAL_WIDTH_SCAN_MAX_BATCH_LINES
                ) {
                    val lineLength = textBuffer.getLine(widthScanNextLine).length
                    widthScanLineLengths[widthScanNextLine] = lineLength
                    if (lineLength > widthScanMaxChars) {
                        widthScanMaxChars = lineLength
                    }
                    widthScanNextLine++
                    scannedCount++
                    if (
                        scannedCount % HORIZONTAL_WIDTH_SCAN_CHECK_INTERVAL == 0 &&
                        (System.nanoTime() - scanStartNs) >= HORIZONTAL_WIDTH_SCAN_TIME_BUDGET_NS
                    ) {
                        break
                    }
                }

                if (widthScanNextLine >= lineCount) {
                    widthLineLengths = widthScanLineLengths
                    widthLineMaxChars = widthScanMaxChars
                    widthLineSnapshotVersion = version
                    resolvedForCurrentVersion = true
                } else {
                    widthLineSnapshotVersion = -1L
                }
                widthScanMaxChars
            }
            cachedMaxLineCharsVersion = if (
                resolvedForCurrentVersion ||
                textBuffer.lineCount <= HORIZONTAL_FULL_SCAN_LINE_THRESHOLD ||
                widthScanNextLine >= textBuffer.lineCount
            ) {
                version
            } else {
                -1L
            }
        }
        return maxOf(cachedMaxLineChars, ensureInlayMaxColumns()) * host.charWidthPx
    }

    private fun ensureInlayMaxColumns(): Int {
        val textVersion = textBuffer.version
        val hintsVersion = host.inlayHintsVersion
        if (
            cachedInlayMaxTextVersion != textVersion ||
            cachedInlayHintsVersion != hintsVersion
        ) {
            cachedInlayMaxColumns = host.maxInlayExpandedLineVisualColumns().coerceAtLeast(0)
            cachedInlayMaxTextVersion = textVersion
            cachedInlayHintsVersion = hintsVersion
        }
        return cachedInlayMaxColumns
    }

    private fun visibleAndCursorMaxLineChars(): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0
        val maxLineIndex = lineCount - 1
        val snapshot = widthLineLengths
        val snapshotValid = widthLineSnapshotVersion == textBuffer.version && snapshot.size == lineCount
        val map = host.currentVisualLineMap()
        val visibleLines = host.visibleLines
        val visibleMax = if (visibleLines.isEmpty()) {
            0
        } else {
            var maxChars = 0
            for (visualLine in visibleLines) {
                val safeVisual = visualLine.coerceIn(0, (map.visualLineCount - 1).coerceAtLeast(0))
                val visibleIndex = host.resolveVisibleIndexForVisualLine(map, safeVisual)
                val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
                if (docLine !in 0..maxLineIndex) continue
                val length = if (snapshotValid) {
                    snapshot[docLine]
                } else {
                    host.lineVisualColumns(textBuffer.getLine(docLine))
                }
                if (length > maxChars) {
                    maxChars = length
                }
            }
            maxChars
        }
        val curLine = host.cursorLine.coerceIn(0, maxLineIndex)
        val cursorLineLength = if (snapshotValid) {
            snapshot[curLine]
        } else {
            host.lineVisualColumns(textBuffer.getLine(curLine))
        }
        return maxOf(visibleMax, cursorLineLength + HORIZONTAL_WIDTH_GUARD_CHARS)
    }

    private fun sampleMaxLineChars(sampleCount: Int): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0
        if (lineCount <= sampleCount) {
            return (0 until lineCount).maxOfOrNull { host.lineVisualColumns(textBuffer.getLine(it)) } ?: 0
        }
        val maxLineIndex = lineCount - 1
        var maxChars = 0
        val divisor = (sampleCount - 1).coerceAtLeast(1)
        for (index in 0 until sampleCount) {
            val line = (index.toLong() * maxLineIndex / divisor).toInt()
            val length = host.lineVisualColumns(textBuffer.getLine(line))
            if (length > maxChars) {
                maxChars = length
            }
        }
        return maxChars
    }

    private fun buildFullWidthSnapshot(version: Long): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) {
            widthLineLengths = IntArray(0)
            widthLineMaxChars = 0
            widthLineSnapshotVersion = version
            widthScanVersion = -1L
            widthScanNextLine = 0
            widthScanMaxChars = 0
            widthScanLineLengths = IntArray(0)
            return 0
        }
        val lengths = IntArray(lineCount)
        var maxChars = 0
        for (line in 0 until lineCount) {
            val length = host.lineVisualColumns(textBuffer.getLine(line))
            lengths[line] = length
            if (length > maxChars) {
                maxChars = length
            }
        }
        widthLineLengths = lengths
        widthLineMaxChars = maxChars
        widthLineSnapshotVersion = version
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        return maxChars
    }

    fun invalidateWidthSnapshot() {
        widthLineSnapshotVersion = -1L
        widthLineMaxChars = 0
        widthLineLengths = IntArray(0)
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        cachedMaxLineCharsVersion = -1L
        cachedInlayMaxTextVersion = Long.MIN_VALUE
        cachedInlayHintsVersion = Long.MIN_VALUE
        cachedInlayMaxColumns = 0
    }

    fun applyWidthSnapshotChange(change: TextChange, currentVersion: Long) {
        if (widthLineSnapshotVersion < 0L) {
            cachedMaxLineCharsVersion = -1L
            return
        }
        if (widthLineSnapshotVersion + 1L != currentVersion) {
            invalidateWidthSnapshot()
            return
        }

        val oldLengths = widthLineLengths
        val oldLineCount = oldLengths.size
        val newLineCount = textBuffer.lineCount
        if (oldLineCount <= 0 || newLineCount <= 0) {
            buildFullWidthSnapshot(currentVersion)
            cachedMaxLineChars = maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
            cachedMaxLineCharsVersion = currentVersion
            return
        }

        val startLine = change.startLine.coerceIn(0, oldLineCount - 1)
        val oldChangedEnd = change.endLine.coerceIn(startLine, oldLineCount - 1)
        val lineDelta = change.lineDelta
        val expectedNewLineCount = oldLineCount + lineDelta
        if (expectedNewLineCount != newLineCount) {
            invalidateWidthSnapshot()
            return
        }

        val newChangedEnd = maxOf(startLine, oldChangedEnd + lineDelta)
            .coerceIn(startLine, (newLineCount - 1).coerceAtLeast(startLine))
        val insertedCount = newChangedEnd - startLine + 1
        val insertedLengths = IntArray(insertedCount)
        var insertedMax = 0
        for (index in 0 until insertedCount) {
            val length = host.lineVisualColumns(textBuffer.getLine(startLine + index))
            insertedLengths[index] = length
            if (length > insertedMax) {
                insertedMax = length
            }
        }

        val updatedLengths = IntArray(newLineCount)
        if (startLine > 0) {
            System.arraycopy(oldLengths, 0, updatedLengths, 0, startLine)
        }
        if (insertedCount > 0) {
            System.arraycopy(insertedLengths, 0, updatedLengths, startLine, insertedCount)
        }
        val oldSuffixStart = oldChangedEnd + 1
        val oldSuffixCount = oldLineCount - oldSuffixStart
        if (oldSuffixCount > 0) {
            System.arraycopy(
                oldLengths,
                oldSuffixStart,
                updatedLengths,
                startLine + insertedCount,
                oldSuffixCount
            )
        }

        var removedHadOldMax = false
        val oldMax = widthLineMaxChars
        var line = startLine
        while (line <= oldChangedEnd) {
            if (oldLengths[line] == oldMax) {
                removedHadOldMax = true
                break
            }
            line++
        }

        var updatedMax = maxOf(oldMax, insertedMax)
        if (removedHadOldMax && insertedMax < oldMax) {
            updatedMax = 0
            for (length in updatedLengths) {
                if (length > updatedMax) {
                    updatedMax = length
                }
            }
        }

        widthLineLengths = updatedLengths
        widthLineMaxChars = updatedMax
        widthLineSnapshotVersion = currentVersion
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        cachedMaxLineChars = maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
        cachedMaxLineCharsVersion = currentVersion
    }
}

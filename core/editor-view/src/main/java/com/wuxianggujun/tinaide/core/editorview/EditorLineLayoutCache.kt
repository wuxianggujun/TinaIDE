package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.os.SystemClock
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import java.util.LinkedHashMap
import timber.log.Timber

/**
 * 行内布局缓存（前缀宽度 / prefix advances）。
 *
 * 目标：
 * 1. 让渲染（selection/cursor/语法高亮分段绘制）与 hit-test（x->column）共享同一套布局数据；
 * 2. 避免在每帧/每次 move 中反复调用 [Paint.measureText]；
 * 3. 尽量减少临时对象分配，降低 GC 抖动概率。
 *
 * 说明：
 * - 该缓存基于 [Paint.getTextRunAdvances] 获取每个 char 的 advance，并构建前缀和数组 prefix。
 * - 存在 Inlay Hint 时，[PrefixLayout.beforeInlayPrefix] 表示到达锚点前的位置，
 *   [PrefixLayout.prefix] 表示越过锚点提示后的字符/光标位置。
 * - 对单行的 column->x / x->column 都可以通过 prefix 进行 O(1)/O(logN) 计算。
 * - 缓存按 line 做 LRU，且用 totalFloats 做内存上限控制，避免极端长行撑爆内存。
 *
 * 注意：
 * - 该模块当前为 Android 版编辑器（依赖 android.graphics.Paint），不适用于“浏览器”运行时。
 * - 适用于触摸与鼠标（移动/平板/桌面形态），行为由上层手势逻辑保证一致性。
 */
internal class EditorLineLayoutCache(
    private val maxEntries: Int = 512,
    private val maxTotalFloats: Int = 220_000,
    private val slowBuildThresholdMs: Long = 4L
) {
    internal data class PrefixLayout(
        val lineText: String,
        /** 字符/光标在该文档列的起始位置；包含锚定在该列的 Inlay Hint 宽度。 */
        val prefix: FloatArray,
        /** 到达该文档列、但尚未绘制锚定在该列的 Inlay Hint 时的位置。 */
        val beforeInlayPrefix: FloatArray = prefix,
        val inlayHintPlacements: List<EditorInlayHintPlacement> = emptyList(),
        val inlayHintColumns: IntArray = IntArray(0),
    ) {
        val length: Int
            get() = lineText.length

        fun segmentStartAdvance(column: Int): Float =
            beforeInlayPrefix[column.coerceIn(0, length)]

        fun textStartAdvance(column: Int): Float =
            prefix[column.coerceIn(0, length)]

        fun textEndAdvance(column: Int): Float =
            beforeInlayPrefix[column.coerceIn(0, length)]

        internal val storedFloatCount: Int
            get() = prefix.size + if (beforeInlayPrefix === prefix) 0 else beforeInlayPrefix.size
    }

    private data class Entry(
        val lineText: String,
        val inlayHints: List<EditorInlayHint>,
        var layout: PrefixLayout,
    )

    // accessOrder=true => LRU
    private val lru = LinkedHashMap<Int, Entry>(64, 0.75f, true)
    private var totalFloats: Int = 0

    private var cacheVersion: Long = Long.MIN_VALUE
    private var layoutSignature: Int = 0

    // 复用缓冲区：避免每次计算 advances 都分配数组
    private var scratchChars: CharArray = CharArray(0)
    private var scratchAdvances: FloatArray = FloatArray(0)
    private val hintMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 保护 lru / totalFloats / cacheVersion / layoutSignature / scratch*。
    // TextChange listener 可能从非主线程同步分发（见 RopeTextBuffer.dispatchChange），
    // 与主线程 paint 的 getPrefixLayout 并发会产生 CME / 脏读。
    // LinkedHashMap accessOrder=true 的命中也会修改链表，read-only 不存在，改用统一互斥锁。
    private val lock = Any()

    fun invalidateAll() {
        synchronized(lock) {
            lru.clear()
            totalFloats = 0
            cacheVersion = Long.MIN_VALUE
        }
    }

    fun applyTextChange(change: TextChange, currentVersion: Long) {
        // 对齐 TextRenderer 的策略：只失效受影响的行，并对后续行做 index shift。
        val startLine = change.startLine.coerceAtLeast(0)
        val lineDelta = change.lineDelta
        val oldChangedEndLine = change.endLine.coerceAtLeast(startLine)
        val shiftFromLine = (change.endLine + 1).coerceAtLeast(0)
        val newChangedEndLine = maxOf(startLine, change.endLine + lineDelta)

        synchronized(lock) {
            invalidateRangeInternal(startLine, oldChangedEndLine)
            shiftCacheInternal(shiftFromLine, lineDelta)
            invalidateRangeInternal(startLine, newChangedEndLine)

            cacheVersion = currentVersion
        }
    }

    fun entryCount(): Int = synchronized(lock) { lru.size }

    fun cachedFloatCount(): Int = synchronized(lock) { totalFloats }

    /**
     * 获取某行的 prefix 布局。调用方必须确保 [paint] 的 textSize/typeface 等已设置为最终绘制状态。
     */
    fun getPrefixLayout(
        state: EditorState,
        line: Int,
        lineText: String,
        textVersion: Long,
        paint: Paint,
        lineHeightPx: Float = state.lineHeightPx,
    ): PrefixLayout {
        val inlayHints = if (state.inlayHintsDocumentVersion == textVersion) {
            state.inlayHintsByLine[line].orEmpty()
        } else {
            emptyList()
        }
        return getPrefixLayout(
            line = line,
            lineText = lineText,
            textVersion = textVersion,
            paint = paint,
            tabSize = state.config.tabSize,
            inlayHints = inlayHints,
            lineHeightPx = lineHeightPx,
        )
    }

    fun getPrefixLayout(
        line: Int,
        lineText: String,
        textVersion: Long,
        paint: Paint,
        tabSize: Int,
        inlayHints: List<EditorInlayHint> = emptyList(),
        lineHeightPx: Float = resolveLineHeight(paint),
    ): PrefixLayout {
        synchronized(lock) {
            ensureSignature(textVersion, paint, tabSize, lineHeightPx)
            val entry = lru[line]
            if (entry != null && entry.lineText == lineText && entry.inlayHints == inlayHints) {
                return entry.layout
            }

            val built = buildPrefix(
                lineText = lineText,
                paint = paint,
                tabSize = tabSize,
                inlayHints = inlayHints,
                lineHeightPx = lineHeightPx,
            )
            putEntry(line, built, inlayHints)
            return built
        }
    }

    fun xToColumn(layout: PrefixLayout, contentX: Float): Int {
        if (layout.length <= 0) return 0
        val targetX = contentX.coerceAtLeast(0f)

        // 虚拟提示本身不对应真实字符；点击其范围时应落回提示的 LSP 锚点列。
        layout.inlayHintPlacements.forEach { placement ->
            if (targetX >= placement.startAdvance && targetX <= placement.endAdvance) {
                return snapColumnToCodePointBoundary(layout, placement.column, targetX)
            }
        }

        val beforePrefix = layout.beforeInlayPrefix

        var low = 0
        var high = layout.length
        while (low < high) {
            val mid = (low + high) ushr 1
            if (beforePrefix[mid] < targetX) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val right = low.coerceIn(0, layout.length)
        val left = (right - 1).coerceAtLeast(0)
        if (right == left) return right
        val leftAdvance = layout.prefix[left]
        val rightAdvance = beforePrefix[right]
        val nearest = if ((targetX - leftAdvance) <= (rightAdvance - targetX)) left else right
        return snapColumnToCodePointBoundary(layout, nearest, targetX)
    }

    private fun snapColumnToCodePointBoundary(
        layout: PrefixLayout,
        column: Int,
        targetX: Float,
    ): Int {
        val nearest = column.coerceIn(0, layout.length)
        if (
            nearest <= 0 ||
            nearest >= layout.length ||
            !layout.lineText[nearest - 1].isHighSurrogate() ||
            !layout.lineText[nearest].isLowSurrogate()
        ) {
            return nearest
        }

        val codePointStart = nearest - 1
        val codePointEnd = nearest + 1
        val codePointMidpoint =
            (layout.prefix[codePointStart] + layout.beforeInlayPrefix[codePointEnd]) / 2f
        return if (targetX <= codePointMidpoint) codePointStart else codePointEnd
    }

    private fun ensureSignature(textVersion: Long, paint: Paint, tabSize: Int, lineHeightPx: Float) {
        val sig = layoutSignature(paint, tabSize, lineHeightPx)
        if (sig != layoutSignature) {
            // 字体度量变化（缩放/字体切换/字间距变更）会导致 prefix 全部失效。
            invalidateAll()
            layoutSignature = sig
        }
        if (cacheVersion == Long.MIN_VALUE) {
            cacheVersion = textVersion
            return
        }
        if (cacheVersion != textVersion) {
            // 理论上 applyTextChange 会维护 cacheVersion；此处属于“安全网”。
            invalidateAll()
            cacheVersion = textVersion
        }
    }

    private fun layoutSignature(paint: Paint, tabSize: Int, lineHeightPx: Float): Int {
        val base = paintSignature(paint)
        return 31 * (31 * base + tabSize) + lineHeightPx.toBits()
    }

    private fun paintSignature(paint: Paint): Int {
        var result = 17
        result = 31 * result + paint.textSize.toBits()
        result = 31 * result + (paint.typeface?.hashCode() ?: 0)
        result = 31 * result + paint.letterSpacing.toBits()
        result = 31 * result + if (paint.isFakeBoldText) 1 else 0
        result = 31 * result + paint.flags
        return result
    }

    private fun buildPrefix(
        lineText: String,
        paint: Paint,
        tabSize: Int,
        inlayHints: List<EditorInlayHint>,
        lineHeightPx: Float,
    ): PrefixLayout {
        val startMs = SystemClock.uptimeMillis()
        val length = lineText.length

        val rawPrefix = FloatArray(length + 1)
        if (length > 0) {
            if (scratchChars.size < length) {
                scratchChars = CharArray(length)
            }
            if (scratchAdvances.size < length) {
                scratchAdvances = FloatArray(length)
            }
            for (i in 0 until length) {
                scratchChars[i] = lineText[i]
            }

            val advances = scratchAdvances
            paint.getTextRunAdvances(
                scratchChars,
                0,
                length,
                0,
                length,
                false,
                advances,
                0,
            )

            val safeTabSize = tabSize.coerceAtLeast(1)
            val spaceAdvance = paint.measureText(" ").coerceAtLeast(0f)
            val visualColumns = TextScanKernel.buildVisualColumnPrefix(lineText, safeTabSize)

            var running = 0f
            for (i in 0 until length) {
                val ch = scratchChars[i]
                val advance = if (ch == '\t') {
                    val step = (visualColumns[i + 1] - visualColumns[i]).coerceAtLeast(1)
                    spaceAdvance * step
                } else {
                    val runAdvance = advances[i].coerceAtLeast(0f)
                    if (runAdvance > 0f) {
                        runAdvance
                    } else {
                        // 某些运行时/测试环境里 getTextRunAdvances 可能返回 0，
                        // 这里退回到单 glyph 量测，避免光标/弹窗横向锚点塌到行首。
                        paint.measureText(lineText, i, i + 1).coerceAtLeast(0f)
                    }
                }
                running += advance
                rawPrefix[i + 1] = running
            }
        }

        val layout = applyInlayHints(
            lineText = lineText,
            rawPrefix = rawPrefix,
            inlayHints = inlayHints,
            textPaint = paint,
            lineHeightPx = lineHeightPx,
        )

        val costMs = SystemClock.uptimeMillis() - startMs
        if (costMs >= slowBuildThresholdMs && isDevDiagEnabled()) {
            Timber.tag("EditorLayoutCache").w(
                "Slow prefix build: %dms len=%d textSize=%.1f",
                costMs,
                length,
                paint.textSize
            )
        }

        return layout
    }

    private fun applyInlayHints(
        lineText: String,
        rawPrefix: FloatArray,
        inlayHints: List<EditorInlayHint>,
        textPaint: Paint,
        lineHeightPx: Float,
    ): PrefixLayout {
        if (inlayHints.isEmpty()) {
            return PrefixLayout(lineText = lineText, prefix = rawPrefix)
        }

        val length = lineText.length
        val normalizedHints = inlayHints.withIndex()
            .asSequence()
            .filter { indexed -> indexed.value.label.isNotBlank() }
            .map { indexed ->
                IndexedValue(
                    index = indexed.index,
                    value = indexed.value.copy(
                        column = EditorInlayHintColumnLayout.normalizeAnchorColumn(
                            lineText,
                            indexed.value.column,
                        ),
                    ),
                )
            }
            .sortedWith(compareBy<IndexedValue<EditorInlayHint>> { it.value.column }.thenBy { it.index })
            .map { it.value }
            .toList()
        if (normalizedHints.isEmpty()) {
            return PrefixLayout(lineText = lineText, prefix = rawPrefix)
        }

        EditorInlayHintLayoutMetrics.configureHintPaint(
            hintPaint = hintMeasurePaint,
            textPaint = textPaint,
            lineHeightPx = lineHeightPx,
        )
        val innerPadding = EditorInlayHintLayoutMetrics.innerPadding(hintMeasurePaint)
        val lspPadding = EditorInlayHintLayoutMetrics.lspPadding(hintMeasurePaint)

        val beforeInlayPrefix = FloatArray(length + 1)
        val adjustedPrefix = FloatArray(length + 1)
        val placements = ArrayList<EditorInlayHintPlacement>(normalizedHints.size)
        val columns = IntArray(normalizedHints.size)
        var columnCount = 0
        var hintIndex = 0
        var accumulatedHintWidth = 0f
        for (column in 0..length) {
            val before = rawPrefix[column] + accumulatedHintWidth
            beforeInlayPrefix[column] = before
            var sameColumnWidth = 0f
            var columnHasHint = false
            while (hintIndex < normalizedHints.size && normalizedHints[hintIndex].column == column) {
                val hint = normalizedHints[hintIndex]
                val leftPadding = innerPadding + if (hint.paddingLeft) lspPadding else 0f
                val rightPadding = innerPadding + if (hint.paddingRight) lspPadding else 0f
                val labelWidth = hintMeasurePaint.measureText(hint.label).coerceAtLeast(0f)
                val totalWidth = (labelWidth + leftPadding + rightPadding).coerceAtLeast(0f)
                val startAdvance = before + sameColumnWidth
                placements += EditorInlayHintPlacement(
                    hint = hint,
                    column = column,
                    startAdvance = startAdvance,
                    endAdvance = startAdvance + totalWidth,
                    leftPadding = leftPadding,
                )
                sameColumnWidth += totalWidth
                columnHasHint = true
                hintIndex++
            }
            if (columnHasHint) {
                columns[columnCount++] = column
            }
            accumulatedHintWidth += sameColumnWidth
            adjustedPrefix[column] = rawPrefix[column] + accumulatedHintWidth
        }

        return PrefixLayout(
            lineText = lineText,
            prefix = adjustedPrefix,
            beforeInlayPrefix = beforeInlayPrefix,
            inlayHintPlacements = placements,
            inlayHintColumns = if (columnCount == columns.size) columns else columns.copyOf(columnCount),
        )
    }

    private fun putEntry(line: Int, layout: PrefixLayout, inlayHints: List<EditorInlayHint>) {
        val existing = lru.put(
            line,
            Entry(
                lineText = layout.lineText,
                inlayHints = inlayHints,
                layout = layout,
            ),
        )
        if (existing != null) {
            totalFloats -= existing.layout.storedFloatCount
        }
        totalFloats += layout.storedFloatCount
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        // 先按数量控制，再按内存控制
        while (lru.size > maxEntries) {
            evictOne()
        }
        while (totalFloats > maxTotalFloats && lru.isNotEmpty()) {
            evictOne()
        }
    }

    private fun evictOne() {
        val iterator = lru.entries.iterator()
        if (!iterator.hasNext()) return
        val toRemove = iterator.next()
        totalFloats -= toRemove.value.layout.storedFloatCount
        iterator.remove()
    }

    private fun invalidateRangeInternal(firstLine: Int, lastLine: Int) {
        if (firstLine > lastLine) return
        val iterator = lru.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in firstLine..lastLine) {
                totalFloats -= entry.value.layout.storedFloatCount
                iterator.remove()
            }
        }
    }

    private fun shiftCacheInternal(shiftFromLine: Int, deltaLines: Int) {
        if (deltaLines == 0) return
        if (lru.isEmpty()) return

        // 需要重建 key：LinkedHashMap 不能原地改 key
        val shifted = LinkedHashMap<Int, Entry>(lru.size, 0.75f, true)
        for ((line, entry) in lru) {
            val newLine = if (line >= shiftFromLine) line + deltaLines else line
            if (newLine < 0) {
                totalFloats -= entry.layout.storedFloatCount
                continue
            }
            shifted[newLine] = entry
        }
        lru.clear()
        lru.putAll(shifted)
    }

    private fun isDevDiagEnabled(): Boolean = runCatching {
        Prefs.developerOptionsEnabled && Prefs.devDiagnosticsEnabled
    }.getOrDefault(false)

    private companion object {
        fun resolveLineHeight(paint: Paint): Float {
            val metrics = paint.fontMetrics
            return (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
        }
    }
}

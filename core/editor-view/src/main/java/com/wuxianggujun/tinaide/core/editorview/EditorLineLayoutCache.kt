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
 * - 该缓存基于 [Paint.getTextRunAdvances] 获取每个 char 的 advance，并构建前缀和数组 prefix：
 *   prefix[i] 表示 [0, i) 的累计宽度（像素）。
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
        val prefix: FloatArray
    ) {
        val length: Int
            get() = lineText.length
    }

    private data class Entry(
        val lineText: String,
        var prefix: FloatArray
    )

    // accessOrder=true => LRU
    private val lru = LinkedHashMap<Int, Entry>(64, 0.75f, true)
    private var totalFloats: Int = 0

    private var cacheVersion: Long = Long.MIN_VALUE
    private var layoutSignature: Int = 0

    // 复用缓冲区：避免每次计算 advances 都分配数组
    private var scratchChars: CharArray = CharArray(0)
    private var scratchAdvances: FloatArray = FloatArray(0)

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
        line: Int,
        lineText: String,
        textVersion: Long,
        paint: Paint,
        tabSize: Int
    ): PrefixLayout {
        synchronized(lock) {
            ensureSignature(textVersion, paint, tabSize)
            val entry = lru[line]
            if (entry != null && entry.lineText == lineText) {
                return PrefixLayout(lineText = entry.lineText, prefix = entry.prefix)
            }

            val built = buildPrefix(lineText, paint, tabSize)
            putEntry(line, built.lineText, built.prefix)
            return built
        }
    }

    fun xToColumn(layout: PrefixLayout, contentX: Float): Int {
        if (layout.length <= 0) return 0
        val prefix = layout.prefix
        val targetX = contentX.coerceAtLeast(0f)

        var low = 0
        var high = layout.length
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefix[mid] < targetX) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val right = low.coerceIn(0, layout.length)
        val left = (right - 1).coerceAtLeast(0)
        if (right == left) return right
        val leftAdvance = prefix[left]
        val rightAdvance = prefix[right]
        val nearest = if ((targetX - leftAdvance) <= (rightAdvance - targetX)) left else right
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
        val codePointMidpoint = (prefix[codePointStart] + prefix[codePointEnd]) / 2f
        return if (targetX <= codePointMidpoint) codePointStart else codePointEnd
    }

    private fun ensureSignature(textVersion: Long, paint: Paint, tabSize: Int) {
        val sig = layoutSignature(paint, tabSize)
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

    private fun layoutSignature(paint: Paint, tabSize: Int): Int {
        val base = paintSignature(paint)
        return 31 * base + tabSize
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

    private fun buildPrefix(lineText: String, paint: Paint, tabSize: Int): PrefixLayout {
        val startMs = SystemClock.uptimeMillis()
        val length = lineText.length
        if (length <= 0) {
            return PrefixLayout(lineText = lineText, prefix = FloatArray(1))
        }

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
            0
        )

        val safeTabSize = tabSize.coerceAtLeast(1)
        val spaceAdvance = paint.measureText(" ").coerceAtLeast(0f)
        val visualColumns = TextScanKernel.buildVisualColumnPrefix(lineText, safeTabSize)

        val prefix = FloatArray(length + 1)
        var running = 0f
        prefix[0] = 0f
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
            prefix[i + 1] = running
        }

        val costMs = SystemClock.uptimeMillis() - startMs
        if (costMs >= slowBuildThresholdMs && isDevDiagEnabled()) {
            Timber.tag("EditorLayoutCache").w(
                "Slow prefix build: %dms len=%d textSize=%.1f",
                costMs,
                length,
                paint.textSize
            )
        }

        return PrefixLayout(lineText = lineText, prefix = prefix)
    }

    private fun putEntry(line: Int, lineText: String, prefix: FloatArray) {
        val existing = lru.put(line, Entry(lineText = lineText, prefix = prefix))
        if (existing != null) {
            totalFloats -= existing.prefix.size
        }
        totalFloats += prefix.size
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
        totalFloats -= toRemove.value.prefix.size
        iterator.remove()
    }

    private fun invalidateRangeInternal(firstLine: Int, lastLine: Int) {
        if (firstLine > lastLine) return
        val iterator = lru.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in firstLine..lastLine) {
                totalFloats -= entry.value.prefix.size
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
                totalFloats -= entry.prefix.size
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
}

package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import org.junit.Test

/**
 * [EditorMaxLineWidthTracker.maxScrollXPx] 行为测试。
 *
 * 覆盖：
 * - wordWrap 开启时：冻结布局返回 half-viewport 的 endPadding，未冻结返回 0。
 * - 非 wordWrap：基于最长行宽 + half-viewport endPadding，且按 charWidthPx 缩放。
 * - 空/短内容时下界 coerce 到 0。
 *
 * 注：小文件（<=4000 行）走全量快照路径（buildFullWidthSnapshot），
 * 该路径直接返回最长行的视觉列数，不叠加光标 guard——这是被测源码现状，测试如实对齐。
 * [lineVisualColumns] 在此用每字符 1 列的口径建模（与默认无 Tab 文本一致）。
 */
class EditorMaxLineWidthTrackerTest {

    private class FakeTrackerHost(
        override val textBuffer: TextBuffer,
        override var charWidthPx: Float = 1f,
        override var viewportWidthPx: Float = 20f,
        override var cursorLine: Int = 0,
        override var visibleLines: IntRange = 0..0,
        override var wordWrapEnabled: Boolean = false,
        override var isWordWrapLayoutFrozen: Boolean = false,
        override var inlayHintsVersion: Long = 0L,
        var inlayExpandedLineColumns: Int = 0,
    ) : EditorMaxLineWidthTracker.Host {

        override fun lineVisualColumns(lineText: String): Int = lineText.length

        override fun currentVisualLineMap(): EditorVisualLineMapper.VisualLineMap {
            val count = textBuffer.lineCount.coerceAtLeast(0)
            val v = IntArray(count) { it }
            return EditorVisualLineMapper.VisualLineMap(
                docLineCount = count,
                visibleDocLines = v,
                firstVisualLineByVisibleIndex = v.copyOf(),
                visualLineCountByVisibleIndex = IntArray(count) { 1 },
                visualLineCount = count,
                wordWrapEnabled = false,
                wrapColumns = 0
            )
        }

        override fun resolveVisibleIndexForVisualLine(
            map: EditorVisualLineMapper.VisualLineMap,
            visualLine: Int
        ): Int = visualLine.coerceIn(0, (map.visibleDocLineCount - 1).coerceAtLeast(0))

        override fun maxInlayExpandedLineVisualColumns(): Int = inlayExpandedLineColumns
    }

    @Test
    fun maxScrollXPx_wordWrapFrozenReturnsHalfViewportPadding() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "x".repeat(100)) },
            viewportWidthPx = 20f,
            wordWrapEnabled = true,
            isWordWrapLayoutFrozen = true
        )
        val tracker = EditorMaxLineWidthTracker(host)

        // endPadding = viewportWidthPx * 0.5 = 10
        assertThat(tracker.maxScrollXPx()).isEqualTo(10f)
    }

    @Test
    fun maxScrollXPx_wordWrapNotFrozenReturnsZero() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "x".repeat(100)) },
            viewportWidthPx = 20f,
            wordWrapEnabled = true,
            isWordWrapLayoutFrozen = false
        )
        val tracker = EditorMaxLineWidthTracker(host)

        assertThat(tracker.maxScrollXPx()).isEqualTo(0f)
    }

    @Test
    fun maxScrollXPx_nonWordWrapUsesLongestLineWidthPlusPadding() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "x".repeat(100) + "\n" + "yy") },
            charWidthPx = 1f,
            viewportWidthPx = 20f,
            cursorLine = 0,
            visibleLines = 0..1
        )
        val tracker = EditorMaxLineWidthTracker(host)

        // maxLineWidth = 100 * 1; (100 - 20 + 10) = 90
        assertThat(tracker.maxScrollXPx()).isEqualTo(90f)
    }

    @Test
    fun maxScrollXPx_scalesWithCharWidth() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "x".repeat(100)) },
            charWidthPx = 2f,
            viewportWidthPx = 20f
        )
        val tracker = EditorMaxLineWidthTracker(host)

        // maxLineWidth = 100 * 2 = 200; (200 - 20 + 10) = 190
        assertThat(tracker.maxScrollXPx()).isEqualTo(190f)
    }

    @Test
    fun maxScrollXPx_shortContentCoercesToZero() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "ab") },
            charWidthPx = 1f,
            viewportWidthPx = 20f
        )
        val tracker = EditorMaxLineWidthTracker(host)

        // (2 - 20 + 10) < 0 -> coerced to 0
        assertThat(tracker.maxScrollXPx()).isEqualTo(0f)
    }

    @Test
    fun maxScrollXPx_emptyBufferReturnsZero() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer(),
            charWidthPx = 1f,
            viewportWidthPx = 20f
        )
        val tracker = EditorMaxLineWidthTracker(host)

        assertThat(tracker.maxScrollXPx()).isEqualTo(0f)
    }

    @Test
    fun maxScrollXPx_inlayHintWidthUpdatesScrollLimitWithoutTextEdit() {
        val host = FakeTrackerHost(
            textBuffer = RopeTextBuffer().apply { insert(0, "ab") },
            charWidthPx = 1f,
            viewportWidthPx = 20f,
            inlayExpandedLineColumns = 40,
        )
        val tracker = EditorMaxLineWidthTracker(host)

        assertThat(tracker.maxScrollXPx()).isEqualTo(30f)

        host.inlayExpandedLineColumns = 60
        host.inlayHintsVersion++

        assertThat(tracker.maxScrollXPx()).isEqualTo(50f)
    }

    @Test
    fun invalidateWidthSnapshot_recomputesAfterTextGrows() {
        val buffer = RopeTextBuffer().apply { insert(0, "ab") }
        val host = FakeTrackerHost(
            textBuffer = buffer,
            charWidthPx = 1f,
            viewportWidthPx = 20f
        )
        val tracker = EditorMaxLineWidthTracker(host)

        assertThat(tracker.maxScrollXPx()).isEqualTo(0f)

        buffer.insert(2, "x".repeat(100))
        tracker.invalidateWidthSnapshot()

        // longest line now 102 chars: (102 - 20 + 10) = 92
        assertThat(tracker.maxScrollXPx()).isEqualTo(92f)
    }
}

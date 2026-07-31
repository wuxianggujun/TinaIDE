package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import org.junit.Test

/**
 * [EditorVisualLineMapper] 纯计算逻辑测试。
 *
 * 通过 [FakeMapperHost] 注入只读宿主状态，覆盖：
 * - resolveVisibleIndexForDocLine：越界 coerce、隐藏行回落到 owner、空映射返回 -1。
 * - resolveVisibleIndexForVisualLine：二分查找首/尾、越界、空数组。
 * - visualLineMap：空文档、非 wordWrap（逐行 1 段）、wordWrap 多段累计、缓存命中、
 *   以及“非 wordWrap 下文本编辑不重建缓存”这一刻意保留的现状。
 */
class EditorVisualLineMapperTest {

    private class CountingTextBuffer(
        private val delegate: TextBuffer
    ) : TextBuffer by delegate {
        var getLineCallCount: Int = 0
            private set

        override fun getLine(line: Int): String {
            getLineCallCount++
            return delegate.getLine(line)
        }

        fun resetGetLineCallCount() {
            getLineCallCount = 0
        }
    }

    private class FakeMapperHost(
        override val textBuffer: TextBuffer,
        override var charWidthPx: Float = 1f,
        override var viewportWidthPx: Float = 100f,
        override var wordWrapEnabled: Boolean = false,
        override var tabSize: Int = 4,
        override var codeFoldingEnabled: Boolean = false,
        override var frozenWordWrapColumns: Int? = null,
        override var foldRegionsDocumentVersion: Long = -1L,
        override var foldDataVersion: Int = 0,
        private var lineMapValue: EditorFoldingManager.LineMap
    ) : EditorVisualLineMapper.Host {
        override fun lineMap(): EditorFoldingManager.LineMap = lineMapValue

        fun setLineMap(map: EditorFoldingManager.LineMap) {
            lineMapValue = map
        }
    }

    private fun identityLineMap(lineCount: Int): EditorFoldingManager.LineMap {
        val v = IntArray(lineCount) { it }
        return EditorFoldingManager.LineMap(
            docLineCount = lineCount,
            visualToDocLine = v,
            docToVisualLine = v.copyOf(),
            hiddenDocLine = BooleanArray(lineCount),
            hiddenOwnerStartLine = IntArray(lineCount) { -1 }
        )
    }

    private fun emptyLineMap(): EditorFoldingManager.LineMap = EditorFoldingManager.LineMap(
        docLineCount = 0,
        visualToDocLine = IntArray(0),
        docToVisualLine = IntArray(0),
        hiddenDocLine = BooleanArray(0),
        hiddenOwnerStartLine = IntArray(0)
    )

    // ---- resolveVisibleIndexForDocLine ----

    @Test
    fun resolveVisibleIndexForDocLine_returnsDirectMapping() {
        val buffer = RopeTextBuffer().apply { insert(0, "a\nb\nc") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(3))
        val mapper = EditorVisualLineMapper(host)

        assertThat(mapper.resolveVisibleIndexForDocLine(1)).isEqualTo(1)
    }

    @Test
    fun resolveVisibleIndexForDocLine_coercesOutOfRange() {
        val buffer = RopeTextBuffer().apply { insert(0, "a\nb\nc") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(3))
        val mapper = EditorVisualLineMapper(host)

        assertThat(mapper.resolveVisibleIndexForDocLine(-5)).isEqualTo(0)
        assertThat(mapper.resolveVisibleIndexForDocLine(999)).isEqualTo(2)
    }

    @Test
    fun resolveVisibleIndexForDocLine_hiddenLineFallsBackToOwner() {
        // line2 隐藏（docToVisual=-1），owner=line1。
        val map = EditorFoldingManager.LineMap(
            docLineCount = 3,
            visualToDocLine = intArrayOf(0, 1),
            docToVisualLine = intArrayOf(0, 1, -1),
            hiddenDocLine = booleanArrayOf(false, false, true),
            hiddenOwnerStartLine = intArrayOf(-1, -1, 1)
        )
        val buffer = RopeTextBuffer().apply { insert(0, "a\nb\nc") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = map)
        val mapper = EditorVisualLineMapper(host)

        assertThat(mapper.resolveVisibleIndexForDocLine(2)).isEqualTo(1)
    }

    @Test
    fun resolveVisibleIndexForDocLine_emptyMapReturnsMinusOne() {
        val host = FakeMapperHost(textBuffer = RopeTextBuffer(), lineMapValue = emptyLineMap())
        val mapper = EditorVisualLineMapper(host)

        assertThat(mapper.resolveVisibleIndexForDocLine(0)).isEqualTo(-1)
    }

    // ---- resolveVisibleIndexForVisualLine ----

    @Test
    fun resolveVisibleIndexForVisualLine_binarySearchAndBounds() {
        // 两个可见行，第一行 wrap 成 2 段：firstVisual = [0, 2]
        val buffer = RopeTextBuffer().apply { insert(0, "aaaaaaaa\nbb") }
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 4,
            lineMapValue = identityLineMap(2)
        )
        val mapper = EditorVisualLineMapper(host)
        val map = mapper.visualLineMap()

        assertThat(map.firstVisualLineByVisibleIndex.toList()).containsExactly(0, 2).inOrder()
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, -3)).isEqualTo(0)
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, 0)).isEqualTo(0)
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, 1)).isEqualTo(0)
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, 2)).isEqualTo(1)
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, 999)).isEqualTo(1)
    }

    @Test
    fun resolveVisibleIndexForVisualLine_emptyStartsReturnsZero() {
        val host = FakeMapperHost(textBuffer = RopeTextBuffer(), lineMapValue = emptyLineMap())
        val mapper = EditorVisualLineMapper(host)
        val map = mapper.visualLineMap()

        assertThat(map.firstVisualLineByVisibleIndex).isEmpty()
        assertThat(mapper.resolveVisibleIndexForVisualLine(map, 5)).isEqualTo(0)
    }

    // ---- visualLineMap ----

    @Test
    fun visualLineMap_emptyVisibleLinesYieldsZeroVisualLines() {
        val host = FakeMapperHost(textBuffer = RopeTextBuffer(), lineMapValue = emptyLineMap())
        val mapper = EditorVisualLineMapper(host)

        val map = mapper.visualLineMap()

        assertThat(map.visualLineCount).isEqualTo(0)
        assertThat(map.firstVisualLineByVisibleIndex).isEmpty()
        assertThat(map.visualLineCountByVisibleIndex).isEmpty()
    }

    @Test
    fun visualLineMap_noWordWrapAssignsOneSegmentPerLine() {
        val buffer = RopeTextBuffer().apply { insert(0, "a\nbb\nccc") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(3))
        val mapper = EditorVisualLineMapper(host)

        val map = mapper.visualLineMap()

        assertThat(map.visualLineCount).isEqualTo(3)
        assertThat(map.firstVisualLineByVisibleIndex.toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(map.visualLineCountByVisibleIndex.toList()).containsExactly(1, 1, 1).inOrder()
        assertThat(map.wordWrapEnabled).isFalse()
    }

    @Test
    fun visualLineMap_wordWrapAccumulatesSegmentsAcrossFirstAndLastLine() {
        // line0 = 8 chars -> 2 段（wrap=4），line1 = 2 chars -> 1 段
        val buffer = RopeTextBuffer().apply { insert(0, "aaaaaaaa\nbb") }
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 4,
            lineMapValue = identityLineMap(2)
        )
        val mapper = EditorVisualLineMapper(host)

        val map = mapper.visualLineMap()

        assertThat(map.wordWrapEnabled).isTrue()
        assertThat(map.visualLineCountByVisibleIndex.toList()).containsExactly(2, 1).inOrder()
        assertThat(map.firstVisualLineByVisibleIndex.toList()).containsExactly(0, 2).inOrder()
        assertThat(map.visualLineCount).isEqualTo(3)
    }

    @Test
    fun visualLineMap_returnsCachedInstanceWhenEpochUnchanged() {
        val buffer = RopeTextBuffer().apply { insert(0, "a\nb") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(2))
        val mapper = EditorVisualLineMapper(host)

        val first = mapper.visualLineMap()
        val second = mapper.visualLineMap()

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun invalidateVisualLineMapCache_forcesRebuild() {
        val buffer = RopeTextBuffer().apply { insert(0, "a\nb") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(2))
        val mapper = EditorVisualLineMapper(host)

        val first = mapper.visualLineMap()
        mapper.invalidateVisualLineMapCache()
        val second = mapper.visualLineMap()

        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.visualLineCount).isEqualTo(first.visualLineCount)
    }

    @Test
    fun visualLineMap_nonWordWrapEditKeepsCachedInstance() {
        // 刻意保留的现状：wordWrap 关闭时 epoch 把 textVersion 归零，
        // 纯文本编辑（不改行数/折叠）不会重建视觉行映射。
        val buffer = RopeTextBuffer().apply { insert(0, "abc\ndef") }
        val host = FakeMapperHost(textBuffer = buffer, lineMapValue = identityLineMap(2))
        val mapper = EditorVisualLineMapper(host)

        val first = mapper.visualLineMap()
        // 行内编辑：行数不变，版本号增加。
        buffer.insert(1, "ZZZ")
        val second = mapper.visualLineMap()

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun visualLineMap_wrapParameterChange_shouldOnlyScanVisibleDocumentLines() {
        val buffer = CountingTextBuffer(RopeTextBuffer("aaaa\nbbbb\ncccc"))
        val foldedMap = EditorFoldingManager.LineMap(
            docLineCount = 3,
            visualToDocLine = intArrayOf(0, 2),
            docToVisualLine = intArrayOf(0, -1, 1),
            hiddenDocLine = booleanArrayOf(false, true, false),
            hiddenOwnerStartLine = intArrayOf(-1, 0, -1)
        )
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 4,
            lineMapValue = foldedMap
        )
        val mapper = EditorVisualLineMapper(host)

        mapper.visualLineMap()
        assertThat(buffer.getLineCallCount).isEqualTo(2)

        buffer.resetGetLineCallCount()
        host.frozenWordWrapColumns = 2
        mapper.visualLineMap()

        assertThat(buffer.getLineCallCount).isEqualTo(2)
    }

    @Test
    fun applyTextChangeToDocSegmentCounts_shouldOnlyInvalidateEditedLine() {
        val rope = RopeTextBuffer("aaaa\nbb\ncccc")
        val buffer = CountingTextBuffer(rope)
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 4,
            lineMapValue = identityLineMap(3)
        )
        val mapper = EditorVisualLineMapper(host)
        mapper.visualLineMap()
        buffer.resetGetLineCallCount()
        var change: TextChange? = null
        rope.addChangeListener { change = it }

        rope.insert(1, "x")
        mapper.applyTextChangeToDocSegmentCounts(checkNotNull(change), rope.version)
        val updated = mapper.visualLineMap()

        assertThat(buffer.getLineCallCount).isEqualTo(1)
        assertThat(updated.visualLineCountByVisibleIndex.toList()).containsExactly(2, 1, 1).inOrder()
    }

    @Test
    fun applyTextChangeToDocSegmentCounts_inconsistentLineDeltaShouldDiscardCacheSafely() {
        val rope = RopeTextBuffer("aa\nbb")
        val buffer = CountingTextBuffer(rope)
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 2,
            lineMapValue = identityLineMap(2)
        )
        val mapper = EditorVisualLineMapper(host)
        mapper.visualLineMap()
        var firstChange: TextChange? = null
        rope.addChangeListener { change ->
            if (firstChange == null) firstChange = change
        }
        rope.insert(1, "\n")
        rope.insert(0, "\n")
        host.setLineMap(identityLineMap(4))
        buffer.resetGetLineCallCount()

        mapper.applyTextChangeToDocSegmentCounts(
            change = checkNotNull(firstChange),
            newVersion = rope.version
        )
        val rebuilt = mapper.visualLineMap()

        assertThat(rebuilt.docLineCount).isEqualTo(4)
        assertThat(rebuilt.visualLineCountByVisibleIndex).hasLength(4)
        assertThat(buffer.getLineCallCount).isEqualTo(4)
    }

    @Test
    fun applyTextChangeToDocSegmentCounts_skippedSameLineEventShouldDiscardCacheSafely() {
        val rope = RopeTextBuffer("aaaa\nbbbb\ncccc")
        val buffer = CountingTextBuffer(rope)
        val host = FakeMapperHost(
            textBuffer = buffer,
            wordWrapEnabled = true,
            frozenWordWrapColumns = 4,
            lineMapValue = identityLineMap(3)
        )
        val mapper = EditorVisualLineMapper(host)
        mapper.visualLineMap()
        var latestChange: TextChange? = null
        rope.addChangeListener { latestChange = it }

        // 刻意漏掉 version=1 的事件；两次编辑均不改变行数，单靠 lineDelta 无法发现缓存已陈旧。
        rope.insert(0, "zzzz")
        rope.insert(rope.length, "x")
        buffer.resetGetLineCallCount()

        mapper.applyTextChangeToDocSegmentCounts(
            change = checkNotNull(latestChange),
            newVersion = rope.version
        )
        val rebuilt = mapper.visualLineMap()

        assertThat(rebuilt.visualLineCountByVisibleIndex.toList()).containsExactly(2, 1, 2).inOrder()
        assertThat(buffer.getLineCallCount).isEqualTo(3)
    }
}

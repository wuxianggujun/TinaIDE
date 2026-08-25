package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import org.junit.Test

class EditorStateWordWrapTest {

    @Test
    fun visualLineMap_shouldMatchWrapLayoutAcrossRepresentativeInputs() {
        val cases = listOf(
            WrapCase(lineText = "", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "abcd", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "abcde", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "ab\tcd", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "\t\tab", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "a😀bc", wrapColumns = 4, tabSize = 4)
        )

        cases.forEach { case ->
            val buffer = RopeTextBuffer().apply {
                if (case.lineText.isNotEmpty()) {
                    insert(0, case.lineText)
                }
            }
            val state = createWordWrapState(buffer, case.wrapColumns, case.tabSize)
            val wrapLayout = EditorWordWrapLayoutCache().getWrapLayout(
                line = 0,
                lineText = case.lineText,
                textVersion = buffer.version,
                wrapColumns = case.wrapColumns,
                tabSize = case.tabSize
            )

            assertThat(state.visualLineCount()).isEqualTo(wrapLayout.segmentCount)
            repeat(wrapLayout.segmentCount) { segmentIndex ->
                assertThat(state.visualLineStartColumn(segmentIndex))
                    .isEqualTo(wrapLayout.startColumnForSegment(segmentIndex))
                assertThat(state.visualLineEndColumn(segmentIndex))
                    .isEqualTo(wrapLayout.endColumnForSegment(segmentIndex))
                assertThat(
                    state.visualLineForPosition(
                        line = 0,
                        column = wrapLayout.startColumnForSegment(segmentIndex)
                    )
                ).isEqualTo(segmentIndex)
            }
        }
    }

    @Test
    fun visualLineCount_shouldAccumulateSegmentCountsAcrossLines() {
        val lines = listOf("ab\tcd", "12345", "😀xy")
        val wrapColumns = 4
        val tabSize = 4
        val content = lines.joinToString("\n")
        val buffer = RopeTextBuffer().apply { insert(0, content) }
        val state = createWordWrapState(buffer, wrapColumns, tabSize)
        val cache = EditorWordWrapLayoutCache()

        val expectedVisualLines = lines.mapIndexed { index, lineText ->
            cache.getWrapLayout(
                line = index,
                lineText = lineText,
                textVersion = buffer.version,
                wrapColumns = wrapColumns,
                tabSize = tabSize
            ).segmentCount
        }.sum()

        assertThat(state.visualLineCount()).isEqualTo(expectedVisualLines)
    }

    @Test
    fun wrapLayoutCache_withSameLengthReplacement_shouldUseNewText() {
        val cache = EditorWordWrapLayoutCache()

        val withTab = cache.getWrapLayout(0, "a\tb", 1L, 3, 4)
        val withoutTab = cache.getWrapLayout(0, "abc", 1L, 3, 4)

        assertThat(withTab.segmentCount).isGreaterThan(1)
        assertThat(withoutTab.segmentCount).isEqualTo(1)
    }

    @Test
    fun wrapLayoutCache_withCollidingLegacyParameterHash_shouldRebuildLayout() {
        val lineText = "\tabc"
        val cache = EditorWordWrapLayoutCache()

        val first = cache.getWrapLayout(0, lineText, 1L, wrapColumns = 1, tabSize = 32)
        val second = cache.getWrapLayout(0, lineText, 1L, wrapColumns = 2, tabSize = 1)
        val expected = EditorWordWrapLayoutCache()
            .getWrapLayout(0, lineText, 1L, wrapColumns = 2, tabSize = 1)

        assertThat(first.starts.asList()).isNotEqualTo(expected.starts.asList())
        assertThat(second.starts.asList()).containsExactlyElementsIn(expected.starts.asList()).inOrder()
    }

    @Test
    fun maxVerticalScrollOffset_shouldRefreshWhenWordWrapChangesWithoutTextEdit() {
        val buffer = RopeTextBuffer().apply { insert(0, "abcdefghij\nabcdefghij") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(wordWrap = false, codeFolding = false)
        ).apply {
            updateMetrics(10f, 1f, 20f, 5f, 0f)
        }
        val unwrappedMaxScroll = state.maxVerticalScrollOffsetPx()

        state.config = state.config.copy(wordWrap = true)

        assertThat(state.maxVerticalScrollOffsetPx()).isGreaterThan(unwrappedMaxScroll)
    }

    @Test
    fun maxVerticalScrollOffset_shouldRefreshWhenFoldStateChangesWithoutTextEdit() {
        val buffer = RopeTextBuffer().apply { insert(0, "zero\none\ntwo\nthree\nfour") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(wordWrap = false, codeFolding = true)
        ).apply {
            updateMetrics(10f, 1f, 20f, 20f, 0f)
            setFoldRegions(listOf(FoldRegion(startLine = 0, endLine = 3)), buffer.version)
        }
        val expandedMaxScroll = state.maxVerticalScrollOffsetPx()

        state.toggleFoldAtLine(0)

        assertThat(state.maxVerticalScrollOffsetPx()).isLessThan(expandedMaxScroll)
    }

    private fun createWordWrapState(
        buffer: RopeTextBuffer,
        wrapColumns: Int,
        tabSize: Int
    ): EditorState = EditorState(
        textBuffer = buffer,
        config = EditorConfig(
            wordWrap = true,
            codeFolding = false,
            tabSize = tabSize
        )
    ).apply {
        updateMetrics(
            lineHeightPx = 1f,
            charWidthPx = 1f,
            viewportHeightPx = 100f,
            viewportWidthPx = wrapColumns.toFloat(),
            contentStartXPx = 0f
        )
    }

    private data class WrapCase(
        val lineText: String,
        val wrapColumns: Int,
        val tabSize: Int
    )
}

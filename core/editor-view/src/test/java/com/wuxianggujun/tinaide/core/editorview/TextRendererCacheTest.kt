package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.HighlightLineSegment
import com.wuxianggujun.tinaide.core.treesitter.HighlightSpan
import com.wuxianggujun.tinaide.core.treesitter.HighlightType
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TextRendererCacheTest {

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldBoundSynchronousLookups() {
        val state = EditorState(RopeTextBuffer((0 until 10_000).joinToString("\n") { "line$it" }))
        val highlighter = CountingSyntaxHighlighter()
        state.highlighter = highlighter

        TextRenderer().resolveDrawHighlightSegmentsForVisibleWindow(state, 5_000..5_020)

        assertThat(highlighter.requestedLines).containsExactlyElementsIn(4_968..5_052).inOrder()
    }

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldRefreshWhenHighlighterChanges() {
        val state = EditorState(RopeTextBuffer("text"))
        val first = CountingSyntaxHighlighter()
        val second = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = first
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0)

        state.highlighter = second
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0)

        assertThat(second.requestedLines).containsExactly(0)
    }

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldReuseCachedWindowUntilHighlightVersionChanges() {
        val buffer = RopeTextBuffer().apply {
            insert(0, (0..80).joinToString("\n") { "line$it" })
        }
        val state = EditorState(buffer)
        val highlighter = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = highlighter

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 10..12)
        val firstPassCalls = highlighter.requestedLines.size

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 11..12)

        assertThat(highlighter.requestedLines.size).isEqualTo(firstPassCalls)

        state.notifyHighlightChanged()
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 11..12)

        assertThat(highlighter.requestedLines.size).isGreaterThan(firstPassCalls)
    }

    @Test
    fun applyTextChange_shouldInvalidateVisibleHighlightCache() {
        val buffer = RopeTextBuffer().apply {
            insert(0, (0..40).joinToString("\n") { "line$it" })
        }
        val state = EditorState(buffer)
        val highlighter = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = highlighter

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 5..8)
        val firstPassCalls = highlighter.requestedLines.size

        renderer.applyTextChange(
            change = TextChange(
                startOffset = 0,
                endOffset = 0,
                oldText = "",
                newText = "/",
                startLine = 0,
                startColumn = 0,
                endLine = 0,
                endColumn = 0
            ),
            currentVersion = state.textBuffer.version
        )
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 5..8)

        assertThat(highlighter.requestedLines.size).isGreaterThan(firstPassCalls)
    }

    private class CountingSyntaxHighlighter : SyntaxHighlighter {
        val requestedLines = mutableListOf<Int>()

        override fun highlight(text: String, visibleRange: IntRange): List<HighlightSpan> = emptyList()

        override fun getLineSegments(line: Int): List<HighlightLineSegment> {
            requestedLines += line
            return listOf(
                HighlightLineSegment(
                    startColumn = 0,
                    endColumn = 1,
                    type = HighlightType.KEYWORD
                )
            )
        }
    }
}

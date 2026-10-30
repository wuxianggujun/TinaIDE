package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.treesitter.HighlightLineSegment
import com.wuxianggujun.tinaide.core.treesitter.HighlightSpan
import com.wuxianggujun.tinaide.core.treesitter.HighlightType
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeBlockHighlightingTest {
    @Test
    fun pushUpdates_refreshCacheMissesAndDetachOnCancellation() = runTest {
        val highlighter = FakeHighlighter(emptyMap())
        val results = mutableListOf<List<HighlightSpan>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            codeBlockHighlightUpdates("int", highlighter).collect { results += it }
        }
        runCurrent()
        assertThat(highlighter.openedText).isEqualTo("int")
        assertThat(results).containsExactly(emptyList<HighlightSpan>())

        highlighter.lines = mapOf(0 to listOf(HighlightLineSegment(0, 3, HighlightType.TYPE)))
        val callback = checkNotNull(highlighter.onUpdated)
        callback()
        runCurrent()

        assertThat(results.last()).containsExactly(HighlightSpan(0, 3, HighlightType.TYPE))
        collector.cancelAndJoin()
        assertThat(highlighter.onUpdated).isNull()
        val resultCount = results.size
        callback()
        runCurrent()
        assertThat(results).hasSize(resultCount)
    }

    @Test
    fun openFailure_detachesCallbackAndPropagatesError() = runTest {
        val highlighter = FakeHighlighter(emptyMap())
        val failure = IOException("Cannot open test document")
        highlighter.openFailure = failure

        val result = runCatching { codeBlockHighlightUpdates("int", highlighter).first() }

        // Coroutine stack-trace recovery may copy the exception while preserving its type/message.
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo(failure.message)
        assertThat(highlighter.onUpdated).isNull()
    }

    @Test
    fun crlfAndUnicode_preserveOriginalUtf16Offsets() {
        val code = "// 注释 😀\r\nint first;\r\nreturn second;"
        val highlighter = FakeHighlighter(
            mapOf(
                1 to listOf(HighlightLineSegment(0, 3, HighlightType.TYPE, 42)),
                2 to listOf(HighlightLineSegment(0, 6, HighlightType.KEYWORD)),
            )
        )

        val spans = collectCodeBlockHighlights(code, highlighter)

        assertThat(spans).containsExactly(
            HighlightSpan(code.indexOf("int"), code.indexOf("int") + 3, HighlightType.TYPE, 42),
            HighlightSpan(code.indexOf("return"), code.indexOf("return") + 6, HighlightType.KEYWORD),
        ).inOrder()
    }

    @Test
    fun bareCarriageReturn_doesNotInventATreeSitterLine() {
        val code = "a\rb\nreturn"
        val highlighter = FakeHighlighter(
            mapOf(
                0 to listOf(HighlightLineSegment(2, 3, HighlightType.VARIABLE)),
                1 to listOf(HighlightLineSegment(0, 6, HighlightType.KEYWORD)),
            )
        )

        assertThat(collectCodeBlockHighlights(code, highlighter)).containsExactly(
            HighlightSpan(2, 3, HighlightType.VARIABLE),
            HighlightSpan(4, 10, HighlightType.KEYWORD),
        ).inOrder()
        assertThat(highlighter.requestedLines).containsExactly(0, 1).inOrder()
    }

    @Test
    fun emptyAndTrailingLines_haveNoOutOfBoundsSpans() {
        val highlighter = FakeHighlighter(
            mapOf(0 to listOf(HighlightLineSegment(0, 100, HighlightType.KEYWORD)))
        )

        assertThat(collectCodeBlockHighlights("", highlighter)).isEmpty()
        highlighter.requestedLines.clear()
        assertThat(collectCodeBlockHighlights("a\n\n", highlighter))
            .containsExactly(HighlightSpan(0, 1, HighlightType.KEYWORD))
        assertThat(highlighter.requestedLines).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun invalidColumns_areClippedWithoutChangingPriority() {
        val highlighter = FakeHighlighter(
            mapOf(
                0 to listOf(
                    HighlightLineSegment(-2, 50, HighlightType.COMMENT, 7),
                    HighlightLineSegment(8, 10, HighlightType.TYPE),
                    HighlightLineSegment(2, 1, HighlightType.NUMBER),
                )
            )
        )

        assertThat(collectCodeBlockHighlights("abc\n", highlighter))
            .containsExactly(HighlightSpan(0, 3, HighlightType.COMMENT, 7))
    }

    private class FakeHighlighter(
        var lines: Map<Int, List<HighlightLineSegment>>,
    ) : SyntaxHighlighter {
        val requestedLines = mutableListOf<Int>()
        var onUpdated: (() -> Unit)? = null
        var openedText: String? = null
        var openFailure: IOException? = null

        override fun openDocument(text: String) {
            openFailure?.let { throw it }
            openedText = text
        }

        override fun openDocumentBlocking(text: String): Unit = error("Use the push-model lifecycle")

        override fun setOnStateUpdated(callback: (() -> Unit)?) {
            onUpdated = callback
        }

        override fun getLineSegments(line: Int): List<HighlightLineSegment> {
            requestedLines += line
            return lines[line].orEmpty()
        }
    }
}

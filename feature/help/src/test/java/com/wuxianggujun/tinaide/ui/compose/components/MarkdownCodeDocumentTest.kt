package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownCodeDocumentTest {
    @Test
    fun recognizedFenceAliasesOpenTheEditorsSyntaxHighlighterWithoutLsp() {
        for ((fence, expectedLanguage) in listOf("c++" to "cpp", "cmake" to "cmake", "json" to "json")) {
            val highlighter = RecordingHighlighter()
            var requested: String? = null
            MarkdownCodeDocument("code", fence) { requested = it; highlighter }.use { document ->
                assertThat(requested).isEqualTo(expectedLanguage)
                assertThat(highlighter.opened).isEqualTo("code")
                assertThat(document.state.highlighter).isSameInstanceAs(highlighter)
                assertThat(document.state.onRequestCompletion).isNull()
                assertThat(document.state.onRequestHover).isNull()
                assertThat(document.state.config.wordWrap).isFalse()
            }
            assertThat(highlighter.disposals).isEqualTo(1)
            assertThat(highlighter.updated).isNull()
        }
    }

    @Test
    fun unknownLanguageStillKeepsTheEntireSourceWithoutCreatingAParser() {
        val source = "unknown syntax\nsecond line"
        MarkdownCodeDocument(source, "unsupported-language") { error("Unexpected parser") }.use { document ->
            assertThat(document.state.highlighter).isNull()
            assertThat(document.state.textBuffer.substring(0, document.state.textBuffer.length)).isEqualTo(source)
        }
    }

    @Test
    fun immutableSnapshotsDoNotShareCallbacksAndReleaseTheirResourcesOnce() {
        val firstHighlighter = RecordingHighlighter()
        val secondHighlighter = RecordingHighlighter()
        val first = MarkdownCodeDocument("first", "cpp") { firstHighlighter }
        val second = MarkdownCodeDocument("second", "cpp") { secondHighlighter }
        try {
            val secondCallback = secondHighlighter.updated
            assertThat(firstHighlighter.updated).isNotSameInstanceAs(secondCallback)
            firstHighlighter.updated?.invoke()
            assertThat(secondHighlighter.updated).isSameInstanceAs(secondCallback)
            assertThat(second.state.highlighter).isSameInstanceAs(secondHighlighter)
            first.close()
            first.close()
            assertThat(first.state.textBuffer.isClosed).isTrue()
            assertThat(firstHighlighter.disposals).isEqualTo(1)
            assertThat(firstHighlighter.updated).isNull()
            assertThat(second.state.textBuffer.isClosed).isFalse()
        } finally {
            first.close()
            second.close()
        }
    }

    private class RecordingHighlighter : SyntaxHighlighter {
        var opened: String? = null
        var updated: (() -> Unit)? = null
        var disposals = 0
        override fun openDocument(text: String) { opened = text }
        override fun openDocumentBlocking(text: String) { opened = text }
        override fun setOnStateUpdated(callback: (() -> Unit)?) { updated = callback }
        override fun dispose() { disposals += 1 }
    }
}

package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditorSnippetChoiceCompletionTest {

    @Test
    fun startSnippetSession_shouldShowChoiceCompletionItems() {
        val state = createSnippetState("fun \${1|one,two,three|}()")

        assertThat(state.showCompletion).isTrue()
        assertThat(state.snippetChoiceCompletionActive).isTrue()
        assertThat(state.completionItems.map { it.label })
            .containsExactly("one", "two", "three")
            .inOrder()
        assertThat(state.completionSelectedIndex).isEqualTo(0)
        assertThat(state.selectionRange).isEqualTo(OffsetRange(4, 7))
        assertThat(state.completionItems.map { it.textEdit?.newText })
            .containsExactly("one", "two", "three")
            .inOrder()
    }

    @Test
    fun applySelectedCompletion_shouldSyncRepeatedChoiceTabstopGroup() {
        val state = createSnippetState("const \${1|one,two|} = \${1|one,two|};")

        val selected = state.setCompletionSelectedIndex(1)
        val applied = state.applySelectedCompletion()

        assertThat(selected).isTrue()
        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const two = two;")
        assertThat(state.activeSnippetSession).isNotNull()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(3, 3)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 12)
            .inOrder()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.snippetChoiceCompletionActive).isFalse()
    }

    @Test
    fun advanceSnippet_shouldDismissChoiceCompletionWhenSessionEnds() {
        val state = createSnippetState("\${1|one,two|}")

        val consumed = state.advanceSnippet()

        assertThat(consumed).isTrue()
        assertThat(state.activeSnippetSession).isNull()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.snippetChoiceCompletionActive).isFalse()
    }

    private fun createSnippetState(snippet: String): EditorState {
        val parsed = parseSnippet(snippet)
        val buffer = RopeTextBuffer().apply {
            insert(0, parsed.expandedText)
        }
        return EditorState(buffer).apply {
            startSnippetSession(
                SnippetSession(
                    baseOffset = 0,
                    parsed = parsed
                )
            )
        }
    }
}

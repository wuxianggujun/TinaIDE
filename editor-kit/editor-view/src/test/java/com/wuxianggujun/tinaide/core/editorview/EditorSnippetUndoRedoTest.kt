package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditorSnippetUndoRedoTest {

    @Test
    fun undo_shouldCancelSnippetSessionAndDismissChoiceCompletion() {
        val state = createSnippetState("\${1|one,two|}")

        editorInsert(state, "two")
        val undone = state.undo()

        assertThat(undone).isTrue()
        // undo 只回退 choice 编辑本身，不回退 snippet 展开：editorUndo 的设计契约是
        // cancelSnippet()（见 docs/design/LSP-Snippet-Placeholder-Handling.md「无法可靠追踪偏移」）。
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("one")
        assertThat(state.activeSnippetSession).isNull()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.snippetChoiceCompletionActive).isFalse()
    }

    @Test
    fun redo_shouldRestoreTextWithoutRestoringSnippetSession() {
        val state = createSnippetState("\${1|one,two|}")

        editorInsert(state, "two")
        state.undo()
        val redone = state.redo()

        assertThat(redone).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("two")
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

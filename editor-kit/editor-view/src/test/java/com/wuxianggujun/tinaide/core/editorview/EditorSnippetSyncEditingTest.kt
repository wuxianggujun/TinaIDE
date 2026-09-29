package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditorSnippetSyncEditingTest {

    @Test
    fun editorInsert_shouldSyncWholeRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        editorInsert(state, "value")

        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const value = value;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(5, 5)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 14)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(11)
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun editorInsert_shouldSyncPartialEditsWithinRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        state.moveCursorTo(8)
        editorInsert(state, "X")

        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const naXme = naXme;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(5, 5)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 14)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(9)
    }

    @Test
    fun editorBackspace_shouldSyncRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        editorInsert(state, "value")
        editorBackspace(state)

        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const valu = valu;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(4, 4)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 13)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(10)
    }

    @Test
    fun editorDeleteForward_shouldSyncRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        state.moveCursorTo(8)
        editorDeleteForward(state)

        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const nae = nae;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(3, 3)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 12)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(8)
    }

    @Test
    fun editorReplaceRange_shouldSyncRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        val replaced = editorReplaceRange(
            state = state,
            startOffset = 7,
            endOffset = 9,
            replacement = "XY"
        )

        assertThat(replaced).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const nXYe = nXYe;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(4, 4)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 13)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(9)
    }

    @Test
    fun editorReplaceSelection_shouldSyncRepeatedTabstopGroup() {
        val state = createSnippetState("const \${1:name} = \${1:name};")
        state.selectionRange = OffsetRange(6, 10)

        val replaced = editorReplaceSelection(state, "value")

        assertThat(replaced).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length))
            .isEqualTo("const value = value;")
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.length })
            .containsExactly(5, 5)
            .inOrder()
        assertThat(state.activeSnippetSession!!.currentGroup().map { it.offsetInText })
            .containsExactly(6, 14)
            .inOrder()
        assertThat(state.cursorOffset).isEqualTo(11)
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun editorInsert_shouldUndoAndRedoWholeRepeatedTabstopGroupInOneStep() {
        val state = createSnippetState("const \${1:name} = \${1:name};")

        editorInsert(state, "value")

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("const name = name;")
        assertThat(state.cursorOffset).isEqualTo(10)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("const value = value;")
        assertThat(state.cursorOffset).isEqualTo(11)
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

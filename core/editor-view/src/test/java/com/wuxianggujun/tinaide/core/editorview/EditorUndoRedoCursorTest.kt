package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorUndoRedoCursorTest {

    @Test
    fun undoInsert_shouldMoveCursorToInsertionStart() {
        val state = createState("abc")
        state.moveCursorTo(1)

        state.insert("XYZ")
        assertThat(state.cursorOffset).isEqualTo(4)

        val undone = state.undo()

        assertThat(undone).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun redoInsert_shouldMoveCursorToInsertedTextEnd() {
        val state = createState("abc")
        state.moveCursorTo(1)
        state.insert("XYZ")
        state.undo()

        val redone = state.redo()

        assertThat(redone).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("aXYZbc")
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun undoBackspace_shouldMoveCursorAfterRestoredText() {
        val state = createState("abc")
        state.moveCursorTo(2)

        state.backspace()
        assertThat(state.cursorOffset).isEqualTo(1)

        val undone = state.undo()

        assertThat(undone).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun undoReplace_shouldMoveCursorAfterRestoredText() {
        val state = createState("abc")

        state.replaceRange(startOffset = 1, endOffset = 2, replacement = "XYZ")
        assertThat(state.cursorOffset).isEqualTo(4)

        val undone = state.undo()

        assertThat(undone).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun redoReplace_shouldMoveCursorAfterReplacementText() {
        val state = createState("abc")
        state.replaceRange(startOffset = 1, endOffset = 2, replacement = "XYZ")
        state.undo()

        val redone = state.redo()

        assertThat(redone).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun undoInsertOverSelection_shouldRestoreSelectionTextInOneStep() {
        val state = createState("abc")
        state.selectRange(1, 2)

        state.insert("XYZ")

        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
    }

    @Test
    fun undoReplaceSelection_shouldRestoreSelectionTextInOneStep() {
        val state = createState("abc")
        state.selectRange(1, 2)

        assertThat(state.replaceSelection("XYZ")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
    }

    @Test
    fun deleteForward_shouldNotConsumeCharacterAfterLoneHighSurrogate() {
        val state = createState("\uD83Dx")
        state.moveCursorTo(0)

        state.deleteForward()

        assertThat(state.textBuffer.toString()).isEqualTo("x")
    }

    @Test
    fun backspaceAndMoveLeft_shouldNotConsumeCharacterBeforeLoneLowSurrogate() {
        val state = createState("a\uDE00x")
        state.moveCursorTo(2)

        state.moveLeft()
        assertThat(state.cursorOffset).isEqualTo(1)

        state.moveCursorTo(2)
        state.backspace()
        assertThat(state.textBuffer.toString()).isEqualTo("ax")
    }

    @Test
    fun cursorAndDelete_shouldTreatValidSurrogatePairAsSingleCharacter() {
        val state = createState("😀x")

        state.moveRight()
        assertThat(state.cursorOffset).isEqualTo(2)

        state.backspace()
        assertThat(state.textBuffer.toString()).isEqualTo("x")
        assertThat(state.cursorOffset).isEqualTo(0)
    }

    @Test
    fun undoReplaceAll_shouldRestoreWholeDocumentInOneStep() {
        val state = createState("one two one")
        state.moveCursorTo(5)

        assertThat(state.replaceAll("one", "three")).isEqualTo(2)
        assertThat(state.textBuffer.toString()).isEqualTo("three two three")
        assertThat(state.cursorOffset).isEqualTo(0)

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("one two one")
        assertThat(state.cursorOffset).isEqualTo(5)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("three two three")
        assertThat(state.cursorOffset).isEqualTo(0)
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer(text)
        return EditorState(buffer)
    }
}

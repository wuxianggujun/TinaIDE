package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextSelectionSnapshot
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
        state.selectRange(2, 1)

        state.insert("XYZ")

        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.selectionRange).isNull()
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 2, caret = 1))
        assertThat(state.cursorOffset).isEqualTo(1)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun undoReplaceSelection_shouldRestoreSelectionTextInOneStep() {
        val state = createState("abc")
        state.selectRange(1, 2)

        assertThat(state.replaceSelection("XYZ")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 1, caret = 2))
    }

    @Test
    fun undoReplaceRangeOutsideSelection_shouldRestoreExistingSelection() {
        val state = createState("abcdef")
        state.selectRange(5, 3)

        assertThat(state.replaceRange(startOffset = 0, endOffset = 1, replacement = "XY")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("XYbcdef")
        assertThat(state.selectionRange).isNull()
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abcdef")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 5, caret = 3))
        assertThat(state.cursorOffset).isEqualTo(3)
    }

    @Test
    fun undoExternalEdit_shouldSnapRestoredReverseSelectionToEditorUnitBoundaries() {
        val state = createState("A\uD83D\uDE00\r\nB")

        state.textBuffer.editTransaction(
            cursorBefore = 2,
            selectionBefore = TextSelectionSnapshot(anchor = 4, caret = 2),
        ) {
            insert(length, "x")
        }

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("A\uD83D\uDE00\r\nB")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 5, caret = 1))
        assertThat(state.cursorOffset).isEqualTo(1)
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
    fun cursorMovement_shouldTreatCrLfAsSingleLineBreak() {
        val state = createState("a\r\nb")
        state.moveCursorTo(1)

        state.moveRight()
        assertThat(state.cursorOffset).isEqualTo(3)
        assertThat(state.cursorPosition).isEqualTo(Position(1, 0))

        state.moveLeft()
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.cursorPosition).isEqualTo(Position(0, 1))
    }

    @Test
    fun backspaceAndDeleteForward_shouldRemoveWholeCrLfLineBreak() {
        val backspaceState = createState("a\r\nb")
        backspaceState.moveCursorTo(3)

        backspaceState.backspace()

        assertThat(backspaceState.textBuffer.toString()).isEqualTo("ab")
        assertThat(backspaceState.cursorOffset).isEqualTo(1)
        assertThat(backspaceState.undo()).isTrue()
        assertThat(backspaceState.textBuffer.toString()).isEqualTo("a\r\nb")
        assertThat(backspaceState.cursorOffset).isEqualTo(3)
        assertThat(backspaceState.redo()).isTrue()
        assertThat(backspaceState.textBuffer.toString()).isEqualTo("ab")
        assertThat(backspaceState.cursorOffset).isEqualTo(1)

        val deleteState = createState("a\r\nb")
        deleteState.moveCursorTo(1)

        deleteState.deleteForward()

        assertThat(deleteState.textBuffer.toString()).isEqualTo("ab")
        assertThat(deleteState.cursorOffset).isEqualTo(1)
        assertThat(deleteState.undo()).isTrue()
        assertThat(deleteState.textBuffer.toString()).isEqualTo("a\r\nb")
        assertThat(deleteState.cursorOffset).isEqualTo(1)
        assertThat(deleteState.redo()).isTrue()
        assertThat(deleteState.textBuffer.toString()).isEqualTo("ab")
        assertThat(deleteState.cursorOffset).isEqualTo(1)
    }

    @Test
    fun undoPairBackspace_shouldRestoreCursorBetweenPair() {
        val state = createState("{}")
        state.moveCursorTo(1)

        state.backspace()

        assertThat(state.textBuffer.toString()).isEmpty()
        assertThat(state.cursorOffset).isEqualTo(0)
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("{}")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEmpty()
        assertThat(state.cursorOffset).isEqualTo(0)
    }

    @Test
    fun consecutiveForwardDeletes_shouldMergeWithoutLosingCursor() {
        val state = createState("abcd")
        state.moveCursorTo(1)

        state.deleteForward()
        state.deleteForward()

        assertThat(state.textBuffer.toString()).isEqualTo("ad")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abcd")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("ad")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun smartPairInsertionRedo_shouldRestoreCursorBetweenPair() {
        val state = createState("")

        assertThat(state.insertUserInput("(")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("()")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.undo()).isTrue()
        assertThat(state.cursorOffset).isEqualTo(0)
        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("()")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun indentReverseSelection_shouldPreserveDirectionAndUndoRedoSelection() {
        val state = createState("a\nb")
        state.selectRange(startOffset = 3, endOffset = 0)

        assertThat(editorIndentOrOutdentSelectionByTab(state, outdent = false)).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("    a\n    b")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 11, caret = 4))
        assertThat(state.cursorOffset).isEqualTo(4)

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("a\nb")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 3, caret = 0))
        assertThat(state.cursorOffset).isEqualTo(0)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("    a\n    b")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 11, caret = 4))
        assertThat(state.cursorOffset).isEqualTo(4)
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

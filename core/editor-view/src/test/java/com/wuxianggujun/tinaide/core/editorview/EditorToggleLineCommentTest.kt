package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorToggleLineCommentTest {

    @Test
    fun toggleLineComment_withoutSelection_shouldMoveCursorAfterInsertedPrefix() {
        val state = createState("val answer = 42")
        state.moveCursorTo(4)

        val changed = state.toggleLineComment("//")

        assertThat(changed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("// val answer = 42")
        assertThat(state.cursorOffset).isEqualTo(7)
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun toggleLineComment_withSelection_shouldPreserveSelectionRange() {
        val state = createState("first\nsecond\nthird")
        state.selectRange(startOffset = 1, endOffset = 12)

        val changed = state.toggleLineComment("//")

        assertThat(changed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("// first\n// second\nthird")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(4, 18))
        assertThat(state.cursorOffset).isEqualTo(18)
    }

    @Test
    fun toggleLineComment_withReversedSelection_shouldPreserveSelectionDirection() {
        val state = createState("first\nsecond\nthird")
        state.selectionRange = OffsetRange(anchor = 12, caret = 1)
        state.moveCursorTo(1, clearSelection = false)

        val changed = state.toggleLineComment("//")

        assertThat(changed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("// first\n// second\nthird")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 18, caret = 4))
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun toggleLineComment_withCrlfReverseSelection_shouldPreserveSeparatorsAndUndoRedoSelection() {
        val state = createState("first\r\nsecond\r\nthird")
        state.selectRange(startOffset = 13, endOffset = 1)

        assertThat(state.toggleLineComment("//")).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("// first\r\n// second\r\nthird")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 19, caret = 4))
        assertThat(state.cursorOffset).isEqualTo(4)

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("first\r\nsecond\r\nthird")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 13, caret = 1))
        assertThat(state.cursorOffset).isEqualTo(1)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("// first\r\n// second\r\nthird")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 19, caret = 4))
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun toggleLineComment_uncomment_shouldMoveCursorAfterRemovedPrefix() {
        val state = createState("// val answer = 42")
        state.moveCursorTo(7)

        val changed = state.toggleLineComment("//")

        assertThat(changed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("val answer = 42")
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer(text)
        return EditorState(buffer)
    }
}

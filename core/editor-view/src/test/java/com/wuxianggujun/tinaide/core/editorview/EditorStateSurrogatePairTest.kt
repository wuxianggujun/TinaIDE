package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorStateSurrogatePairTest {

    private val sampleText = "a😀b"
    private val beforeEmojiOffset = "a".length
    private val afterEmojiOffset = "a😀".length

    @Test
    fun moveLeft_shouldSkipWholeSurrogatePair() {
        val state = createState()

        state.moveCursorTo(afterEmojiOffset)
        state.moveLeft()

        assertThat(state.cursorOffset).isEqualTo(beforeEmojiOffset)
    }

    @Test
    fun moveRight_shouldSkipWholeSurrogatePair() {
        val state = createState()

        state.moveCursorTo(beforeEmojiOffset)
        state.moveRight()

        assertThat(state.cursorOffset).isEqualTo(afterEmojiOffset)
    }

    @Test
    fun backspace_shouldDeleteWholeSurrogatePair() {
        val state = createState()

        state.moveCursorTo(afterEmojiOffset)
        state.backspace()

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(beforeEmojiOffset)
    }

    @Test
    fun deleteForward_shouldDeleteWholeSurrogatePair() {
        val state = createState()

        state.moveCursorTo(beforeEmojiOffset)
        state.deleteForward()

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(beforeEmojiOffset)
    }

    @Test
    fun moveCursorTo_insideSurrogatePair_shouldSnapAfterCodePoint() {
        val state = createState()

        state.moveCursorTo(beforeEmojiOffset + 1)

        assertThat(state.cursorOffset).isEqualTo(afterEmojiOffset)
    }

    @Test
    fun setCursorFromPoint_insideSurrogatePair_shouldNeverSplitCodePoint() {
        val state = createState().apply {
            updateMetrics(1f, 1f, 10f, 10f, 0f)
        }

        state.setCursorFromPoint(xPx = 1.75f, yPx = 0f, textStartXPx = 0f)
        assertThat(state.cursorOffset).isEqualTo(beforeEmojiOffset)

        state.setCursorFromPoint(xPx = 2.25f, yPx = 0f, textStartXPx = 0f)
        assertThat(state.cursorOffset).isEqualTo(afterEmojiOffset)
    }

    @Test
    fun selectRange_withBoundaryInsideSurrogatePair_shouldExpandToCodePoint() {
        val state = createState()

        state.selectRange(startOffset = beforeEmojiOffset + 1, endOffset = 0)

        assertThat(state.selectionRange?.anchor).isEqualTo(afterEmojiOffset)
        assertThat(state.selectionRange?.caret).isEqualTo(0)
    }

    @Test
    fun applyTextBufferChange_shouldClampSelectionToNewDocumentBoundary() {
        val buffer = RopeTextBuffer("abcdef")
        val state = EditorState(buffer)
        state.selectRange(startOffset = 6, endOffset = 4)
        buffer.addChangeListener { change -> state.applyTextBufferChange(change) }

        buffer.replaceAll("a\uD83D\uDE00")

        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 3, caret = 3))
        assertThat(state.cursorOffset).isEqualTo(3)
    }

    private fun createState(): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, sampleText) }
        return EditorState(buffer)
    }
}

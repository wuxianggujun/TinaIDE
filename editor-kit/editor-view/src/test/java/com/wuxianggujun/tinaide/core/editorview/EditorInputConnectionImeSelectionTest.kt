package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorInputConnectionImeSelectionTest {

    @Test
    fun moveLeft_withExtendSelection_shouldCreateBackwardSelectionFromCaret() {
        val state = createState("abcd")
        state.moveCursorTo(2)

        state.moveLeft(extendSelection = true)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 2, caret = 1))
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun moveRight_withExtendSelection_shouldKeepAnchorAndAdvanceCaret() {
        val state = createState("abcd")
        state.selectRange(startOffset = 3, endOffset = 1)

        state.moveRight(extendSelection = true)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 3, caret = 2))
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun moveRight_withoutExtendSelection_shouldCollapseSelectionToTrailingEdge() {
        val state = createState("abcd")
        state.selectRange(startOffset = 3, endOffset = 1)

        state.moveRight()

        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(3)
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }
}

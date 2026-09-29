package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorStateCompletionQueryTest {

    @Test
    fun completionQueryFromCursor_shouldReturnWordPrefixOnCurrentLine() {
        val state = EditorState(RopeTextBuffer("alphaBeta gamma"))
        state.moveCursorTo(state.textBuffer.positionToOffset(0, 5))

        assertThat(state.completionQueryFromCursor()).isEqualTo("alpha")
    }

    @Test
    fun completionQueryFromCursor_shouldReturnEmptyInsideWhitespace() {
        val state = EditorState(RopeTextBuffer("alpha  beta"))
        state.moveCursorTo(state.textBuffer.positionToOffset(0, 6))

        assertThat(state.completionQueryFromCursor()).isEmpty()
    }

    @Test
    fun completionQueryFromCursor_shouldSupportUnicodeLetters() {
        val state = EditorState(RopeTextBuffer("变量名 value"))
        state.moveCursorTo(state.textBuffer.positionToOffset(0, 2))

        assertThat(state.completionQueryFromCursor()).isEqualTo("变量")
    }
}

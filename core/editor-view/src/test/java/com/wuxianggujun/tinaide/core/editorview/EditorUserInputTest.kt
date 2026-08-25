package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorUserInputTest {

    @Test
    fun insertUserInput_tabShouldRespectSpacesConfiguration() {
        val state = EditorState(
            textBuffer = RopeTextBuffer(),
            config = EditorConfig(tabSize = 4, insertSpacesForTabs = true),
        )

        assertThat(state.insertUserInput("\t")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("    ")
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    @Test
    fun insertUserInput_openingAndClosingBraceShouldReuseSmartInsertion() {
        val state = EditorState(RopeTextBuffer())

        assertThat(state.insertUserInput("{")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("{}")
        assertThat(state.cursorOffset).isEqualTo(1)

        assertThat(state.insertUserInput("}")).isTrue()

        assertThat(state.textBuffer.toString()).isEqualTo("{}")
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun insertUserInput_closingBraceShouldReplaceSelectionInsteadOfSkippingIt() {
        val state = EditorState(RopeTextBuffer("}tail"))
        state.selectRange(0, 1)

        state.insertUserInput("}")

        assertThat(state.textBuffer.toString()).isEqualTo("}tail")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.selectionRange).isNull()
    }
}

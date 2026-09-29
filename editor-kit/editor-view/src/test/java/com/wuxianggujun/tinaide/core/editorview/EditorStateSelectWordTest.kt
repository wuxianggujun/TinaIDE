package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorStateSelectWordTest {
    @Test
    fun hasWordAt_shouldReturnTrueOnlyOnIdentifier() {
        val state = EditorState(RopeTextBuffer("alpha  beta"))

        assertThat(state.hasWordAt(line = 0, column = 2)).isTrue()
        assertThat(state.hasWordAt(line = 0, column = 6)).isFalse()
    }

    @Test
    fun selectWord_shouldSelectIdentifierAtBoundary() {
        val state = EditorState(RopeTextBuffer("alpha_beta gamma"))

        val selected = state.selectWord(line = 0, column = 10)

        assertThat(selected).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, 10))
    }

    @Test
    fun selectWord_shouldSelectIdentifierBeforeAttachedOperatorSuffix() {
        val state = EditorState(RopeTextBuffer("operator+"))

        val selected = state.selectWord(line = 0, column = "operator+".length)

        assertThat(selected).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, "operator".length))
    }

    @Test
    fun hasWordAt_shouldAcceptAttachedOperatorSuffixBoundary() {
        val state = EditorState(RopeTextBuffer("operator-"))

        assertThat(state.hasWordAt(line = 0, column = "operator-".length)).isTrue()
    }

    @Test
    fun selectWord_shouldNotCrossWhitespaceBeforeOperatorSuffix() {
        val state = EditorState(RopeTextBuffer("operator +"))

        val selected = state.selectWord(line = 0, column = "operator +".length)

        assertThat(selected).isFalse()
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun selectWord_shouldReturnFalseOnWhitespace() {
        val state = EditorState(RopeTextBuffer("alpha  beta"))

        val selected = state.selectWord(line = 0, column = 6)

        assertThat(selected).isFalse()
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun selectWord_shouldSupportUnicodeLetters() {
        val state = EditorState(RopeTextBuffer("变量名 value"))

        val selected = state.selectWord(line = 0, column = 2)

        assertThat(selected).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, 3))
    }
}

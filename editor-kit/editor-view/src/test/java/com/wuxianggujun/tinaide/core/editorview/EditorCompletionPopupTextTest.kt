package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorCompletionPopupTextTest {

    @Test
    fun fuzzyMatchIndices_shouldMatchCharactersCaseInsensitivelyInOrder() {
        assertThat(fuzzyMatchIndices(label = "printLine", query = "PL"))
            .containsExactly(0, 5)
            .inOrder()
    }

    @Test
    fun fuzzyMatchIndices_shouldReturnEmptyWhenSequenceCannotBeCompleted() {
        assertThat(fuzzyMatchIndices(label = "printLine", query = "pz")).isEmpty()
    }

    @Test
    fun highlightMatchedChars_shouldStyleMatchedCharactersOnly() {
        val annotated = highlightMatchedChars(
            label = "printLine",
            query = "pl",
            highlightColor = Color(0xFF2F74FF)
        )

        assertThat(annotated.text).isEqualTo("printLine")
        assertThat(
            annotated.spanStyles.map { range ->
                Triple(range.start, range.end, range.item.color)
            }
        ).containsExactly(
            Triple(0, 1, Color(0xFF2F74FF)),
            Triple(5, 6, Color(0xFF2F74FF))
        ).inOrder()
    }
}

package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorCompletionUtilsTest {

    @Test
    fun shouldRequestCompletionAfterInsert_shouldTreatMakeVariantsAsMakeFiles() {
        assertThat(
            shouldRequestCompletionAfterInsert(
                insertedText = "$",
                trigger = '$',
                fileName = "rules.mak"
            )
        ).isTrue()

        assertThat(
            shouldRequestCompletionAfterInsert(
                insertedText = "(",
                trigger = '(',
                fileName = "Makefile.in"
            )
        ).isTrue()

        assertThat(
            shouldRequestCompletionAfterInsert(
                insertedText = "{",
                trigger = '{',
                fileName = "BSDmakefile"
            )
        ).isTrue()
    }
}

package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorInputConnectionUtilsTest {

    @Test
    fun mapImeSelectionToDocument_shouldKeepAbsoluteSelectionEvenWhenOffsetIsSmall() {
        val mapped = mapImeSelectionToDocument(
            start = 512,
            end = 514,
            documentLength = 10_000
        )

        assertThat(mapped.first).isEqualTo(512)
        assertThat(mapped.second).isEqualTo(514)
    }

    @Test
    fun mapImeSelectionToDocument_shouldClampSelectionWithoutLosingDirection() {
        val mapped = mapImeSelectionToDocument(
            start = 12_000,
            end = -5,
            documentLength = 10_000
        )

        assertThat(mapped.first).isEqualTo(10_000)
        assertThat(mapped.second).isEqualTo(0)
    }

    @Test
    fun mapImeSelectionToDocument_shouldClampCollapsedSelection() {
        val mapped = mapImeSelectionToDocument(
            start = 20_000,
            end = 20_000,
            documentLength = 10_000
        )

        assertThat(mapped.first).isEqualTo(10_000)
        assertThat(mapped.second).isEqualTo(10_000)
    }

    @Test
    fun extractedTextSelectionOffset_shouldReturnWindowRelativeOffset() {
        val offset = extractedTextSelectionOffset(
            documentOffset = 1_260,
            windowStartOffset = 1_000,
            windowLength = 512
        )

        assertThat(offset).isEqualTo(260)
    }

    @Test
    fun resolveEditRange_shouldPreferComposingRange() {
        val range = resolveEditRange(
            selectionStart = 100,
            selectionEnd = 100,
            composingRange = ComposingRange(60, 65)
        )

        assertThat(range.first).isEqualTo(60)
        assertThat(range.second).isEqualTo(65)
    }

    @Test
    fun normalizeComposingRange_shouldClampAndValidate() {
        val clamped = normalizeComposingRange(
            start = -4,
            end = 8,
            documentLength = 6
        )
        assertThat(clamped).isNotNull()
        assertThat(clamped!!.start).isEqualTo(0)
        assertThat(clamped.end).isEqualTo(6)

        val invalid = normalizeComposingRange(
            start = 5,
            end = 5,
            documentLength = 10
        )
        assertThat(invalid).isNull()
    }

    @Test
    fun nextComposingRange_shouldReturnNullWhenNotComposing() {
        val noCompose = nextComposingRange(
            editStart = 20,
            replacementLength = 3,
            keepComposing = false
        )
        assertThat(noCompose).isNull()

        val emptyCompose = nextComposingRange(
            editStart = 20,
            replacementLength = 0,
            keepComposing = true
        )
        assertThat(emptyCompose).isNull()

        val composing = nextComposingRange(
            editStart = 20,
            replacementLength = 4,
            keepComposing = true
        )
        assertThat(composing).isNotNull()
        assertThat(composing!!.start).isEqualTo(20)
        assertThat(composing.end).isEqualTo(24)
    }

    @Test
    fun imeDeleteSurroundingCharRange_shouldClampAroundCursor() {
        val range = imeDeleteSurroundingCharRange(
            cursorOffset = 2,
            beforeLength = 5,
            afterLength = 2,
            documentLength = 6
        )

        assertThat(range).isEqualTo(ImeDeleteRange(start = 0, end = 4))
    }

    @Test
    fun imeDeleteSurroundingCodePointRange_shouldKeepEmojiSurrogatePairTogether() {
        val buffer = RopeTextBuffer().apply { insert(0, "a😀b") }

        val beforeRange = imeDeleteSurroundingCodePointRange(
            textBuffer = buffer,
            cursorOffset = 3,
            beforeLength = 1,
            afterLength = 0
        )
        val afterRange = imeDeleteSurroundingCodePointRange(
            textBuffer = buffer,
            cursorOffset = 1,
            beforeLength = 0,
            afterLength = 1
        )

        assertThat(beforeRange).isEqualTo(ImeDeleteRange(start = 1, end = 3))
        assertThat(afterRange).isEqualTo(ImeDeleteRange(start = 1, end = 3))
    }
}

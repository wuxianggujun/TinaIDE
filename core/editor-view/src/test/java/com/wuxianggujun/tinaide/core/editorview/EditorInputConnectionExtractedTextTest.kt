package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorInputConnectionExtractedTextTest {

    @Test
    fun getExtractedText_shouldReportSelectionRelativeToWindowStart() {
        val text = largeText()
        val cursorOffset = 5_000
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(cursorOffset)

        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        assertThat(extracted.startOffset).isGreaterThan(0)
        assertThat(extracted.partialStartOffset).isEqualTo(-1)
        assertThat(extracted.partialEndOffset).isEqualTo(-1)
        assertThat(extracted.selectionStart).isEqualTo(cursorOffset - extracted.startOffset)
        assertThat(extracted.selectionEnd).isEqualTo(cursorOffset - extracted.startOffset)
    }

    @Test
    fun setSelection_whenImeSelectsWholeExtractedWindow_shouldSelectWholeDocument() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(5_000)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.setSelection(0, extracted.text.length)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, text.length))
        assertThat(state.cursorOffset).isEqualTo(text.length)
    }

    @Test
    fun setSelection_afterExtractedText_shouldKeepSmallAbsoluteSelection() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(5_000)
        connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.setSelection(1, 3)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(1, 3))
        assertThat(state.cursorOffset).isEqualTo(3)
    }

    @Test
    fun setSelection_whenImeEchoesWindowRelativeSelection_shouldPreserveDocumentSelection() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.selectRange(startOffset = 4_992, endOffset = 5_008)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.setSelection(extracted.selectionStart, extracted.selectionEnd)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(4_992, 5_008))
        assertThat(state.cursorOffset).isEqualTo(5_008)
    }

    @Test
    fun commitText_whenImeEchoesUpdatedWindowCursor_shouldKeepCursorAfterInsertedText() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(5_000)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.commitText("pasted", 1)
        val relativeCursorAfterPaste = state.cursorOffset - extracted.startOffset
        connection.setSelection(relativeCursorAfterPaste, relativeCursorAfterPaste)

        assertThat(state.cursorOffset).isEqualTo(5_006)
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun configureEditorInfo_shouldDisableFullscreenExtractEditing() {
        val editorInfo = EditorInfo()

        configureCodeEditorInput(
            outAttrs = editorInfo,
            selectionStart = 12,
            selectionEnd = 18
        )

        assertThat(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI).isNotEqualTo(0)
        assertThat(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN).isNotEqualTo(0)
        assertThat(editorInfo.initialSelStart).isEqualTo(12)
        assertThat(editorInfo.initialSelEnd).isEqualTo(18)
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }

    private fun createConnection(state: EditorState): EditorInputConnection = EditorInputConnection(
        targetView = EditorInputHostView(ApplicationProvider.getApplicationContext<Context>()),
        state = state,
        onInsertedText = {},
        onNonInsertEdit = {}
    )

    private fun largeText(): String = buildString {
        repeat(1_200) { index ->
            append("line_")
            append(index)
            append("_abcdefghijklmnopqrstuvwxyz")
            append('\n')
        }
    }
}

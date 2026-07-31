package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.focus.FocusRequester
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChangeListener
import kotlinx.coroutines.test.runTest
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
    fun getExtractedText_nearDocumentEndShouldBackfillRequestedWindow() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(text.length)

        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        assertThat(extracted.text.length).isEqualTo(512)
        assertThat(extracted.startOffset + extracted.text.length).isEqualTo(text.length)
        assertThat(extracted.selectionStart).isEqualTo(512)
        assertThat(extracted.selectionEnd).isEqualTo(512)
    }

    @Test
    fun getExtractedText_shouldExpandWindowAcrossSurrogatePairBoundaries() {
        val emoji = "\uD83D\uDE00"
        val text = emoji + "x".repeat(254) + emoji + "y".repeat(100)
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(129)

        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 256 },
            0
        )

        assertThat(extracted.startOffset).isEqualTo(0)
        assertThat(extracted.text.toString()).startsWith(emoji)
        assertThat(extracted.text.toString()).endsWith(emoji)
        assertThat(extracted.text.length).isEqualTo(258)
        assertThat(extracted.selectionStart).isEqualTo(129)
        assertThat(extracted.selectionEnd).isEqualTo(129)
    }

    @Test
    fun setSelection_whenCalledAfterWindowQuery_shouldStillUseAbsoluteCoordinates() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(5_000)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.setSelection(0, extracted.text.length)

        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, extracted.text.length))
        assertThat(state.cursorOffset).isEqualTo(extracted.text.length)
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
    fun setSelection_matchingWindowRelativeValues_shouldNotEatLegalAbsoluteJump() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.selectRange(startOffset = 4_992, endOffset = 5_008)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.setSelection(extracted.selectionStart, extracted.selectionEnd)

        assertThat(state.selectionRange).isEqualTo(
            OffsetRange(extracted.selectionStart, extracted.selectionEnd)
        )
        assertThat(state.cursorOffset).isEqualTo(extracted.selectionEnd)
    }

    @Test
    fun setSelection_afterCommitAndWindowQuery_shouldApplyAbsoluteCursorJump() {
        val text = largeText()
        val state = createState(text)
        val connection = createConnection(state)
        state.moveCursorTo(5_000)
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = 512 },
            0
        )

        connection.commitText("pasted", 1)
        connection.setSelection(42, 42)

        assertThat(extracted.startOffset).isGreaterThan(0)
        assertThat(state.cursorOffset).isEqualTo(42)
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

    @Test
    fun hostRebindAndDispose_shouldCloseMonitorAndDetachOldHostCallbacks() = runTest {
        val buffer = ListenerCountingTextBuffer(RopeTextBuffer("content"))
        val state = EditorState(buffer)
        val controller = EditorInteractionController(
            state = state,
            coroutineScope = this,
            focusRequester = FocusRequester(),
            keyboardController = null,
            inputMethodManager = null
        )
        val firstHost = EditorInputHostView(applicationContext())
        controller.bindInputHostView(firstHost)
        val firstConnection = firstHost.onCreateInputConnection(EditorInfo()) as EditorInputConnection
        firstConnection.getExtractedText(
            ExtractedTextRequest().apply { token = 1 },
            InputConnection.GET_EXTRACTED_TEXT_MONITOR
        )
        assertThat(buffer.activeListenerCount).isEqualTo(1)

        val secondHost = EditorInputHostView(applicationContext())
        controller.bindInputHostView(secondHost)

        assertThat(buffer.activeListenerCount).isEqualTo(0)
        assertThat(firstHost.inputConnectionFactory).isNull()
        assertThat(firstHost.keyEventHandler).isNull()
        assertThat(firstHost.onInputConnectionDetached).isNull()

        val secondConnection = secondHost.onCreateInputConnection(EditorInfo()) as EditorInputConnection
        secondConnection.getExtractedText(
            ExtractedTextRequest().apply { token = 2 },
            InputConnection.GET_EXTRACTED_TEXT_MONITOR
        )
        assertThat(buffer.activeListenerCount).isEqualTo(1)

        controller.onDispose()

        assertThat(buffer.activeListenerCount).isEqualTo(0)
        assertThat(secondHost.inputConnectionFactory).isNull()
        assertThat(secondHost.keyEventHandler).isNull()
        assertThat(secondHost.onInputConnectionDetached).isNull()
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }

    private fun createConnection(state: EditorState): EditorInputConnection = EditorInputConnection(
        targetView = EditorInputHostView(applicationContext()),
        state = state,
        onInsertedText = {},
        onNonInsertEdit = {}
    )

    private fun applicationContext(): Context = ApplicationProvider.getApplicationContext()

    private class ListenerCountingTextBuffer(
        private val delegate: TextBuffer
    ) : TextBuffer by delegate {
        private val listeners = mutableSetOf<TextChangeListener>()

        val activeListenerCount: Int
            get() = listeners.size

        override fun addChangeListener(listener: TextChangeListener) {
            listeners += listener
            delegate.addChangeListener(listener)
        }

        override fun removeChangeListener(listener: TextChangeListener) {
            listeners -= listener
            delegate.removeChangeListener(listener)
        }
    }

    private fun largeText(): String = buildString {
        repeat(1_200) { index ->
            append("line_")
            append(index)
            append("_abcdefghijklmnopqrstuvwxyz")
            append('\n')
        }
    }
}

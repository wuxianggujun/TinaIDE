package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorInputConnectionEditTest {

    @Test
    fun deleteSurroundingText_shouldDeleteSelectionFirst() {
        val state = createState("abcdef")
        val connection = createConnection(state)
        state.selectRange(startOffset = 2, endOffset = 4)

        connection.deleteSurroundingText(beforeLength = 1, afterLength = 1)

        assertThat(state.textBuffer.toString()).isEqualTo("abef")
        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun deleteSurroundingText_shouldDeleteAroundCursorWhenNoSelection() {
        val state = createState("abcdef")
        val connection = createConnection(state)
        state.moveCursorTo(3)

        connection.deleteSurroundingText(beforeLength = 1, afterLength = 2)

        assertThat(state.textBuffer.toString()).isEqualTo("abf")
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun deleteSurroundingTextInCodePoints_shouldDeleteWholeEmojiBeforeCursor() {
        val state = createState("a😀b")
        val connection = createConnection(state)
        state.moveCursorTo(3)

        connection.deleteSurroundingTextInCodePoints(beforeLength = 1, afterLength = 0)

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun deleteSurroundingTextInCodePoints_shouldDeleteWholeEmojiAfterCursor() {
        val state = createState("a😀b")
        val connection = createConnection(state)
        state.moveCursorTo(1)

        connection.deleteSurroundingTextInCodePoints(beforeLength = 0, afterLength = 1)

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun commitText_shouldReplaceSelection() {
        val state = createState("abc")
        val connection = createConnection(state)
        state.selectRange(startOffset = 1, endOffset = 2)

        connection.commitText("X", 1)

        assertThat(state.textBuffer.toString()).isEqualTo("aXc")
        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun surroundingText_shouldExcludeSelectedTextFromBeforeAndAfterCursor() {
        val state = createState("abcdef")
        val connection = createConnection(state)
        state.selectRange(startOffset = 4, endOffset = 1)

        assertThat(connection.getTextBeforeCursor(10, 0).toString()).isEqualTo("a")
        assertThat(connection.getSelectedText(0).toString()).isEqualTo("bcd")
        assertThat(connection.getTextAfterCursor(10, 0).toString()).isEqualTo("ef")
    }

    @Test
    fun getSelectedText_withoutSelection_shouldReturnNull() {
        val state = createState("abc")
        val connection = createConnection(state)
        state.moveCursorTo(1)

        assertThat(connection.getSelectedText(0)).isNull()
    }

    @Test
    fun repeatedComposingText_shouldNotEmitFalseTextChangeAndCommitShouldFinishComposition() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)

        connection.setComposingText("a", 1)
        val versionAfterInsert = state.textBuffer.version
        connection.setComposingText("a", 1)
        connection.commitText("a", 1)

        assertThat(state.textBuffer.version).isEqualTo(versionAfterInsert)
        connection.commitText("b", 1)
        assertThat(state.textBuffer.toString()).isEqualTo("ab")
    }

    @Test
    fun performEditorAction_shouldInsertNewlineAndReplaceSelection() {
        val state = createState("abc")
        val connection = createConnection(state)
        state.selectRange(startOffset = 1, endOffset = 2)

        connection.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)

        assertThat(state.textBuffer.toString()).isEqualTo("a\nc")
        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(2)
    }

    @Test
    fun pasteContextMenuAction_shouldReplaceSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        clipboardManager.setPrimaryClip(ClipData.newPlainText("test", "XYZ"))
        val state = createState("abc")
        val connection = createConnection(state, context)
        state.selectRange(startOffset = 1, endOffset = 2)

        val handled = connection.performContextMenuAction(android.R.id.paste)

        assertThat(handled).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("aXYZc")
        assertThat(state.selectionRange).isNull()
        assertThat(state.cursorOffset).isEqualTo(4)
    }

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }

    private fun createConnection(
        state: EditorState,
        context: Context = ApplicationProvider.getApplicationContext()
    ): EditorInputConnection = EditorInputConnection(
        targetView = EditorInputHostView(context),
        state = state,
        onInsertedText = {},
        onNonInsertEdit = {}
    )
}

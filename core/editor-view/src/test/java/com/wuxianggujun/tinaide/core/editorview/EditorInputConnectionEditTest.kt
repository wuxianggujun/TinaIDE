package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
    fun deleteSurroundingTextInChars_shouldExpandPartialEmojiRange() {
        val state = createState("a\uD83D\uDE00b")
        val connection = createConnection(state)
        state.moveCursorTo(3)

        connection.deleteSurroundingText(beforeLength = 1, afterLength = 0)

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun deleteSurroundingTextInChars_shouldKeepCrLfAtomic() {
        val state = createState("a\r\nb")
        val connection = createConnection(state)
        state.moveCursorTo(3)

        connection.deleteSurroundingText(beforeLength = 1, afterLength = 0)

        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun deleteSurroundingTextInCodePoints_shouldKeepCrLfAtomic() {
        val state = createState("a\r\nb")
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
    fun commitText_withRelativeCursor_shouldPreserveCursorThroughUndoRedo() {
        val state = createState("ab")
        val connection = createConnection(state)
        state.moveCursorTo(1)

        connection.commitText("XY", 0)

        assertThat(state.textBuffer.toString()).isEqualTo("aXYb")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("ab")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("aXYb")
        assertThat(state.cursorOffset).isEqualTo(1)
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
    fun composingText_shouldBecomeSingleUndoEntryAfterCommit() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)

        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.setComposingText("\u4F60", 1)
        assertThat(state.textBuffer.canUndo()).isFalse()

        connection.commitText("\u4F60", 1)

        assertThat(state.textBuffer.canUndo()).isTrue()
        state.textBuffer.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
        assertThat(state.textBuffer.canUndo()).isFalse()
        state.textBuffer.redo()
        assertThat(state.textBuffer.toString()).isEqualTo("\u4F60")
    }

    @Test
    fun deleteSurroundingText_beforeComposition_shouldShiftComposingRange() {
        val state = createState("abcdeXYz")
        val connection = createConnection(state)
        state.moveCursorTo(5)
        connection.setComposingRegion(5, 7)

        connection.deleteSurroundingText(beforeLength = 1, afterLength = 0)
        connection.setComposingText("Q", 1)
        connection.finishComposingText()

        assertThat(state.textBuffer.toString()).isEqualTo("abcdQz")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("abcdeXYz")
    }

    @Test
    fun cutDuringComposition_shouldCreateSeparateUndoEntry() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("draft", 1)
        state.selectRange(startOffset = 0, endOffset = 5)

        assertThat(connection.performContextMenuAction(android.R.id.cut)).isTrue()

        assertThat(state.textBuffer.toString()).isEmpty()
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("draft")
        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun emptyComposingText_shouldFinishCompositionHistory() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)

        connection.setComposingText("n", 1)
        connection.setComposingText("", 1)

        assertThat(state.textBuffer.canUndo()).isFalse()
        connection.commitText("x", 1)
        assertThat(state.textBuffer.toString()).isEqualTo("x")
        assertThat(state.textBuffer.canUndo()).isTrue()
        state.textBuffer.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
        assertThat(state.textBuffer.canUndo()).isFalse()
    }

    @Test
    fun zeroLengthComposingRegion_shouldFinishCompositionHistory() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("n", 1)

        connection.setComposingRegion(1, 1)

        assertThat(state.textBuffer.canUndo()).isTrue()
    }

    @Test
    fun selectAll_shouldFinishCompositionBeforeChangingSelection() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("draft", 1)

        connection.performContextMenuAction(android.R.id.selectAll)

        assertThat(state.textBuffer.canUndo()).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(0, 5))
    }

    @Test
    fun hardwareBackspace_shouldFinishCompositionWithValidUndoEntry() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("ni", 1)

        connection.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertThat(state.textBuffer.toString()).isEqualTo("n")
        assertThat(state.textBuffer.canUndo()).isTrue()
        state.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun composingReplacement_shouldRestoreOriginalSelectionOnUndo() {
        val state = createState("abc")
        val connection = createConnection(state)
        state.selectRange(startOffset = 2, endOffset = 1)

        connection.setComposingText("n", 1)
        connection.commitText("\u4F60", 1)
        state.undo()

        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 2, caret = 1))
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun closeConnection_shouldFinishActiveCompositionHistory() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("draft", 1)

        connection.closeConnection()

        assertThat(state.textBuffer.canUndo()).isTrue()
        state.textBuffer.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun externalEditAfterFinishingComposition_shouldCreateSeparateUndoEntry() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("ni", 1)

        connection.finishComposingText()
        state.insert("!")

        assertThat(state.textBuffer.toString()).isEqualTo("ni!")
        state.undo()
        assertThat(state.textBuffer.toString()).isEqualTo("ni")
        state.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun externalSelectionOutsideComposition_shouldFinishCompositionHistory() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)
        connection.setComposingText("draft", 1)

        state.moveCursorTo(0)
        connection.updateSelectionToIme()

        assertThat(state.textBuffer.canUndo()).isTrue()
        state.undo()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun composingText_afterReplaceAll_shouldDiscardStaleRangeAndUseFreshHistoryScope() {
        val buffer = RopeTextBuffer()
        val state = EditorState(buffer)
        val connection = createConnection(state)
        connection.setComposingText("n", 1)

        buffer.replaceAll("reset")
        connection.setComposingText("x", 1)
        connection.commitText("x", 1)

        assertThat(buffer.toString()).isEqualTo("rxeset")
        assertThat(buffer.canUndo()).isTrue()
        buffer.undo()
        assertThat(buffer.toString()).isEqualTo("reset")
        assertThat(buffer.canUndo()).isFalse()
    }

    @Test
    fun cursorAnchorInfo_afterReplaceAllShouldDiscardOutOfBoundsComposition() {
        val buffer = RopeTextBuffer()
        val state = EditorState(buffer)
        val connection = createConnection(state)
        connection.setComposingText("draft", 1)

        buffer.replaceAll("")
        val info = connection.buildCursorAnchorInfo()
        connection.commitText("x", 1)

        assertThat(info.selectionStart).isEqualTo(0)
        assertThat(info.selectionEnd).isEqualTo(0)
        assertThat(buffer.toString()).isEqualTo("x")
        assertThat(buffer.canUndo()).isTrue()
        buffer.undo()
        assertThat(buffer.toString()).isEmpty()
        assertThat(buffer.canUndo()).isFalse()
    }

    @Test
    fun setSelection_insideEmoji_shouldSnapCaretAfterSurrogatePair() {
        val state = createState("a\uD83D\uDE00b")
        val connection = createConnection(state)

        connection.setSelection(2, 2)

        assertThat(state.cursorOffset).isEqualTo(3)
    }

    @Test
    fun setSelection_insideCrLf_shouldSnapCaretAfterLineBreak() {
        val state = createState("a\r\nb")
        val connection = createConnection(state)

        connection.setSelection(2, 2)

        assertThat(state.cursorOffset).isEqualTo(3)
        assertThat(state.cursorPosition).isEqualTo(
            com.wuxianggujun.tinaide.core.textengine.Position(line = 1, column = 0)
        )
    }

    @Test
    fun cursorAnchorInfo_shouldUseEditorVisualCoordinates() {
        val state = createState("abcd")
        state.updateMetrics(
            lineHeightPx = 20f,
            charWidthPx = 5f,
            viewportHeightPx = 100f,
            viewportWidthPx = 200f,
            contentStartXPx = 10f
        )
        state.moveCursorTo(2)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetView = EditorInputHostView(context).apply {
            layout(24, 36, 25, 37)
        }
        val connection = createConnection(state, context, targetView)

        val info = connection.buildCursorAnchorInfo()
        val targetLocation = IntArray(2).also(targetView::getLocationOnScreen)
        val mappedOrigin = floatArrayOf(0f, 0f).also(info.matrix::mapPoints)

        assertThat(info.selectionStart).isEqualTo(2)
        assertThat(info.selectionEnd).isEqualTo(2)
        assertThat(info.insertionMarkerHorizontal).isWithin(0.01f).of(20f)
        assertThat(info.insertionMarkerTop).isWithin(0.01f).of(0f)
        assertThat(info.insertionMarkerBottom).isWithin(0.01f).of(20f)
        assertThat(mappedOrigin[0]).isWithin(0.01f).of(targetLocation[0].toFloat())
        assertThat(mappedOrigin[1]).isWithin(0.01f).of(targetLocation[1].toFloat())
    }

    @Test
    fun requestCursorUpdates_shouldAcceptSupportedInsertionMarkerFilters() {
        val connection = createConnection(createState("abc"))

        assertThat(
            connection.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE,
                InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
            )
        ).isTrue()
        assertThat(
            connection.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or
                    InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
            )
        ).isTrue()
    }

    @Test
    fun requestCursorUpdates_shouldRejectUnsupportedCursorAnchorFilters() {
        val connection = createConnection(createState("abc"))

        assertThat(
            connection.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE,
                InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS
            )
        ).isFalse()
        assertThat(
            connection.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or
                    InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS
            )
        ).isFalse()
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
        val buffer = RopeTextBuffer(text)
        return EditorState(buffer)
    }

    private fun createConnection(
        state: EditorState,
        context: Context = ApplicationProvider.getApplicationContext(),
        targetView: EditorInputHostView = EditorInputHostView(context)
    ): EditorInputConnection = EditorInputConnection(
        targetView = targetView,
        state = state,
        onInsertedText = {},
        onNonInsertEdit = {}
    )
}

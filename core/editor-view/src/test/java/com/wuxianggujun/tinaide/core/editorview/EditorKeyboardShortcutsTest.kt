package com.wuxianggujun.tinaide.core.editorview

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorKeyboardShortcutsTest {

    @Test
    fun handleEditorShortcut_shouldCycleSignatureHelpOnAltDirection() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp()
        var cycleDelta: Int? = null
        var completionDelta: Int? = null

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                metaState = AndroidKeyEvent.META_ALT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = {
                cycleDelta = it
                true
            },
            onCompletionNavigate = {
                completionDelta = it
                true
            },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(cycleDelta).isEqualTo(1)
        assertThat(completionDelta).isNull()
    }

    @Test
    fun handleEditorShortcut_shouldPreferSignatureHelpCycleWhenCompletionIsAlsoVisible() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp()
        state.seedVisibleCompletion(
            items = listOf(EditorCompletionItem(label = "print")),
            requestId = 1L
        )
        var cycleDelta: Int? = null
        var completionDelta: Int? = null

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                metaState = AndroidKeyEvent.META_ALT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = {
                cycleDelta = it
                true
            },
            onCompletionNavigate = {
                completionDelta = it
                true
            },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(cycleDelta).isEqualTo(1)
        assertThat(completionDelta).isNull()
    }

    @Test
    fun handleEditorShortcut_shouldKeepCompletionNavigationForPlainDirection() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp()
        state.seedVisibleCompletion(
            items = listOf(EditorCompletionItem(label = "print")),
            requestId = 1L
        )
        var cycleDelta: Int? = null
        var completionDelta: Int? = null

        val consumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_DPAD_DOWN),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = {
                cycleDelta = it
                true
            },
            onCompletionNavigate = {
                completionDelta = it
                true
            },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(completionDelta).isEqualTo(1)
        assertThat(cycleDelta).isNull()
    }

    @Test
    fun handleEditorShortcut_shouldApplyCompletionWithNumpadEnter() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleCompletion(
            items = listOf(EditorCompletionItem(label = "println")),
            requestId = 1L
        )
        var applyCount = 0

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_NUMPAD_ENTER),
            onApplySelectedCompletion = {
                applyCount++
                true
            }
        )

        assertThat(consumed).isTrue()
        assertThat(applyCount).isEqualTo(1)
    }

    @Test
    fun handleEditorShortcut_shouldInsertNewLineWithNumpadEnter() {
        val state = createState("ab")
        state.moveCursorTo(1)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("a\nb")
        assertThat(state.cursorPosition.line).isEqualTo(1)
        assertThat(state.cursorPosition.column).isEqualTo(0)
    }

    @Test
    fun handleEditorShortcut_shouldPrepareExternalEditBeforeBufferMutation() {
        val state = createState("ab")
        state.moveCursorTo(2)
        var textObservedByPreparer: String? = null

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_DEL),
            onBeforeTextEdit = { textObservedByPreparer = state.textBuffer.toString() }
        )

        assertThat(consumed).isTrue()
        assertThat(textObservedByPreparer).isEqualTo("ab")
        assertThat(state.textBuffer.toString()).isEqualTo("a")
    }

    @Test
    fun handleEditorShortcut_shouldNotPrepareExternalEditForCursorMovement() {
        val state = createState("ab")
        state.moveCursorTo(2)
        var prepareCount = 0

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_DPAD_LEFT),
            onBeforeTextEdit = { prepareCount++ }
        )

        assertThat(consumed).isTrue()
        assertThat(prepareCount).isEqualTo(0)
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun handleEditorShortcut_shouldDismissSignatureHelpOnEscape() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp()
        var dismissed = false

        val consumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_ESCAPE),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = { dismissed = true },
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun handleEditorShortcut_shouldSyncSignaturePreviewWhileLoadingPreviousResult() {
        val state = EditorState(RopeTextBuffer())
        state.seedLoadingSignatureHelp(
            previousResult = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int count, String label)",
                    "foo(Int count, Boolean enabled)",
                    "foo(Int count, Map<String, List<Int>> metadata)"
                ),
                activeSignature = 0,
                activeParameter = 1
            ),
            requestId = 2L
        )

        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("String label")

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                metaState = AndroidKeyEvent.META_ALT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { delta -> state.cycleSignatureHelp(delta) },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(1)
        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("Boolean enabled")
    }

    @Test
    fun handleEditorShortcut_shouldWrapSignaturePreviewBackwardWhileLoadingPreviousResult() {
        val state = EditorState(RopeTextBuffer())
        state.seedLoadingSignatureHelp(
            previousResult = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int count, String label)",
                    "foo(Int count, Boolean enabled)",
                    "foo(Int count, Map<String, List<Int>> metadata)"
                ),
                activeSignature = 0,
                activeParameter = 1
            ),
            requestId = 3L
        )

        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("String label")

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_UP,
                metaState = AndroidKeyEvent.META_ALT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { delta -> state.cycleSignatureHelp(delta) },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(2)
        assertThat(resolveDisplayedSignaturePreview(state))
            .isEqualTo("Map<String, List<Int>> metadata")
    }

    @Test
    fun handleEditorShortcut_shouldMoveCursorWithPlainDirectionKeys() {
        val state = createState("ab\ncd")
        state.moveCursorTo(4)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_DPAD_LEFT),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.cursorOffset).isEqualTo(3)
    }

    @Test
    fun handleEditorShortcut_shouldMoveCursorByPageDown() {
        val state = createState("a\nb\nc\nd\ne")
        state.updateMetrics(
            lineHeightPx = 10f,
            charWidthPx = 10f,
            viewportHeightPx = 30f,
            viewportWidthPx = 200f,
            contentStartXPx = 0f
        )
        state.moveCursorTo(0)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_PAGE_DOWN),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.cursorPosition.line).isEqualTo(2)
    }

    @Test
    fun coerceColumnToVisualLineBounds_shouldHandleReversedBounds() {
        assertThat(
            listOf(
                coerceColumnToVisualLineBounds(2, startColumn = 10, endColumn = 4, lineLength = 12),
                coerceColumnToVisualLineBounds(8, startColumn = 10, endColumn = 4, lineLength = 12),
                coerceColumnToVisualLineBounds(20, startColumn = 10, endColumn = 4, lineLength = 12),
            )
        ).containsExactly(4, 8, 10).inOrder()
    }

    @Test
    fun handleEditorShortcut_shouldIgnoreCtrlAltAsShortcutForAltGr() {
        val state = createState("abc")
        var afterTextEditCalled = false

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_A,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_ALT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = { afterTextEditCalled = true },
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isFalse()
        assertThat(afterTextEditCalled).isFalse()
        assertThat(state.selectionRange).isNull()
    }

    @Test
    fun handleEditorShortcut_shouldMoveByWordWithCtrlDirection() {
        val state = createState("alpha beta")
        state.moveCursorTo(state.textBuffer.length)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                metaState = AndroidKeyEvent.META_CTRL_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.cursorOffset).isEqualTo(6)
    }

    @Test
    fun handleEditorShortcut_shouldExtendSelectionByWordWithCtrlShiftDirection() {
        val state = createState("alpha beta")
        state.moveCursorTo(0)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 0, caret = 5))
    }

    @Test
    fun handleEditorShortcut_shouldDeletePreviousWordWithCtrlBackspace() {
        val state = createState("alpha beta")
        state.moveCursorTo(state.textBuffer.length)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DEL,
                metaState = AndroidKeyEvent.META_CTRL_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("alpha ")
        assertThat(state.cursorOffset).isEqualTo(6)
    }

    @Test
    fun handleEditorShortcut_shouldDeleteNextWordWithCtrlDelete() {
        val state = createState("alpha beta")
        state.moveCursorTo(0)

        val consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_FORWARD_DEL,
                metaState = AndroidKeyEvent.META_CTRL_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {}
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(" beta")
        assertThat(state.cursorOffset).isEqualTo(0)
    }

    @Test
    fun handleEditorShortcut_shouldSelectCurrentWordWithCtrlD() {
        val state = createState("alpha beta")
        state.moveCursorTo(2)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 0, caret = 5))
    }

    @Test
    fun handleEditorShortcut_shouldSelectNextWholeWordWithCtrlD() {
        val state = createState("foo foobar foo")
        state.selectRange(0, 3)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 11, caret = 14))
    }

    @Test
    fun handleEditorShortcut_shouldWrapNextOccurrenceWithCtrlD() {
        val state = createState("foo bar foo")
        state.selectRange(8, 11)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 0, caret = 3))
    }

    @Test
    fun handleEditorShortcut_shouldSelectNextExactSelectionWithCtrlD() {
        val state = createState("a+b a-b a+b")
        state.selectRange(0, 3)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 8, caret = 11))
    }

    @Test
    fun handleEditorShortcut_shouldDuplicateCurrentLineWithCtrlShiftD() {
        val state = createState("one\ntwo\nthree")
        var afterTextEditCalled = false
        state.moveCursorTo(state.textBuffer.positionToOffset(1, 1))

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON
            ),
            onAfterTextEdit = { afterTextEditCalled = true }
        )

        assertThat(consumed).isTrue()
        assertThat(afterTextEditCalled).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("one\ntwo\ntwo\nthree")
        assertThat(state.cursorPosition.line).isEqualTo(2)
        assertThat(state.cursorPosition.column).isEqualTo(1)
    }

    @Test
    fun handleEditorShortcut_shouldDuplicateSelectedLinesWithCtrlShiftD() {
        val state = createState("a\nb\nc\nd")
        state.selectRange(
            startOffset = state.textBuffer.positionToOffset(1, 0),
            endOffset = state.textBuffer.positionToOffset(2, 1)
        )

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("a\nb\nc\nb\nc\nd")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 6, caret = 9))
    }

    @Test
    fun handleEditorShortcut_shouldDuplicateLastLineWithCtrlShiftD() {
        val state = createState("one\ntwo")
        state.moveCursorTo(state.textBuffer.positionToOffset(1, 1))

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_D,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("one\ntwo\ntwo")
        assertThat(state.cursorPosition.line).isEqualTo(2)
        assertThat(state.cursorPosition.column).isEqualTo(1)
    }

    @Test
    fun handleEditorShortcut_shouldMoveCurrentLineUpWithAltDirection() {
        val state = createState("one\ntwo\nthree")
        state.moveCursorTo(state.textBuffer.positionToOffset(1, 1))

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_UP,
                metaState = AndroidKeyEvent.META_ALT_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("two\none\nthree")
        assertThat(state.cursorPosition.line).isEqualTo(0)
        assertThat(state.cursorPosition.column).isEqualTo(1)
    }

    @Test
    fun handleEditorShortcut_shouldMoveCurrentLineDownWithAltDirection() {
        val state = createState("one\ntwo\nthree")
        state.moveCursorTo(state.textBuffer.positionToOffset(1, 0))

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                metaState = AndroidKeyEvent.META_ALT_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("one\nthree\ntwo")
        assertThat(state.cursorPosition.line).isEqualTo(2)
        assertThat(state.cursorPosition.column).isEqualTo(0)
    }

    @Test
    fun handleEditorShortcut_shouldMoveSelectedLinesUpWithAltDirection() {
        val state = createState("a\nb\nc\nd")
        state.selectRange(
            startOffset = state.textBuffer.positionToOffset(1, 0),
            endOffset = state.textBuffer.positionToOffset(2, 1)
        )

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_UP,
                metaState = AndroidKeyEvent.META_ALT_ON
            )
        )

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("b\nc\na\nd")
        assertThat(state.selectionRange).isEqualTo(OffsetRange(anchor = 0, caret = 3))
    }

    @Test
    fun handleEditorShortcut_shouldConsumeButNotEditWhenMovingBoundaryLine() {
        val state = createState("one\ntwo")
        var afterTextEditCalled = false
        state.moveCursorTo(0)

        val consumed = handleShortcut(
            state = state,
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_DPAD_UP,
                metaState = AndroidKeyEvent.META_ALT_ON
            ),
            onAfterTextEdit = { afterTextEditCalled = true }
        )

        assertThat(consumed).isTrue()
        assertThat(afterTextEditCalled).isFalse()
        assertThat(state.textBuffer.toString()).isEqualTo("one\ntwo")
        assertThat(state.cursorOffset).isEqualTo(0)
    }

    @Test
    fun handleEditorShortcut_shouldOpenContextMenuWithKeyboardMenuKeys() {
        val state = createState("alpha")
        var contextMenuRequests = 0

        val menuConsumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_MENU),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {},
            onRequestContextMenu = { contextMenuRequests++ }
        )
        val plainF10Consumed = handleEditorShortcut(
            event = keyDownEvent(keyCode = AndroidKeyEvent.KEYCODE_F10),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {},
            onRequestContextMenu = { contextMenuRequests++ }
        )
        val shiftF10Consumed = handleEditorShortcut(
            event = keyDownEvent(
                keyCode = AndroidKeyEvent.KEYCODE_F10,
                metaState = AndroidKeyEvent.META_SHIFT_ON
            ),
            state = state,
            onCopy = {},
            onCut = {},
            readPasteText = { null },
            onAfterTextEdit = {},
            onRequestCompletion = {},
            onRequestSignatureHelp = {},
            onCycleSignatureHelp = { false },
            onCompletionNavigate = { false },
            onApplySelectedCompletion = { false },
            onDismissCompletion = {},
            onDismissSignatureHelp = {},
            onIncreaseFont = {},
            onDecreaseFont = {},
            onRequestContextMenu = { contextMenuRequests++ }
        )

        assertThat(menuConsumed).isTrue()
        assertThat(plainF10Consumed).isFalse()
        assertThat(shiftF10Consumed).isTrue()
        assertThat(contextMenuRequests).isEqualTo(2)
    }

    private fun handleShortcut(
        state: EditorState,
        event: ComposeKeyEvent,
        onAfterTextEdit: () -> Unit = {},
        onApplySelectedCompletion: () -> Boolean = { false },
        onBeforeTextEdit: () -> Unit = {}
    ): Boolean = handleEditorShortcut(
        event = event,
        state = state,
        onCopy = {},
        onCut = {},
        readPasteText = { null },
        onAfterTextEdit = onAfterTextEdit,
        onRequestCompletion = {},
        onRequestSignatureHelp = {},
        onCycleSignatureHelp = { false },
        onCompletionNavigate = { false },
        onApplySelectedCompletion = onApplySelectedCompletion,
        onDismissCompletion = {},
        onDismissSignatureHelp = {},
        onIncreaseFont = {},
        onDecreaseFont = {},
        onBeforeTextEdit = onBeforeTextEdit
    )

    private fun keyDownEvent(
        keyCode: Int,
        metaState: Int = 0
    ): ComposeKeyEvent = ComposeKeyEvent(
        AndroidKeyEvent(
            0L,
            0L,
            AndroidKeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState
        )
    )

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }

    private fun resolveDisplayedSignaturePreview(state: EditorState): String? {
        val result = when (val uiState = state.signatureHelpUiState) {
            is SignatureHelpUiState.Visible -> uiState.result
            is SignatureHelpUiState.Loading -> uiState.previousResult
            SignatureHelpUiState.Hidden -> return null
        } ?: return null
        val displayedIndex = state.resolveDisplayedSignatureHelpIndex(result)
        return resolveSignatureActiveParameterPreview(
            signature = result.signatures[displayedIndex],
            activeParameter = result.activeParameter
        )
    }
}

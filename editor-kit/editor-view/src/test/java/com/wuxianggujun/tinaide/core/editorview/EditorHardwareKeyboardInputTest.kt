package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditorHardwareKeyboardInputTest {

    @Test
    fun handleKeyEvent_shouldInsertPrintableHardwareKey() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)

        val consumed = connection.handleKeyEvent(keyDown(KeyEvent.KEYCODE_A))

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("a")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    @Test
    fun handleKeyEvent_shouldNotInsertCtrlShortcutCharacter() {
        val state = EditorState(RopeTextBuffer())
        val connection = createConnection(state)

        val consumed = connection.handleKeyEvent(
            keyDown(
                keyCode = KeyEvent.KEYCODE_A,
                metaState = KeyEvent.META_CTRL_ON
            )
        )

        assertThat(consumed).isFalse()
        assertThat(state.textBuffer.toString()).isEmpty()
    }

    @Test
    fun hostDispatchKeyEvent_shouldInsertHardwareKeyWithoutImeConnectionCreated() {
        val state = EditorState(RopeTextBuffer())
        val host = EditorInputHostView(ApplicationProvider.getApplicationContext<Context>())
        val controller = EditorInteractionController(
            state = state,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            focusRequester = FocusRequester(),
            keyboardController = null,
            inputMethodManager = null
        )
        controller.bindInputHostView(host)

        val consumed = host.dispatchKeyEvent(keyDown(KeyEvent.KEYCODE_A))

        assertThat(consumed).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo("a")
    }

    @Test
    fun handleKeyEvent_shouldDeferModifiedNavigationKeysToGlobalShortcuts() {
        val state = EditorState(RopeTextBuffer().apply { insert(0, "abc") })
        val connection = createConnection(state)
        state.moveCursorTo(1)

        val altLeftConsumed = connection.handleKeyEvent(
            keyDown(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                metaState = KeyEvent.META_ALT_ON
            )
        )
        val ctrlTabConsumed = connection.handleKeyEvent(
            keyDown(
                keyCode = KeyEvent.KEYCODE_TAB,
                metaState = KeyEvent.META_CTRL_ON
            )
        )

        assertThat(altLeftConsumed).isFalse()
        assertThat(ctrlTabConsumed).isFalse()
        assertThat(state.textBuffer.toString()).isEqualTo("abc")
        assertThat(state.cursorOffset).isEqualTo(1)
    }

    private fun createConnection(state: EditorState): EditorInputConnection = EditorInputConnection(
        targetView = EditorInputHostView(ApplicationProvider.getApplicationContext<Context>()),
        state = state,
        onInsertedText = {},
        onNonInsertEdit = {}
    )

    private fun keyDown(
        keyCode: Int,
        metaState: Int = 0
    ): KeyEvent = KeyEvent(
        0L,
        0L,
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        metaState
    )
}

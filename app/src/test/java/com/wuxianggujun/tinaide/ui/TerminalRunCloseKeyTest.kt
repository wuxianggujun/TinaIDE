package com.wuxianggujun.tinaide.ui

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 运行终端"程序结束后按 Enter 关闭"的判据。
 *
 * 核心不变量：程序结束**之前**按下的那次 Enter 属于程序的标准输入，它的任何残余事件
 * （KeyUp、自动重复、IME Done、已入队的快捷键点击）都不能关闭界面。
 */
class TerminalRunCloseKeyTest {

    private val runEndedAt = 10_000L

    @Test
    fun `enter pressed before program end must not close terminal`() {
        // 用户为程序输入数字后按下的那次 Enter：downTime 早于程序结束时刻。
        assertThat(
            shouldCloseRunTerminalOnKey(
                runEndedAtMs = runEndedAt,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTimeMs = runEndedAt - 50,
            )
        ).isFalse()
    }

    @Test
    fun `key up of the pre-end enter must not close terminal`() {
        assertThat(
            shouldCloseRunTerminalOnKey(
                runEndedAtMs = runEndedAt,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                downTimeMs = runEndedAt - 50,
            )
        ).isFalse()
    }

    @Test
    fun `enter pressed after program end closes terminal`() {
        assertThat(
            shouldCloseRunTerminalOnKey(
                runEndedAtMs = runEndedAt,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTimeMs = runEndedAt + 1,
            )
        ).isTrue()
    }

    @Test
    fun `auto repeat after program end must not close terminal`() {
        assertThat(
            shouldCloseRunTerminalOnKey(
                runEndedAtMs = runEndedAt,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                downTimeMs = runEndedAt + 1,
            )
        ).isFalse()
    }

    @Test
    fun `key event before program has ended must not close terminal`() {
        assertThat(
            shouldCloseRunTerminalOnKey(
                runEndedAtMs = null,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTimeMs = runEndedAt + 100,
            )
        ).isFalse()
    }

    @Test
    fun `both main and numpad enter are close keys`() {
        assertThat(isRunTerminalCloseEnterKey(KeyEvent.KEYCODE_ENTER)).isTrue()
        assertThat(isRunTerminalCloseEnterKey(KeyEvent.KEYCODE_NUMPAD_ENTER)).isTrue()
        assertThat(isRunTerminalCloseEnterKey(KeyEvent.KEYCODE_1)).isFalse()
        assertThat(isRunTerminalCloseEnterKey(KeyEvent.KEYCODE_DPAD_CENTER)).isFalse()
    }

    @Test
    fun `grace window rejects events still in flight at program end`() {
        assertThat(hasRunTerminalCloseGracePassed(runEndedAt, runEndedAt)).isFalse()
        assertThat(
            hasRunTerminalCloseGracePassed(runEndedAt, runEndedAt + RUN_TERMINAL_CLOSE_GRACE_MS - 1)
        ).isFalse()
    }

    @Test
    fun `grace window admits events after the window elapses`() {
        assertThat(
            hasRunTerminalCloseGracePassed(runEndedAt, runEndedAt + RUN_TERMINAL_CLOSE_GRACE_MS)
        ).isTrue()
    }

    @Test
    fun `grace window rejects everything before program has ended`() {
        assertThat(hasRunTerminalCloseGracePassed(null, runEndedAt + 10_000)).isFalse()
    }

    @Test
    fun `soft keyboard newline code points are recognized`() {
        // Termux 把 commitText 的 '\n' 折成 ctrl+'m'。
        assertThat(isRunTerminalCloseEnterCodePoint('m'.code, ctrlDown = true)).isTrue()
        assertThat(isRunTerminalCloseEnterCodePoint('j'.code, ctrlDown = true)).isTrue()
        assertThat(isRunTerminalCloseEnterCodePoint('\r'.code, ctrlDown = false)).isTrue()
        assertThat(isRunTerminalCloseEnterCodePoint('\n'.code, ctrlDown = false)).isTrue()
    }

    @Test
    fun `plain characters are not close code points`() {
        assertThat(isRunTerminalCloseEnterCodePoint('m'.code, ctrlDown = false)).isFalse()
        assertThat(isRunTerminalCloseEnterCodePoint('5'.code, ctrlDown = false)).isFalse()
        assertThat(isRunTerminalCloseEnterCodePoint('c'.code, ctrlDown = true)).isFalse()
    }
}

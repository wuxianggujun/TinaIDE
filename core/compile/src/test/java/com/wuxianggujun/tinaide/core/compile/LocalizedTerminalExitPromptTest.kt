package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalizedTerminalExitPromptTest {

    @Test
    fun `chinese prompt keeps localized text and runtime exit code`() {
        val suffix = buildLocalizedWaitForEnterSuffix(
            "\n[程序已退出，退出码: %1\$d]\n按 Enter 键关闭当前界面..."
        )

        assertThat(suffix).contains("程序已退出，退出码: ")
        assertThat(suffix).contains("按 Enter 键关闭当前界面...")
        assertThat(suffix).contains("printf '%s%d%s'")
        assertThat(suffix).contains("\"\$__tina_rc\"")
        assertThat(suffix).doesNotContain("%1\$d")
    }

    @Test
    fun `english prompt keeps localized text and runtime exit code`() {
        val suffix = buildLocalizedWaitForEnterSuffix(
            "\n[Program exited, code: %1\$d]\nPress Enter to close this screen..."
        )

        assertThat(suffix).contains("Program exited, code: ")
        assertThat(suffix).contains("Press Enter to close this screen...")
        assertThat(suffix).contains("printf '%s%d%s'")
        assertThat(suffix).doesNotContain("程序已退出")
    }

    @Test
    fun `percent and apostrophe in translation stay printf data`() {
        val suffix = buildLocalizedWaitForEnterSuffix(
            "\n[It's 100% complete; code: %1\$d]\nPress Enter..."
        )

        assertThat(suffix).contains("100% complete")
        assertThat(suffix).contains("'\"'\"'")
        assertThat(suffix).contains("printf '%s%d%s'")
        assertThat(suffix).doesNotContain("100%% complete")
    }

    @Test
    fun `missing exit code placeholder fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildLocalizedWaitForEnterSuffix("Program exited")
        }
    }
}

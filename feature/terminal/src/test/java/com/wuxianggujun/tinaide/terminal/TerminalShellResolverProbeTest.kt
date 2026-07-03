package com.wuxianggujun.tinaide.terminal.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalShellResolverProbeTest {

    @Test
    fun buildGuestCommandAvailabilityProbe_shouldUseShellTestForAbsolutePath() {
        assertThat(buildGuestCommandAvailabilityProbe(" /usr/bin/zsh ")).isEqualTo(
            listOf("/bin/sh", "-lc", "[ -x '/usr/bin/zsh' ]")
        )
    }

    @Test
    fun buildGuestCommandAvailabilityProbe_shouldUseCommandVForPathCommand() {
        assertThat(buildGuestCommandAvailabilityProbe(" sh ")).isEqualTo(
            listOf("/bin/sh", "-lc", "command -v 'sh' >/dev/null 2>&1")
        )
    }
}

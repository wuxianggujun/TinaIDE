package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PRootEnvironmentProbeTest {

    @Test
    fun buildCommandAvailabilityProbe_shouldUseShellTestForAbsolutePath() {
        assertThat(buildCommandAvailabilityProbe(" /sbin/apk ")).isEqualTo(
            listOf("/bin/sh", "-lc", "[ -x '/sbin/apk' ]")
        )
    }

    @Test
    fun buildCommandAvailabilityProbe_shouldUseCommandVForPathCommand() {
        assertThat(buildCommandAvailabilityProbe(" apk ")).isEqualTo(
            listOf("/bin/sh", "-lc", "command -v 'apk' >/dev/null 2>&1")
        )
    }
}

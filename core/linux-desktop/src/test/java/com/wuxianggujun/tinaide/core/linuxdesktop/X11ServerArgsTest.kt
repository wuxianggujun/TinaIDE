package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class X11ServerArgsTest {

    @Test
    fun toArgv_shouldStartWithDisplayAndCarryGeometry() {
        // argv[0] 由 native 侧补成 "Xlorie"，所以这里第一项就是 display。
        val argv = X11ServerArgs(
            displayNumber = 0,
            config = X11DisplayConfig(width = 1920, height = 1080, dpi = 160, colorDepth = 24),
        ).toArgv()

        assertThat(argv).containsExactly(
            ":0",
            "-ac",
            "-noreset",
            "-screen",
            "1920x1080x24",
            "-dpi",
            "160",
        ).inOrder()
    }

    @Test
    fun toArgv_shouldUseNoResetSoTheServerSurvivesTheLastClient() {
        // 桌面会话退出后 X server 应当留着，否则重开桌面要重启整个 :x11 进程。
        val argv = X11ServerArgs(displayNumber = 1, config = X11DisplayConfig.default()).toArgv()

        assertThat(argv).contains("-noreset")
    }

    @Test
    fun constructor_shouldRejectNegativeDisplay() {
        assertThat(
            runCatching {
                X11ServerArgs(displayNumber = -1, config = X11DisplayConfig.default())
            }.isFailure
        ).isTrue()
    }

    @Test
    fun constructor_shouldRejectNonPositiveGeometry() {
        assertThat(
            runCatching {
                X11ServerArgs(
                    displayNumber = 0,
                    config = X11DisplayConfig(width = 0, height = 1080, dpi = 160),
                )
            }.isFailure
        ).isTrue()
    }
}

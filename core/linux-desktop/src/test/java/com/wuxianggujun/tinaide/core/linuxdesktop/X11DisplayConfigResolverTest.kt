package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class X11DisplayConfigResolverTest {

    @Test
    fun `should normalise a portrait phone screen to landscape`() {
        val config = x11DisplayConfigOf(screenWidth = 1080, screenHeight = 2400, densityDpi = 420)

        assertThat(config.width).isEqualTo(2400)
        assertThat(config.height).isEqualTo(1080)
        assertThat(config.dpi).isEqualTo(420)
    }

    @Test
    fun `should keep an already landscape screen unchanged`() {
        val config = x11DisplayConfigOf(screenWidth = 2560, screenHeight = 1600, densityDpi = 240)

        assertThat(config.width).isEqualTo(2560)
        assertThat(config.height).isEqualTo(1600)
    }

    @Test
    fun `should fall back to the default geometry when metrics are unavailable`() {
        val fallback = X11DisplayConfig.default()

        val config = x11DisplayConfigOf(screenWidth = 0, screenHeight = 0, densityDpi = 0)

        assertThat(config.width).isEqualTo(fallback.width)
        assertThat(config.height).isEqualTo(fallback.height)
        assertThat(config.dpi).isEqualTo(fallback.dpi)
    }

    @Test
    fun `should clamp degenerate dimensions so the X server gets a usable root window`() {
        // X server 对退化尺寸没有保护；1x1 的 root window 会让 XFCE 起来就崩。
        val config = x11DisplayConfigOf(screenWidth = 1, screenHeight = 1, densityDpi = 1)

        assertThat(config.width).isAtLeast(640)
        assertThat(config.height).isAtLeast(640)
        assertThat(config.dpi).isAtLeast(72)
    }

    @Test
    fun `should clamp absurdly large dimensions`() {
        val config = x11DisplayConfigOf(
            screenWidth = 100_000,
            screenHeight = 90_000,
            densityDpi = 10_000,
        )

        assertThat(config.width).isEqualTo(7680)
        assertThat(config.height).isEqualTo(7680)
        assertThat(config.dpi).isEqualTo(640)
    }
}

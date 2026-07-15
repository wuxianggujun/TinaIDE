package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginCompatibilityTest {
    @Test
    fun `missing minimum version remains compatible with legacy manifests`() {
        val result = PluginCompatibility.evaluate("0.18.11", null)

        assertThat(result.status).isEqualTo(PluginCompatibilityStatus.COMPATIBLE)
        assertThat(result.isCompatible).isTrue()
    }

    @Test
    fun `invalid minimum version fails open for legacy compatibility`() {
        val result = PluginCompatibility.evaluate("0.18.11", "next")

        assertThat(result.status).isEqualTo(PluginCompatibilityStatus.INVALID_MIN_APP_VERSION)
        assertThat(result.isCompatible).isTrue()
    }

    @Test
    fun `unknown host version fails open instead of disabling legacy plugins`() {
        val result = PluginCompatibility.evaluate(null, "0.18.11")

        assertThat(result.status).isEqualTo(PluginCompatibilityStatus.HOST_VERSION_UNKNOWN)
        assertThat(result.isCompatible).isTrue()
    }

    @Test
    fun `host below declared minimum is incompatible`() {
        val result = PluginCompatibility.evaluate("0.18.10", "0.18.11")

        assertThat(result.status).isEqualTo(PluginCompatibilityStatus.HOST_TOO_OLD)
        assertThat(result.isCompatible).isFalse()
    }

    @Test
    fun `host at or above declared minimum is compatible`() {
        assertThat(PluginCompatibility.evaluate("0.18.11", "0.18.11").isCompatible).isTrue()
        assertThat(PluginCompatibility.evaluate("0.19.0", "0.18.11").isCompatible).isTrue()
    }
}

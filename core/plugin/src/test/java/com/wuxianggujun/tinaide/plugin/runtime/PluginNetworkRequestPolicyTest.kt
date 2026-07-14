package com.wuxianggujun.tinaide.plugin.runtime

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class PluginNetworkRequestPolicyTest {
    @Test
    fun `redirect target must remain inside declared hosts`() {
        val policy = PluginNetworkRequestPolicy(
            unrestricted = false,
            allowedHosts = setOf("example.com"),
        )

        assertThat(policy.allows("https://api.example.com/next".toHttpUrl())).isTrue()
        assertThat(policy.allows("https://attacker.invalid/next".toHttpUrl())).isFalse()
    }

    @Test
    fun `unrestricted permission allows redirect target`() {
        val policy = PluginNetworkRequestPolicy(
            unrestricted = true,
            allowedHosts = emptySet(),
        )

        assertThat(policy.allows("https://attacker.invalid/next".toHttpUrl())).isTrue()
    }
}

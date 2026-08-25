package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteLspSecurityPolicyTest {
    @Test
    fun config_shouldRequireValidHostTokenAndSecureRemoteTransport() {
        assertThat(
            RemoteLspConfig(
                enabled = true,
                host = "example.com",
                hasAuthenticationToken = true,
            ).isValid()
        ).isTrue()
        assertThat(
            RemoteLspConfig(
                enabled = true,
                host = "example.com",
                secureTransport = false,
                hasAuthenticationToken = true,
            ).isValid()
        ).isFalse()
        assertThat(
            RemoteLspConfig(
                enabled = true,
                host = "localhost",
                secureTransport = false,
                hasAuthenticationToken = true,
            ).isValid()
        ).isTrue()
        assertThat(
            RemoteLspConfig(
                enabled = true,
                host = "::1",
                secureTransport = false,
                hasAuthenticationToken = true,
            ).isValid()
        ).isTrue()
        assertThat(
            RemoteLspConfig(enabled = true, host = "example.com").isValid()
        ).isFalse()
    }

    @Test
    fun config_shouldRejectMalformedHostsAndNormalizeSupportedLoopbackHosts() {
        assertThat(RemoteLspConfig(host = "example..com").hasValidHostSyntax()).isFalse()
        assertThat(RemoteLspConfig(host = "example.com/path").hasValidHostSyntax()).isFalse()
        assertThat(RemoteLspConfig(host = "[2001:db8::1]").hasValidHostSyntax()).isTrue()
        assertThat(RemoteLspConfig(host = "::1").getWebSocketUrl()).isEqualTo("wss://localhost:6789")
        assertThat(RemoteLspConfig(host = "[::1]").getWebSocketUrl()).isEqualTo("wss://localhost:6789")
        assertThat(RemoteLspConfig(host = "127.0.0.1").getWebSocketUrl())
            .isEqualTo("wss://localhost:6789")
    }

    @Test
    fun authenticationTokenPolicy_shouldRejectOversizedAndControlCharacters() {
        assertThat(RemoteLspAuthenticationTokenPolicy.validate("token"))
            .isEqualTo(RemoteLspAuthenticationTokenValidation.VALID)
        assertThat(RemoteLspAuthenticationTokenPolicy.validate("x".repeat(8 * 1024 + 1)))
            .isEqualTo(RemoteLspAuthenticationTokenValidation.TOO_LONG)
        assertThat(RemoteLspAuthenticationTokenPolicy.validate("界".repeat(3 * 1024)))
            .isEqualTo(RemoteLspAuthenticationTokenValidation.TOO_LONG)
        assertThat(RemoteLspAuthenticationTokenPolicy.validate("token\nvalue"))
            .isEqualTo(RemoteLspAuthenticationTokenValidation.CONTROL_CHARACTER)
    }
}

package com.wuxianggujun.tinaide.plugin.lsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LspServerCommandPolicyTest {

    @Test
    fun `accepts bounded command arguments and environment`() {
        assertThat(
            LspServerCommandPolicy.isValid(
                command = "clangd",
                args = listOf("--background-index", "--compile-commands-dir=build"),
                environment = mapOf("CLANGD_FLAGS" to "--log=error"),
            ),
        ).isTrue()
    }

    @Test
    fun `rejects control characters and invalid environment names`() {
        assertThat(LspServerCommandPolicy.isValid("clangd\nrm", emptyList(), emptyMap())).isFalse()
        assertThat(LspServerCommandPolicy.isValid("clangd", listOf("ok\u0000bad"), emptyMap())).isFalse()
        assertThat(LspServerCommandPolicy.isValid("clangd", emptyList(), mapOf("BAD-NAME" to "value"))).isFalse()
    }

    @Test
    fun `rejects resource limit violations`() {
        assertThat(LspServerCommandPolicy.isValid("x".repeat(1_025), emptyList(), emptyMap())).isFalse()
        assertThat(LspServerCommandPolicy.isValid("clangd", List(129) { "arg" }, emptyMap())).isFalse()
        assertThat(LspServerCommandPolicy.isValid("clangd", emptyList(), mapOf("VALUE" to "x".repeat(8_193)))).isFalse()
    }
}

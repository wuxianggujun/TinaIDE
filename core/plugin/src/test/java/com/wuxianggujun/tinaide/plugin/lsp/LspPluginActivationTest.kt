package com.wuxianggujun.tinaide.plugin.lsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class LspPluginActivationTest {
    @Test
    fun `empty activation events allow contributed languages`() {
        assertThat(pluginInfo(emptyList()).supportsLanguageActivation("python")).isTrue()
    }

    @Test
    fun `declared activation events gate language activation case-insensitively`() {
        val plugin = pluginInfo(listOf("onLanguage:python"))

        assertThat(plugin.supportsLanguageActivation("PYTHON")).isTrue()
        assertThat(plugin.supportsLanguageActivation("javascript")).isFalse()
    }

    private fun pluginInfo(events: List<String>) = LspPluginInfo(
        pluginId = "test.lsp",
        pluginName = "Test LSP",
        pluginVersion = "1.0.0",
        directory = File("test.lsp"),
        serverConfigs = emptyList(),
        toolchainConfigs = emptyList(),
        activationEvents = events,
    )
}

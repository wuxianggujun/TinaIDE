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

    @Test
    fun `server activation requires at least one contributed language event`() {
        val plugin = pluginInfo(listOf("onLanguage:python"))

        assertThat(plugin.supportsServerActivation(serverConfig(listOf("python", "javascript")))).isTrue()
        assertThat(plugin.supportsServerActivation(serverConfig(listOf("javascript")))).isFalse()
    }

    @Test
    fun `multi language server activation follows the detected document language`() {
        val plugin = pluginInfo(listOf("onLanguage:python"))
        val server = serverConfig(listOf("python", "javascript"))

        assertThat(plugin.supportsServerActivation(server, "python")).isTrue()
        assertThat(plugin.supportsServerActivation(server, "javascript")).isFalse()
    }

    @Test
    fun `document language resolution prefers exact and language family matches`() {
        val server = serverConfig(listOf("typescript", "javascript"))

        assertThat(server.resolveDocumentLanguageId("javascript", "js")).isEqualTo("javascript")
        assertThat(server.resolveDocumentLanguageId("typescriptreact", "tsx")).isEqualTo("typescript")
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

    private fun serverConfig(languages: List<String>) = LspServerConfig(
        id = "test-server",
        name = "Test Server",
        languages = languages,
        fileExtensions = listOf("test"),
        server = LspServerConnectionConfig(type = "stdio", command = "test-server"),
    )
}

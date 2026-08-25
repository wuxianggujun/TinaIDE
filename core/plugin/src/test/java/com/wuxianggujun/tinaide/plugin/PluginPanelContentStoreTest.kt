package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Test

class PluginPanelContentStoreTest {
    private val first = PluginPanelKey("plugin.first", "status")
    private val second = PluginPanelKey("plugin.second", "status")

    @After
    fun tearDown() {
        PluginPanelContentStore.clearAll()
    }

    @Test
    fun `set append and targeted cleanup keep plugin ownership isolated`() {
        PluginPanelContentStore.set(first, "ready")
        PluginPanelContentStore.append(first, "\ndone")
        PluginPanelContentStore.set(second, "survivor")

        PluginPanelContentStore.clearPlugin(first.pluginId)

        assertThat(PluginPanelContentStore.contents.value)
            .containsExactly(second, "survivor")
    }

    @Test
    fun `content limit is enforced in UTF-8 bytes`() {
        val oversized = "界".repeat(PluginPanelContentStore.MAX_CONTENT_BYTES / 3 + 1)

        val error = runCatching { PluginPanelContentStore.set(first, oversized) }.exceptionOrNull()

        assertThat(oversized.toByteArray(StandardCharsets.UTF_8).size)
            .isGreaterThan(PluginPanelContentStore.MAX_CONTENT_BYTES)
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(PluginPanelContentStore.contents.value).isEmpty()
    }
}

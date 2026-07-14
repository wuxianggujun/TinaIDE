package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class PluginStateSnapshotTest {

    @Test
    fun create_shouldSplitInstalledAndEnabledStateConsistently() {
        val snapshot = PluginStateSnapshotFactory.create(
            listOf(
                installedPlugin(
                    id = "system.disabled",
                    version = "1.0.0",
                    type = PluginTypes.SYSTEM,
                    enabled = false,
                    capabilities = listOf(PluginCapabilities.LINUX_ENVIRONMENT),
                ),
                installedPlugin(
                    id = "config.enabled",
                    version = "2.3.4",
                    type = PluginTypes.CONFIG,
                    enabled = true,
                ),
                installedPlugin(
                    id = "system.enabled",
                    version = "3.0.0",
                    type = PluginTypes.SYSTEM,
                    enabled = true,
                    capabilities = listOf(" featureA ", "", PluginCapabilities.LINUX_ENVIRONMENT),
                ),
                installedPlugin(
                    id = "script.panels",
                    version = "1.0.0",
                    type = PluginTypes.SCRIPT,
                    enabled = true,
                    panels = listOf(PluginPanel("status", "Status")),
                ),
            )
        )

        assertThat(snapshot.installedPluginIds)
            .containsExactly("system.disabled", "config.enabled", "system.enabled", "script.panels")
            .inOrder()
        assertThat(snapshot.enabledPluginIds)
            .containsExactly("config.enabled", "system.enabled", "script.panels")
            .inOrder()
        assertThat(snapshot.installedVersions)
            .containsExactly(
                "system.disabled",
                "1.0.0",
                "config.enabled",
                "2.3.4",
                "system.enabled",
                "3.0.0",
                "script.panels",
                "1.0.0",
            )
        assertThat(snapshot.enabledCapabilities)
            .containsExactly("featureA", PluginCapabilities.LINUX_ENVIRONMENT)
        assertThat(snapshot.resolvedPanels).containsExactly(
            ResolvedPluginPanel(
                pluginId = "script.panels",
                pluginName = "script.panels",
                panelId = "status",
                title = "Status",
            ),
        )
        assertThat(snapshot.isInstalled("system.disabled")).isTrue()
        assertThat(snapshot.isEnabled("system.disabled")).isFalse()
        assertThat(snapshot.isEnabled("system.enabled")).isTrue()
        assertThat(snapshot.getInstalledVersion("config.enabled")).isEqualTo("2.3.4")
    }

    private fun installedPlugin(
        id: String,
        version: String,
        type: String,
        enabled: Boolean,
        capabilities: List<String>? = null,
        panels: List<PluginPanel>? = null,
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = id,
            version = version,
            type = type,
            capabilities = capabilities,
            contributions = panels?.let { PluginContributions(panels = it) },
        ),
        directory = File(id),
        enabled = enabled,
    )
}

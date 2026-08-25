package com.wuxianggujun.tinaide.plugin

data class PluginStateSnapshot(
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    val enabledPlugins: List<InstalledPlugin> = emptyList(),
    val installedPluginIds: Set<String> = emptySet(),
    val enabledPluginIds: Set<String> = emptySet(),
    val installedVersions: Map<String, String> = emptyMap(),
    val enabledCapabilities: Set<String> = emptySet(),
    val resolvedPanels: List<ResolvedPluginPanel> = emptyList(),
) {
    fun isInstalled(pluginId: String): Boolean = pluginId in installedPluginIds

    fun isEnabled(pluginId: String): Boolean = pluginId in enabledPluginIds

    fun getInstalledVersion(pluginId: String): String? = installedVersions[pluginId]
}

internal object PluginStateSnapshotFactory {
    fun create(installedPlugins: List<InstalledPlugin>): PluginStateSnapshot {
        val enabledPlugins = installedPlugins.filter { it.enabled }
        return PluginStateSnapshot(
            installedPlugins = installedPlugins,
            enabledPlugins = enabledPlugins,
            installedPluginIds = installedPlugins.mapTo(linkedSetOf()) { it.manifest.id },
            enabledPluginIds = enabledPlugins.mapTo(linkedSetOf()) { it.manifest.id },
            installedVersions = installedPlugins.associate { it.manifest.id to it.manifest.version },
            enabledCapabilities = enabledPlugins.asSequence()
                .filter { it.manifest.type == PluginTypes.SYSTEM }
                .flatMap { it.manifest.capabilities.orEmpty().asSequence() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            resolvedPanels = enabledPlugins.flatMap { plugin ->
                plugin.manifest.contributions?.panels.orEmpty().map { panel ->
                    ResolvedPluginPanel(
                        pluginId = plugin.manifest.id,
                        pluginName = plugin.manifest.name,
                        panelId = panel.id,
                        title = panel.title,
                        icon = panel.icon,
                    )
                }
            },
        )
    }
}

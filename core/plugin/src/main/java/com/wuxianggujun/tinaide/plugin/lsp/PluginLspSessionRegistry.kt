package com.wuxianggujun.tinaide.plugin.lsp

import java.util.concurrent.ConcurrentHashMap

/** Tracks LSP processes by owning plugin so disable, upgrade, quarantine and uninstall can close them immediately. */
object PluginLspSessionRegistry {
    private val sessions = ConcurrentHashMap<String, MutableSet<PluginLspConnectionProvider>>()

    internal fun register(ownerPluginId: String, provider: PluginLspConnectionProvider) {
        sessions.computeIfAbsent(ownerPluginId) { ConcurrentHashMap.newKeySet() }.add(provider)
    }

    internal fun unregister(ownerPluginId: String, provider: PluginLspConnectionProvider) {
        sessions[ownerPluginId]?.let { providers ->
            providers.remove(provider)
            if (providers.isEmpty()) sessions.remove(ownerPluginId, providers)
        }
    }

    fun closeAll(ownerPluginId: String) {
        sessions.remove(ownerPluginId)?.toList()?.forEach { provider -> provider.closeFromOwner() }
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::closeAll)
    }
}

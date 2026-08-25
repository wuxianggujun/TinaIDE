package com.wuxianggujun.tinaide.plugin.lsp

import java.util.concurrent.ConcurrentHashMap

/** Tracks LSP processes by owning plugin so disable, upgrade, quarantine and uninstall can close them immediately. */
object PluginLspSessionRegistry {
    internal data class OwnerLease(
        val ownerPluginId: String,
        val generation: Long,
    )

    private data class OwnerState(
        val generation: Long,
        val active: Boolean,
    )

    private val sessions = ConcurrentHashMap<String, MutableSet<PluginLspConnectionProvider>>()
    private val ownerStates = ConcurrentHashMap<String, OwnerState>()
    private val ownerLocks = ConcurrentHashMap<String, Any>()

    fun activate(ownerPluginId: String) {
        synchronized(ownerLock(ownerPluginId)) {
            val current = ownerStates[ownerPluginId]
            if (current?.active == true) return
            ownerStates[ownerPluginId] = OwnerState(
                generation = (current?.generation ?: 0L) + 1L,
                active = true,
            )
        }
    }

    internal fun acquire(ownerPluginId: String): OwnerLease? = synchronized(ownerLock(ownerPluginId)) {
        val state = ownerStates[ownerPluginId]?.takeIf { it.active } ?: return@synchronized null
        OwnerLease(ownerPluginId, state.generation)
    }

    internal fun register(
        lease: OwnerLease,
        provider: PluginLspConnectionProvider,
    ): Boolean = synchronized(ownerLock(lease.ownerPluginId)) {
        val state = ownerStates[lease.ownerPluginId]
        if (state?.active != true || state.generation != lease.generation) return@synchronized false
        sessions.computeIfAbsent(lease.ownerPluginId) { ConcurrentHashMap.newKeySet() }.add(provider)
        true
    }

    internal fun unregister(ownerPluginId: String, provider: PluginLspConnectionProvider) {
        synchronized(ownerLock(ownerPluginId)) {
            sessions[ownerPluginId]?.let { providers ->
                providers.remove(provider)
                if (providers.isEmpty()) sessions.remove(ownerPluginId, providers)
            }
        }
    }

    fun closeAll(ownerPluginId: String) {
        val providers = synchronized(ownerLock(ownerPluginId)) {
            val current = ownerStates[ownerPluginId]
            ownerStates[ownerPluginId] = OwnerState(
                generation = (current?.generation ?: 0L) + 1L,
                active = false,
            )
            sessions.remove(ownerPluginId)?.toList().orEmpty()
        }
        providers.forEach(PluginLspConnectionProvider::closeFromOwner)
    }

    fun closeAll() {
        (ownerStates.keys + sessions.keys).toSet().forEach(::closeAll)
    }

    private fun ownerLock(ownerPluginId: String): Any = ownerLocks.computeIfAbsent(ownerPluginId) { Any() }
}

package com.wuxianggujun.tinaide.plugin

import java.util.concurrent.CopyOnWriteArraySet

/** Coordinates plugin filesystem transactions with the active runtime without creating a manager cycle. */
internal object PluginRuntimeLifecycle {
    data class Handler(
        val stop: suspend (String) -> Unit,
        val activate: suspend (String) -> Result<Unit>,
    )

    private val handlers = CopyOnWriteArraySet<Handler>()

    fun register(handler: Handler) {
        handlers += handler
    }

    fun unregister(handler: Handler) {
        handlers -= handler
    }

    suspend fun stop(pluginId: String) {
        var firstFailure: Throwable? = null
        handlers.forEach { handler ->
            runCatching { handler.stop(pluginId) }
                .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
        }
        firstFailure?.let { throw it }
    }

    suspend fun activate(pluginId: String): Result<Unit> {
        handlers.forEach { handler ->
            val result = handler.activate(pluginId)
            if (result.isFailure) return result
        }
        return Result.success(Unit)
    }
}

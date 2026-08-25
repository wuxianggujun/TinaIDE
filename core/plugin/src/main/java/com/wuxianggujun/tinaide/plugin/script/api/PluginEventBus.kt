package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

enum class PluginEvent(val id: String) {
    EDITOR_OPENED("editor.opened"),
    EDITOR_CLOSED("editor.closed"),
    EDITOR_SAVED("editor.saved"),
    EDITOR_ACTIVE_CHANGED("editor.activeChanged"),
    EDITOR_SELECTION_CHANGED("editor.selectionChanged"),
    EDITOR_DIRTY_CHANGED("editor.dirtyChanged"),
    FILE_CREATED("file.created"),
    FILE_DELETED("file.deleted"),
    FILE_RENAMED("file.renamed"),
    DIAGNOSTICS_CHANGED("diagnostics.changed"),
    PROJECT_OPENED("project.opened"),
    PROJECT_CLOSED("project.closed"),
    BUILD_STARTED("build.started"),
    BUILD_FINISHED("build.finished"),
    CONFIG_CHANGED("config.changed"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String): PluginEvent? = entries.find { it.id == id }
    }
}

data class EventListener(
    val pluginId: String,
    val eventId: String,
    val callbackName: String,
)

object PluginEventBus {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>>()

    @Volatile
    private var callbackInvoker: (suspend (String, String, Map<String, Any?>?) -> PluginExecutionResult)? = null

    fun setCallbackInvoker(invoker: suspend (String, String, Map<String, Any?>?) -> PluginExecutionResult) {
        callbackInvoker = invoker
    }

    fun subscribe(pluginId: String, eventId: String, callbackName: String) {
        requireNotNull(PluginEvent.fromId(eventId)) { "Unknown plugin event: $eventId" }
        listeners.getOrPut(eventId) { CopyOnWriteArrayList() }
            .addIfAbsent(EventListener(pluginId, eventId, callbackName))
    }

    fun unsubscribe(pluginId: String, eventId: String) {
        listeners[eventId]?.removeIf { it.pluginId == pluginId }
    }

    fun unsubscribeAll(pluginId: String) {
        listeners.values.forEach { list -> list.removeIf { it.pluginId == pluginId } }
    }

    suspend fun emit(
        eventId: String,
        data: Map<String, Any?>? = null,
        targetPluginId: String? = null,
    ) {
        requireNotNull(PluginEvent.fromId(eventId)) { "Unknown plugin event: $eventId" }
        val invoker = callbackInvoker ?: return
        val targets = listeners[eventId].orEmpty().filter { listener ->
            targetPluginId == null || listener.pluginId == targetPluginId
        }
        targets.forEach { listener ->
            runCatching { invoker(listener.pluginId, listener.callbackName, data) }
                .onFailure { error ->
                    Timber.tag("PluginEventBus").e(
                        error,
                        "Event dispatch boundary failed pluginId=%s eventId=%s",
                        listener.pluginId,
                        eventId,
                    )
                }
        }
    }

    fun clear() {
        listeners.clear()
        callbackInvoker = null
    }
}

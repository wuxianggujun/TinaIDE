package com.wuxianggujun.tinaide.plugin

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local content for manifest-declared plugin panels.
 *
 * Panel content is intentionally text-only and ephemeral. A plugin republishes it after reload,
 * while disable, unload, quarantine, or isolated-runtime death removes stale content immediately.
 */
object PluginPanelContentStore {
    const val MAX_CONTENT_BYTES: Int = 256 * 1024

    private val lock = Any()
    private val _contents = MutableStateFlow<Map<PluginPanelKey, String>>(emptyMap())
    val contents: StateFlow<Map<PluginPanelKey, String>> = _contents.asStateFlow()

    fun set(key: PluginPanelKey, content: String) {
        requireUtf8Size(content)
        synchronized(lock) {
            _contents.value = _contents.value + (key to content)
        }
    }

    fun append(key: PluginPanelKey, content: String) {
        synchronized(lock) {
            val updated = _contents.value[key].orEmpty() + content
            requireUtf8Size(updated)
            _contents.value = _contents.value + (key to updated)
        }
    }

    fun clear(key: PluginPanelKey) {
        synchronized(lock) {
            _contents.value = _contents.value - key
        }
    }

    fun clearPlugin(pluginId: String) {
        synchronized(lock) {
            _contents.value = _contents.value.filterKeys { key -> key.pluginId != pluginId }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _contents.value = emptyMap()
        }
    }

    private fun requireUtf8Size(content: String) {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONTENT_BYTES) {
            "Plugin panel content exceeds $MAX_CONTENT_BYTES UTF-8 bytes"
        }
    }
}

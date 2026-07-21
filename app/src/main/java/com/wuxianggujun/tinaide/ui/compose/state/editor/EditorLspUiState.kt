package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.PluginLspDependencyNotReadyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class EditorLspUiState {
    private val statusesByTabId = mutableStateMapOf<String, EditorStatus>()
    private var pluginDependencyAlertSequence: Long = 0L

    var pluginDependencyAlert by mutableStateOf<PluginLspDependencyAlert?>(null)
        private set

    fun handleStatusChanged(tabId: String, status: EditorStatus) {
        statusesByTabId[tabId] = status
    }

    fun getStatus(tabId: String): EditorStatus = statusesByTabId[tabId] ?: EditorStatus.NoLsp

    fun getStatusFlow(tabId: String): Flow<EditorStatus> =
        snapshotFlow { getStatus(tabId) }
            .distinctUntilChanged()

    fun removeStatus(tabId: String) {
        statusesByTabId.remove(tabId)
    }

    fun remapTabIds(idMap: Map<String, String>) {
        idMap.forEach { (oldId, newId) ->
            statusesByTabId.remove(oldId)?.let { status -> statusesByTabId[newId] = status }
        }
    }

    fun handlePluginDependencyNotReady(event: PluginLspDependencyNotReadyEvent) {
        pluginDependencyAlertSequence += 1
        pluginDependencyAlert = PluginLspDependencyAlert(
            sequence = pluginDependencyAlertSequence,
            pluginId = event.pluginId,
            pluginName = event.pluginName,
            message = event.message,
        )
    }

    fun consumePluginDependencyAlert(): PluginLspDependencyAlert? {
        val alert = pluginDependencyAlert
        pluginDependencyAlert = null
        return alert
    }

    fun clear() {
        statusesByTabId.clear()
        pluginDependencyAlert = null
        pluginDependencyAlertSequence = 0L
    }
}

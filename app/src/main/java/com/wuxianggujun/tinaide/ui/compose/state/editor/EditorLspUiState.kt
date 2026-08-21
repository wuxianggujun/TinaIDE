package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.PluginLspDependencyNotReadyEvent
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class EditorLspUiState {
    private val statusesByTabId = mutableStateMapOf<String, EditorStatus>()
    private val cxxCompileContextsByTabId = mutableStateMapOf<String, CxxCompileContextSnapshot>()
    private val documentVersionsByTabId = mutableStateMapOf<String, Long>()
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

    fun handleDocumentVersionChanged(tabId: String, documentVersion: Long) {
        documentVersionsByTabId[tabId] = documentVersion
    }

    fun getDocumentVersion(tabId: String): Long? = documentVersionsByTabId[tabId]

    fun handleCxxCompileContextChanged(tabId: String, context: CxxCompileContextSnapshot?) {
        if (context == null) {
            cxxCompileContextsByTabId.remove(tabId)
        } else {
            cxxCompileContextsByTabId[tabId] = context
        }
    }

    fun getCxxCompileContext(tabId: String): CxxCompileContextSnapshot? =
        cxxCompileContextsByTabId[tabId]

    fun removeTab(tabId: String) {
        statusesByTabId.remove(tabId)
        cxxCompileContextsByTabId.remove(tabId)
        documentVersionsByTabId.remove(tabId)
    }

    fun remapTabIds(idMap: Map<String, String>) {
        idMap.forEach { (oldId, newId) ->
            statusesByTabId.remove(oldId)?.let { status -> statusesByTabId[newId] = status }
            cxxCompileContextsByTabId.remove(oldId)?.let { context ->
                cxxCompileContextsByTabId[newId] = context
            }
            documentVersionsByTabId.remove(oldId)?.let { version ->
                documentVersionsByTabId[newId] = version
            }
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
        cxxCompileContextsByTabId.clear()
        documentVersionsByTabId.clear()
        pluginDependencyAlert = null
        pluginDependencyAlertSequence = 0L
    }
}

internal fun EditorStatus.isInteractiveForCodeActionProbe(): Boolean =
    this == EditorStatus.Ready || this == EditorStatus.Busy

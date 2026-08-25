package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.mutableStateMapOf

internal class EditorSplitPaneState {
    private val paneByTabId = mutableStateMapOf<String, EditorPaneId>()
    private val mirroredIdsByPane =
        mutableStateMapOf<EditorPaneId, Set<String>>()
    private val activeIdByPane = mutableStateMapOf<EditorPaneId, String>()

    val activeTabIds: Collection<String>
        get() = activeIdByPane.values

    fun mirroredTabIdsByPane(): Map<EditorPaneId, Set<String>> =
        mirroredIdsByPane

    fun activeTabIdsByPane(): Map<EditorPaneId, String> = activeIdByPane

    fun clear() {
        paneByTabId.clear()
        mirroredIdsByPane.clear()
        activeIdByPane.clear()
    }

    fun clearMirrors() {
        mirroredIdsByPane.clear()
    }

    fun clearActiveTabs() {
        activeIdByPane.clear()
    }

    fun paneFor(tabId: String, isSplitEnabled: Boolean): EditorPaneId =
        if (isSplitEnabled) {
            paneByTabId[tabId] ?: EditorPaneId.PRIMARY
        } else {
            EditorPaneId.PRIMARY
        }

    fun setPane(tabId: String, pane: EditorPaneId) {
        paneByTabId[tabId] = pane
    }

    fun setPaneIfAbsent(tabId: String, pane: EditorPaneId) {
        paneByTabId.putIfAbsent(tabId, pane)
    }

    fun moveAllTabsToPane(pane: EditorPaneId) {
        paneByTabId.keys.toList().forEach { tabId ->
            paneByTabId[tabId] = pane
        }
    }

    fun removeTab(tabId: String) {
        paneByTabId.remove(tabId)
        removeMirroredTabId(tabId)
        removeActiveTabReferences(tabId)
    }

    fun removeTabsExcept(keptTabId: String) {
        paneByTabId.keys
            .filter { it != keptTabId }
            .toList()
            .forEach(paneByTabId::remove)
        removeMirroredTabsExcept(keptTabId)
    }

    fun removeMissingTabs(liveTabIds: Set<String>) {
        paneByTabId.keys
            .filter { it !in liveTabIds }
            .toList()
            .forEach(paneByTabId::remove)
        activeIdByPane
            .filterValues { it !in liveTabIds }
            .keys
            .toList()
            .forEach(activeIdByPane::remove)
    }

    fun ensurePaneForTabs(tabIds: Iterable<String>, pane: EditorPaneId) {
        tabIds.forEach { tabId ->
            paneByTabId.putIfAbsent(tabId, pane)
        }
    }

    fun activeTabId(pane: EditorPaneId): String? = activeIdByPane[pane]

    fun setActiveTabId(pane: EditorPaneId, tabId: String) {
        activeIdByPane[pane] = tabId
    }

    fun removeActiveTabsExceptPane(keptPane: EditorPaneId) {
        activeIdByPane.keys
            .filter { it != keptPane }
            .toList()
            .forEach(activeIdByPane::remove)
    }

    private fun removeActiveTabReferences(tabId: String) {
        activeIdByPane
            .filterValues { it == tabId }
            .keys
            .toList()
            .forEach(activeIdByPane::remove)
    }

    fun removeActiveTab(pane: EditorPaneId) {
        activeIdByPane.remove(pane)
    }

    fun isMirroredToPane(
        tabId: String,
        pane: EditorPaneId,
        isSplitEnabled: Boolean,
    ): Boolean = isSplitEnabled && mirroredIdsByPane[pane]?.contains(tabId) == true

    fun addMirroredTabToPane(
        pane: EditorPaneId,
        tabId: String,
        ownerPane: EditorPaneId,
    ) {
        if (ownerPane == pane) return
        mirroredIdsByPane[pane] = mirroredIdsByPane[pane].orEmpty() + tabId
    }

    fun removeMirroredTabId(tabId: String) {
        mirroredIdsByPane.keys.toList().forEach { pane ->
            val updated = mirroredIdsByPane[pane].orEmpty() - tabId
            if (updated.isEmpty()) {
                mirroredIdsByPane.remove(pane)
            } else {
                mirroredIdsByPane[pane] = updated
            }
        }
    }

    fun removeMirroredTabsExcept(keptTabId: String) {
        mirroredIdsByPane.keys.toList().forEach { pane ->
            val updated = mirroredIdsByPane[pane].orEmpty()
                .filterTo(linkedSetOf()) { it == keptTabId }
            if (updated.isEmpty()) {
                mirroredIdsByPane.remove(pane)
            } else {
                mirroredIdsByPane[pane] = updated
            }
        }
    }

    fun pruneMirroredTabs(
        liveTabIds: Set<String>,
        resolveOwnerPane: (String) -> EditorPaneId,
    ) {
        mirroredIdsByPane.keys.toList().forEach { pane ->
            val updated = mirroredIdsByPane[pane]
                .orEmpty()
                .filterTo(linkedSetOf()) { tabId ->
                    tabId in liveTabIds && resolveOwnerPane(tabId) != pane
                }
            if (updated.isEmpty()) {
                mirroredIdsByPane.remove(pane)
            } else {
                mirroredIdsByPane[pane] = updated
            }
        }
    }

    fun remapTabIds(idMap: Map<String, String>) {
        idMap.forEach { (oldId, newId) ->
            paneByTabId.remove(oldId)?.let { pane -> paneByTabId[newId] = pane }
        }

        mirroredIdsByPane.keys.toList().forEach { pane ->
            val updated = mirroredIdsByPane[pane]
                .orEmpty()
                .mapTo(linkedSetOf()) { tabId -> idMap[tabId] ?: tabId }
            if (updated.isEmpty()) {
                mirroredIdsByPane.remove(pane)
            } else {
                mirroredIdsByPane[pane] = updated
            }
        }

        activeIdByPane.keys.toList().forEach { pane ->
            val activeTabId = activeIdByPane[pane] ?: return@forEach
            idMap[activeTabId]?.let { activeIdByPane[pane] = it }
        }
    }
}

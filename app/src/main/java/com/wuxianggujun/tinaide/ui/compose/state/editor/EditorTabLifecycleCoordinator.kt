package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType

internal class EditorTabLifecycleCoordinator(
    private val splitPaneState: EditorSplitPaneState,
    private val isCodeEditableType: (ContentType) -> Boolean,
    private val releaseLspForTab: (String) -> Unit,
    private val clearCodeEditorRuntime: (String) -> Unit,
    private val removeCodeEditorCallback: (String) -> Unit,
    private val cleanupSearchState: (String) -> Unit,
    private val dismissPeekDefinitionPanel: (String) -> Unit,
    private val normalizeEditorPaneState: () -> Unit,
) {
    fun handleManagerTabClosed(tabId: String, contentType: ContentType) {
        if (isCodeEditableType(contentType)) {
            releaseLspForTab(tabId)
            clearCodeEditorRuntime(tabId)
        }
        splitPaneState.removeTab(tabId)
        normalizeEditorPaneState()
        removeCodeEditorCallback(tabId)
        cleanupSearchState(tabId)
        dismissPeekDefinitionPanel(tabId)
    }

    fun releaseRemovedTabResources(tabId: String) {
        releaseLspForTab(tabId)
        clearCodeEditorRuntime(tabId)
    }

    fun releaseRemovedTabResources(tabIds: Iterable<String>) {
        tabIds.forEach(::releaseRemovedTabResources)
    }

    fun retainOnlyTabPaneState(
        keptTabId: String,
        keptPane: EditorContainerState.EditorPaneId,
    ) {
        splitPaneState.removeTabsExcept(keptTabId)
        splitPaneState.removeActiveTabsExceptPane(keptPane)
    }

    fun clearSplitPaneState() {
        splitPaneState.clear()
    }
}

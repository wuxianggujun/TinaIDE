package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import org.junit.Test

class EditorTabLifecycleCoordinatorTest {
    @Test
    fun managerClosedCodeTab_shouldReleaseRuntimeAndLspExactlyOnce() {
        val releasedLspTabs = mutableListOf<String>()
        val clearedRuntimeTabs = mutableListOf<String>()
        val removedCallbackTabs = mutableListOf<String>()
        val cleanedSearchTabs = mutableListOf<String>()
        val dismissedPeekTabs = mutableListOf<String>()
        var normalizeCount = 0
        val coordinator = EditorTabLifecycleCoordinator(
            splitPaneState = EditorSplitPaneState(),
            isCodeEditableType = { it == ContentType.CODE || it == ContentType.JSON },
            releaseLspForTab = releasedLspTabs::add,
            clearCodeEditorRuntime = clearedRuntimeTabs::add,
            removeCodeEditorCallback = removedCallbackTabs::add,
            cleanupSearchState = cleanedSearchTabs::add,
            dismissPeekDefinitionPanel = dismissedPeekTabs::add,
            normalizeEditorPaneState = { normalizeCount++ },
        )

        coordinator.handleManagerTabClosed("tab-1", ContentType.CODE)

        assertThat(releasedLspTabs).containsExactly("tab-1")
        assertThat(clearedRuntimeTabs).containsExactly("tab-1")
        assertThat(removedCallbackTabs).containsExactly("tab-1")
        assertThat(cleanedSearchTabs).containsExactly("tab-1")
        assertThat(dismissedPeekTabs).containsExactly("tab-1")
        assertThat(normalizeCount).isEqualTo(1)
    }
}

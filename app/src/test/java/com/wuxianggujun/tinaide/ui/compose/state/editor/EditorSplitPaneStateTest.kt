package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorSplitPaneStateTest {
    @Test fun removedTabFallsBackToNonemptyPane() {
        val state = EditorSplitPaneState()
        state.setPane("closed", EditorPaneId.SECONDARY)
        state.setActiveTabId(EditorPaneId.SECONDARY, "closed")
        val selected = state.normalize(listOf("live"), true, EditorPaneId.SECONDARY, "closed")
        assertThat(selected).isEqualTo(EditorSplitPaneState.Selection(EditorPaneId.PRIMARY, "live"))
        assertThat(state.activeTabId(EditorPaneId.SECONDARY)).isNull()
    }

    @Test fun mirrorKeepsFocusAndPreferredSelection() {
        val state = EditorSplitPaneState()
        state.setPane("a", EditorPaneId.PRIMARY)
        state.addMirroredTabToPane(EditorPaneId.SECONDARY, "a", EditorPaneId.PRIMARY)
        val selected = state.normalize(listOf("a"), true, EditorPaneId.SECONDARY, "a")
        assertThat(selected.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(state.activeTabId(EditorPaneId.SECONDARY)).isEqualTo("a")
    }

    @Test fun disablingSplitConsolidatesTabsAndRemovesStaleMirrors() {
        val state = EditorSplitPaneState()
        state.setPane("b", EditorPaneId.SECONDARY)
        state.addMirroredTabToPane(EditorPaneId.PRIMARY, "b", EditorPaneId.SECONDARY)
        val selected = state.normalize(listOf("a", "b"), false, EditorPaneId.SECONDARY, "b")
        assertThat(selected).isEqualTo(EditorSplitPaneState.Selection(EditorPaneId.PRIMARY, "b"))
        assertThat(state.paneFor("b", true)).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.mirroredTabIdsByPane()).isEmpty()
    }

    @Test fun emptyWorkspaceClearsSelection() {
        val state = EditorSplitPaneState()
        state.setActiveTabId(EditorPaneId.PRIMARY, "gone")
        assertThat(state.normalize(emptyList(), true, EditorPaneId.SECONDARY, "gone").activeTabId).isNull()
        assertThat(state.activeTabIds).isEmpty()
    }
}

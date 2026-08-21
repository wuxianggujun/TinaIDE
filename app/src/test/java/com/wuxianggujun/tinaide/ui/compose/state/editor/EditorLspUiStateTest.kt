package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextMode
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextSnapshot
import org.junit.Test

class EditorLspUiStateTest {
    @Test
    fun cxxCompileContext_followsTabLifecycle() {
        val state = EditorLspUiState()
        val context = CxxCompileContextSnapshot(
            filePath = "/workspace/main.cpp",
            workspaceRootPath = "/workspace",
            mode = CxxCompileContextMode.LOCAL,
        )

        state.handleCxxCompileContextChanged("old-tab", context)
        assertThat(state.getCxxCompileContext("old-tab")).isEqualTo(context)

        state.remapTabIds(mapOf("old-tab" to "new-tab"))
        assertThat(state.getCxxCompileContext("old-tab")).isNull()
        assertThat(state.getCxxCompileContext("new-tab")).isEqualTo(context)

        state.removeTab("new-tab")
        assertThat(state.getCxxCompileContext("new-tab")).isNull()
    }

    @Test
    fun cxxCompileContext_nullUpdateAndClearRemoveStaleSnapshots() {
        val state = EditorLspUiState()
        val context = CxxCompileContextSnapshot(
            filePath = "/workspace/main.cpp",
            workspaceRootPath = "/workspace",
            mode = CxxCompileContextMode.REMOTE,
        )

        state.handleCxxCompileContextChanged("tab", context)
        state.handleCxxCompileContextChanged("tab", null)
        assertThat(state.getCxxCompileContext("tab")).isNull()

        state.handleCxxCompileContextChanged("tab", context)
        state.clear()
        assertThat(state.getCxxCompileContext("tab")).isNull()
    }

    @Test
    fun documentVersion_followsTabLifecycle() {
        val state = EditorLspUiState()

        state.handleDocumentVersionChanged("old-tab", 7L)
        assertThat(state.getDocumentVersion("old-tab")).isEqualTo(7L)

        state.remapTabIds(mapOf("old-tab" to "new-tab"))
        assertThat(state.getDocumentVersion("old-tab")).isNull()
        assertThat(state.getDocumentVersion("new-tab")).isEqualTo(7L)

        state.removeTab("new-tab")
        assertThat(state.getDocumentVersion("new-tab")).isNull()

        state.handleDocumentVersionChanged("tab", 9L)
        state.clear()
        assertThat(state.getDocumentVersion("tab")).isNull()
    }

    @Test
    fun codeActionProbeInteractionState_doesNotChangeWhileRequestIsBusy() {
        assertThat(EditorStatus.Ready.isInteractiveForCodeActionProbe()).isTrue()
        assertThat(EditorStatus.Busy.isInteractiveForCodeActionProbe()).isTrue()
        assertThat(EditorStatus.Connecting.isInteractiveForCodeActionProbe()).isFalse()
        assertThat(EditorStatus.NoLsp.isInteractiveForCodeActionProbe()).isFalse()
        assertThat(EditorStatus.Error.isInteractiveForCodeActionProbe()).isFalse()
    }
}

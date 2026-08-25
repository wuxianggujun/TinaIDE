package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.lsp.LspCodeActionService
import com.wuxianggujun.tinaide.ui.WorkspaceEditPreview
import kotlinx.coroutines.CompletableDeferred

/**
 * 合并 Code Actions / LSP Rename 相关的 UI 状态，减少 MainScreen 中的散落状态变量。
 */
@Stable
internal class EditorActionsState {
    // Code Actions
    var showCodeActionsMenu by mutableStateOf(false)
    var codeActionsTabId by mutableStateOf<String?>(null)
    var codeActionsLoading by mutableStateOf(false)
    var codeActions by mutableStateOf<List<LspCodeActionService.CodeActionItem>>(emptyList())

    var workspaceEditPreview by mutableStateOf<WorkspaceEditPreview?>(null)
        private set
    private var workspaceEditConfirmation: CompletableDeferred<Boolean>? = null

    // LSP Rename
    var showLspRenameDialog by mutableStateOf(false)
    var renameTabId by mutableStateOf<String?>(null)
    var renameLine by mutableIntStateOf(0)
    var renameColumn by mutableIntStateOf(0)
    var renameCurrentName by mutableStateOf("")
    var renameIsLoading by mutableStateOf(false)
    var renameError by mutableStateOf<String?>(null)

    fun openCodeActions(tabId: String, actions: List<LspCodeActionService.CodeActionItem>) {
        codeActionsTabId = tabId
        codeActions = actions
        showCodeActionsMenu = true
    }

    fun dismissCodeActions() {
        showCodeActionsMenu = false
    }

    suspend fun requestWorkspaceEditConfirmation(preview: WorkspaceEditPreview): Boolean {
        workspaceEditConfirmation?.complete(false)
        val confirmation = CompletableDeferred<Boolean>()
        workspaceEditConfirmation = confirmation
        workspaceEditPreview = preview
        return try {
            confirmation.await()
        } finally {
            if (workspaceEditConfirmation === confirmation) {
                workspaceEditConfirmation = null
                workspaceEditPreview = null
            }
        }
    }

    fun confirmWorkspaceEdit() {
        completeWorkspaceEditConfirmation(confirmed = true)
    }

    fun dismissWorkspaceEditPreview() {
        completeWorkspaceEditConfirmation(confirmed = false)
    }

    private fun completeWorkspaceEditConfirmation(confirmed: Boolean) {
        val confirmation = workspaceEditConfirmation ?: return
        workspaceEditConfirmation = null
        workspaceEditPreview = null
        confirmation.complete(confirmed)
    }

    fun openRename(tabId: String, line: Int, column: Int, currentName: String) {
        renameTabId = tabId
        renameLine = line
        renameColumn = column
        renameCurrentName = currentName
        renameError = null
        showLspRenameDialog = true
    }

    fun dismissRename() {
        if (!renameIsLoading) showLspRenameDialog = false
    }
}

@Composable
internal fun rememberEditorActionsState(): EditorActionsState = remember { EditorActionsState() }

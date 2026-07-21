package com.wuxianggujun.tinaide.ui

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LSP 编辑器动作请求的处理委托。
 *
 * 负责处理 Code Actions 与 Rename 的请求，并把结果写入 MainActivity 的对话框状态。
 */
class LspEditorActionsDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var onToastInfo: ((String) -> Unit)? = null
    var onToastError: ((String) -> Unit)? = null

    internal fun bind(
        editorContainerState: EditorContainerState,
        editorActionsState: EditorActionsState,
        onToastInfo: (String) -> Unit,
        onToastError: (String) -> Unit,
    ) {
        this.onToastInfo = onToastInfo
        this.onToastError = onToastError

        editorContainerState.onLspCodeActionsRequested =
            { tabId, startLine, startColumn, endLine, endColumn ->
                handleCodeActionsRequest(
                    tabId = tabId,
                    startLine = startLine,
                    startColumn = startColumn,
                    endLine = endLine,
                    endColumn = endColumn,
                    editorContainerState = editorContainerState,
                    editorActionsState = editorActionsState,
                )
            }

        editorContainerState.onLspRenameRequested =
            { tabId, line, column, currentName ->
                handleRenameRequest(
                    tabId = tabId,
                    line = line,
                    column = column,
                    currentName = currentName,
                    editorContainerState = editorContainerState,
                    editorActionsState = editorActionsState,
                )
            }
    }

    private fun handleCodeActionsRequest(
        tabId: String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        editorContainerState: EditorContainerState,
        editorActionsState: EditorActionsState,
    ) {
        if (editorContainerState.getLspStatus(tabId) != EditorStatus.Ready) {
            onToastInfo?.invoke(Strings.lsp_error_not_connected.strOr(context))
            return
        }

        editorActionsState.codeActionsTabId = tabId
        editorActionsState.codeActions = emptyList()
        editorActionsState.codeActionsLoading = true
        editorActionsState.showCodeActionsMenu = true

        scope.launch {
            val actions = runCatching {
                editorContainerState.requestCodeActions(
                    tabId = tabId,
                    startLine = startLine,
                    startColumn = startColumn,
                    endLine = endLine,
                    endColumn = endColumn,
                )
            }.onFailure {
                editorActionsState.codeActionsLoading = false
                editorActionsState.dismissCodeActions()
                onToastError?.invoke(Strings.code_action_failed.strOr(context))
            }.getOrNull() ?: return@launch

            editorActionsState.codeActions = actions
            editorActionsState.codeActionsLoading = false

            if (actions.isEmpty()) {
                editorActionsState.dismissCodeActions()
                onToastInfo?.invoke(Strings.code_actions_empty.strOr(context))
            }
        }
    }

    private fun handleRenameRequest(
        tabId: String,
        line: Int,
        column: Int,
        currentName: String,
        editorContainerState: EditorContainerState,
        editorActionsState: EditorActionsState,
    ) {
        if (editorContainerState.getLspStatus(tabId) != EditorStatus.Ready) {
            onToastInfo?.invoke(Strings.lsp_error_not_connected.strOr(context))
            return
        }

        scope.launch {
            val prepareResult = runCatching {
                editorContainerState.prepareRename(tabId, line, column)
            }.getOrElse {
                onToastError?.invoke(Strings.lsp_error_rename_failed.strOr(context))
                return@launch
            }

            if (prepareResult == null || !prepareResult.canRename) {
                onToastError?.invoke(Strings.lsp_error_rename_failed.strOr(context))
                return@launch
            }

            val displayName = prepareResult.placeholder
                ?.takeIf { it.isNotBlank() }
                ?: currentName

            editorActionsState.openRename(
                tabId = tabId,
                line = line,
                column = column,
                currentName = displayName,
            )
        }
    }
}

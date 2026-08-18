package com.wuxianggujun.tinaide.ui

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LSP 编辑器动作请求的处理委托。
 *
 * 负责处理 Code Actions 与 Rename 的请求，并把结果写入 MainActivity 的对话框状态。
 */
class LspEditorActionsDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private companion object {
        private const val CODE_ACTION_READY_TIMEOUT_MS = 16_000L
        private const val CODE_ACTION_ACTIVATION_GRACE_MS = 300L
        private const val CODE_ACTION_READY_POLL_MS = 40L
    }

    var onToastInfo: ((String) -> Unit)? = null
    var onToastError: ((String) -> Unit)? = null

    private var codeActionsRequestGeneration = 0L
    private var codeActionsRequestJob: Job? = null

    internal fun bind(
        editorContainerState: EditorContainerState,
        editorActionsState: EditorActionsState,
        onToastInfo: (String) -> Unit,
        onToastError: (String) -> Unit,
    ) {
        codeActionsRequestJob?.cancel()
        codeActionsRequestGeneration++
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
        codeActionsRequestJob?.cancel()
        val requestGeneration = ++codeActionsRequestGeneration

        if (!editorContainerState.supportsLspRefactorActions(tabId)) {
            editorActionsState.codeActionsLoading = false
            editorActionsState.dismissCodeActions()
            onToastInfo?.invoke(Strings.lsp_error_not_connected.strOr(context))
            return
        }

        editorActionsState.codeActionsTabId = tabId
        editorActionsState.codeActions = emptyList()
        editorActionsState.codeActionsLoading = true
        editorActionsState.showCodeActionsMenu = true

        codeActionsRequestJob = scope.launch {
            val ready = awaitCodeActionsReady(editorContainerState, tabId)
            if (!isCurrentCodeActionsRequest(requestGeneration)) return@launch
            if (!ready) {
                editorActionsState.codeActionsLoading = false
                editorActionsState.dismissCodeActions()
                onToastInfo?.invoke(Strings.lsp_error_not_connected.strOr(context))
                return@launch
            }

            val actions = try {
                editorContainerState.requestCodeActions(
                    tabId = tabId,
                    startLine = startLine,
                    startColumn = startColumn,
                    endLine = endLine,
                    endColumn = endColumn,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (!isCurrentCodeActionsRequest(requestGeneration)) return@launch
                editorActionsState.codeActionsLoading = false
                editorActionsState.dismissCodeActions()
                onToastError?.invoke(Strings.code_actions_load_failed.strOr(context))
                return@launch
            }

            if (!isCurrentCodeActionsRequest(requestGeneration)) return@launch
            editorActionsState.codeActions = actions
            editorActionsState.codeActionsLoading = false

            if (actions.isEmpty()) {
                editorActionsState.dismissCodeActions()
                onToastInfo?.invoke(Strings.code_actions_empty.strOr(context))
            }
        }
    }

    private suspend fun awaitCodeActionsReady(
        editorContainerState: EditorContainerState,
        tabId: String,
    ): Boolean = withTimeoutOrNull(CODE_ACTION_READY_TIMEOUT_MS) {
        val graceDeadlineNanos = System.nanoTime() + CODE_ACTION_ACTIVATION_GRACE_MS * 1_000_000L
        var attachmentStarted = false
        while (true) {
            val status = editorContainerState.getLspStatus(tabId)
            val interactive = status == EditorStatus.Ready || status == EditorStatus.Busy
            if (interactive && editorContainerState.hasActiveLspConnection(tabId)) {
                return@withTimeoutOrNull true
            }
            if (status == EditorStatus.Connecting || status == EditorStatus.Busy) {
                attachmentStarted = true
            }
            val terminal = status == EditorStatus.Error || status == EditorStatus.NoLsp
            if (terminal && (attachmentStarted || System.nanoTime() >= graceDeadlineNanos)) {
                return@withTimeoutOrNull false
            }
            delay(CODE_ACTION_READY_POLL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false

    private fun isCurrentCodeActionsRequest(generation: Long): Boolean =
        generation == codeActionsRequestGeneration

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

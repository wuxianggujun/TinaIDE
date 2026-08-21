package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import timber.log.Timber

/**
 * LSP 代码操作服务
 *
 * 封装 clangd 的 Code Actions 和 Rename 功能
 */
class LspCodeActionService {

    companion object {
        private const val TAG = "LspCodeAction"
        private const val TIMEOUT_SECONDS = 10L
    }

    /**
     * 代码操作项
     */
    data class CodeActionItem(
        val title: String,
        val kind: String?,
        val isPreferred: Boolean,
        val diagnostics: List<String>,
        val isDisabled: Boolean,
        val disabledReason: String?,
        internal val action: Either<Command, CodeAction>
    ) {
        val isEnabled: Boolean
            get() = !isDisabled
    }

    /**
     * 重命名结果
     */
    data class RenameResult(
        val success: Boolean,
        val changedFiles: List<String> = emptyList(),
        val error: String? = null
    )

    /**
     * 请求代码操作列表
     */
    suspend fun requestCodeActions(
        documentUri: String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        diagnostics: List<org.eclipse.lsp4j.Diagnostic> = emptyList(),
        onlyKinds: List<String> = emptyList(),
        codeActionRequest: suspend (CodeActionParams, Long) -> List<Either<Command, CodeAction>>?,
    ): List<CodeActionItem> = withContext(Dispatchers.IO) {
        try {
            val requestRange = Range(
                Position(startLine, startColumn),
                Position(endLine, endColumn)
            )
            val params = CodeActionParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                range = requestRange
                context = CodeActionContext().apply {
                    // LSP servers use these diagnostics to produce diagnostic quick fixes.
                    this.diagnostics = diagnostics.filter { diagnostic ->
                        diagnostic.range?.let { it.intersects(requestRange) } == true
                    }
                    if (onlyKinds.isNotEmpty()) {
                        only = onlyKinds
                    }
                }
            }

            val result = codeActionRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()

            result.mapNotNull { item ->
                val title: String
                val kind: String?
                val isPreferred: Boolean
                val diagnosticsList: List<String>
                val isDisabled: Boolean
                val disabledReason: String?

                when {
                    item.isRight -> {
                        val codeAction = item.right
                        title = codeAction.title
                        kind = codeAction.kind
                        isPreferred = codeAction.isPreferred == true
                        diagnosticsList = codeAction.diagnostics?.map { it.message } ?: emptyList()
                        isDisabled = codeAction.disabled != null
                        disabledReason = codeAction.disabled?.reason
                    }
                    item.isLeft -> {
                        val command = item.left
                        title = command.title
                        kind = null
                        isPreferred = false
                        diagnosticsList = emptyList()
                        isDisabled = false
                        disabledReason = null
                    }
                    else -> return@mapNotNull null
                }

                CodeActionItem(
                    title = title,
                    kind = kind,
                    isPreferred = isPreferred,
                    diagnostics = diagnosticsList,
                    isDisabled = isDisabled,
                    disabledReason = disabledReason,
                    action = item
                )
            }.filter { item -> item.matchesRequestedKinds(onlyKinds) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to get code actions: ${e.message}")
            throw e
        }
    }

    private fun CodeActionItem.matchesRequestedKinds(onlyKinds: List<String>): Boolean {
        if (onlyKinds.isEmpty()) return true
        if (kind == null) {
            return onlyKinds.none { requestedKind -> requestedKind.requiresExplicitActionKind() }
        }
        return onlyKinds.any { requestedKind ->
            kind == requestedKind || kind.startsWith("$requestedKind.")
        }
    }

    private fun String.requiresExplicitActionKind(): Boolean =
        this == CodeActionKind.SourceFixAll || startsWith("${CodeActionKind.SourceFixAll}.")

    private fun Range.intersects(other: Range): Boolean {
        val thisStart = start ?: return false
        val thisEnd = end ?: thisStart
        val otherStart = other.start ?: return false
        val otherEnd = other.end ?: otherStart

        // Include boundary points so a cursor at a zero-width diagnostic or at the
        // end of a diagnostic still receives its quick fix.
        return comparePositions(thisStart, otherEnd) <= 0 &&
            comparePositions(otherStart, thisEnd) <= 0
    }

    private fun comparePositions(left: Position, right: Position): Int = when {
        left.line != right.line -> left.line.compareTo(right.line)
        else -> left.character.compareTo(right.character)
    }

    /**
     * 执行代码操作
     */
    suspend fun executeCodeAction(
        item: CodeActionItem,
        resolveCodeActionRequest: suspend (CodeAction, Long) -> CodeAction?,
        executeCommandRequest: suspend (ExecuteCommandParams, Long) -> Any?,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!item.isEnabled) return@withContext false

            val action = item.action

            when {
                action.isRight -> {
                    val codeAction = action.right

                    executeCodeActionPayload(
                        codeAction = codeAction,
                        executeCommandRequest = executeCommandRequest,
                        onApplyEdit = onApplyEdit,
                    )?.let { return@withContext it }

                    // 如果需要 resolve
                    val resolved = resolveCodeActionRequest(codeAction, TIMEOUT_SECONDS)
                    if (resolved?.disabled != null) return@withContext false
                    resolved?.let { resolvedAction ->
                        executeCodeActionPayload(
                            codeAction = resolvedAction,
                            executeCommandRequest = executeCommandRequest,
                            onApplyEdit = onApplyEdit,
                        )?.let { return@withContext it }
                    }
                }

                action.isLeft -> {
                    return@withContext executeCommand(executeCommandRequest, action.left)
                }
            }

            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to execute code action: ${e.message}")
            false
        }
    }

    /** Returns null when the action still needs resolve. */
    private suspend fun executeCodeActionPayload(
        codeAction: CodeAction,
        executeCommandRequest: suspend (ExecuteCommandParams, Long) -> Any?,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean,
    ): Boolean? {
        var executed = false
        codeAction.edit?.let { edit ->
            if (!onApplyEdit(edit)) return false
            executed = true
        }
        codeAction.command?.let { command ->
            if (!executeCommand(executeCommandRequest, command)) return false
            executed = true
        }
        return if (executed) true else null
    }

    private suspend fun executeCommand(
        executeCommandRequest: suspend (ExecuteCommandParams, Long) -> Any?,
        command: Command
    ): Boolean = try {
        executeCommandRequest(
            ExecuteCommandParams(command.command, command.arguments),
            TIMEOUT_SECONDS
        )
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        Timber.tag(TAG).w("Failed to execute command: ${e.message}")
        false
    }

    /**
     * 准备重命名（检查是否可以重命名）
     */
    suspend fun prepareRename(
        documentUri: String,
        line: Int,
        column: Int,
        prepareRenameRequest: suspend (PrepareRenameParams, Long) -> Any?,
    ): PrepareRenameResult? = withContext(Dispatchers.IO) {
        try {
            val params = PrepareRenameParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                position = Position(line, column)
            }

            val result = prepareRenameRequest(params, TIMEOUT_SECONDS)

            parsePrepareRenameResult(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to prepare rename: ${e.message}")
            null
        }
    }

    data class PrepareRenameResult(
        val canRename: Boolean,
        val range: Range?,
        val placeholder: String?
    )

    /**
     * 执行重命名
     */
    suspend fun rename(
        documentUri: String,
        line: Int,
        column: Int,
        newName: String,
        renameRequest: suspend (RenameParams, Long) -> WorkspaceEdit?,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ): RenameResult = withContext(Dispatchers.IO) {
        try {
            val params = RenameParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                position = Position(line, column)
                this.newName = newName
            }

            val edit = renameRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext RenameResult(false, error = Strings.lsp_error_rename_failed.str())

            val changedFiles = mutableListOf<String>()

            // 收集变更的文件
            edit.changes?.keys?.forEach { uri ->
                changedFiles.add(File(java.net.URI(uri).path).name)
            }
            edit.documentChanges?.forEach { change ->
                if (change.isLeft) {
                    val textEdit = change.left
                    changedFiles.add(File(java.net.URI(textEdit.textDocument.uri).path).name)
                }
            }

            val success = onApplyEdit(edit)

            RenameResult(
                success = success,
                changedFiles = changedFiles.distinct(),
                error = if (!success) Strings.lsp_error_apply_edit_failed.str() else null
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to rename: ${e.message}")
            RenameResult(false, error = e.message)
        }
    }

    private fun parsePrepareRenameResult(result: Any?): PrepareRenameResult? = when (result) {
        null -> null
        is Range -> PrepareRenameResult(canRename = true, range = result, placeholder = null)
        is org.eclipse.lsp4j.PrepareRenameResult -> {
            PrepareRenameResult(
                canRename = true,
                range = result.range,
                placeholder = result.placeholder
            )
        }
        is org.eclipse.lsp4j.PrepareRenameDefaultBehavior -> {
            PrepareRenameResult(canRename = true, range = null, placeholder = null)
        }
        is Either3<*, *, *> -> parseEither3PrepareRenameResult(result)
        is Either<*, *> -> parseEitherPrepareRenameResult(result)
        else -> null
    }

    private fun parseEither3PrepareRenameResult(result: Either3<*, *, *>): PrepareRenameResult? {
        return when {
            result.isFirst -> {
                val range = result.first as? Range ?: return null
                PrepareRenameResult(canRename = true, range = range, placeholder = null)
            }

            result.isSecond -> {
                val renameResult = result.second as? org.eclipse.lsp4j.PrepareRenameResult
                    ?: return null
                PrepareRenameResult(
                    canRename = true,
                    range = renameResult.range,
                    placeholder = renameResult.placeholder
                )
            }

            result.isThird -> {
                PrepareRenameResult(canRename = true, range = null, placeholder = null)
            }

            else -> null
        }
    }

    private fun parseEitherPrepareRenameResult(result: Either<*, *>): PrepareRenameResult? {
        return when {
            result.isLeft -> {
                val range = result.left as? Range ?: return null
                PrepareRenameResult(canRename = true, range = range, placeholder = null)
            }

            result.isRight -> {
                val right = result.right
                when (right) {
                    is org.eclipse.lsp4j.PrepareRenameResult -> {
                        PrepareRenameResult(
                            canRename = true,
                            range = right.range,
                            placeholder = right.placeholder
                        )
                    }

                    is org.eclipse.lsp4j.PrepareRenameDefaultBehavior -> {
                        PrepareRenameResult(canRename = true, range = null, placeholder = null)
                    }

                    is Either<*, *> -> parseNestedPrepareRenameResult(right)
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun parseNestedPrepareRenameResult(result: Either<*, *>): PrepareRenameResult? {
        return when {
            result.isLeft -> {
                val renameResult = result.left as? org.eclipse.lsp4j.PrepareRenameResult
                    ?: return null
                PrepareRenameResult(
                    canRename = true,
                    range = renameResult.range,
                    placeholder = renameResult.placeholder
                )
            }

            result.isRight -> {
                PrepareRenameResult(canRename = true, range = null, placeholder = null)
            }

            else -> null
        }
    }
}

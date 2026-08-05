package com.wuxianggujun.tinaide.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.core.format.CodeFormatter
import com.wuxianggujun.tinaide.core.format.FormatResult
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.io.AtomicTextFileWriter
import com.wuxianggujun.tinaide.editor.io.FileCharsetDetector
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveBookmarkCursorContext
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveBookmarkCursorContextResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveBookmarkTarget
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveBookmarkTargetResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditableEditorSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditableEditorSnapshotResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditorCommandResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveSaveTarget
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveSaveTargetResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.ConditionalEditorTextReplaceResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.TextEditOperation
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import timber.log.Timber

/**
 * MainActivity 动作处理 ViewModel
 *
 * 职责：
 * - 文件保存操作
 * - 代码格式化
 * - 撤销/重做操作
 * - 剪贴板操作
 * - WorkspaceEdit 应用
 *
 * 设计原则：
 * - 从 MainActivity 中提取业务逻辑
 * - 使用有界 Channel 可靠发送一次性事件（如 Toast）
 */
class MainActivityActionsViewModel(
    application: Application,
    private val editorManager: IEditorManager,
    private val projectContext: IProjectContext,
    private val projectSession: IProjectSession,
    private val bookmarkRepository: IBookmarkRepository,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) : AndroidViewModel(application) {

    /**
     * UI 事件（Toast 消息等）
     */
    sealed class UiEvent {
        data class ShowToast(val message: String, val type: ToastType = ToastType.INFO) : UiEvent()
    }

    enum class ToastType { SUCCESS, ERROR, INFO }

    private data class WorkspaceFileEditBatch(
        val edits: MutableList<TextEdit> = mutableListOf(),
        var expectedVersion: Int? = null
    )

    private data class OpenWorkspaceEditPlan(
        val tabId: String,
        val edits: List<TextEditOperation>,
        val originalText: String,
        val documentVersion: Long?,
        val expectedLspVersion: Int?
    )

    private data class ClosedWorkspaceEditPlan(
        val file: File,
        val originalText: String,
        val updatedText: String,
        val charset: Charset
    )

    private val uiEventsChannel = Channel<UiEvent>(capacity = Channel.BUFFERED)
    val uiEvents = uiEventsChannel.receiveAsFlow()

    private val context: Context get() = getApplication()

    // ============ 文件保存操作 ============

    /**
     * 保存当前文件
     */
    fun saveCurrentFile(editorContainerState: EditorContainerState) {
        val saveTarget = when (val result = editorContainerState.getActiveSaveTargetResult()) {
            ActiveSaveTargetResult.NoOpenFile -> {
                showToast(Strings.toast_no_open_file.strOr(context), ToastType.INFO)
                return
            }

            is ActiveSaveTargetResult.Available -> result.target
        }
        viewModelScope.launch {
            val result = editorManager.save(saveTarget.tabId, SaveReason.MANUAL)
            when (result) {
                is SaveResult.Success -> {
                    showToast(Strings.toast_saved.strOr(context), ToastType.SUCCESS)
                    val fullText = editorContainerState.readActiveTabText() ?: ""
                    editorContainerState.notifyFileSaved(saveTarget.tabId, saveTarget.file, fullText)
                }
                is SaveResult.Failure -> showToast(Strings.toast_save_failed.strOr(context, result.message), ToastType.ERROR)
                SaveResult.NoOp -> { }
            }
        }
    }

    // ============ 书签功能 ============

    fun toggleBookmark(editorContainerState: EditorContainerState) {
        val projectRoot = projectContext.getCurrentProject()?.rootPath
        if (projectRoot.isNullOrBlank()) {
            showToast(Strings.bookmarks_no_project.strOr(context), ToastType.INFO)
            return
        }

        val bookmarkTarget = when (val result = editorContainerState.getActiveBookmarkTargetResult()) {
            ActiveBookmarkTargetResult.NoOpenFile,
            ActiveBookmarkTargetResult.UnsupportedEditor -> {
                showToast(Strings.toast_no_open_file.strOr(context), ToastType.INFO)
                return
            }

            ActiveBookmarkTargetResult.NoBookmarkableLine -> {
                showToast(Strings.toast_bookmark_ignored_blank_line.strOr(context), ToastType.INFO)
                return
            }

            is ActiveBookmarkTargetResult.Success -> result.target
        }

        viewModelScope.launch {
            runCatching {
                bookmarkRepository.toggle(
                    projectRoot,
                    bookmarkTarget.file.absolutePath,
                    bookmarkTarget.line
                )
            }
        }
    }

    fun goToNextBookmark(editorContainerState: EditorContainerState) {
        goToAdjacentBookmark(editorContainerState, next = true)
    }

    fun goToPreviousBookmark(editorContainerState: EditorContainerState) {
        goToAdjacentBookmark(editorContainerState, next = false)
    }

    fun navigateToBookmark(editorContainerState: EditorContainerState, filePath: String, line: Int) {
        navigateToLocation(editorContainerState, filePath, line)
    }

    private fun goToAdjacentBookmark(editorContainerState: EditorContainerState, next: Boolean) {
        val projectRoot = projectContext.getCurrentProject()?.rootPath
        if (projectRoot.isNullOrBlank()) {
            showToast(Strings.bookmarks_no_project.strOr(context), ToastType.INFO)
            return
        }

        val bookmarkContext = when (val result = editorContainerState.getActiveBookmarkCursorContextResult()) {
            ActiveBookmarkCursorContextResult.NoOpenFile,
            ActiveBookmarkCursorContextResult.UnsupportedEditor -> {
                showToast(Strings.toast_no_open_file.strOr(context), ToastType.INFO)
                return
            }

            is ActiveBookmarkCursorContextResult.Success -> result.context
        }
        val currentLine = bookmarkContext.line
        val currentFilePath = bookmarkContext.file.absolutePath

        viewModelScope.launch {
            val target = runCatching {
                if (next) {
                    bookmarkRepository.findNext(projectRoot, currentFilePath, currentLine)
                } else {
                    bookmarkRepository.findPrevious(projectRoot, currentFilePath, currentLine)
                }
            }.getOrNull()

            if (target == null) {
                showToast(Strings.bookmarks_empty.strOr(context), ToastType.INFO)
                return@launch
            }

            navigateToLocation(editorContainerState, target.filePath, target.line)
        }
    }

    private fun navigateToLocation(editorContainerState: EditorContainerState, filePath: String, line: Int) {
        editorContainerState.openFileAndGoToPosition(File(filePath), line, 0)
    }

    /**
     * 保存全部文件
     */
    fun saveAllFiles(editorContainerState: EditorContainerState? = null) {
        viewModelScope.launch {
            editorContainerState?.rememberDirtyTabsForSaveAllNotification()
            val results = editorManager.saveAll(SaveReason.MANUAL)
            val successes = results.filterIsInstance<SaveResult.Success>()
            val failures = results.filterIsInstance<SaveResult.Failure>()

            when {
                results.isEmpty() -> showToast(Strings.toast_no_files_to_save.strOr(context), ToastType.INFO)
                failures.isNotEmpty() -> showToast(Strings.toast_some_files_save_failed.strOr(context), ToastType.ERROR)
                else -> showToast(Strings.toast_files_saved.strOr(context, successes.size), ToastType.SUCCESS)
            }

            if (editorContainerState != null && results.isNotEmpty()) {
                editorContainerState.notifySuccessfulSaveAllResults(results)
            }
        }
    }

    /**
     * 确保所有编辑器已保存（用于编译/调试前）
     *
     * @return true 如果所有文件保存成功，false 如果有失败
     */
    suspend fun ensureAllEditorsSaved(actionName: String): Boolean {
        val results = editorManager.saveAll(SaveReason.MANUAL)
        if (results.isEmpty()) return true
        val failures = results.filterIsInstance<SaveResult.Failure>()
        if (failures.isNotEmpty()) {
            showToast(Strings.toast_save_failed_cancelled.strOr(context, actionName, failures.first().message), ToastType.ERROR)
            return false
        }
        showToast(Strings.toast_auto_saved.strOr(context, results.size), ToastType.SUCCESS)
        return true
    }

    // ============ 撤销/重做操作 ============

    /**
     * 执行撤销操作
     */
    fun performUndo(editorContainerState: EditorContainerState) {
        if (editorContainerState.undoInActiveTab()) {
            return
        }
        Timber.tag(TAG).w("performUndo: Tina editor callback unavailable")
    }

    /**
     * 执行重做操作
     */
    fun performRedo(editorContainerState: EditorContainerState) {
        if (editorContainerState.redoInActiveTab()) {
            return
        }
        Timber.tag(TAG).w("performRedo: Tina editor callback unavailable")
    }

    // ============ 编辑器文本操作 ============

    fun performSelectAll(editorContainerState: EditorContainerState) {
        editorContainerState.selectAllInActiveTab()
    }

    fun performCopy(editorContainerState: EditorContainerState) {
        val selection = editorContainerState.getSelectionSnapshotInActiveTab()
        if (selection != null) {
            writeTextToClipboard(label = "selection", text = selection.text)
            showToast(Strings.toast_copied.strOr(context), ToastType.SUCCESS)
        }
    }

    fun performCut(editorContainerState: EditorContainerState) {
        val selection = editorContainerState.getSelectionSnapshotInActiveTab()
        if (selection != null && editorContainerState.replaceSelectionInActiveTab("")) {
            writeTextToClipboard(label = "selection", text = selection.text)
            showToast(Strings.toast_cut.strOr(context), ToastType.SUCCESS)
        }
    }

    fun performPaste(editorContainerState: EditorContainerState) {
        val text = readTextFromClipboard().orEmpty()
        if (text.isBlank()) return
        if (editorContainerState.replaceSelectionInActiveTab(text)) {
            showToast(Strings.toast_pasted.strOr(context), ToastType.SUCCESS)
        }
    }

    fun toggleLineComment(editorContainerState: EditorContainerState) {
        when (
            editorContainerState.requestToggleLineCommentInActiveEditor { file ->
                guessLineCommentToken(file.extension.lowercase())
            }
        ) {
            ActiveEditorCommandResult.SUCCESS -> Unit
            ActiveEditorCommandResult.NO_OPEN_FILE -> {
                showToast(Strings.toast_no_open_file.strOr(context), ToastType.INFO)
            }

            ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
                showToast(Strings.toast_file_not_support_format.strOr(context), ToastType.INFO)
            }
        }
    }

    // ============ 代码格式化 ============

    /**
     * 格式化当前文件的代码
     */
    fun formatCode(editorContainerState: EditorContainerState) {
        val formatTarget = when (val result = editorContainerState.snapshotActiveEditableEditorContent()) {
            ActiveEditableEditorSnapshotResult.NoOpenFile -> {
                showToast(Strings.toast_no_open_file.strOr(context), ToastType.INFO)
                return
            }

            ActiveEditableEditorSnapshotResult.UnsupportedEditor -> {
                showToast(Strings.toast_file_not_support_format.strOr(context), ToastType.INFO)
                return
            }

            is ActiveEditableEditorSnapshotResult.Success -> result.snapshot
        }
        val file = formatTarget.file
        val content = formatTarget.text
        val formatter = CodeFormatter(
            context = context,
            linuxEnvironmentProvider = linuxEnvironmentProvider
        )

        // 检查是否支持格式化
        if (!formatter.isSupported(file)) {
            showToast(Strings.toast_file_type_not_support_format.strOr(context), ToastType.INFO)
            return
        }

        viewModelScope.launch {
            showToast(Strings.toast_formatting.strOr(context), ToastType.INFO)

            try {
                // 检查 clang-format 是否可用
                if (!formatter.isAvailable()) {
                    showToast(Strings.toast_clang_format_not_available.strOr(context), ToastType.ERROR)
                    return@launch
                }

                // 执行格式化
                val result = formatter.format(
                    content = content,
                    sourceFile = file,
                )

                when (result) {
                    is FormatResult.Success -> {
                        // 在主线程更新编辑器内容
                        withContext(Dispatchers.Main) {
                            applyFormattedContent(
                                editorContainerState = editorContainerState,
                                formatTarget = formatTarget,
                                formattedContent = result.formattedContent
                            )
                        }
                    }
                    is FormatResult.Error -> {
                        showToast(Strings.toast_format_failed.strOr(context, result.message), ToastType.ERROR)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(Strings.toast_format_error.strOr(context, e.message ?: ""), ToastType.ERROR)
            }
        }
    }

    /**
     * 应用格式化后的内容到编辑器
     */
    private fun applyFormattedContent(
        editorContainerState: EditorContainerState,
        formatTarget: ActiveEditableEditorSnapshot,
        formattedContent: String
    ) {
        when (
            editorContainerState.replaceTextInTabIfUnchanged(
                tabId = formatTarget.tabId,
                expectedText = formatTarget.text,
                newText = formattedContent,
            )
        ) {
            ConditionalEditorTextReplaceResult.REPLACED ->
                showToast(Strings.toast_format_done.strOr(context), ToastType.SUCCESS)
            ConditionalEditorTextReplaceResult.UNCHANGED ->
                showToast(Strings.toast_code_already_formatted.strOr(context), ToastType.INFO)
            ConditionalEditorTextReplaceResult.CONTENT_CHANGED ->
                showToast(Strings.toast_format_source_changed.strOr(context), ToastType.INFO)
            ConditionalEditorTextReplaceResult.TARGET_UNAVAILABLE ->
                showToast(Strings.toast_file_not_support_format.strOr(context), ToastType.INFO)
        }
    }

    // ============ 剪贴板操作 ============

    /**
     * 复制文件路径到剪贴板
     */
    fun copyPathToClipboard(file: File) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("file path", file.absolutePath)
        clipboard.setPrimaryClip(clip)
        showToast(Strings.toast_path_copied.strOr(context), ToastType.SUCCESS)
    }

    fun copyNameToClipboard(file: File) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("file name", file.name)
        clipboard.setPrimaryClip(clip)
        showToast(Strings.toast_name_copied.strOr(context), ToastType.SUCCESS)
    }

    fun copyRelativePathToClipboard(file: File) {
        val project = projectContext.getCurrentProject()
        val relativePath = project?.rootPath
            ?.let { rootPath ->
                val root = File(rootPath)
                file.relativeToOrNull(root)?.path
            }
            ?.replace(File.separatorChar, '/')
            ?: file.absolutePath

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("relative path", relativePath)
        clipboard.setPrimaryClip(clip)
        showToast(Strings.toast_relative_path_copied.strOr(context), ToastType.SUCCESS)
    }

    // ============ WorkspaceEdit 应用 ============

    /**
     * 应用 LSP WorkspaceEdit
     */
    suspend fun applyWorkspaceEdit(
        editorContainerState: EditorContainerState,
        edit: WorkspaceEdit
    ): Boolean {
        val editsByFile = linkedMapOf<File, WorkspaceFileEditBatch>()

        fun addEdits(uri: String, edits: List<TextEdit>, expectedVersion: Int?): Boolean {
            val file = workspaceUriToFile(uri) ?: return false
            val batch = editsByFile.getOrPut(file) { WorkspaceFileEditBatch() }
            if (expectedVersion != null) {
                val previousVersion = batch.expectedVersion
                if (previousVersion != null && previousVersion != expectedVersion) return false
                batch.expectedVersion = expectedVersion
            }
            batch.edits.addAll(edits)
            return true
        }

        edit.changes.orEmpty().forEach { (uri, edits) ->
            if (!addEdits(uri, edits, expectedVersion = null)) return false
        }

        edit.documentChanges.orEmpty().forEach { change ->
            if (!change.isLeft) return false
            val documentEdit = change.left
            val extractedEdits = mutableListOf<TextEdit>()
            @Suppress("UNCHECKED_CAST")
            val rawEdits = documentEdit.edits as List<*>
            rawEdits.forEach { item ->
                val textEdit = when (item) {
                    is TextEdit -> item
                    is org.eclipse.lsp4j.jsonrpc.messages.Either<*, *> -> when {
                        item.isLeft -> item.left as? TextEdit
                        item.isRight -> item.right as? TextEdit
                        else -> null
                    }
                    else -> null
                } ?: return false
                extractedEdits += textEdit
            }
            if (!addEdits(
                    uri = documentEdit.textDocument.uri,
                    edits = extractedEdits,
                    expectedVersion = documentEdit.textDocument.version
                )
            ) {
                return false
            }
        }

        if (editsByFile.isEmpty()) return false

        val openPlans = mutableListOf<OpenWorkspaceEditPlan>()
        val closedPlans = mutableListOf<ClosedWorkspaceEditPlan>()
        editsByFile.forEach { (file, batch) ->
            if (batch.edits.isEmpty()) return@forEach
            val tabId = editorContainerState.findOpenTabIdByFileOrNull(file)
            if (tabId != null) {
                if (batch.expectedVersion != null &&
                    !editorContainerState.isLspDocumentVersionCurrent(tabId, batch.expectedVersion!!)
                ) {
                    return false
                }
                val mappedEdits = batch.edits.map { textEdit ->
                    TextEditOperation(
                        startLine = textEdit.range.start.line,
                        startColumn = textEdit.range.start.character,
                        endLine = textEdit.range.end.line,
                        endColumn = textEdit.range.end.character,
                        newText = textEdit.newText.orEmpty()
                    )
                }
                val plan = withContext(Dispatchers.Main.immediate) {
                    val originalText = editorContainerState.readTextFromTab(tabId) ?: return@withContext null
                    val updatedText = WorkspaceTextEditApplier.apply(originalText, batch.edits) ?: return@withContext null
                    if (updatedText == originalText) return@withContext OpenWorkspaceEditPlan(
                        tabId = tabId,
                        edits = emptyList(),
                        originalText = originalText,
                        documentVersion = editorContainerState.readTabDocumentVersion(tabId),
                        expectedLspVersion = batch.expectedVersion
                    )
                    if (!editorContainerState.canApplyTextEditsInTab(tabId, mappedEdits)) return@withContext null
                    OpenWorkspaceEditPlan(
                        tabId = tabId,
                        edits = mappedEdits,
                        originalText = originalText,
                        documentVersion = editorContainerState.readTabDocumentVersion(tabId),
                        expectedLspVersion = batch.expectedVersion
                    )
                } ?: return false
                openPlans += plan
            } else {
                if (batch.expectedVersion != null) return false
                val plan = withContext(Dispatchers.IO) {
                    runCatching {
                        if (!file.isFile) return@runCatching null
                        val charset = FileCharsetDetector.detect(file)
                        val originalText = file.readText(charset)
                        val updatedText = WorkspaceTextEditApplier.apply(originalText, batch.edits)
                            ?: return@runCatching null
                        ClosedWorkspaceEditPlan(file, originalText, updatedText, charset)
                    }.getOrNull()
                } ?: return false
                closedPlans += plan
            }
        }

        if (openPlans.all { it.edits.isEmpty() } && closedPlans.all { it.originalText == it.updatedText }) {
            return true
        }

        val writtenClosedPlans = mutableListOf<ClosedWorkspaceEditPlan>()
        closedPlans.filter { it.originalText != it.updatedText }.forEach { plan ->
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    if (!plan.file.isFile || plan.file.readText(plan.charset) != plan.originalText) {
                        return@runCatching false
                    }
                    AtomicTextFileWriter.write(plan.file, plan.updatedText, plan.charset)
                    true
                }
                    .onFailure { Timber.tag("WorkspaceEdit").w(it, "Failed to update %s", plan.file.absolutePath) }
                    .getOrDefault(false)
            }
            if (!written) {
                rollbackClosedWorkspaceEdits(writtenClosedPlans)
                return false
            }
            writtenClosedPlans += plan
        }

        val appliedOpenPlans = mutableListOf<OpenWorkspaceEditPlan>()
        openPlans.filter { it.edits.isNotEmpty() }.forEach { plan ->
            val applied = withContext(Dispatchers.Main.immediate) {
                val localVersionCurrent = plan.documentVersion == null ||
                    editorContainerState.readTabDocumentVersion(plan.tabId) == plan.documentVersion
                val lspVersionCurrent = plan.expectedLspVersion == null ||
                    editorContainerState.isLspDocumentVersionCurrent(plan.tabId, plan.expectedLspVersion)
                localVersionCurrent && lspVersionCurrent &&
                    editorContainerState.applyTextEditsInTab(plan.tabId, plan.edits)
            }
            if (!applied) {
                rollbackOpenWorkspaceEdits(editorContainerState, appliedOpenPlans)
                rollbackClosedWorkspaceEdits(writtenClosedPlans)
                return false
            }
            appliedOpenPlans += plan
        }

        return true
    }

    private suspend fun rollbackOpenWorkspaceEdits(
        editorContainerState: EditorContainerState,
        plans: List<OpenWorkspaceEditPlan>
    ) {
        withContext(Dispatchers.Main.immediate) {
            plans.asReversed().forEach { plan ->
                val restored = runCatching {
                    editorContainerState.replaceTextInTab(plan.tabId, plan.originalText)
                }.onFailure {
                    Timber.tag("WorkspaceEdit").e(it, "Failed to roll back tab %s", plan.tabId)
                }.getOrDefault(false)
                if (!restored) {
                    Timber.tag("WorkspaceEdit").e("Editor rejected rollback for tab %s", plan.tabId)
                }
            }
        }
    }

    private suspend fun rollbackClosedWorkspaceEdits(plans: List<ClosedWorkspaceEditPlan>) {
        withContext(Dispatchers.IO) {
            plans.asReversed().forEach { plan ->
                runCatching {
                    if (plan.file.isFile && plan.file.readText(plan.charset) == plan.updatedText) {
                        AtomicTextFileWriter.write(plan.file, plan.originalText, plan.charset)
                    }
                }
                    .onFailure { Timber.tag("WorkspaceEdit").e(it, "Failed to roll back %s", plan.file.absolutePath) }
            }
        }
    }

    /**
     * 将 URI 转换为文件
     */
    private fun workspaceUriToFile(uri: String): File? = runCatching {
        val parsed = URI(uri)
        val file = when {
            parsed.scheme == null -> File(uri)
            parsed.scheme.equals("file", ignoreCase = true) -> File(parsed)
            else -> null
        }
        file?.absoluteFile?.toPath()?.normalize()?.toFile()
    }.getOrNull()

    // ============ 项目关闭 ============

    /**
     * 关闭项目并返回项目选择界面
     */
    suspend fun closeProjectAndReturn(forgetSession: Boolean): Boolean {
        val actionName = if (forgetSession) {
            Strings.action_close_and_forget.strOr(context)
        } else {
            Strings.action_close_project.strOr(context)
        }

        if (!ensureAllEditorsSaved(actionName)) return false

        if (!forgetSession) {
            editorManager.persistStateSnapshot()
        }
        editorManager.closeAll(clearPersistentState = forgetSession)

        withContext(NonCancellable + Dispatchers.IO) {
            if (forgetSession) {
                clearCurrentProjectState()
            }
            projectSession.closeProject()
        }

        return true
    }

    /**
     * 清除当前项目状态
     */
    private fun clearCurrentProjectState() {
        val project = projectContext.getCurrentProject() ?: return
        val stateDir = ProjectDirStructure.getStateDir(project.rootPath)
        if (stateDir.exists()) stateDir.deleteRecursively()
        val tinaideDir = ProjectDirStructure.getTinaideDir(project.rootPath)
        if (tinaideDir.exists() && tinaideDir.list()?.isEmpty() == true) {
            tinaideDir.delete()
        }
    }

    // ============ 辅助方法 ============

    private fun showToast(message: String, type: ToastType) {
        uiEventsChannel.trySend(UiEvent.ShowToast(message, type))
    }

    private fun readTextFromClipboard(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun writeTextToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun guessLineCommentToken(extension: String): String = when (extension.lowercase()) {
        in CxxFileSupport.editorRelatedExtensions,
        "java", "kt", "kts",
        "js", "ts",
        "rs", "go", "cs", "swift" -> "//"
        "py", "sh", "bash", "zsh", "rb", "pl", "yaml", "yml", "toml", "ini", "conf" -> "#"
        else -> "//"
    }

    companion object {
        private const val TAG = "MainActionsViewModel"
    }
}

package com.wuxianggujun.tinaide.ui.commands

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompileActionsHelper
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.TerminalActivity
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTab
import com.wuxianggujun.tinaide.ui.compose.components.DrawerTab
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditorCommandResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun rememberMainActivityHostCommandExecutor(
    activity: Activity,
    editorContainerState: EditorContainerState,
    fileTreeState: FileTreeState,
    projectContext: IProjectContext,
    drawerState: SwipeableDrawerState,
    dialogState: DialogState,
    bottomPanelViewModel: BottomPanelViewModel,
    bottomPanelController: BottomPanelController,
    actionsViewModel: MainActivityActionsViewModel,
    compileActionsHelper: CompileActionsHelper,
    scope: CoroutineScope,
    openSettings: () -> Unit,
    openRunConfig: () -> Unit,
    openCommandPalette: () -> Unit,
    toastInfo: (String) -> Unit,
): HostCommandExecutor {
    val latestOpenSettings = rememberUpdatedState(openSettings)
    val latestOpenRunConfig = rememberUpdatedState(openRunConfig)
    val latestOpenCommandPalette = rememberUpdatedState(openCommandPalette)
    val latestToastInfo = rememberUpdatedState(toastInfo)

    return remember(
        activity,
        editorContainerState,
        fileTreeState,
        projectContext,
        drawerState,
        dialogState,
        bottomPanelViewModel,
        bottomPanelController,
        actionsViewModel,
        compileActionsHelper,
        scope,
    ) {
        MainActivityHostCommandExecutor(
            activity = activity,
            editorContainerState = editorContainerState,
            fileTreeState = fileTreeState,
            projectContext = projectContext,
            drawerState = drawerState,
            dialogState = dialogState,
            bottomPanelViewModel = bottomPanelViewModel,
            bottomPanelController = bottomPanelController,
            actionsViewModel = actionsViewModel,
            compileActionsHelper = compileActionsHelper,
            scope = scope,
            openSettings = { latestOpenSettings.value() },
            openRunConfig = { latestOpenRunConfig.value() },
            openCommandPalette = { latestOpenCommandPalette.value() },
            toastInfo = { latestToastInfo.value(it) }
        )
    }
}

class MainActivityHostCommandExecutor(
    private val activity: Activity,
    private val editorContainerState: EditorContainerState,
    private val fileTreeState: FileTreeState,
    private val projectContext: IProjectContext,
    private val drawerState: SwipeableDrawerState,
    private val dialogState: DialogState,
    private val bottomPanelViewModel: BottomPanelViewModel,
    private val bottomPanelController: BottomPanelController,
    private val actionsViewModel: MainActivityActionsViewModel,
    private val compileActionsHelper: CompileActionsHelper,
    private val scope: CoroutineScope,
    private val openSettings: () -> Unit,
    private val openRunConfig: () -> Unit,
    private val openCommandPalette: () -> Unit,
    private val toastInfo: (String) -> Unit
) : HostCommandExecutor {

    override fun execute(commandId: String, invocation: HostCommandInvocation): Boolean {
        val normalizedCommandId = normalizeHostCommandId(commandId)
        if (normalizedCommandId.isBlank()) return false

        val file = invocation.file

        return when (normalizedCommandId) {
            // ==================== 文件操作 ====================
            HostCommands.FILE_NEW -> {
                val targetDir = resolveTargetDirectoryForNew(file)
                if (targetDir == null) {
                    toastInfo(Strings.error_no_directory_selected.strOr(activity))
                    false
                } else {
                    dialogState.openNewFileDialog(targetDir)
                    true
                }
            }
            HostCommands.FILE_NEW_FOLDER -> {
                val targetDir = resolveTargetDirectoryForNew(file)
                if (targetDir == null) {
                    toastInfo(Strings.error_no_directory_selected.strOr(activity))
                    false
                } else {
                    dialogState.openCreateFolderDialog(targetDir)
                    true
                }
            }
            HostCommands.FILE_RENAME -> {
                val target = file ?: return false
                dialogState.openRenameDialog(target)
                true
            }
            HostCommands.FILE_DELETE -> {
                val target = file ?: return false
                dialogState.openDeleteDialog(target)
                true
            }
            HostCommands.FILE_COPY_PATH -> {
                val target = file ?: return false
                actionsViewModel.copyPathToClipboard(target)
                true
            }
            HostCommands.FILE_COPY_NAME -> {
                val target = file ?: return false
                actionsViewModel.copyNameToClipboard(target)
                true
            }
            HostCommands.FILE_COPY_RELATIVE_PATH -> {
                val target = file ?: return false
                actionsViewModel.copyRelativePathToClipboard(target)
                true
            }
            HostCommands.FILE_DUPLICATE -> {
                val target = file ?: return false
                scope.launch {
                    val duplicated = duplicateFileOrDirectory(target)
                    if (duplicated != null) {
                        if (duplicated.isFile) {
                            editorContainerState.openFile(duplicated)
                        }
                        fileTreeState.reveal(duplicated)
                    }
                }
                true
            }
            HostCommands.FILE_OPEN_WITH -> {
                val target = file ?: return false
                openExternal(target)
                true
            }
            HostCommands.FILE_SHARE -> {
                val target = file ?: return false
                shareExternal(target)
                true
            }
            HostCommands.FILE_REVEAL_IN_FILE_MANAGER -> {
                val target = file ?: return false
                drawerState.open()
                scope.launch { fileTreeState.reveal(target) }
                true
            }

            // ==================== 编辑器操作 ====================
            HostCommands.EDITOR_SAVE -> {
                actionsViewModel.saveCurrentFile(editorContainerState)
                true
            }
            HostCommands.EDITOR_SAVE_ALL -> {
                actionsViewModel.saveAllFiles(editorContainerState)
                true
            }
            HostCommands.EDITOR_CLOSE -> editorContainerState.requestCloseActiveTab()
            HostCommands.EDITOR_CLOSE_ALL -> editorContainerState.closeAllTabs()
            HostCommands.EDITOR_CLOSE_OTHERS -> editorContainerState.closeOtherTabsForActiveTab()
            HostCommands.EDITOR_NEXT_TAB -> editorContainerState.selectNextTab()
            HostCommands.EDITOR_PREVIOUS_TAB -> editorContainerState.selectPreviousTab()
            HostCommands.EDITOR_UNDO -> {
                actionsViewModel.performUndo(editorContainerState)
                true
            }
            HostCommands.EDITOR_REDO -> {
                actionsViewModel.performRedo(editorContainerState)
                true
            }
            HostCommands.EDITOR_SELECT_ALL -> {
                actionsViewModel.performSelectAll(editorContainerState)
                true
            }
            HostCommands.EDITOR_COPY -> {
                actionsViewModel.performCopy(editorContainerState)
                true
            }
            HostCommands.EDITOR_CUT -> {
                actionsViewModel.performCut(editorContainerState)
                true
            }
            HostCommands.EDITOR_PASTE -> {
                actionsViewModel.performPaste(editorContainerState)
                true
            }
            HostCommands.EDITOR_FIND -> {
                editorContainerState.showSearch()
                true
            }
            HostCommands.EDITOR_REPLACE -> openDialogForActiveEditableEditor(dialogState::openReplaceDialog)
            HostCommands.EDITOR_GOTO_LINE -> openDialogForActiveEditableEditor(dialogState::openGotoLineDialog)
            HostCommands.EDITOR_NAVIGATE_BACK -> editorContainerState.navigateBack()
            HostCommands.EDITOR_NAVIGATE_FORWARD -> editorContainerState.navigateForward()
            HostCommands.EDITOR_TOGGLE_WORD_WRAP -> {
                Prefs.setEditorWordWrap(!Prefs.editorWordWrap)
                true
            }
            HostCommands.EDITOR_FORMAT -> {
                actionsViewModel.formatCode(editorContainerState)
                true
            }
            HostCommands.EDITOR_TOGGLE_COMMENT -> {
                actionsViewModel.toggleLineComment(editorContainerState)
                true
            }
            HostCommands.EDITOR_PEEK_DEFINITION -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("peekDefinition")
            }
            HostCommands.EDITOR_GOTO_DEFINITION -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("definition")
            }
            HostCommands.EDITOR_FIND_REFERENCES -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("references")
            }
            HostCommands.EDITOR_GOTO_TYPE_DEFINITION -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("typeDefinition")
            }
            HostCommands.EDITOR_GOTO_IMPLEMENTATION -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("implementation")
            }
            HostCommands.EDITOR_CODE_ACTIONS -> executeActiveLspCommand {
                editorContainerState.requestActiveLspCodeActions()
            }
            HostCommands.EDITOR_RENAME_SYMBOL -> executeActiveLspCommand {
                editorContainerState.requestActiveLspRename()
            }
            HostCommands.EDITOR_SWITCH_HEADER_SOURCE -> executeActiveLspCommand {
                editorContainerState.requestActiveLspNavigation("switchHeaderSource")
            }
            HostCommands.EDITOR_TOGGLE_BOOKMARK -> {
                actionsViewModel.toggleBookmark(editorContainerState)
                true
            }
            HostCommands.EDITOR_NEXT_BOOKMARK -> {
                actionsViewModel.goToNextBookmark(editorContainerState)
                true
            }
            HostCommands.EDITOR_PREVIOUS_BOOKMARK -> {
                actionsViewModel.goToPreviousBookmark(editorContainerState)
                true
            }

            // ==================== 终端操作 ====================
            HostCommands.TERMINAL_TOGGLE,
            HostCommands.VIEW_TOGGLE_TERMINAL -> {
                openTerminal(workDir = resolveTerminalWorkDir(file), createNewSession = false, command = null)
                true
            }
            HostCommands.TERMINAL_NEW -> {
                openTerminal(workDir = resolveTerminalWorkDir(file), createNewSession = true, command = null)
                true
            }
            HostCommands.TERMINAL_OPEN_HERE -> {
                openTerminal(workDir = resolveTerminalWorkDir(file), createNewSession = false, command = null)
                true
            }
            HostCommands.TERMINAL_CLEAR -> {
                openTerminal(workDir = resolveTerminalWorkDir(file), createNewSession = false, command = "clear")
                true
            }

            // ==================== 项目/工作区操作 ====================
            HostCommands.PROJECT_REFRESH -> {
                scope.launch { fileTreeState.refresh() }
                true
            }
            HostCommands.PROJECT_BUILD -> {
                scope.launch { compileActionsHelper.buildProject() }
                true
            }
            HostCommands.PROJECT_RUN -> {
                scope.launch { compileActionsHelper.runProject() }
                true
            }
            HostCommands.PROJECT_SETTINGS -> {
                openRunConfig()
                true
            }
            HostCommands.PROJECT_CLOSE -> {
                dialogState.openCloseProjectDialog()
                true
            }

            // ==================== 视图切换 ====================
            HostCommands.VIEW_TOGGLE_FILE_TREE -> {
                drawerState.toggle()
                true
            }
            HostCommands.VIEW_TOGGLE_SYMBOLS -> {
                // 与侧栏「符号」统一：打开抽屉大纲，不再堆底栏次级 Tab
                openDrawerSymbols()
                true
            }
            HostCommands.VIEW_COMMAND_PALETTE -> {
                openCommandPalette()
                true
            }
            HostCommands.VIEW_BOOKMARKS -> {
                openBottomPanelBookmarks()
                true
            }
            HostCommands.VIEW_SETTINGS -> {
                openSettings()
                true
            }

            else -> executePluginCommand(normalizedCommandId, invocation)
        }
    }

    private fun executePluginCommand(
        commandId: String,
        invocation: HostCommandInvocation
    ): Boolean {
        val result = PluginCommandRegistry.dispatchWithResult(commandId, invocation)
        result.errorMessage
            ?.takeIf { message -> message.isNotBlank() }
            ?.let(toastInfo)
        return result.handled
    }

    private fun resolveTargetDirectoryForNew(file: File?): File? {
        val projectRoot = projectContext.getCurrentProject()?.rootPath?.let(::File)
        return when {
            file == null -> projectRoot
            file.isDirectory -> file
            else -> file.parentFile ?: projectRoot
        }
    }

    private fun resolveTerminalWorkDir(file: File?): String? {
        val projectRoot = projectContext.getCurrentProject()?.rootPath
        val candidate = when {
            file == null -> projectRoot
            file.isDirectory -> file.absolutePath
            else -> file.parentFile?.absolutePath
        }
        return candidate ?: projectRoot
    }

    private fun openTerminal(workDir: String?, createNewSession: Boolean, command: String?) {
        val intent = Intent(activity, TerminalActivity::class.java).apply {
            workDir?.let {
                putExtra(TerminalActivity.EXTRA_WORK_DIR, it)
                putExtra(TerminalActivity.EXTRA_PROJECT_PATH, it)
            }
            if (createNewSession) putExtra(TerminalActivity.EXTRA_NEW_SESSION, true)
            if (!command.isNullOrBlank()) putExtra(TerminalActivity.EXTRA_COMMAND, command)
        }
        activity.startActivity(intent)
    }

    private fun openDialogForActiveEditableEditor(onSupported: () -> Unit): Boolean = when (editorContainerState.getActiveEditableEditorCommandAvailability()) {
        ActiveEditorCommandResult.SUCCESS -> {
            onSupported()
            true
        }
        ActiveEditorCommandResult.NO_OPEN_FILE -> {
            toastInfo(Strings.toast_no_open_file.strOr(activity))
            true
        }
        ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
            toastInfo(Strings.toast_file_not_support_format.strOr(activity))
            true
        }
    }

    private fun executeActiveLspCommand(action: () -> Boolean): Boolean {
        val handled = action()
        if (!handled) {
            toastInfo(Strings.lsp_error_not_connected.strOr(activity))
        }
        return handled
    }

    private fun openDrawerSymbols() {
        drawerState.toggleTab(DrawerTab.SYMBOLS)
    }

    private fun openBottomPanelBookmarks() {
        scope.launch {
            bottomPanelViewModel.setSelectedTab(BottomPanelTab.BOOKMARKS)
            bottomPanelController.expandToDefault()
        }
    }

    private fun duplicateFileOrDirectory(source: File): File? {
        val parent = source.parentFile ?: return null
        val (base, ext) = if (source.isFile) {
            source.nameWithoutExtension to source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        } else {
            source.name to ""
        }
        for (i in 1..999) {
            val suffix = if (i == 1) " copy" else " copy $i"
            val candidate = File(parent, base + suffix + ext)
            if (!candidate.exists()) {
                return runCatching {
                    if (source.isDirectory) {
                        source.copyRecursively(candidate, overwrite = false)
                    } else {
                        source.copyTo(candidate, overwrite = false)
                    }
                    candidate
                }.getOrNull()
            }
        }
        return null
    }

    private fun openExternal(file: File) {
        val launcher = activity as? com.wuxianggujun.tinaide.ui.MainActivityExternalFileLauncher ?: return
        launcher.openWithExternalApp(file)
    }

    private fun shareExternal(file: File) {
        val launcher = activity as? com.wuxianggujun.tinaide.ui.MainActivityExternalFileLauncher ?: return
        launcher.shareFileOrDirectory(file)
    }
}

internal fun normalizeHostCommandId(commandId: String): String = commandId.trim()

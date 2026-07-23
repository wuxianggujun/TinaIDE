package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.config.DebugToolbarPosition
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.compose.components.DebugBar
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.RunConfigSelector
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDivider
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionTitle
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter
import com.wuxianggujun.tinaide.ui.compose.state.editor.SplitEditorLayout

internal class TopBarCallbacks(
    val onOpenDrawer: () -> Unit,
    val onOpenCommandPalette: () -> Unit,
    val onBuild: () -> Unit,
    val onCompile: () -> Unit,
    val onRebuildAndRun: () -> Unit,
    val onCompileInTerminal: () -> Unit,
    val onDebug: () -> Unit,
    val onSave: () -> Unit,
    val onSaveAll: () -> Unit,
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onFormatCode: () -> Unit,
    val onGotoLine: () -> Unit,
    val onNavigateBack: () -> Unit = {},
    val onNavigateForward: () -> Unit = {},
    val onPeekDefinition: () -> Unit = {},
    val onGotoDefinition: () -> Unit = {},
    val onFindReferences: () -> Unit = {},
    val onGotoTypeDefinition: () -> Unit = {},
    val onGotoImplementation: () -> Unit = {},
    val onCallHierarchyIncoming: () -> Unit = {},
    val onCodeActions: () -> Unit = {},
    val onRenameSymbol: () -> Unit = {},
    val onSwitchHeaderSource: () -> Unit = {},
    val onToggleSplitEditor: () -> Unit = {},
    val onSetSplitEditorLayout: (SplitEditorLayout) -> Unit = {},
    val onMoveTabToSecondaryPane: () -> Unit = {},
    val onCopyTabToSecondaryPane: () -> Unit = {},
    val onOpenExplorer: () -> Unit,
    val onOpenGlobalSearch: () -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onPrevBookmark: () -> Unit,
    val onNextBookmark: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onExitWorkspace: () -> Unit,
    val onPackageApk: () -> Unit = {},
    val onCmakeOpenArtifactsDir: () -> Unit = {},
    val onCmakeReconfigure: () -> Unit = {},
    val onCmakeCleanAndReconfigure: () -> Unit = {},
    val onCmakeClearBuildDir: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityTopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    isCompiling: Boolean,
    isDirty: Boolean,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    isDebugActive: Boolean,
    debugStatus: DebugStatus,
    runConfigManager: RunConfigurationManager,
    onRunConfigManagerChange: (RunConfigurationManager) -> Unit,
    onEditConfig: (RunConfiguration?) -> Unit,
    onShowRunConfigDialog: () -> Unit,
    callbacks: TopBarCallbacks,
    debugViewModel: DebugViewModel,
    overflowCommands: List<MainActivityCommand>,
    onExecuteCommand: (MainActivityCommand) -> Unit,
) {
    val debugToolbarPosition by Prefs.debugToolbarPositionFlow.collectAsStateWithLifecycle()
    val showDebugBarInTop =
        isDebugActive && debugToolbarPosition != DebugToolbarPosition.BOTTOM

    TopAppBar(
        expandedHeight = 48.dp,
        title = {
            val screenWidthPx = LocalWindowInfo.current.containerSize.width
            val compactTitleWidthPx = with(LocalDensity.current) { 360.dp.toPx() }
            val useCompactTitleLayout = screenWidthPx < compactTitleWidthPx
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDebugBarInTop) {
                    DebugBar(
                        debugStatus = debugStatus,
                        onContinue = { debugViewModel.continueExecution() },
                        onStepOver = { debugViewModel.stepOver() },
                        onStepInto = { debugViewModel.stepInto() },
                        onStepOut = { debugViewModel.stepOut() },
                        onPause = { debugViewModel.pauseExecution() },
                        onStop = { debugViewModel.stopSession() },
                        modifier = if (useCompactTitleLayout) Modifier.fillMaxWidth() else Modifier
                    )
                } else if (!isDebugActive) {
                    val defaultRunConfigName = stringResource(Strings.run_config_default_name)
                    // 手机：配置更短；平板/宽屏：配置名更宽。Build/Debug 默认进 Run 菜单，避免顶栏图标墙。
                    val isWideTopBar = screenWidthPx >= with(LocalDensity.current) { 600.dp.toPx() }
                    RunConfigSelector(
                        configManager = runConfigManager,
                        onSelectConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.selectConfig(id))
                        },
                        onAddConfig = {
                            onEditConfig(RunConfiguration(name = defaultRunConfigName))
                            onShowRunConfigDialog()
                        },
                        onEditConfig = {
                            onEditConfig(runConfigManager.selectedConfig)
                            onShowRunConfigDialog()
                        },
                        onDuplicateConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.duplicateConfig(id))
                        },
                        onDeleteConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.removeConfig(id))
                        },
                        onBuild = callbacks.onBuild,
                        onRun = callbacks.onCompile,
                        onRebuildAndRun = callbacks.onRebuildAndRun,
                        onRunInTerminal = callbacks.onCompileInTerminal,
                        onDebug = callbacks.onDebug,
                        isBuildEnabled = !isCompiling,
                        isRunEnabled = !isCompiling,
                        isDebugEnabled = !isCompiling,
                        buildIconRes = Drawables.ic_build,
                        debugIconRes = Drawables.ic_debug,
                        runTint = MaterialTheme.colorScheme.primary,
                        disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        configSegmentMaxWidth = when {
                            useCompactTitleLayout -> 72.dp
                            isWideTopBar -> 140.dp
                            else -> 110.dp
                        },
                        // 默认只常驻「配置 + Run」；平板也不默认铺 Build/Debug 图标，保持简洁
                        showBuildButton = false,
                        showDebugButton = false,
                    )
                }
            }
        },
        navigationIcon = {
            // 左侧：文件树 + 撤销/重做（与右侧保存/溢出分开，降低误触）
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = callbacks.onOpenDrawer) {
                    Icon(Icons.Default.Menu, stringResource(Strings.content_desc_open_file_tree))
                }
                if (!isDebugActive) {
                    EditHistoryActionButton(
                        iconRes = Drawables.ic_undo,
                        contentDescription = stringResource(Strings.content_desc_undo),
                        enabled = canUndo,
                        onClick = callbacks.onUndo,
                    )
                    EditHistoryActionButton(
                        iconRes = Drawables.ic_redo,
                        contentDescription = stringResource(Strings.content_desc_redo),
                        enabled = canRedo,
                        onClick = callbacks.onRedo,
                    )
                }
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 0.dp)
            ) {
                if (!isDebugActive) {
                    SaveActionButton(
                        isDirty = isDirty,
                        onSave = callbacks.onSave
                    )
                }
                MainActivityOverflowMenu(
                    commands = overflowCommands,
                    onExecuteCommand = onExecuteCommand
                )
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun MainActivityOverflowMenu(
    commands: List<MainActivityCommand>,
    onExecuteCommand: (MainActivityCommand) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(Strings.content_desc_more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        var previousSection: OverflowMenuSection? = null
        commands.forEach { command ->
            val section = command.overflowSection()
            if (previousSection != section) {
                if (previousSection != null) {
                    TinaDropdownMenuDivider()
                }
                // 工程区仅「退出」一项时不显示分组标题，只保留分隔
                if (section.titleRes != 0) {
                    TinaDropdownMenuSectionHeader {
                        TinaDropdownMenuSectionTitle(
                            text = stringResource(section.titleRes)
                        )
                    }
                }
                previousSection = section
            }
            TinaDropdownMenuItem(
                text = { Text(command.title.resolve(context)) },
                onClick = {
                    expanded = false
                    onExecuteCommand(command)
                },
                enabled = command.enabled
            )
        }
    }
}

/**
 * 溢出菜单展示分组（与 HostCommandCategory 解耦，避免退出工程被归到「文件」导致结构乱）。
 */
private enum class OverflowMenuSection(
    @param:StringRes val titleRes: Int,
) {
    FILE(Strings.menu_section_file),
    VIEW(Strings.menu_section_view),
    PROJECT(0),
}

private fun MainActivityCommand.overflowSection(): OverflowMenuSection = when (id) {
    HostCommands.EDITOR_SAVE_ALL,
    HostCommands.EDITOR_FORMAT,
    -> OverflowMenuSection.FILE

    HostCommands.VIEW_COMMAND_PALETTE,
    "view.globalSearch",
    HostCommands.VIEW_BOOKMARKS,
    HostCommands.VIEW_TOGGLE_TERMINAL,
    HostCommands.VIEW_SETTINGS,
    -> OverflowMenuSection.VIEW

    HostCommands.PROJECT_CLOSE,
    -> OverflowMenuSection.PROJECT

    else -> when (category) {
        MainActivityCommandCategory.FILE,
        MainActivityCommandCategory.CODE,
        -> OverflowMenuSection.FILE

        MainActivityCommandCategory.BUILD -> OverflowMenuSection.PROJECT

        MainActivityCommandCategory.VIEW,
        MainActivityCommandCategory.TERMINAL,
        MainActivityCommandCategory.PLUGIN,
        -> OverflowMenuSection.VIEW
    }
}

@Composable
private fun EditHistoryActionButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = rememberTinaPainter(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SaveActionButton(
    isDirty: Boolean,
    onSave: () -> Unit
) {
    IconButton(onClick = onSave, enabled = isDirty) {
        Icon(
            painter = rememberTinaPainter(Drawables.ic_save),
            contentDescription = stringResource(Strings.content_desc_save),
            tint = if (isDirty) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

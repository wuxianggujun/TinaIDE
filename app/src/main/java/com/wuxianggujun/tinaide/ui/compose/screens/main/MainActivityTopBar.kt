package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.config.DebugToolbarPosition
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.DebugBar
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.RunConfigSelector
import com.wuxianggujun.tinaide.ui.compose.components.SubMenuDropdownItem
import com.wuxianggujun.tinaide.ui.compose.components.SubMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDivider
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionTitle
import com.wuxianggujun.tinaide.ui.compose.components.TinaPanelSegmentButton
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

/**
 * TopAppBar 回调接口，减少参数传递。
 */
internal class TopBarCallbacks(
    val onOpenDrawer: () -> Unit,
    val onBuild: () -> Unit,
    val onCompile: () -> Unit,
    val onRebuildAndRun: () -> Unit,
    val onCompileInTerminal: () -> Unit,
    val onDebug: () -> Unit,
    val onSave: () -> Unit,
    val onSaveAll: () -> Unit,
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
    val onMoveTabToSecondaryPane: () -> Unit = {},
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
    isDebugActive: Boolean,
    debugStatus: DebugStatus,
    canPackageApk: Boolean,
    isBasicLspNavigationAvailable: Boolean,
    isAdvancedLspNavigationAvailable: Boolean,
    isCallHierarchyIncomingAvailable: Boolean,
    isLspRefactorAvailable: Boolean,
    isHeaderSourceSwitchAvailable: Boolean,
    canNavigateBack: Boolean,
    canNavigateForward: Boolean,
    isSplitEditorEnabled: Boolean,
    canMoveTabToSecondaryPane: Boolean,
    currentBuildSystem: BuildSystem,
    availableTargets: List<TargetInfo>,
    runConfigManager: RunConfigurationManager,
    onRunConfigManagerChange: (RunConfigurationManager) -> Unit,
    onEditConfig: (RunConfiguration?) -> Unit,
    onShowRunConfigDialog: () -> Unit,
    callbacks: TopBarCallbacks,
    debugViewModel: com.wuxianggujun.tinaide.ui.DebugViewModel,
) {
    var showMenu by remember { mutableStateOf(false) }
    val debugToolbarPosition by Prefs.debugToolbarPositionFlow.collectAsStateWithLifecycle()
    val showDebugBarInTop =
        isDebugActive && debugToolbarPosition != DebugToolbarPosition.BOTTOM

    @Composable
    fun OverflowMenuButton() {
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Strings.content_desc_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TinaDropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                TinaDropdownMenuSectionHeader {
                    TinaDropdownMenuSectionTitle(
                        text = stringResource(Strings.menu_section_file),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_save_all)) },
                    onClick = {
                        showMenu = false
                        callbacks.onSaveAll()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_save),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_format_code)) },
                    onClick = {
                        showMenu = false
                        callbacks.onFormatCode()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_format),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_goto_line)) },
                    onClick = {
                        showMenu = false
                        callbacks.onGotoLine()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_goto_line),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                if (
                    canNavigateBack ||
                    canNavigateForward ||
                    isBasicLspNavigationAvailable ||
                    isAdvancedLspNavigationAvailable ||
                    isCallHierarchyIncomingAvailable ||
                    isLspRefactorAvailable ||
                    isHeaderSourceSwitchAvailable
                ) {
                    TinaDropdownMenuDivider()

                    TinaDropdownMenuSectionHeader {
                        TinaDropdownMenuSectionTitle(
                            text = stringResource(Strings.menu_section_code),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.cmd_editor_navigate_back)) },
                        enabled = canNavigateBack,
                        onClick = {
                            showMenu = false
                            callbacks.onNavigateBack()
                        }
                    )

                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.cmd_editor_navigate_forward)) },
                        enabled = canNavigateForward,
                        onClick = {
                            showMenu = false
                            callbacks.onNavigateForward()
                        }
                    )

                    if (isBasicLspNavigationAvailable) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_peek_definition)) },
                            onClick = {
                                showMenu = false
                                callbacks.onPeekDefinition()
                            }
                        )

                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_goto_definition)) },
                            onClick = {
                                showMenu = false
                                callbacks.onGotoDefinition()
                            }
                        )

                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_find_references)) },
                            onClick = {
                                showMenu = false
                                callbacks.onFindReferences()
                            }
                        )
                    }

                    if (isAdvancedLspNavigationAvailable) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_goto_type_definition)) },
                            onClick = {
                                showMenu = false
                                callbacks.onGotoTypeDefinition()
                            }
                        )

                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_goto_implementation)) },
                            onClick = {
                                showMenu = false
                                callbacks.onGotoImplementation()
                            }
                        )
                    }

                    if (isCallHierarchyIncomingAvailable) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_call_hierarchy_incoming)) },
                            onClick = {
                                showMenu = false
                                callbacks.onCallHierarchyIncoming()
                            }
                        )
                    }

                    if (isLspRefactorAvailable) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.code_actions_title)) },
                            onClick = {
                                showMenu = false
                                callbacks.onCodeActions()
                            }
                        )

                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.lsp_template_rename)) },
                            onClick = {
                                showMenu = false
                                callbacks.onRenameSymbol()
                            }
                        )
                    }

                    if (isHeaderSourceSwitchAvailable) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.cmd_editor_switch_header_source)) },
                            onClick = {
                                showMenu = false
                                callbacks.onSwitchHeaderSource()
                            }
                        )
                    }
                }

                if (canPackageApk || currentBuildSystem == BuildSystem.CMAKE) {
                    TinaDropdownMenuDivider()

                    TinaDropdownMenuSectionHeader {
                        TinaDropdownMenuSectionTitle(
                            text = stringResource(Strings.menu_section_build),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.cmd_project_build)) },
                        onClick = {
                            showMenu = false
                            callbacks.onBuild()
                        },
                        leadingIcon = {
                            Icon(
                                painter = rememberTinaPainter(Drawables.ic_build),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )

                    if (currentBuildSystem == BuildSystem.CMAKE) {
                        SubMenuItem(
                            text = stringResource(Strings.menu_cmake_tools),
                            leadingIcon = rememberTinaPainter(Drawables.ic_file_cmake),
                            onParentDismiss = { showMenu = false }
                        ) {
                            SubMenuDropdownItem(
                                text = stringResource(Strings.menu_cmake_open_artifacts_dir),
                                onClick = { callbacks.onCmakeOpenArtifactsDir() }
                            )
                            SubMenuDropdownItem(
                                text = stringResource(Strings.menu_cmake_reconfigure),
                                onClick = { callbacks.onCmakeReconfigure() }
                            )
                            SubMenuDropdownItem(
                                text = stringResource(Strings.menu_cmake_clean_and_reconfigure),
                                onClick = { callbacks.onCmakeCleanAndReconfigure() }
                            )
                            SubMenuDropdownItem(
                                text = stringResource(Strings.menu_cmake_clear_build_dir),
                                onClick = { callbacks.onCmakeClearBuildDir() }
                            )
                        }
                    }

                    if (canPackageApk) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(Strings.menu_package_apk)) },
                            onClick = {
                                showMenu = false
                                callbacks.onPackageApk()
                            },
                            leadingIcon = {
                                Icon(
                                    painter = rememberTinaPainter(Drawables.ic_package),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }

                TinaDropdownMenuDivider()

                TinaDropdownMenuSectionHeader {
                    TinaDropdownMenuSectionTitle(
                        text = stringResource(Strings.menu_section_view),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                TinaDropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isSplitEditorEnabled) {
                                    Strings.menu_disable_split_editor
                                } else {
                                    Strings.menu_enable_split_editor
                                }
                            )
                        )
                    },
                    onClick = {
                        showMenu = false
                        callbacks.onToggleSplitEditor()
                    }
                )

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_move_tab_to_secondary_pane)) },
                    enabled = canMoveTabToSecondaryPane,
                    onClick = {
                        showMenu = false
                        callbacks.onMoveTabToSecondaryPane()
                    }
                )

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_explorer)) },
                    onClick = {
                        showMenu = false
                        callbacks.onOpenExplorer()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_explorer),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_global_search)) },
                    onClick = {
                        showMenu = false
                        callbacks.onOpenGlobalSearch()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_search),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                SubMenuItem(
                    text = stringResource(Strings.menu_bookmarks),
                    leadingIcon = rememberTinaPainter(Drawables.ic_bookmark),
                    onParentDismiss = { showMenu = false }
                ) {
                    SubMenuDropdownItem(
                        text = stringResource(Strings.menu_bookmark_toggle),
                        onClick = { callbacks.onToggleBookmark() },
                        leadingIcon = rememberTinaPainter(Drawables.ic_bookmark)
                    )
                    SubMenuDropdownItem(
                        text = stringResource(Strings.menu_bookmark_prev),
                        onClick = { callbacks.onPrevBookmark() },
                        leadingIcon = rememberTinaPainter(Drawables.ic_arrow_back)
                    )
                    SubMenuDropdownItem(
                        text = stringResource(Strings.menu_bookmark_next),
                        onClick = { callbacks.onNextBookmark() },
                        leadingIcon = rememberTinaPainter(Drawables.ic_arrow_forward)
                    )
                    SubMenuDropdownItem(
                        text = stringResource(Strings.menu_bookmark_list),
                        onClick = { callbacks.onOpenBookmarks() },
                        leadingIcon = rememberTinaPainter(Drawables.ic_menu_explorer)
                    )
                }

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_terminal)) },
                    onClick = {
                        showMenu = false
                        callbacks.onOpenTerminal()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                TinaDropdownMenuDivider()

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_settings)) },
                    onClick = {
                        showMenu = false
                        callbacks.onOpenSettings()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_settings),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                TinaDropdownMenuDivider()

                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.menu_exit_workspace)) },
                    onClick = {
                        showMenu = false
                        callbacks.onExitWorkspace()
                    },
                    leadingIcon = {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_menu_exit),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }
    }

    TopAppBar(
        expandedHeight = 48.dp,
        title = {
            // title 槽位按内容宽度布局；用 Row + Arrangement.End 把胶囊推到靠近 actions 的一侧，
            // 避免 Material3 TopAppBar 默认让 title 左贴 nav、在右侧留出大片空白。
            val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
            val useCompactTitleLayout = screenWidthDp < 360.dp
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
                    RunConfigSelector(
                        configManager = runConfigManager,
                        onSelectConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.selectConfig(id))
                        },
                        onAddConfig = {
                            onEditConfig(RunConfiguration(name = "New Config"))
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
                        runTint = Color(0xFF4CAF50),
                        disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        configSegmentMaxWidth = if (useCompactTitleLayout) 72.dp else 110.dp,
                        showBuildButton = true
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onOpenDrawer) {
                Icon(Icons.Default.Menu, stringResource(Strings.content_desc_open_file_tree))
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
                OverflowMenuButton()
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun SaveActionButton(
    isDirty: Boolean,
    onSave: () -> Unit
) {
    // 用图标颜色直接表达脏状态：脏时用 onSurface，非脏时用淡化色；不再叠加鲜艳小圆点，
    // 避免与主题的 primary 产生视觉冲突。
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

@Composable
private fun TopBarActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 30.dp,
    height: Dp = 36.dp,
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.size(width = width, height = height),
        enabled = enabled,
        minHeight = height,
        color = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

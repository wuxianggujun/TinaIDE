package com.wuxianggujun.tinaide.ui.compose.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.config.DebugToolbarPosition
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.core.git.GitBranch
import com.wuxianggujun.tinaide.core.git.GitCommit
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginPanelContentStore
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.compose.components.SymbolsContent
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 底部面板组件
 *
 * 包含：
 * - 状态栏（带拖拽）
 * - 调试工具栏（调试且工具栏位置为底部时）
 * - 底部面板内容（问题 / 构建 / 输出 等）
 *
 * 注意：终端已移至独立 TerminalActivity；撤销/重做在顶栏左侧。
 */
@Composable
fun BottomPanel(
    editorContainerState: EditorContainerState,
    bottomPanelState: BottomPanelDragState,
    bottomPanelViewModel: BottomPanelViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    projectSymbolIndexService: ProjectSymbolIndexService?,
    onBookmarkNavigate: (filePath: String, line: Int) -> Unit,
    onDiagnosticClick: (Diagnostic) -> Unit,
    modifier: Modifier = Modifier,
    // Git 相关参数
    gitCurrentBranch: String? = null,
    gitBranches: List<GitBranch> = emptyList(),
    gitCommits: List<GitCommit> = emptyList(),
    gitIsLoadingHistory: Boolean = false,
    onGitRefresh: () -> Unit = {},
    onGitBranchSelect: (String) -> Unit = {},
    onGitCommitClick: (GitCommit) -> Unit = {},
    // 状态栏参数
    cursorLine: Int = 1,
    cursorColumn: Int = 1,
    fileEncoding: String = "",
    onCursorPositionClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentHeightDp = with(density) { bottomPanelState.currentHeight.toDp() }

    // 从 ViewModel 获取状态
    val selectedBottomTab by bottomPanelViewModel.selectedBottomTab.collectAsStateWithLifecycle()

    // 直接读取状态层收口后的窄语义，避免外层自己拆 tabs/活动态。
    val editorToolBarState = editorContainerState.getActiveEditorToolBarState()
    val hasOpenFiles = editorToolBarState.hasFiles

    // 从 DebugViewModel 获取调试状态
    val isActive by debugViewModel.isActive.collectAsStateWithLifecycle()

    val editorStatus = editorContainerState.getBottomPanelEditorStatus(
        isDebugSessionActive = isActive
    )

    val debugToolbarPosition = Prefs.debugToolbarPositionFlow.collectAsStateWithLifecycleWhen(isActive)
    val developerOptionsEnabled by Prefs.developerOptionsEnabledFlow.collectAsStateWithLifecycle()
    val diagnosticsSettings = Prefs.devDiagnosticsSettingsFlow.collectAsStateWithLifecycleWhen(
        developerOptionsEnabled
    )
    val showDebugBarInBottom = isActive && debugToolbarPosition != DebugToolbarPosition.TOP
    val showEditorPerformanceTab = shouldShowEditorPerformanceTab(
        developerOptionsEnabled = developerOptionsEnabled,
        diagnosticsEnabled = diagnosticsSettings.diagnosticsEnabled,
        activeTabSupportsEditorPerformancePanel = editorContainerState.activeTabSupportsEditorPerformancePanel()
    )
    val debugStatus = debugViewModel.debugStatus.collectAsStateWithLifecycleWhen(showDebugBarInBottom)
    val breakpoints = debugViewModel.breakpoints.collectAsStateWithLifecycleWhen(isActive)
    val variables = debugViewModel.variables.collectAsStateWithLifecycleWhen(isActive)
    val callStack = debugViewModel.callStack.collectAsStateWithLifecycleWhen(isActive)
    val consoleLines = debugViewModel.consoleLines.collectAsStateWithLifecycleWhen(isActive)
    val pluginManager = remember(context) { PluginManager.getInstance(context) }
    val pluginState by pluginManager.pluginStateFlow.collectAsStateWithLifecycle()
    val pluginPanelContents by PluginPanelContentStore.contents.collectAsStateWithLifecycle()

    // 变量详情对话框状态
    var showVariableDetailDialog by remember { mutableStateOf(false) }
    var selectedVariableForDetail by remember { mutableStateOf<DebugVariable?>(null) }

    // 默认底栏：问题 / 构建 / 输出；命令打开的次级 Tab 临时并入可见列表
    val normalModeTabs = resolveNormalModeBottomTabs(
        showEditorPerformanceTab = showEditorPerformanceTab,
        hasPluginPanels = pluginState.resolvedPanels.isNotEmpty(),
    )
    val visibleBottomTabs = resolveVisibleBottomPanelTabs(
        normalModeTabs = normalModeTabs,
        selectedBottomTab = selectedBottomTab,
    )
    val resolvedBottomTab = resolveSelectedBottomPanelTab(
        selectedBottomTab = selectedBottomTab,
        normalModeTabs = normalModeTabs
    )
    val buildLogs = bottomPanelViewModel.buildLogs.collectAsStateWithLifecycleWhen(
        !isActive && resolvedBottomTab == BottomPanelTab.BUILD_LOG
    )
    val runOutputLogs = bottomPanelViewModel.runOutputLogs.collectAsStateWithLifecycleWhen(
        !isActive && resolvedBottomTab == BottomPanelTab.RUN_OUTPUT
    )
    val diagnostics = bottomPanelViewModel.diagnostics.collectAsStateWithLifecycleWhen(
        !isActive && resolvedBottomTab == BottomPanelTab.DIAGNOSTICS
    )
    val buildLogCount by bottomPanelViewModel.buildLogCount.collectAsStateWithLifecycle()
    val runOutputCount by bottomPanelViewModel.runOutputCount.collectAsStateWithLifecycle()
    val normalModeTabBadges = mapOf(
        BottomPanelTab.BUILD_LOG to buildLogCount,
        BottomPanelTab.RUN_OUTPUT to runOutputCount
    )

    val bookmarkRepository: IBookmarkRepository = koinInject()
    val projectRootPath = if (resolvedBottomTab == BottomPanelTab.BOOKMARKS) {
        editorContainerState.getBookmarksProjectRootPathOrNull()
    } else {
        null
    }

    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        // 底部停靠面板需要与屏幕边缘齐平，圆角会在左右留下漏底色的空白角。
        shape = RectangleShape,
        tonalElevation = 2.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 底部状态栏（带拖拽功能，合并了拖拽条）- 只在有打开文件时显示
            AnimatedVisibility(visible = hasOpenFiles) {
                EditorStatusBar(
                    status = editorStatus,
                    encoding = fileEncoding,
                    line = cursorLine,
                    column = cursorColumn,
                    bottomPanelState = bottomPanelState,
                    onCursorPositionClick = onCursorPositionClick
                )
            }

            // 调试工具栏（底栏位置）；撤销/重做在顶栏左侧，符号快捷栏已移除以统一交互
            AnimatedVisibility(visible = showDebugBarInBottom) {
                DebugBar(
                    debugStatus = debugStatus,
                    onContinue = { debugViewModel.continueExecution() },
                    onStepOver = { debugViewModel.stepOver() },
                    onStepInto = { debugViewModel.stepInto() },
                    onStepOut = { debugViewModel.stepOut() },
                    onPause = { debugViewModel.pauseExecution() },
                    onStop = { debugViewModel.stopSession() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 底部面板内容（使用动态高度，跟随拖拽）
            if (currentHeightDp > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentHeightDp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 调试模式：全屏接管，显示水平标签页布局
                        if (isActive) {
                            DebugContentWithHorizontalTabs(
                                breakpoints = breakpoints,
                                variables = variables,
                                callStack = callStack,
                                consoleLines = consoleLines,
                                onBreakpointToggle = { id -> debugViewModel.toggleBreakpoint(id) },
                                onBreakpointRemove = { id -> debugViewModel.removeBreakpoint(id) },
                                onVariableClick = { variable ->
                                    selectedVariableForDetail = variable
                                    showVariableDetailDialog = true
                                },
                                onStackFrameClick = { frame -> debugViewModel.requestVariables(frame.id) },
                                onClearConsole = { debugViewModel.clearConsole() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        } else {
                            // 普通模式：问题 / 构建 / 输出（+ 按需性能/插件/命令打开的次级 Tab）
                            BottomPanelTabRow(
                                selectedTab = resolvedBottomTab,
                                tabs = visibleBottomTabs,
                                badges = normalModeTabBadges,
                                onTabSelected = { tab ->
                                    // 切换标签页时，如果面板未展开则展开
                                    scope.launch {
                                        if (!bottomPanelState.isExpanded) {
                                            bottomPanelState.expandToDefault()
                                        }
                                    }
                                    bottomPanelViewModel.setSelectedTab(tab)
                                },
                                isNearFullScreen = bottomPanelState.isNearFullScreen,
                                onToggleFullScreen = {
                                    scope.launch {
                                        if (bottomPanelState.isNearFullScreen) {
                                            bottomPanelState.expandToDefault()
                                        } else {
                                            bottomPanelState.expandToFullScreen()
                                        }
                                    }
                                },
                                onClose = { scope.launch { bottomPanelState.collapse() } }
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                when (resolvedBottomTab) {
                                    BottomPanelTab.BUILD_LOG -> BuildLogContent(
                                        logs = buildLogs,
                                        modifier = Modifier.fillMaxSize(),
                                        copyActionTitleRes = Strings.btn_copy_log,
                                        onCopyAll = {
                                            copyLogEntriesToClipboard(
                                                context = context,
                                                logs = bottomPanelViewModel.buildLogs.value,
                                                clipboardLabelRes = Strings.build_log_clipboard_label,
                                                emptyMessageRes = Strings.build_log_empty
                                            )
                                        },
                                        onClear = { bottomPanelViewModel.clearBuildLogs() }
                                    )
                                    BottomPanelTab.RUN_OUTPUT -> {
                                        BuildLogContent(
                                            logs = runOutputLogs,
                                            modifier = Modifier.fillMaxSize(),
                                            emptyMessageRes = Strings.run_output_empty,
                                            copyActionTitleRes = Strings.action_copy,
                                            onCopyAll = {
                                                copyLogEntriesToClipboard(
                                                    context = context,
                                                    logs = bottomPanelViewModel.runOutputLogs.value,
                                                    clipboardLabelRes = Strings.run_output_clipboard_label,
                                                    emptyMessageRes = Strings.run_output_empty
                                                )
                                            },
                                            onClear = { bottomPanelViewModel.clearRunOutput() }
                                        )
                                    }
                                    BottomPanelTab.DIAGNOSTICS -> DiagnosticsContent(
                                        diagnostics = diagnostics,
                                        onDiagnosticClick = onDiagnosticClick
                                    )
                                    BottomPanelTab.PERFORMANCE -> EditorPerformanceContent(
                                        snapshotProvider = if (showEditorPerformanceTab) {
                                            { editorContainerState.readActiveEditorPerformanceSnapshot() }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    BottomPanelTab.OUTLINE -> OutlineContent(
                                        editorContainerState = editorContainerState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    BottomPanelTab.SYMBOLS -> SymbolsContent(
                                        editorContainerState = editorContainerState,
                                        projectSymbolIndexService = projectSymbolIndexService,
                                    )
                                    BottomPanelTab.BOOKMARKS -> BookmarksContent(
                                        projectRootPath = projectRootPath,
                                        bookmarkRepository = bookmarkRepository,
                                        onNavigate = onBookmarkNavigate,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    BottomPanelTab.PLUGINS -> PluginPanelsContent(
                                        panels = pluginState.resolvedPanels,
                                        contents = pluginPanelContents,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    BottomPanelTab.GIT -> GitLogPanel(
                                        currentBranch = gitCurrentBranch,
                                        branches = gitBranches,
                                        commits = gitCommits,
                                        isLoading = gitIsLoadingHistory,
                                        onRefresh = onGitRefresh,
                                        onBranchSelect = onGitBranchSelect,
                                        onCommitClick = onGitCommitClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 变量详情对话框
    if (showVariableDetailDialog && selectedVariableForDetail != null) {
        DebugVariableDetailDialog(
            variable = selectedVariableForDetail!!,
            onDismiss = {
                showVariableDetailDialog = false
                selectedVariableForDetail = null
            }
        )
    }
}

@Composable
@SuppressLint("StateFlowValueCalledInComposition")
private fun <T> StateFlow<T>.collectAsStateWithLifecycleWhen(shouldCollect: Boolean): T = if (shouldCollect) {
    val collectedValue by collectAsStateWithLifecycle()
    collectedValue
} else {
    value
}

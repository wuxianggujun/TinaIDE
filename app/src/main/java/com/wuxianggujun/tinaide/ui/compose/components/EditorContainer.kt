package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.BuildConfig
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.io.FileCharsetDetector
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabBar
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import com.wuxianggujun.tinaide.ui.compose.components.editor.EmptyEditorView
import com.wuxianggujun.tinaide.ui.compose.components.editor.TinaCodeEditorPage
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState.EditorPaneId
import com.wuxianggujun.tinaide.ui.compose.viewer.HexViewerScreen
import com.wuxianggujun.tinaide.ui.compose.viewer.ImagePreviewScreen
import com.wuxianggujun.tinaide.ui.compose.viewer.LargeTextViewerScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 编辑器容器组件
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorContainer(
    state: EditorContainerState,
    editorManager: com.wuxianggujun.tinaide.editor.IEditorManager,
    pluginManager: com.wuxianggujun.tinaide.plugin.PluginManager,
    hostCommandExecutor: com.wuxianggujun.tinaide.core.commands.HostCommandExecutor?,
    onOpenFileTree: () -> Unit,
    onEditorStateChanged: (hasFiles: Boolean, canUndo: Boolean, canRedo: Boolean, isDirty: Boolean) -> Unit,
    onCursorPositionChanged: (line: Int, column: Int) -> Unit = { _, _ -> },
    onFileEncodingChanged: (encoding: String) -> Unit = { _ -> },
    onSaveFile: (tabId: String, onComplete: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 外部文件修改对话框状态
    var showExternalModificationDialog by remember { mutableStateOf(false) }
    var conflictTabId by remember { mutableStateOf<String?>(null) }
    var conflictFile by remember { mutableStateOf<java.io.File?>(null) }
    val shownSessionErrors = remember { mutableMapOf<String, String>() }
    val openErrorMessage = state.lastOpenError

    LaunchedEffect(openErrorMessage) {
        val message = state.consumeLastOpenError()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true
        )
    }

    // 监听外部文件修改
    val activeEditorSessionAlertFlow = state.getActiveEditorSessionAlertFlow()
    LaunchedEffect(activeEditorSessionAlertFlow) {
        val sessionAlertFlow = activeEditorSessionAlertFlow ?: return@LaunchedEffect
        sessionAlertFlow.collect { sessionAlert ->
            if (sessionAlert.hasExternalModification && !showExternalModificationDialog) {
                conflictTabId = sessionAlert.tabId
                conflictFile = sessionAlert.file
                showExternalModificationDialog = true
            }
            val latestError = sessionAlert.lastError
            if (latestError == null) {
                shownSessionErrors.remove(sessionAlert.tabId)
            } else if (shownSessionErrors[sessionAlert.tabId] != latestError) {
                shownSessionErrors[sessionAlert.tabId] = latestError
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = latestError,
                        withDismissAction = true
                    )
                }
            }
        }
    }

    // 显示外部修改对话框
    if (showExternalModificationDialog && conflictFile != null && conflictTabId != null) {
        ExternalModificationDialog(
            file = conflictFile!!,
            onReload = {
                val tabId = conflictTabId!!
                val success = editorManager.reloadFromDisk(tabId)
                showExternalModificationDialog = false
                conflictTabId = null
                conflictFile = null
                scope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar(
                            com.wuxianggujun.tinaide.core.i18n.Strings.editor_reload_success.strOr(context)
                        )
                    } else {
                        snackbarHostState.showSnackbar(
                            com.wuxianggujun.tinaide.core.i18n.Strings.editor_reload_failed.strOr(context)
                        )
                    }
                }
            },
            onKeepMine = {
                val tabId = conflictTabId!!
                editorManager.acknowledgeExternalModification(tabId)
                showExternalModificationDialog = false
                conflictTabId = null
                conflictFile = null
            },
            onDismiss = {
                showExternalModificationDialog = false
            }
        )
    }

    // 未保存文件对话框
    state.pendingCloseTab?.let { tab ->
        UnsavedFileDialog(
            fileName = tab.file.name,
            onSaveAndClose = {
                onSaveFile(tab.id) {
                    state.confirmSaveAndClose()
                }
            },
            onDiscardAndClose = {
                state.confirmDiscardAndClose()
            },
            onCancel = {
                state.cancelClose()
            }
        )
    }

    val tabs = state.tabs
    val activeTabIndex = state.activeTabIndex

    // 这里保留 tabs/activeTabIndex 的直接读取，仅用于 Pager/TabBar 渲染同步；
    // 活动编辑器的业务语义继续统一走状态层窄入口，不在外层自行拼装。
    if (tabs.isEmpty()) {
        EmptyEditorView(
            onOpenFileTree = onOpenFileTree,
            modifier = modifier
        )
        LaunchedEffect(Unit) {
            onEditorStateChanged(false, false, false, false)
            onFileEncodingChanged(Strings.editor_encoding_utf8.str())
        }
        return
    }

    // Pager 状态
    if (state.isSplitEditorEnabled) {
        EditorToolBarStateEffect(
            state = state,
            onEditorStateChanged = onEditorStateChanged
        )

        val tabLoadingMap = remember { mutableStateMapOf<String, Boolean>() }
        val showFilePath: (String) -> Unit = { path ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = path,
                    withDismissAction = true
                )
            }
        }

        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                EditorPane(
                    pane = EditorPaneId.PRIMARY,
                    state = state,
                    pluginManager = pluginManager,
                    hostCommandExecutor = hostCommandExecutor,
                    tabLoadingMap = tabLoadingMap,
                    onOpenFileTree = onOpenFileTree,
                    onShowFilePath = showFilePath,
                    onCursorPositionChanged = onCursorPositionChanged,
                    onFileEncodingChanged = onFileEncodingChanged,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                EditorPane(
                    pane = EditorPaneId.SECONDARY,
                    state = state,
                    pluginManager = pluginManager,
                    hostCommandExecutor = hostCommandExecutor,
                    tabLoadingMap = tabLoadingMap,
                    onOpenFileTree = onOpenFileTree,
                    onShowFilePath = showFilePath,
                    onCursorPositionChanged = onCursorPositionChanged,
                    onFileEncodingChanged = onFileEncodingChanged,
                    modifier = Modifier.weight(1f)
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = activeTabIndex.coerceAtLeast(0),
        pageCount = { tabs.size }
    )

    // 同步 pager 和 state
    // 使用 snapshotFlow 监听 settledPage（动画完成后的页面），避免动画过程中的中间状态触发循环
    LaunchedEffect(tabs.size, activeTabIndex) {
        val lastIndex = tabs.lastIndex
        if (lastIndex < 0) return@LaunchedEffect

        val safeActiveIndex = activeTabIndex.coerceIn(0, lastIndex)
        if (safeActiveIndex != activeTabIndex) {
            state.selectTab(safeActiveIndex)
            return@LaunchedEffect
        }

        if (pagerState.currentPage != safeActiveIndex) {
            pagerState.animateScrollToPage(safeActiveIndex)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                if (settledPage !in tabs.indices) return@collect
                if (settledPage != activeTabIndex) {
                    state.selectTab(settledPage)
                }
            }
    }

    EditorToolBarStateEffect(
        state = state,
        onEditorStateChanged = onEditorStateChanged
    )

    // Tab 上下文菜单状态
    var showTabMenu by remember { mutableStateOf(false) }
    var menuTargetIndex by remember { mutableIntStateOf(-1) }

    // 记录每个 tab 的加载状态，TabBar 据此为活跃 tab 播放扩散指示器动画。
    val tabLoadingMap = remember { mutableStateMapOf<String, Boolean>() }
    val activeTabLoading = tabs.getOrNull(activeTabIndex)?.id?.let { tabLoadingMap[it] } == true

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标签栏
            EditorTabBar(
                tabs = tabs,
                selectedIndex = activeTabIndex,
                showMenuForIndex = if (showTabMenu) menuTargetIndex else -1,
                pluginManager = pluginManager,
                hostCommandExecutor = hostCommandExecutor,
                isLoading = activeTabLoading,
                onTabClick = { index ->
                    if (index == activeTabIndex) {
                        // 单击已选中的标签页：显示菜单
                        menuTargetIndex = index
                        showTabMenu = true
                    } else {
                        // 单击未选中的标签页：切换到该标签页
                        state.selectTab(index)
                    }
                },
                onTabDoubleClick = { index ->
                    // 双击直接关闭标签页
                    state.requestCloseTab(index)
                },
                onTabLongPress = { index ->
                    // 长按显示文件完整路径
                    val tab = tabs.getOrNull(index)
                    if (tab != null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = tab.file.absolutePath,
                                withDismissAction = true
                            )
                        }
                    }
                },
                onMenuDismiss = { showTabMenu = false },
                onCloseCurrent = { index ->
                    showTabMenu = false
                    state.requestCloseTab(index)
                },
                onCloseOthers = { index ->
                    showTabMenu = false
                    state.closeOtherTabs(index)
                },
                onCloseAll = {
                    showTabMenu = false
                    state.closeAllTabs()
                }
            )

            // 编辑器 Pager 与搜索浮层拆开，避免搜索状态变化把整个 Pager 内容树带进重组。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false // 禁用手势滑动，避免与编辑器滑动冲突
                ) { page ->
                    val tab = tabs.getOrNull(page)
                    if (tab != null) {
                        key(tab.id) {
                            EditorPage(
                                state = state,
                                tab = tab,
                                onCursorPositionChanged = onCursorPositionChanged,
                                onFileEncodingChanged = onFileEncodingChanged,
                                onLoadingStateChanged = { loading -> tabLoadingMap[tab.id] = loading },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                EditorSearchOverlay(
                    state = state,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Snackbar Host（用于显示文件路径）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorPane(
    pane: EditorPaneId,
    state: EditorContainerState,
    pluginManager: com.wuxianggujun.tinaide.plugin.PluginManager,
    hostCommandExecutor: com.wuxianggujun.tinaide.core.commands.HostCommandExecutor?,
    tabLoadingMap: MutableMap<String, Boolean>,
    onOpenFileTree: () -> Unit,
    onShowFilePath: (String) -> Unit,
    onCursorPositionChanged: (line: Int, column: Int) -> Unit = { _, _ -> },
    onFileEncodingChanged: (encoding: String) -> Unit = { _ -> },
    modifier: Modifier = Modifier
) {
    val allTabs = state.tabs
    val paneTabs = state.getTabsForPane(pane)
    val activeGlobalIndex = state.getActiveIndexForPane(pane)
    val activeTabId = allTabs.getOrNull(activeGlobalIndex)?.id
    val selectedLocalIndex = paneTabs.indexOfFirst { it.id == activeTabId }
    val pagerState = rememberPagerState(
        initialPage = selectedLocalIndex.coerceAtLeast(0),
        pageCount = { paneTabs.size }
    )

    var showTabMenu by remember { mutableStateOf(false) }
    var menuTargetLocalIndex by remember { mutableIntStateOf(-1) }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        if (paneTabs.isEmpty()) {
            EmptyEditorView(
                onOpenFileTree = {
                    state.focusEditorPane(pane)
                    onOpenFileTree()
                },
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        val safeSelectedLocalIndex = selectedLocalIndex.coerceIn(0, paneTabs.lastIndex)
        val activeTabLoading = paneTabs.getOrNull(safeSelectedLocalIndex)
            ?.id
            ?.let { tabLoadingMap[it] } == true

        EditorTabBar(
            tabs = paneTabs,
            selectedIndex = safeSelectedLocalIndex,
            showMenuForIndex = if (showTabMenu) menuTargetLocalIndex else -1,
            pluginManager = pluginManager,
            hostCommandExecutor = hostCommandExecutor,
            isLoading = activeTabLoading,
            onTabClick = tabClick@ { localIndex ->
                val tab = paneTabs.getOrNull(localIndex) ?: return@tabClick
                val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                if (localIndex == safeSelectedLocalIndex) {
                    state.focusEditorPane(pane)
                    menuTargetLocalIndex = localIndex
                    showTabMenu = true
                } else if (globalIndex >= 0) {
                    state.selectTabInPane(pane, globalIndex)
                }
            },
            onTabDoubleClick = tabDoubleClick@ { localIndex ->
                val tab = paneTabs.getOrNull(localIndex) ?: return@tabDoubleClick
                val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                if (globalIndex >= 0) {
                    state.requestCloseTab(globalIndex)
                }
            },
            onTabLongPress = { localIndex ->
                paneTabs.getOrNull(localIndex)?.let { tab ->
                    onShowFilePath(tab.file.absolutePath)
                }
            },
            onMenuDismiss = { showTabMenu = false },
            onCloseCurrent = closeCurrent@ { localIndex ->
                showTabMenu = false
                val tab = paneTabs.getOrNull(localIndex) ?: return@closeCurrent
                val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                if (globalIndex >= 0) {
                    state.requestCloseTab(globalIndex)
                }
            },
            onCloseOthers = closeOthers@ { localIndex ->
                showTabMenu = false
                val tab = paneTabs.getOrNull(localIndex) ?: return@closeOthers
                val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                if (globalIndex >= 0) {
                    state.closeOtherTabs(globalIndex)
                }
            },
            onCloseAll = {
                showTabMenu = false
                state.closeAllTabs()
            }
        )

        LaunchedEffect(paneTabs.map { it.id }, safeSelectedLocalIndex) {
            if (pagerState.currentPage != safeSelectedLocalIndex) {
                pagerState.animateScrollToPage(safeSelectedLocalIndex)
            }
        }

        LaunchedEffect(pagerState, paneTabs.map { it.id }) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settledPage ->
                    val tab = paneTabs.getOrNull(settledPage) ?: return@collect
                    val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                    if (globalIndex >= 0 && globalIndex != state.activeTabIndex) {
                        state.selectTabInPane(pane, globalIndex)
                    }
                }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false
            ) { page ->
                val tab = paneTabs.getOrNull(page)
                if (tab != null) {
                    key(tab.id) {
                        EditorPage(
                            state = state,
                            tab = tab,
                            onActivate = {
                                val globalIndex = allTabs.indexOfFirst { it.id == tab.id }
                                if (
                                    globalIndex >= 0 &&
                                    (state.focusedPane != pane || state.activeTabIndex != globalIndex)
                                ) {
                                    state.selectTabInPane(pane, globalIndex)
                                }
                            },
                            onCursorPositionChanged = onCursorPositionChanged,
                            onFileEncodingChanged = onFileEncodingChanged,
                            onLoadingStateChanged = { loading -> tabLoadingMap[tab.id] = loading },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            if (state.focusedPane == pane) {
                EditorSearchOverlay(
                    state = state,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun EditorToolBarStateEffect(
    state: EditorContainerState,
    onEditorStateChanged: (hasFiles: Boolean, canUndo: Boolean, canRedo: Boolean, isDirty: Boolean) -> Unit
) {
    val latestOnEditorStateChanged by rememberUpdatedState(onEditorStateChanged)

    LaunchedEffect(state) {
        snapshotFlow { state.getActiveEditorToolBarState() }
            .distinctUntilChanged()
            .collect { snapshot ->
                if (BuildConfig.DEBUG) {
                    Timber.tag("EditorContainer").d(
                        "onEditorStateChanged callback: hasFiles=%s, canUndo=%s, canRedo=%s, isDirty=%s",
                        snapshot.hasFiles,
                        snapshot.canUndo,
                        snapshot.canRedo,
                        snapshot.isDirty
                    )
                }
                latestOnEditorStateChanged(
                    snapshot.hasFiles,
                    snapshot.canUndo,
                    snapshot.canRedo,
                    snapshot.isDirty
                )
            }
    }
}

@Composable
private fun EditorSearchOverlay(
    state: EditorContainerState,
    modifier: Modifier = Modifier
) {
    FloatingSearchBarContainer(
        searchState = state.currentSearchState,
        onQueryChange = { state.updateSearchQuery(it) },
        onSearch = { state.performSearch() },
        onToggleCaseSensitive = { state.toggleSearchCaseSensitive() },
        onToggleRegex = { state.toggleSearchUseRegex() },
        onPrevious = { state.findPrevious() },
        onNext = { state.findNext() },
        onClose = { state.hideSearch() },
        modifier = modifier
    ) {}
}

/**
 * 编辑器页面（根据内容类型显示不同的视图）
 */
@Composable
private fun EditorPage(
    state: EditorContainerState,
    tab: EditorTabState,
    onActivate: () -> Unit = {},
    onCursorPositionChanged: (line: Int, column: Int) -> Unit = { _, _ -> },
    onFileEncodingChanged: (encoding: String) -> Unit = { _ -> },
    onLoadingStateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val latestOnActivate by rememberUpdatedState(onActivate)
    val activatingModifier = modifier.pointerInput(tab.id) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { change -> change.pressed && !change.previousPressed }) {
                    latestOnActivate()
                }
            }
        }
    }

    when (tab.contentType) {
        ContentType.CODE,
        ContentType.JSON -> {
            TinaCodeEditorPage(
                state = state,
                tab = tab,
                onCursorPositionChanged = { line, column ->
                    latestOnActivate()
                    onCursorPositionChanged(line, column)
                },
                onFileEncodingChanged = onFileEncodingChanged,
                onLoadingStateChanged = onLoadingStateChanged,
                modifier = activatingModifier
            )
        }
        ContentType.LARGE_TEXT -> {
            LaunchedEffect(tab.id) {
                onFileEncodingChanged(FileCharsetDetector.detect(tab.file).name())
            }
            LargeTextViewerScreen(
                filePath = tab.file.absolutePath,
                onOpenAsEditor = { state.openFileWithType(tab.file, ContentType.CODE) },
                onOpenAsHex = { state.openFileWithType(tab.file, ContentType.HEX) },
                modifier = activatingModifier
            )
        }
        ContentType.IMAGE -> {
            // 图片文件显示为二进制
            LaunchedEffect(tab.id) {
                onFileEncodingChanged(Strings.editor_encoding_binary.str())
            }
            ImagePreviewScreen(
                filePath = tab.file.absolutePath,
                modifier = activatingModifier
            )
        }
        ContentType.HEX -> {
            // 十六进制查看器显示为二进制
            LaunchedEffect(tab.id) {
                onFileEncodingChanged(Strings.editor_encoding_binary.str())
            }
            HexViewerScreen(
                filePath = tab.file.absolutePath,
                tabId = tab.id,
                onRegisterSearch = { searchFn, goToOffsetFn ->
                    state.bindHexViewerSearchCallback(
                        tabId = tab.id,
                        search = searchFn,
                        goToOffset = goToOffsetFn
                    )
                },
                onUnregisterSearch = {
                    state.unbindHexViewerSearchCallback(tab.id)
                },
                modifier = activatingModifier
            )
        }
    }
}

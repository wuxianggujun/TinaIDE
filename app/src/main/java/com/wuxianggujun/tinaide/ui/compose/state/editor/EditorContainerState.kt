package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.config.EditorSettings
import com.wuxianggujun.tinaide.core.config.LspAssistSettings
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.config.ThemeManager
import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItem
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItemKind
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.editorview.EditorRenderPerformanceSnapshot
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.lsp.WorkspaceSymbolItem
import com.wuxianggujun.tinaide.core.packages.PackageDependencyEvents
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.DetachedEditorSnapshot
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.file.IFileWatchService
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.script.api.EditorSelectionPayload
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.search.SearchOptions
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.LspEditorManager
import com.wuxianggujun.tinaide.core.editorlsp.SemanticTokensRequestResult
import com.wuxianggujun.tinaide.core.editorlsp.resolveEditorLanguageId
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorToolBarState
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.flow.Flow
import org.eclipse.lsp4j.WorkspaceEdit
import org.koin.compose.koinInject
import timber.log.Timber

private const val DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO = 0.5f
private const val MIN_SPLIT_EDITOR_PRIMARY_RATIO = 0.25f
private const val MAX_SPLIT_EDITOR_PRIMARY_RATIO = 0.75f

private fun coerceSplitEditorPrimaryRatio(ratio: Float): Float = if (ratio.isFinite()) {
    ratio.coerceIn(MIN_SPLIT_EDITOR_PRIMARY_RATIO, MAX_SPLIT_EDITOR_PRIMARY_RATIO)
} else {
    DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO
}

/**
 * 编辑器容器状态管理
 *
 * 职责：
 * - 作为协调器，组合各子管理器
 * - 提供统一的公共 API
 *
 * 子管理器：
 * - EditorTabManager: 标签页管理
 * - LspEditorManager: LSP 编辑器生命周期
 * - SearchStateManager: 搜索状态管理
 */
class EditorContainerState(
    private val context: android.content.Context,
    private val editorManager: IEditorManager,
    private val snippetManager: PluginSnippetManager,
    private val pluginThemeRegistry: PluginEditorThemeRegistry,
    private val projectSymbolIndexServiceProvider: () -> ProjectSymbolIndexService?,
    private val projectRootPathProvider: () -> String?,
    private val fileWatchService: IFileWatchService? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    private val lspPluginManager: LspPluginManager? = null,
) {
    internal companion object {
        const val CODE_EDITOR_RUNTIME_CACHE_LIMIT = 16
    }

    data class NavigationHistoryEntry(
        val filePath: String,
        val line: Int,
        val column: Int
    )

    data class ActiveEditableEditorSnapshot(
        val file: File,
        val text: String
    )

    data class ActivePluginEditorContext(
        val tabId: String,
        val file: File,
        val languageId: String
    )

    data class PluginLspDependencyAlert(
        val sequence: Long,
        val pluginId: String,
        val pluginName: String,
        val message: String
    )

    data class ActiveSaveTarget(
        val tabId: String,
        val file: File
    )

    data class ActiveBookmarkCursorContext(
        val file: File,
        val line: Int
    )

    data class ActiveBookmarkTarget(
        val file: File,
        val line: Int
    )

    enum class ActiveEditorCommandResult {
        SUCCESS,
        NO_OPEN_FILE,
        UNSUPPORTED_EDITOR
    }

    enum class EditorPaneId {
        PRIMARY,
        SECONDARY
    }

    enum class SplitEditorLayout {
        HORIZONTAL,
        VERTICAL
    }

    data class SplitEditorStateSnapshot(
        val isEnabled: Boolean = false,
        val focusedPane: EditorPaneId = EditorPaneId.PRIMARY,
        val layout: SplitEditorLayout = SplitEditorLayout.HORIZONTAL,
        val primaryRatio: Float = DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO,
        val tabPaneAssignments: Map<String, EditorPaneId> = emptyMap(),
        val mirroredFilePathsByPane: Map<EditorPaneId, Set<String>> = emptyMap(),
        val activeFilePathByPane: Map<EditorPaneId, String> = emptyMap()
    ) {
        fun normalized(): SplitEditorStateSnapshot {
            val sanitizedAssignments = linkedMapOf<String, EditorPaneId>()
            tabPaneAssignments.forEach { (path, pane) ->
                if (path.isNotBlank()) sanitizedAssignments[path] = pane
            }

            val sanitizedMirrors = linkedMapOf<EditorPaneId, Set<String>>()
            mirroredFilePathsByPane.forEach { (pane, paths) ->
                val sanitizedPaths = paths.filterTo(linkedSetOf()) { it.isNotBlank() }
                if (sanitizedPaths.isNotEmpty()) sanitizedMirrors[pane] = sanitizedPaths
            }

            val sanitizedActivePaths = linkedMapOf<EditorPaneId, String>()
            activeFilePathByPane.forEach { (pane, path) ->
                if (path.isNotBlank()) sanitizedActivePaths[pane] = path
            }

            return copy(
                primaryRatio = coerceSplitEditorPrimaryRatio(primaryRatio),
                tabPaneAssignments = sanitizedAssignments,
                mirroredFilePathsByPane = sanitizedMirrors,
                activeFilePathByPane = sanitizedActivePaths
            )
        }
    }

    sealed interface ActiveEditableEditorSnapshotResult {
        object NoOpenFile : ActiveEditableEditorSnapshotResult
        object UnsupportedEditor : ActiveEditableEditorSnapshotResult
        data class Success(val snapshot: ActiveEditableEditorSnapshot) : ActiveEditableEditorSnapshotResult
    }

    sealed interface ReplaceAllInActiveEditorResult {
        object NoOpenFile : ReplaceAllInActiveEditorResult
        object UnsupportedEditor : ReplaceAllInActiveEditorResult
        object NoMatches : ReplaceAllInActiveEditorResult
        data class Success(val count: Int) : ReplaceAllInActiveEditorResult
    }

    sealed interface ActiveBookmarkCursorContextResult {
        object NoOpenFile : ActiveBookmarkCursorContextResult
        object UnsupportedEditor : ActiveBookmarkCursorContextResult
        data class Success(val context: ActiveBookmarkCursorContext) : ActiveBookmarkCursorContextResult
    }

    sealed interface ActiveBookmarkTargetResult {
        object NoOpenFile : ActiveBookmarkTargetResult
        object UnsupportedEditor : ActiveBookmarkTargetResult
        object NoBookmarkableLine : ActiveBookmarkTargetResult
        data class Success(val target: ActiveBookmarkTarget) : ActiveBookmarkTargetResult
    }

    sealed interface ActiveDocumentSymbolsTargetResult {
        object NoOpenFile : ActiveDocumentSymbolsTargetResult
        object Unavailable : ActiveDocumentSymbolsTargetResult
        data class Available(val tabId: String) : ActiveDocumentSymbolsTargetResult
    }

    sealed interface ActiveWorkspaceSymbolsTargetResult {
        object NoOpenFile : ActiveWorkspaceSymbolsTargetResult
        object Unavailable : ActiveWorkspaceSymbolsTargetResult
        data class Available(val tabId: String) : ActiveWorkspaceSymbolsTargetResult
    }

    sealed interface ActiveSaveTargetResult {
        object NoOpenFile : ActiveSaveTargetResult
        data class Available(val target: ActiveSaveTarget) : ActiveSaveTargetResult
    }

    data class TabToolbarState(
        val isDirty: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val charsetName: String
    )

    data class ActiveEditorSessionAlertState(
        val tabId: String,
        val file: File,
        val hasExternalModification: Boolean,
        val lastError: String?
    )

    private sealed interface ActiveEditableEditorBindingResult {
        object NoOpenFile : ActiveEditableEditorBindingResult
        object UnsupportedEditor : ActiveEditableEditorBindingResult
        data class Available(
            val file: File,
            val callback: CodeEditorCallback
        ) : ActiveEditableEditorBindingResult
    }

    /**
     * 记录最近一次已处理的依赖变更 revision（实例字段，避免多实例间的静态变量竞争）。
     */
    private var lastHandledDependencyRevision: Long = 0L

    // ========== 子管理器 ==========

    private val lspEditorManager = LspEditorManager(
        fileWatchService = fileWatchService,
        linuxEnvironmentProvider = linuxEnvironmentProvider,
    )
    private val searchStateManager = SearchStateManager()
    private val tabManager = EditorTabManager(context, editorManager)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val documentSessionCoordinator = EditorDocumentSessionCoordinator(
        editorManager = editorManager,
        activeTabProvider = { getActiveTab() },
    )
    private val splitPaneState = EditorSplitPaneState()
    // 延迟读取 registry，避免与 codeCallbackRegistry 初始化环。
    private var attachedCodeEditorTabIdsProvider: () -> Set<String> = { emptySet() }
    private val codeRuntimeCache = EditorCodeRuntimeCache(
        context = context,
        cacheLimit = CODE_EDITOR_RUNTIME_CACHE_LIMIT,
        projectRootPathProvider = ::getEditorProjectRootPathOrNull,
        activeTabIdProvider = ::getActiveTabId,
        isSplitEditorEnabledProvider = { isSplitEditorEnabled },
        splitPaneState = splitPaneState,
        attachedCodeEditorTabIdsProvider = { attachedCodeEditorTabIdsProvider() },
        openTabsProvider = { tabs },
    )
    private val codeCallbackRegistry = EditorCodeCallbackRegistry(
        context = context,
        searchStateManager = searchStateManager,
        codeRuntimeCache = codeRuntimeCache,
        resolveEditorColorScheme = ::resolveEditorColorScheme,
    ).also { registry ->
        attachedCodeEditorTabIdsProvider = { registry.keys() }
    }
    private val saveAllNotificationTracker = EditorSaveAllNotificationTracker()

    private val peekDefinitionState = EditorPeekDefinitionState()
    private val lspUiState = EditorLspUiState()
    private val diagnosticsState = EditorDiagnosticsState(
        filePathNormalizer = ::fileToNormalizedPath,
        fileUriNormalizer = ::fileUriToNormalizedPath,
    )
    private val lspNavigationFacade = EditorLspNavigationFacade(
        lspEditorManager = lspEditorManager,
        lspUiState = lspUiState,
        activeTabProvider = { getActiveTab() },
        cursorProvider = { getCursorPositionInActiveTab() },
        selectionProvider = { getSelectionSnapshotInActiveTab() },
        activeTabTextProvider = { readActiveTabText() },
    )
    val pluginLspDependencyAlert: PluginLspDependencyAlert?
        get() = lspUiState.pluginDependencyAlert

    private val tabLifecycleCoordinator = EditorTabLifecycleCoordinator(
        splitPaneState = splitPaneState,
        isCodeEditableType = ::isCodeEditableType,
        releaseLspForTab = ::releaseTinaLspForTab,
        clearCodeEditorRuntime = codeRuntimeCache::remove,
        removeCodeEditorCallback = codeCallbackRegistry::remove,
        cleanupSearchState = searchStateManager::cleanupForTab,
        dismissPeekDefinitionPanel = ::dismissPeekDefinitionPanel,
        normalizeEditorPaneState = { normalizeEditorPaneState() },
    )
    private val navigationHistoryManager = EditorNavigationHistoryManager(
        currentLocationProvider = ::snapshotActiveNavigationLocationOrNull,
        openLocation = { target ->
            val targetFile = File(target.filePath)
            if (!targetFile.exists() || targetFile.isDirectory) {
                false
            } else {
                openFileAndGoToPosition(targetFile, target.line, target.column, recordHistory = false)
            }
        }
    )
    private val splitSessionCoordinator = EditorSplitSessionCoordinator(
        storage = SplitEditorSessionStorage(context),
        projectPathProvider = ::resolveSplitEditorSessionProjectPath,
        hasTabs = { tabs.isNotEmpty() },
        createSnapshot = ::createSplitEditorStateSnapshot,
        restoreSnapshot = ::restoreSplitEditorStateSnapshot,
        normalizePaneState = { normalizeEditorPaneState() },
        clearInMemory = ::clearSplitEditorStateInMemory,
    )
    private val fileMutationCoordinator = EditorFileMutationCoordinator(
        editorManager = editorManager,
        tabManager = tabManager,
        tabs = tabManager.tabs,
        navigationBackStack = navigationHistoryManager.backStack,
        navigationForwardStack = navigationHistoryManager.forwardStack,
        splitPaneState = splitPaneState,
        codeRuntimeCache = codeRuntimeCache,
        codeEditorCallbacks = codeCallbackRegistry.mutableCallbacks,
        lspUiState = lspUiState,
        diagnosticsState = diagnosticsState,
        isCodeEditableType = ::isCodeEditableType,
        requestCloseTabAt = ::requestCloseTab,
        releaseTinaLspForTab = ::releaseTinaLspForTab,
        normalizeEditorPaneState = { normalizeEditorPaneState() },
        persistSplitEditorState = ::persistSplitEditorState,
    )

    init {
        lspPluginManager?.let { lspEditorManager.setLspPluginManager(it) }

        lspEditorManager.onDiagnosticsChanged = diagnosticsState::handleDiagnosticsChanged

        lspEditorManager.onLspStatusChanged = lspUiState::handleStatusChanged

        lspEditorManager.onPluginLspDependencyNotReady = lspUiState::handlePluginDependencyNotReady

        // 设置标签关闭回调，清理状态
        tabManager.onTabClosed = { tabId, contentType ->
            tabLifecycleCoordinator.handleManagerTabClosed(tabId, contentType)
        }
    }

    // ========== 标签页状态代理 ==========

    /**
     * 暴露 SnapshotStateList 以便 Compose 能正确追踪列表元素的变化
     */
    val tabs: SnapshotStateList<EditorTabState> get() = tabManager.tabs
    val activeTabIndex: Int get() = tabManager.activeTabIndex
    val pendingCloseTab: EditorTabState? get() = tabManager.pendingCloseTab
    val lastOpenError: String? get() = tabManager.lastOpenError

    fun consumePluginLspDependencyAlert(): PluginLspDependencyAlert? {
        return lspUiState.consumePluginDependencyAlert()
    }

    var isSplitEditorEnabled by mutableStateOf(false)
        private set

    var focusedPane by mutableStateOf(EditorPaneId.PRIMARY)
        private set

    var splitEditorPrimaryRatio by mutableFloatStateOf(DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO)
        private set

    var splitEditorLayout by mutableStateOf(SplitEditorLayout.HORIZONTAL)
        private set

    internal val peekDefinitionPanelState: PeekDefinitionPanelState?
        get() = peekDefinitionState.panelState

    private fun resolveProjectRootPath(): String? = projectRootPathProvider()
        ?.takeIf { it.isNotBlank() }

    private fun resolveSplitEditorSessionProjectPath(): String? = resolveProjectRootPath()?.let(::normalizeOpenTabLookupPath)

    internal fun getEditorProjectRootPathOrNull(): String? = resolveProjectRootPath()

    internal fun showPeekDefinitionLoading(ownerTabId: String, title: String) {
        peekDefinitionState.showLoading(
            ownerTabId = ownerTabId,
            title = title
        )
    }

    internal fun showPeekDefinitionResults(
        ownerTabId: String,
        title: String,
        locations: List<LocationItem>
    ) {
        peekDefinitionState.showResults(
            ownerTabId = ownerTabId,
            title = title,
            locations = locations
        )
    }

    internal fun dismissPeekDefinitionPanel(ownerTabId: String? = null) {
        peekDefinitionState.dismiss(ownerTabId)
    }

    internal fun getBookmarksProjectRootPathOrNull(): String? = resolveProjectRootPath()

    // ========== 诊断回调 ==========

    var onLspDiagnosticsChanged: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null
        set(value) {
            field = value
            diagnosticsState.onDiagnosticsChanged = value
        }

    internal fun getDiagnosticsFlow(file: File): Flow<List<Diagnostic>> =
        diagnosticsState.getDiagnosticsFlow(file)

    // ========== LSP 导航回调 ==========

    /**
     * LSP 导航请求回调
     *
     * 当用户在上下文菜单中点击导航操作时触发。
     * 参数：tabId, navigationType（"definition"/"references"/"typeDefinition"/"implementation"/"callHierarchyIncoming"/"switchHeaderSource"）
     */
    var onLspNavigationRequested: ((tabId: String, navigationType: String) -> Unit)?
        get() = lspNavigationFacade.onLspNavigationRequested
        set(value) {
            lspNavigationFacade.onLspNavigationRequested = value
        }

    /**
     * LSP Code Actions 请求回调
     *
     * 参数：tabId, startLine, startColumn, endLine, endColumn
     */
    var onLspCodeActionsRequested: ((tabId: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) -> Unit)?
        get() = lspNavigationFacade.onLspCodeActionsRequested
        set(value) {
            lspNavigationFacade.onLspCodeActionsRequested = value
        }

    /**
     * LSP Rename 请求回调
     *
     * 参数：tabId, line, column, currentName
     */
    var onLspRenameRequested: ((tabId: String, line: Int, column: Int, currentName: String) -> Unit)?
        get() = lspNavigationFacade.onLspRenameRequested
        set(value) {
            lspNavigationFacade.onLspRenameRequested = value
        }

    internal fun supportsBasicLspNavigation(file: File): Boolean =
        lspNavigationFacade.supportsBasicLspNavigation(file)

    internal fun supportsAdvancedLspNavigation(file: File): Boolean =
        lspNavigationFacade.supportsAdvancedLspNavigation(file)

    internal fun supportsActiveCallHierarchyIncoming(): Boolean =
        lspNavigationFacade.supportsActiveCallHierarchyIncoming()

    internal fun supportsLspRefactorActions(file: File): Boolean =
        lspNavigationFacade.supportsLspRefactorActions(file)

    internal fun supportsHeaderSourceSwitch(file: File): Boolean =
        lspNavigationFacade.supportsHeaderSourceSwitch(file)

    internal fun requestActiveLspNavigation(navigationType: String): Boolean =
        lspNavigationFacade.requestActiveLspNavigation(navigationType)

    internal fun requestActiveLspCodeActions(): Boolean =
        lspNavigationFacade.requestActiveLspCodeActions()

    internal fun requestActiveLspRename(): Boolean =
        lspNavigationFacade.requestActiveLspRename()

    internal fun getLspStatus(tabId: String): EditorStatus = lspUiState.getStatus(tabId)

    internal fun getLspStatusFlow(tabId: String): Flow<EditorStatus> =
        lspUiState.getStatusFlow(tabId)

    internal fun getActiveLspStatus(): EditorStatus {
        val tab = getActiveTab() ?: return EditorStatus.NoLsp
        return getLspStatus(tab.id)
    }

    internal fun getActiveDocumentSymbolsTargetResult(): ActiveDocumentSymbolsTargetResult {
        if (getActiveTab() == null) return ActiveDocumentSymbolsTargetResult.NoOpenFile
        val tabId = getActiveLspTabIdOrNull()
        return if (tabId != null) {
            ActiveDocumentSymbolsTargetResult.Available(tabId)
        } else {
            ActiveDocumentSymbolsTargetResult.Unavailable
        }
    }

    internal fun getActiveWorkspaceSymbolsTargetResult(): ActiveWorkspaceSymbolsTargetResult {
        if (getActiveTab() == null) return ActiveWorkspaceSymbolsTargetResult.NoOpenFile
        val tabId = getActiveLspTabIdOrNull()
        return if (tabId != null) {
            ActiveWorkspaceSymbolsTargetResult.Available(tabId)
        } else {
            ActiveWorkspaceSymbolsTargetResult.Unavailable
        }
    }

    internal fun getActiveSaveTargetResult(): ActiveSaveTargetResult {
        val activeTab = getActiveTab() ?: return ActiveSaveTargetResult.NoOpenFile
        return ActiveSaveTargetResult.Available(
            ActiveSaveTarget(
                tabId = activeTab.id,
                file = activeTab.file
            )
        )
    }

    internal fun getBottomPanelEditorStatus(isDebugSessionActive: Boolean): EditorStatus {
        if (isDebugSessionActive) return EditorStatus.Busy
        return getActiveLspStatus()
    }

    private fun getActiveLspTabIdOrNull(): String? = lspNavigationFacade.getActiveLspTabIdOrNull()

    // ========== 搜索状态代理 ==========

    val currentSearchState get() = searchStateManager.currentSearchState

    fun showSearch() = searchStateManager.showSearch()

    fun hideSearch() = searchStateManager.hideSearch(getActiveTabId())

    fun updateSearchQuery(query: String) {
        searchStateManager.updateSearchQuery(query)
        if (query.isNotEmpty()) {
            performSearch()
        }
    }

    fun toggleSearchCaseSensitive() {
        searchStateManager.toggleSearchCaseSensitive()
        performSearch()
    }

    fun toggleSearchUseRegex() {
        searchStateManager.toggleSearchUseRegex()
        performSearch()
    }

    fun performSearch() {
        val tab = getActiveTab() ?: return
        when (tab.contentType) {
            ContentType.CODE,
            ContentType.JSON -> {
                if (!searchStateManager.hasCodeViewerCallback(tab.id)) return
                searchStateManager.searchInCodeViewer(tab.id)
            }
            ContentType.HEX -> searchStateManager.searchInHexViewer(tab.id)
            ContentType.LARGE_TEXT -> {} // 大文件查看器暂不支持搜索
            ContentType.IMAGE -> {} // 图片不支持搜索
        }
    }

    fun findNext() {
        searchStateManager.findNext()
        goToCurrentMatch()
    }

    fun findPrevious() {
        searchStateManager.findPrevious()
        goToCurrentMatch()
    }

    private fun goToCurrentMatch() {
        val tab = getActiveTab() ?: return
        when (tab.contentType) {
            ContentType.CODE,
            ContentType.JSON -> {
                if (!searchStateManager.hasCodeViewerCallback(tab.id)) return
                searchStateManager.goToMatchInCodeViewer(tab.id)
            }
            ContentType.HEX -> searchStateManager.goToMatchInHexViewer(tab.id)
            ContentType.LARGE_TEXT -> {}
            ContentType.IMAGE -> {}
        }
    }

    // ========== 搜索回调注册 ==========

    internal fun bindCodeViewerSearchCallback(
        tabId: String,
        search: (String, SearchOptions) -> List<CodeSearchResult>,
        goToMatch: (CodeSearchResult) -> Unit
    ) {
        searchStateManager.registerCodeViewerCallback(
            tabId,
            SearchStateManager.CodeViewerCallback(
                search = search,
                goToMatch = goToMatch
            )
        )
    }

    internal fun unbindCodeViewerSearchCallback(tabId: String) {
        searchStateManager.unregisterCodeViewerCallback(tabId)
    }

    internal fun bindHexViewerSearchCallback(
        tabId: String,
        search: (String) -> List<Long>,
        goToOffset: (Long) -> Unit
    ) {
        searchStateManager.registerHexViewerCallback(
            tabId,
            SearchStateManager.HexViewerCallback(
                search = search,
                goToOffset = goToOffset
            )
        )
    }

    internal fun unbindHexViewerSearchCallback(tabId: String) {
        searchStateManager.unregisterHexViewerCallback(tabId)
    }

    internal fun bindCodeEditorCallbacks(
        tabId: String,
        registrationId: Any,
        search: (String, SearchOptions) -> List<CodeSearchResult>,
        goToMatch: (CodeSearchResult) -> Unit,
        editorCallback: CodeEditorCallback
    ) {
        codeCallbackRegistry.bindCodeEditorCallbacks(
            tabId = tabId,
            registrationId = registrationId,
            search = search,
            goToMatch = goToMatch,
            editorCallback = editorCallback,
        )
    }

    internal fun unbindCodeEditorCallbacks(tabId: String, registrationId: Any) {
        codeCallbackRegistry.unbindCodeEditorCallbacks(tabId, registrationId)
    }

    internal fun getOrCreateCodeEditorRuntime(tab: EditorTabState): CodeEditorRuntime =
        codeRuntimeCache.getOrCreate(tab)

    internal fun getOrCreateSyntaxHighlighter(tab: EditorTabState): TreeSitterHighlighter? =
        codeRuntimeCache.getOrCreateSyntaxHighlighter(tab)

    internal fun getOrCreateFoldingProvider(tab: EditorTabState): TreeSitterFoldingProvider? =
        codeRuntimeCache.getOrCreateFoldingProvider(tab)

    internal fun isCodeEditorRuntimeLoaded(tabId: String): Boolean =
        codeRuntimeCache.isLoaded(tabId)

    internal fun markCodeEditorRuntimeLoaded(tabId: String) {
        codeRuntimeCache.markLoaded(tabId)
    }

    internal fun registerCodeEditorCallback(tabId: String, callback: CodeEditorCallback) {
        codeCallbackRegistry.register(tabId, callback)
    }

    internal fun unregisterCodeEditorCallback(tabId: String) {
        codeCallbackRegistry.unregister(tabId)
    }

    internal fun activeTabSupportsEditorPerformancePanel(): Boolean {
        val tab = getActiveTab() ?: return false
        return hasAttachedCodeEditor(tab.id, tab.contentType)
    }

    internal fun getActiveEditableEditorCommandAvailability(): ActiveEditorCommandResult = when (resolveActiveEditableEditorBindingResult()) {
        ActiveEditableEditorBindingResult.NoOpenFile -> ActiveEditorCommandResult.NO_OPEN_FILE
        ActiveEditableEditorBindingResult.UnsupportedEditor -> ActiveEditorCommandResult.UNSUPPORTED_EDITOR
        is ActiveEditableEditorBindingResult.Available -> ActiveEditorCommandResult.SUCCESS
    }

    internal fun getActiveEditorToolBarState(): EditorToolBarState {
        val activeTab = getActiveTab()
        return EditorToolBarState(
            hasFiles = tabs.isNotEmpty(),
            canUndo = activeTab?.canUndo ?: false,
            canRedo = activeTab?.canRedo ?: false,
            isDirty = activeTab?.isDirty ?: false
        )
    }

    internal fun isActiveTabDirty(): Boolean = getActiveTab()?.isDirty ?: false

    private fun getActiveCodeEditorCallback(): CodeEditorCallback? {
        val tab = getActiveTab() ?: return null
        if (!hasAttachedCodeEditor(tab.id, tab.contentType)) return null
        return codeCallbackRegistry.get(tab.id)
    }

    fun goToPositionInActiveTab(line: Int, column: Int): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.goToPosition(line, column)
    }

    internal fun goToPositionInActiveEditableEditor(line: Int, column: Int): Boolean {
        val activeEditor = resolveActiveEditableEditorBindingResult()
            as? ActiveEditableEditorBindingResult.Available
            ?: return false
        return activeEditor.callback.goToPosition(line, column)
    }

    internal fun requestGoToPositionInActiveEditableEditor(
        line: Int,
        column: Int
    ): ActiveEditorCommandResult = when (val activeEditor = resolveActiveEditableEditorBindingResult()) {
        ActiveEditableEditorBindingResult.NoOpenFile -> ActiveEditorCommandResult.NO_OPEN_FILE
        ActiveEditableEditorBindingResult.UnsupportedEditor -> ActiveEditorCommandResult.UNSUPPORTED_EDITOR
        is ActiveEditableEditorBindingResult.Available -> {
            val source = snapshotActiveNavigationLocationOrNull()
            if (activeEditor.callback.goToPosition(line, column)) {
                navigationHistoryManager.recordTransition(
                    source = source,
                    target = navigationHistoryManager.entryOf(activeEditor.file, line, column)
                )
                ActiveEditorCommandResult.SUCCESS
            } else {
                ActiveEditorCommandResult.UNSUPPORTED_EDITOR
            }
        }
    }

    internal fun requestToggleLineCommentInActiveEditor(
        commentTokenResolver: (File) -> String
    ): ActiveEditorCommandResult = when (val activeEditor = resolveActiveEditableEditorBindingResult()) {
        ActiveEditableEditorBindingResult.NoOpenFile -> ActiveEditorCommandResult.NO_OPEN_FILE
        ActiveEditableEditorBindingResult.UnsupportedEditor -> ActiveEditorCommandResult.UNSUPPORTED_EDITOR
        is ActiveEditableEditorBindingResult.Available -> {
            val commentToken = commentTokenResolver(activeEditor.file)
            if (activeEditor.callback.toggleLineComment(commentToken)) {
                ActiveEditorCommandResult.SUCCESS
            } else {
                ActiveEditorCommandResult.UNSUPPORTED_EDITOR
            }
        }
    }

    internal fun requestNavigateToPositionInActiveTab(
        line: Int,
        column: Int,
        maxAttempts: Int = 100,
        retryDelayMillis: Long = 50L
    ): Boolean {
        val activeTab = getActiveTab() ?: return false
        if (!isCodeEditableType(activeTab.contentType)) return false
        if (goToPositionInActiveTab(line, column)) return true
        if (maxAttempts <= 1) return false

        fun retryNavigate(remainingAttempts: Int) {
            if (goToPositionInActiveTab(line, column) || remainingAttempts <= 0) return
            mainHandler.postDelayed(
                { retryNavigate(remainingAttempts - 1) },
                retryDelayMillis
            )
        }

        mainHandler.postDelayed(
            { retryNavigate(maxAttempts - 1) },
            retryDelayMillis
        )
        return true
    }

    internal fun canNavigateBack(): Boolean = navigationHistoryManager.canNavigateBack()

    internal fun canNavigateForward(): Boolean = navigationHistoryManager.canNavigateForward()

    internal fun navigateBack(): Boolean = navigationHistoryManager.navigateBack()

    internal fun navigateForward(): Boolean = navigationHistoryManager.navigateForward()

    private fun snapshotActiveNavigationLocationOrNull(): NavigationHistoryEntry? {
        val file = getActiveFileOrNull() ?: return null
        val cursor = getCursorPositionInActiveTab() ?: return null
        return navigationHistoryManager.entryOf(file, cursor.line, cursor.column)
    }

    internal fun snapshotActiveEditableEditorContent(): ActiveEditableEditorSnapshotResult = when (val activeEditor = resolveActiveEditableEditorBindingResult()) {
        ActiveEditableEditorBindingResult.NoOpenFile -> ActiveEditableEditorSnapshotResult.NoOpenFile
        ActiveEditableEditorBindingResult.UnsupportedEditor -> ActiveEditableEditorSnapshotResult.UnsupportedEditor
        is ActiveEditableEditorBindingResult.Available -> ActiveEditableEditorSnapshotResult.Success(
            ActiveEditableEditorSnapshot(
                file = activeEditor.file,
                text = activeEditor.callback.readAllText()
            )
        )
    }

    internal fun requestReplaceAllInActiveEditor(
        findText: String,
        replaceText: String
    ): ReplaceAllInActiveEditorResult {
        val activeEditor = when (val result = resolveActiveEditableEditorBindingResult()) {
            ActiveEditableEditorBindingResult.NoOpenFile -> return ReplaceAllInActiveEditorResult.NoOpenFile
            ActiveEditableEditorBindingResult.UnsupportedEditor -> return ReplaceAllInActiveEditorResult.UnsupportedEditor
            is ActiveEditableEditorBindingResult.Available -> result
        }
        val searchState = currentSearchState
        val count = activeEditor.callback.replaceAll(
            findText,
            replaceText,
            searchState.caseSensitive,
            searchState.useRegex
        )
        return if (count > 0) {
            ReplaceAllInActiveEditorResult.Success(count)
        } else {
            ReplaceAllInActiveEditorResult.NoMatches
        }
    }

    fun selectAllInActiveTab(): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.selectAll()
    }

    fun replaceSelectionInActiveTab(replacement: String): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.replaceSelection(replacement)
    }

    fun setSelectionInActiveTab(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.setSelectionRange(startLine, startColumn, endLine, endColumn)
    }

    fun getSelectionSnapshotInActiveTab(): SelectionSnapshot? {
        val callback = getActiveCodeEditorCallback() ?: return null
        return callback.readSelection()
    }

    fun readActiveTabText(): String? {
        val callback = getActiveCodeEditorCallback() ?: return null
        return callback.readAllText()
    }

    fun replaceActiveTabText(newText: String): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.replaceWholeText(newText)
    }

    fun applyTextEditsInTab(tabId: String, edits: List<TextEditOperation>): Boolean {
        if (edits.isEmpty()) return false
        val tab = tabManager.findTab(tabId) ?: return false
        if (!isCodeEditableType(tab.contentType)) return false
        val callback = codeCallbackRegistry.get(tabId) ?: return false
        return callback.applyTextEdits(edits)
    }

    internal fun canApplyTextEditsInTab(tabId: String, edits: List<TextEditOperation>): Boolean {
        if (edits.isEmpty()) return false
        val tab = tabManager.findTab(tabId) ?: return false
        if (!isCodeEditableType(tab.contentType)) return false
        val callback = codeCallbackRegistry.get(tabId) ?: return false
        return callback.validateTextEdits(edits)
    }

    internal fun isLspDocumentVersionCurrent(tabId: String, expectedVersion: Int): Boolean =
        lspEditorManager.isDocumentVersionCurrent(tabId, expectedVersion)

    internal fun readTabDocumentVersion(tabId: String): Long? =
        codeCallbackRegistry.get(tabId)?.documentVersion?.invoke()

    internal fun readTextFromTab(tabId: String): String? =
        codeCallbackRegistry.get(tabId)?.readAllText?.invoke()

    internal fun replaceTextInTab(tabId: String, text: String): Boolean =
        codeCallbackRegistry.get(tabId)?.replaceWholeText?.invoke(text) ?: false

    fun applyTextEditsInActiveTab(edits: List<TextEditOperation>): Boolean {
        val activeTab = getActiveTab() ?: return false
        return applyTextEditsInTab(
            tabId = activeTab.id,
            edits = edits
        )
    }

    fun undoInActiveTab(): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.undo()
    }

    fun redoInActiveTab(): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.redo()
    }

    fun getCursorPositionInActiveTab(): CursorSnapshot? {
        val callback = getActiveCodeEditorCallback() ?: return null
        return callback.cursorPosition()
    }

    fun resolveMarkerLineFromSnapshot(requestedLine: Int): Int? {
        val lines = readActiveTabText()?.lineSequence()?.toList() ?: return null
        return resolveMarkerLine(
            requestedLine = requestedLine,
            lineCount = lines.size,
            lineTextAt = { line -> lines[line] }
        )
    }

    internal fun getActiveBookmarkCursorContextResult(): ActiveBookmarkCursorContextResult {
        val activeFile = getActiveFileOrNull() ?: return ActiveBookmarkCursorContextResult.NoOpenFile
        val cursor = getCursorPositionInActiveTab() ?: return ActiveBookmarkCursorContextResult.UnsupportedEditor
        return ActiveBookmarkCursorContextResult.Success(
            ActiveBookmarkCursorContext(
                file = activeFile,
                line = cursor.line
            )
        )
    }

    internal fun getActiveBookmarkTargetResult(): ActiveBookmarkTargetResult {
        return when (val cursorContext = getActiveBookmarkCursorContextResult()) {
            ActiveBookmarkCursorContextResult.NoOpenFile -> ActiveBookmarkTargetResult.NoOpenFile
            ActiveBookmarkCursorContextResult.UnsupportedEditor -> ActiveBookmarkTargetResult.UnsupportedEditor
            is ActiveBookmarkCursorContextResult.Success -> {
                val targetLine = resolveMarkerLineFromSnapshot(cursorContext.context.line)
                    ?: return ActiveBookmarkTargetResult.NoBookmarkableLine
                ActiveBookmarkTargetResult.Success(
                    ActiveBookmarkTarget(
                        file = cursorContext.context.file,
                        line = targetLine
                    )
                )
            }
        }
    }

    fun readActiveEditorPerformanceSnapshot(): EditorRenderPerformanceSnapshot? {
        val callback = getActiveCodeEditorCallback() ?: return null
        return callback.readPerformanceSnapshot()
    }

    // ========== 标签页管理代理 ==========

    fun syncFromManager(managerTabs: List<EditorTab>, activeTabId: String?) {
        val managerTabIds = managerTabs.map { it.id }.toSet()
        tabs.map { it.id }
            .filter { it !in managerTabIds }
            .let { removedTabIds -> tabLifecycleCoordinator.releaseRemovedTabResources(removedTabIds) }
        tabManager.syncFromManager(managerTabs, activeTabId)
        normalizeEditorPaneState(preferredActiveTabId = activeTabId)
        restoreSplitEditorStateIfNeeded()
    }

    fun openFile(file: File): Int {
        val existingTabIds = tabs.map { it.id }.toSet()
        val openedIndex = tabManager.openFile(file)
        syncOpenedTabPane(openedIndex, existingTabIds)
        return openedIndex
    }

    internal fun openFileAndGoToPosition(
        file: File,
        line: Int,
        column: Int,
        recordHistory: Boolean = true
    ): Boolean {
        if (!file.exists() || file.isDirectory) return false

        val source = if (recordHistory) snapshotActiveNavigationLocationOrNull() else null
        val openedIndex = openFile(file)
        val openedTab = tabs.getOrNull(openedIndex) ?: return false
        if (openedTab.file.absolutePath != file.absolutePath) return false
        if (!isCodeEditableType(openedTab.contentType)) return false

        val requested = requestNavigateToPositionInActiveTab(line, column)
        if (requested && recordHistory) {
            navigationHistoryManager.recordTransition(
                source = source,
                target = navigationHistoryManager.entryOf(openedTab.file, line, column)
            )
        }
        return requested
    }

    internal fun findOpenTabIdByFileOrNull(file: File): String? {
        val tabIndex = findOpenTabIndexByFileOrNull(file) ?: return null
        return tabs.getOrNull(tabIndex)?.id
    }

    internal fun readTextFromOpenTabIfPresent(file: File): String? = withOpenTabSelected(file) { readActiveTabText() }

    internal fun updateOpenTabTextIfPresent(file: File, newText: String): Boolean = withOpenTabSelected(file) {
        replaceActiveTabText(newText)
        true
    } ?: false

    internal fun requestCloseTabForFile(file: File): Boolean {
        val tabIndex = findOpenTabIndexByFileOrNull(file) ?: return false
        requestCloseTab(tabIndex)
        return true
    }

    internal fun closeTabsForDeletedPath(deletedPath: File): Int = fileMutationCoordinator.closeTabsForDeletedPath(deletedPath)

    internal fun syncTabsForMovedPath(oldPath: File, newPath: File): Int = fileMutationCoordinator.syncTabsForMovedPath(oldPath, newPath)

    fun openFileWithType(file: File, contentType: ContentType): Int {
        val existingTabIds = tabs.map { it.id }.toSet()
        val openedIndex = tabManager.openFileWithType(file, contentType)
        syncOpenedTabPane(openedIndex, existingTabIds)
        return openedIndex
    }

    fun requestCloseTab(index: Int) {
        tabManager.requestCloseTab(index)
        normalizeEditorPaneState()
        persistSplitEditorState()
    }

    fun requestCloseActiveTab(): Boolean {
        val activeIndex = activeTabIndex.takeIf { it in tabs.indices } ?: return false
        requestCloseTab(activeIndex)
        return true
    }

    internal fun selectNextTab(): Boolean {
        val tabCount = tabs.size
        if (tabCount <= 0) return false
        val nextIndex = if (activeTabIndex in 0 until tabCount - 1) activeTabIndex + 1 else 0
        selectTab(nextIndex)
        return true
    }

    internal fun selectPreviousTab(): Boolean {
        val tabCount = tabs.size
        if (tabCount <= 0) return false
        val previousIndex = if (activeTabIndex > 0) activeTabIndex - 1 else tabCount - 1
        selectTab(previousIndex)
        return true
    }

    fun confirmSaveAndClose(): Boolean {
        val closed = tabManager.confirmSaveAndClose()
        if (closed) {
            normalizeEditorPaneState()
            persistSplitEditorState()
        }
        return closed
    }

    fun confirmDiscardAndClose() {
        val hadPendingClose = pendingCloseTab != null
        tabManager.confirmDiscardAndClose()
        if (hadPendingClose) {
            normalizeEditorPaneState()
            persistSplitEditorState()
        }
    }

    fun cancelClose() = tabManager.cancelClose()

    fun consumeLastOpenError(): String? = tabManager.consumeLastOpenError()

    fun selectTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val pane = resolvePaneForTab(tab.id)
        selectTabInPane(pane, index)
    }

    fun closeOtherTabs(exceptIndex: Int): Boolean {
        val keptTabId = tabs.getOrNull(exceptIndex)?.id
        if (keptTabId == null) return false
        val completed = tabManager.closeOtherTabs(exceptIndex)
        if (!completed) return true
        val keptPane = resolvePaneForTab(keptTabId)
        tabLifecycleCoordinator.retainOnlyTabPaneState(keptTabId, keptPane)
        normalizeEditorPaneState(preferredActiveTabId = keptTabId)
        persistSplitEditorState()
        return true
    }

    fun closeOtherTabsForActiveTab(): Boolean {
        val activeIndex = activeTabIndex.takeIf { it in tabs.indices } ?: return false
        return closeOtherTabs(activeIndex)
    }

    fun closeAllTabs(): Boolean {
        val hadTabs = tabs.isNotEmpty()
        val completed = tabManager.closeAllTabs()
        if (!completed) return hadTabs
        tabLifecycleCoordinator.clearSplitPaneState()
        isSplitEditorEnabled = false
        focusedPane = EditorPaneId.PRIMARY
        persistSplitEditorState()
        return hadTabs
    }

    fun updateTabState(tabId: String, isDirty: Boolean, canUndo: Boolean, canRedo: Boolean) {
        tabManager.updateTabState(tabId, isDirty, canUndo, canRedo)
        codeRuntimeCache.trim()
    }

    internal fun rememberDirtyTabsForSaveAllNotification() {
        saveAllNotificationTracker.rememberDirtyTabs(tabs)
    }

    internal fun resolveSuccessfulSaveAllNotificationTargets(
        results: List<SaveResult>
    ): List<ActiveSaveTarget> = saveAllNotificationTracker.resolveSuccessfulTargets(results)

    internal fun notifySuccessfulSaveAllResults(
        results: List<SaveResult>,
        fullText: String = ""
    ) {
        resolveSuccessfulSaveAllNotificationTargets(results)
            .forEach { target ->
                notifyFileSaved(target.tabId, target.file, fullText)
            }
    }

    internal fun getTabToolbarStateFlow(tabId: String): Flow<TabToolbarState>? =
        documentSessionCoordinator.getTabToolbarStateFlow(tabId)

    internal fun getTabLastEditAtFlow(tabId: String): Flow<Long?>? =
        documentSessionCoordinator.getTabLastEditAtFlow(tabId)

    internal fun getActiveEditorSessionAlertFlow(): Flow<ActiveEditorSessionAlertState>? =
        documentSessionCoordinator.getActiveEditorSessionAlertFlow()

    internal fun attachTabEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        documentSessionCoordinator.attachEditorBinding(tabId, binding)
    }

    internal fun detachTabEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        documentSessionCoordinator.detachEditorBinding(tabId, binding)
    }

    internal fun getTabDetachedEditorSnapshot(tabId: String): DetachedEditorSnapshot? =
        documentSessionCoordinator.getDetachedEditorSnapshot(tabId)

    internal fun markTabDetachedEditorSnapshotRestored(
        tabId: String,
        snapshot: DetachedEditorSnapshot
    ) {
        documentSessionCoordinator.markDetachedEditorSnapshotRestored(tabId, snapshot)
    }

    internal fun getTabEditorViewState(tabId: String): EditorViewState? =
        documentSessionCoordinator.getEditorViewState(tabId)

    internal fun notifyTabEditorContentChanged(
        tabId: String,
        canUndo: Boolean,
        canRedo: Boolean,
        changeCausedByUndoManager: Boolean
    ) {
        documentSessionCoordinator.notifyEditorContentChanged(
            tabId = tabId,
            canUndo = canUndo,
            canRedo = canRedo,
            changeCausedByUndoManager = changeCausedByUndoManager,
        )
    }

    internal fun markTabEditorSnapshotClean(tabId: String, charset: Charset) {
        documentSessionCoordinator.markEditorSnapshotClean(tabId, charset)
    }

    internal fun updateTabCursorPosition(tabId: String, line: Int, column: Int) {
        documentSessionCoordinator.updateCursorPosition(tabId, line, column)
    }

    internal fun notifyTabSelectionChanged(tabId: String, selection: SelectionSnapshot?) {
        val tab = tabManager.findTab(tabId) ?: return
        PluginHostEventDispatcher.emitEditorSelectionChanged(
            tabId = tab.id,
            file = tab.file,
            selection = selection?.toEventPayload()
        )
    }

    internal fun updateTabScrollPosition(tabId: String, scrollX: Int, scrollY: Int) {
        documentSessionCoordinator.updateScrollPosition(tabId, scrollX, scrollY)
    }

    fun createSplitEditorStateSnapshot(): SplitEditorStateSnapshot {
        val pathByTabId = tabs.associate { tab -> tab.id to normalizeOpenTabLookupPath(tab.file.absolutePath) }
        val assignments = tabs.associate { tab ->
            val pane = if (isSplitEditorEnabled) resolvePaneForTab(tab.id) else EditorPaneId.PRIMARY
            normalizeOpenTabLookupPath(tab.file.absolutePath) to pane
        }
        val mirroredPaths = splitPaneState.mirroredTabIdsByPane().mapValues { (_, tabIds) ->
            tabIds.mapNotNullTo(linkedSetOf()) { tabId -> pathByTabId[tabId] }
        }.filterValues { it.isNotEmpty() }
        val activePaths = splitPaneState.activeTabIdsByPane().mapNotNull { (pane, tabId) ->
            pathByTabId[tabId]?.let { path -> pane to path }
        }.toMap()

        return SplitEditorStateSnapshot(
            isEnabled = isSplitEditorEnabled,
            focusedPane = focusedPane,
            layout = splitEditorLayout,
            primaryRatio = splitEditorPrimaryRatio,
            tabPaneAssignments = assignments,
            mirroredFilePathsByPane = mirroredPaths,
            activeFilePathByPane = activePaths
        ).normalized()
    }

    fun restoreSplitEditorStateSnapshot(snapshot: SplitEditorStateSnapshot) {
        val normalized = snapshot.normalized()
        val tabIdByPath = tabs.associate { tab -> normalizeOpenTabLookupPath(tab.file.absolutePath) to tab.id }
        val paneAssignmentsByPath = normalized.tabPaneAssignments.mapKeys { (path, _) ->
            normalizeOpenTabLookupPath(path)
        }

        splitPaneState.clear()
        splitEditorLayout = normalized.layout
        splitEditorPrimaryRatio = normalized.primaryRatio
        isSplitEditorEnabled = normalized.isEnabled && tabs.isNotEmpty()

        tabs.forEach { tab ->
            val path = normalizeOpenTabLookupPath(tab.file.absolutePath)
            val pane = if (isSplitEditorEnabled) {
                paneAssignmentsByPath[path] ?: EditorPaneId.PRIMARY
            } else {
                EditorPaneId.PRIMARY
            }
            splitPaneState.setPane(tab.id, pane)
        }

        if (isSplitEditorEnabled) {
            normalized.mirroredFilePathsByPane.forEach { (pane, paths) ->
                val mirroredTabIds = paths.mapNotNullTo(linkedSetOf()) { path ->
                    tabIdByPath[normalizeOpenTabLookupPath(path)]
                }
                if (mirroredTabIds.isNotEmpty()) {
                    mirroredTabIds.forEach { tabId ->
                        splitPaneState.addMirroredTabToPane(
                            pane = pane,
                            tabId = tabId,
                            ownerPane = resolvePaneForTab(tabId),
                        )
                    }
                }
            }

            normalized.activeFilePathByPane.forEach { (pane, path) ->
                tabIdByPath[normalizeOpenTabLookupPath(path)]?.let { tabId ->
                    splitPaneState.setActiveTabId(pane, tabId)
                }
            }
        }

        focusedPane = if (isSplitEditorEnabled && getTabsForPaneInternal(normalized.focusedPane).isNotEmpty()) {
            normalized.focusedPane
        } else {
            EditorPaneId.PRIMARY
        }
        normalizeEditorPaneState(preferredActiveTabId = splitPaneState.activeTabId(focusedPane))
    }

    private fun persistSplitEditorState() {
        splitSessionCoordinator.persist()
    }

    private fun restoreSplitEditorStateIfNeeded() {
        splitSessionCoordinator.restoreIfNeeded()
    }

    private fun clearSplitEditorStateInMemory() {
        splitPaneState.clear()
        isSplitEditorEnabled = false
        focusedPane = EditorPaneId.PRIMARY
        splitEditorPrimaryRatio = DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO
        splitEditorLayout = SplitEditorLayout.HORIZONTAL
    }

    fun getTabsForPane(pane: EditorPaneId): List<EditorTabState> = getTabsForPaneInternal(pane)

    fun getActiveIndexForPane(pane: EditorPaneId): Int {
        val activeTabId = splitPaneState.activeTabId(pane)
        val activeIndex = activeTabId?.let { id -> tabs.indexOfFirst { it.id == id } } ?: -1
        if (activeIndex >= 0 && isTabVisibleInPane(activeTabId!!, pane)) return activeIndex
        return tabs.indexOfFirst { isTabVisibleInPane(it.id, pane) }
    }

    fun focusEditorPane(pane: EditorPaneId) {
        val targetPane = pane.takeIf { isSplitEditorEnabled || it == EditorPaneId.PRIMARY }
            ?: EditorPaneId.PRIMARY
        focusedPane = targetPane
        val activeIndex = getActiveIndexForPane(targetPane)
        if (activeIndex in tabs.indices) {
            tabManager.selectTab(activeIndex)
        }
        persistSplitEditorState()
    }

    fun selectTabInPane(pane: EditorPaneId, index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val targetPane = if (isSplitEditorEnabled) pane else EditorPaneId.PRIMARY
        if (isSplitEditorEnabled && !isTabVisibleInPane(tab.id, targetPane)) {
            return
        }
        focusedPane = targetPane
        if (!isTabMirroredToPane(tab.id, targetPane)) {
            splitPaneState.setPane(tab.id, targetPane)
        }
        splitPaneState.setActiveTabId(targetPane, tab.id)
        tabManager.selectTab(index)
        normalizeEditorPaneState(preferredActiveTabId = tab.id)
        persistSplitEditorState()
    }

    fun updateSplitEditorPrimaryRatio(ratio: Float) {
        if (!ratio.isFinite()) return
        splitEditorPrimaryRatio = coerceSplitEditorPrimaryRatio(ratio)
        persistSplitEditorState()
    }

    fun resizeSplitEditorBy(deltaPx: Float, containerWidthPx: Float) {
        if (!deltaPx.isFinite() || !containerWidthPx.isFinite() || containerWidthPx <= 0f) return
        updateSplitEditorPrimaryRatio(splitEditorPrimaryRatio + deltaPx / containerWidthPx)
    }

    fun updateSplitEditorLayout(layout: SplitEditorLayout) {
        splitEditorLayout = layout
        persistSplitEditorState()
    }

    fun toggleSplitEditor() {
        if (isSplitEditorEnabled) {
            closeSecondaryPane()
        } else {
            isSplitEditorEnabled = true
            if (tabs.isNotEmpty()) {
                val activeTab = getActiveTab()
                splitPaneState.ensurePaneForTabs(tabs.map { it.id }, EditorPaneId.PRIMARY)
                activeTab?.let {
                    focusedPane = EditorPaneId.PRIMARY
                    splitPaneState.setActiveTabId(EditorPaneId.PRIMARY, it.id)
                }
            }
            normalizeEditorPaneState()
        }
        persistSplitEditorState()
    }

    fun closeSecondaryPane() {
        val activeTabId = getActiveTabId()
        splitPaneState.moveAllTabsToPane(EditorPaneId.PRIMARY)
        splitPaneState.clearMirrors()
        splitPaneState.clearActiveTabs()
        activeTabId?.let { splitPaneState.setActiveTabId(EditorPaneId.PRIMARY, it) }
        isSplitEditorEnabled = false
        focusedPane = EditorPaneId.PRIMARY
        normalizeEditorPaneState(preferredActiveTabId = activeTabId)
        persistSplitEditorState()
    }

    fun canMoveActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        return !isSplitEditorEnabled || !isTabVisibleInPane(activeTab.id, EditorPaneId.SECONDARY)
    }

    fun moveActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        if (isSplitEditorEnabled && isTabVisibleInPane(activeTab.id, EditorPaneId.SECONDARY)) {
            return false
        }
        isSplitEditorEnabled = true
        splitPaneState.removeMirroredTabId(activeTab.id)
        splitPaneState.setPane(activeTab.id, EditorPaneId.SECONDARY)
        splitPaneState.setActiveTabId(EditorPaneId.SECONDARY, activeTab.id)
        focusedPane = EditorPaneId.SECONDARY
        normalizeEditorPaneState(preferredActiveTabId = activeTab.id)
        tabManager.findTabIndex(activeTab.id)
            .takeIf { it in tabs.indices }
            ?.let(tabManager::selectTab)
        persistSplitEditorState()
        return true
    }

    fun canCopyActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        return !isSplitEditorEnabled || !isTabVisibleInPane(activeTab.id, EditorPaneId.SECONDARY)
    }

    fun copyActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        if (isSplitEditorEnabled && isTabVisibleInPane(activeTab.id, EditorPaneId.SECONDARY)) {
            return false
        }
        isSplitEditorEnabled = true
        val ownerPane = resolvePaneForTab(activeTab.id)
        splitPaneState.setPaneIfAbsent(activeTab.id, ownerPane)
        splitPaneState.addMirroredTabToPane(EditorPaneId.SECONDARY, activeTab.id, ownerPane)
        splitPaneState.setActiveTabId(ownerPane, activeTab.id)
        splitPaneState.setActiveTabId(EditorPaneId.SECONDARY, activeTab.id)
        focusedPane = EditorPaneId.SECONDARY
        normalizeEditorPaneState(preferredActiveTabId = activeTab.id)
        tabManager.findTabIndex(activeTab.id)
            .takeIf { it in tabs.indices }
            ?.let(tabManager::selectTab)
        persistSplitEditorState()
        return true
    }

    private fun assignOpenedTabToFocusedPane(openedIndex: Int) {
        val openedTab = tabs.getOrNull(openedIndex) ?: return
        val targetPane = if (isSplitEditorEnabled) focusedPane else EditorPaneId.PRIMARY
        splitPaneState.setPane(openedTab.id, targetPane)
        splitPaneState.setActiveTabId(targetPane, openedTab.id)
        focusedPane = targetPane
        normalizeEditorPaneState(preferredActiveTabId = openedTab.id)
        persistSplitEditorState()
    }

    private fun syncOpenedTabPane(openedIndex: Int, existingTabIds: Set<String>) {
        val openedTab = tabs.getOrNull(openedIndex) ?: return
        if (openedTab.id !in existingTabIds) {
            assignOpenedTabToFocusedPane(openedIndex)
            return
        }

        val targetPane = if (isSplitEditorEnabled && isTabVisibleInPane(openedTab.id, focusedPane)) {
            focusedPane
        } else {
            resolvePaneForTab(openedTab.id)
        }
        focusedPane = targetPane
        splitPaneState.setActiveTabId(targetPane, openedTab.id)
        normalizeEditorPaneState(preferredActiveTabId = openedTab.id)
        persistSplitEditorState()
    }

    private fun normalizeEditorPaneState(preferredActiveTabId: String? = getActiveTabId()) {
        val liveTabIds = tabs.map { it.id }.toSet()
        splitPaneState.removeMissingTabs(liveTabIds)
        splitPaneState.pruneMirroredTabs(liveTabIds, ::resolvePaneForTab)

        splitPaneState.ensurePaneForTabs(tabs.map { it.id }, EditorPaneId.PRIMARY)

        if (!isSplitEditorEnabled) {
            splitPaneState.moveAllTabsToPane(EditorPaneId.PRIMARY)
            splitPaneState.clearMirrors()
            focusedPane = EditorPaneId.PRIMARY
        }

        EditorPaneId.values().forEach { pane ->
            val activeTabId = splitPaneState.activeTabId(pane)
            if (activeTabId == null || activeTabId !in liveTabIds || !isTabVisibleInPane(activeTabId, pane)) {
                getTabsForPaneInternal(pane).firstOrNull()?.let { splitPaneState.setActiveTabId(pane, it.id) }
                    ?: splitPaneState.removeActiveTab(pane)
            }
        }

        if (isSplitEditorEnabled && getTabsForPaneInternal(focusedPane).isEmpty()) {
            focusedPane = EditorPaneId.values()
                .firstOrNull { getTabsForPaneInternal(it).isNotEmpty() }
                ?: EditorPaneId.PRIMARY
        }

        val targetTabId = preferredActiveTabId
            ?.takeIf { it in liveTabIds && isTabVisibleInPane(it, focusedPane) }
            ?: splitPaneState.activeTabId(focusedPane)
            ?: tabs.firstOrNull()?.id
            ?: return
        val targetIndex = tabs.indexOfFirst { it.id == targetTabId }
        if (targetIndex in tabs.indices && targetIndex != activeTabIndex) {
            tabManager.selectTab(targetIndex)
        }
    }

    private fun getTabsForPaneInternal(pane: EditorPaneId): List<EditorTabState> = tabs.filter { isTabVisibleInPane(it.id, pane) }

    private fun isTabVisibleInPane(tabId: String, pane: EditorPaneId): Boolean = resolvePaneForTab(tabId) == pane || isTabMirroredToPane(tabId, pane)

    private fun isTabMirroredToPane(tabId: String, pane: EditorPaneId): Boolean =
        splitPaneState.isMirroredToPane(tabId, pane, isSplitEditorEnabled)

    private fun resolvePaneForTab(tabId: String): EditorPaneId =
        splitPaneState.paneFor(tabId, isSplitEditorEnabled)

    private fun getActiveTab(): EditorTabState? = tabManager.getActiveTab()

    private fun getActiveTabId(): String? = getActiveTab()?.id

    private fun findOpenTabIndexByFileOrNull(file: File): Int? {
        val normalizedPath = normalizeOpenTabLookupPath(file.absolutePath)
        return tabs.indexOfFirst { tab ->
            normalizeOpenTabLookupPath(tab.file.absolutePath) == normalizedPath
        }.takeIf { it >= 0 }
    }

    private inline fun <T> withOpenTabSelected(file: File, action: () -> T): T? {
        val tabIndex = findOpenTabIndexByFileOrNull(file) ?: return null
        val previousIndex = activeTabIndex
        if (previousIndex != tabIndex) {
            selectTab(tabIndex)
        }

        return try {
            action()
        } finally {
            if (previousIndex != tabIndex && previousIndex in tabs.indices) {
                selectTab(previousIndex)
            }
        }
    }

    internal fun getActiveFileOrNull(): File? = getActiveTab()?.file

    internal fun getActiveFileAbsolutePathOrNull(): String? = getActiveFileOrNull()?.absolutePath

    internal fun snapshotActivePluginEditorContextOrNull(
        cHeaderLanguageId: String = "c"
    ): ActivePluginEditorContext? {
        val activeTab = getActiveTab() ?: return null
        return ActivePluginEditorContext(
            tabId = activeTab.id,
            file = activeTab.file,
            languageId = activeTab.file.resolveEditorLanguageId(cHeaderLanguageId = cHeaderLanguageId)
        )
    }

    internal fun isTabActive(tabId: String): Boolean = getActiveTabId() == tabId

    /**
     * 在当前光标位置插入文本
     */
    fun insertTextAtCursor(text: String): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        callback.insertTextAtCursor(text)
        return true
    }

    fun attachTinaLspForTab(tabId: String, file: File, textProvider: () -> String): Boolean = lspEditorManager.attachTinaLsp(
        context = context,
        file = file,
        tabId = tabId,
        projectRootPath = resolveProjectRootPath(),
        textProvider = textProvider
    )

    fun releaseTinaLspForTab(tabId: String) {
        lspEditorManager.releaseLspEditor(tabId)
        lspUiState.removeStatus(tabId)
    }

    fun notifyTinaTextChanged(tabId: String, change: TextChange, documentVersion: Long) {
        lspEditorManager.onTinaDocumentChanged(tabId, change, documentVersion)
    }

    fun notifyFileSaved(tabId: String, file: File, fullText: String) {
        lspEditorManager.onFileSaved(context, tabId, file, fullText)
        PluginHostEventDispatcher.emitEditorSaved(tabId, file)
    }

    suspend fun requestLspCompletion(
        tabId: String,
        position: Position,
        triggerChar: Char?
    ): CompletionFetchResult = lspEditorManager.requestCompletion(tabId, position, triggerChar)

    internal suspend fun requestLspSemanticTokens(
        tabId: String,
        visibleLines: IntRange,
        documentVersion: Long
    ): SemanticTokensRequestResult = lspEditorManager.requestSemanticTokens(
        tabId = tabId,
        visibleLines = visibleLines,
        documentVersion = documentVersion
    )

    suspend fun requestLspFoldingRanges(
        tabId: String,
        documentVersion: Long
    ): List<FoldRegion>? = lspEditorManager.requestFoldingRanges(
        tabId = tabId,
        documentVersion = documentVersion
    )

    fun requestSnippetCompletion(file: File, prefix: String): List<CompletionItem> {
        if (prefix.isBlank()) return emptyList()

        val languageId = file.resolveEditorLanguageId()
        return snippetManager.findSnippetCompletions(languageId, prefix).map { candidate ->
            CompletionItem(
                label = candidate.trigger,
                kind = CompletionItemKind.SNIPPET,
                detail = candidate.description ?: Strings.plugin_marketplace_category_snippet.strOr(context),
                insertText = candidate.plainInsertText,
                source = CompletionSource.LOCAL,
                snippetText = candidate.snippetText
            )
        }
    }

    // ========== LSP Code Actions / Rename 代理 ==========

    suspend fun requestCodeActions(
        tabId: String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ) = lspEditorManager.requestCodeActions(tabId, startLine, startColumn, endLine, endColumn)

    suspend fun executeCodeAction(
        tabId: String,
        action: com.wuxianggujun.tinaide.core.lsp.LspCodeActionService.CodeActionItem,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ) = lspEditorManager.executeCodeAction(tabId, action, onApplyEdit)

    suspend fun prepareRename(
        tabId: String,
        line: Int,
        column: Int
    ) = lspEditorManager.prepareRename(tabId, line, column)

    suspend fun rename(
        tabId: String,
        line: Int,
        column: Int,
        newName: String,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ) = lspEditorManager.rename(tabId, line, column, newName, onApplyEdit)

    // ---- LSP 导航 ----

    suspend fun gotoDefinition(tabId: String, line: Int, column: Int) = lspEditorManager.gotoDefinition(tabId, line, column)

    suspend fun findReferences(tabId: String, line: Int, column: Int) = lspEditorManager.findReferences(tabId, line, column)

    suspend fun gotoTypeDefinition(tabId: String, line: Int, column: Int) = lspEditorManager.gotoTypeDefinition(tabId, line, column)

    suspend fun gotoImplementation(tabId: String, line: Int, column: Int) = lspEditorManager.gotoImplementation(tabId, line, column)

    suspend fun callHierarchyIncomingCalls(tabId: String, line: Int, column: Int) = lspEditorManager.callHierarchyIncomingCalls(tabId, line, column)

    suspend fun switchSourceHeader(tabId: String) = lspEditorManager.switchSourceHeader(tabId)

    suspend fun workspaceSymbol(tabId: String, query: String): List<WorkspaceSymbolItem> = lspEditorManager.workspaceSymbol(tabId, query)

    suspend fun resolveWorkspaceSymbol(tabId: String, item: WorkspaceSymbolItem): WorkspaceSymbolItem? = lspEditorManager.resolveWorkspaceSymbol(tabId, item)

    suspend fun documentSymbols(tabId: String): List<DocumentSymbolItem> = lspEditorManager.documentSymbols(tabId)

    suspend fun requestLspHoverMarkdown(tabId: String, line: Int, column: Int): String? = lspEditorManager.requestHoverMarkdown(tabId, line, column)

    suspend fun requestLspSignatureHelp(tabId: String, line: Int, column: Int): SignatureHelpResult? = lspEditorManager.requestSignatureHelp(tabId, line, column)

    fun updateEditorColorSchemes(context: android.content.Context) {
        val scheme = resolveEditorColorScheme(context)
        codeCallbackRegistry.forEach { tabId, callback ->
            runCatching { callback.applyEditorColorScheme(scheme) }
                .onFailure { t ->
                    Timber.tag("EditorContainerState").w(t, "Failed to apply editor theme for tab=%s", tabId)
                }
        }
    }

    fun updateEditorSettings(context: android.content.Context) {
        val settings = Prefs.editorSettingsFlow.value
        codeCallbackRegistry.forEach { tabId, callback ->
            runCatching { callback.applyEditorSettings(settings) }
                .onFailure { t ->
                    Timber.tag("EditorContainerState").w(t, "Failed to apply editor settings for tab=%s", tabId)
                }
        }
    }

    private fun resolveEditorColorScheme(context: android.content.Context): EditorColorScheme {
        val themeId = Prefs.editorTheme
        if (themeId.startsWith(PluginEditorThemeRegistry.THEME_ID_PREFIX)) {
            val themeConfig = pluginThemeRegistry.themesFlow.value[themeId]
            if (themeConfig == null) {
                Timber.tag("EditorContainerState").w("Editor theme not found: %s", themeId)
                return EditorColorScheme.builtinGray()
            }

            val fallback = if (themeConfig.type.equals("light", ignoreCase = true)) {
                EditorColorScheme.builtinLight()
            } else {
                EditorColorScheme.builtinDark()
            }
            return EditorColorScheme.fromThemeColors(themeConfig.colors, fallback = fallback)
        }

        return when (themeId) {
            "GRAY" -> EditorColorScheme.builtinGray()
            "DARK" -> EditorColorScheme.builtinDark()
            "LIGHT" -> EditorColorScheme.builtinLight()
            "AUTO" -> {
                val nightModeFlags = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val useDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (useDark) EditorColorScheme.builtinDark() else EditorColorScheme.builtinLight()
            }
            else -> EditorColorScheme.builtinGray()
        }
    }

    fun updateLspAssistSettings(settings: LspAssistSettings) {
        lspEditorManager.setAssistSettings(settings)
    }

    fun updateLspFoldingRangeEnabled(enabled: Boolean) {
        lspEditorManager.onLspFoldingRangeEnabledChanged(enabled)
    }

    fun refreshLspConnections() {
        lspEditorManager.refreshLspConnection(context)
    }

    /**
     * 依赖包变更后，刷新已打开的 C/C++ 编辑器绑定，触发 compile_commands/clangd 自动重载。
     */
    fun refreshOpenCxxEditorsForDependencyChange(revision: Long) {
        if (revision <= lastHandledDependencyRevision) return
        lastHandledDependencyRevision = revision

        // 先让编译数据库缓存失效：即使当前没有打开的 C/C++ 编辑器，也要清除内存中的
        // compile setup 缓存。否则在“项目开着 + 装包 + 当前无活跃 C/C++ 文件”时，缓存会残留，
        // 下次打开 C/C++ 文件时会绕过 compile_commands 的包指纹校验，导致头文件假错。
        lspEditorManager.invalidateCompileSetupCache()

        val refreshCandidates = tabs.count { tab ->
            if (!hasAttachedCodeEditor(tab.id, tab.contentType)) return@count false
            tab.file.extension.lowercase() in CxxFileSupport.clangdSupportedExtensions
        }

        if (refreshCandidates <= 0) {
            Timber.tag("EditorContainerState")
                .i("Dependency revision=%d detected, no active C/C++ tab; invalidated compile setup cache only", revision)
            return
        }
        lspEditorManager.refreshLspConnection(context)
        Timber.tag("EditorContainerState")
            .i("Dependency revision=%d detected, refreshed %d C/C++ tab(s)", revision, refreshCandidates)
    }

    fun restoreFromManager() {
        tabManager.restoreFromManager()
        restoreSplitEditorStateIfNeeded()
    }

    // ========== 未保存文件检查 ==========

    /**
     * 检查是否有未保存的更改
     */
    fun hasUnsavedChanges(): Boolean = tabManager.hasUnsavedChanges()

    /**
     * 获取所有有未保存更改的标签页
     */
    fun getUnsavedTabs(): List<EditorTabState> = tabManager.getUnsavedTabs()

    /**
     * 获取未保存文件的数量
     */
    fun getUnsavedCount(): Int = tabManager.getUnsavedCount()

    // ========== 资源释放 ==========

    fun release() {
        lspEditorManager.release()
        searchStateManager.release()
        codeCallbackRegistry.clear()
        codeRuntimeCache.release()
        navigationHistoryManager.clear()
        lspUiState.clear()
        diagnosticsState.clear()
    }

    private fun fileToNormalizedPath(file: File): String = file.absolutePath
        .replace('\\', '/')

    private fun fileUriToNormalizedPath(fileUri: String): String? = runCatching {
        if (fileUri.startsWith("file://")) {
            val file = File(URI(fileUri))
            fileToNormalizedPath(file)
        } else {
            fileUri.replace('\\', '/')
        }
    }.getOrNull()

    private fun isCodeEditableType(contentType: ContentType): Boolean = contentType == ContentType.CODE || contentType == ContentType.JSON

    private fun hasAttachedCodeEditor(tabId: String, contentType: ContentType): Boolean =
        isCodeEditableType(contentType) && codeCallbackRegistry.contains(tabId)

    private fun resolveActiveEditableEditorBindingResult(): ActiveEditableEditorBindingResult {
        val activeTab = getActiveTab() ?: return ActiveEditableEditorBindingResult.NoOpenFile
        if (!hasAttachedCodeEditor(activeTab.id, activeTab.contentType)) {
            return ActiveEditableEditorBindingResult.UnsupportedEditor
        }
        val callback = codeCallbackRegistry.get(activeTab.id)
            ?: return ActiveEditableEditorBindingResult.UnsupportedEditor
        return ActiveEditableEditorBindingResult.Available(
            file = activeTab.file,
            callback = callback
        )
    }
}

private fun SelectionSnapshot.toEventPayload(): EditorSelectionPayload = EditorSelectionPayload(
    text = text,
    startLine = startLine,
    startColumn = startColumn,
    endLine = endLine,
    endColumn = endColumn
)

/**
 * 创建并记住 EditorContainerState
 *
 * 负责：
 * - 订阅 EditorManager 的标签列表和活动标签
 * - 监听主题变化更新编辑器颜色方案
 * - 在 Composition 销毁时释放资源
 */
@Composable
fun rememberEditorContainerState(
    editorManager: IEditorManager,
    snippetManager: PluginSnippetManager,
    pluginThemeRegistry: PluginEditorThemeRegistry,
    projectSymbolIndexServiceProvider: () -> ProjectSymbolIndexService?,
    projectRootPathProvider: () -> String?,
    onLspDiagnosticsChanged: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null
): EditorContainerState {
    val context = LocalContext.current
    val fileWatchService: IFileWatchService = koinInject()
    val linuxEnvironmentProvider: LinuxEnvironmentProvider = koinInject()
    val lspPluginManager: LspPluginManager = koinInject()
    val state = remember(
        editorManager,
        snippetManager,
        pluginThemeRegistry,
        projectSymbolIndexServiceProvider,
        fileWatchService,
        linuxEnvironmentProvider,
        lspPluginManager,
    ) {
        EditorContainerState(
            context = context,
            editorManager = editorManager,
            snippetManager = snippetManager,
            pluginThemeRegistry = pluginThemeRegistry,
            projectSymbolIndexServiceProvider = projectSymbolIndexServiceProvider,
            projectRootPathProvider = projectRootPathProvider,
            fileWatchService = fileWatchService,
            linuxEnvironmentProvider = linuxEnvironmentProvider,
            lspPluginManager = lspPluginManager,
        )
    }

    // 必须在 composition 期间就同步设置，否则 AndroidView(factory) 可能先创建编辑器并触发 LSP 诊断，
    // 导致底部"诊断"面板错过首次 publishDiagnostics 回调（表现为：有波浪线但诊断列表为空）。
    state.onLspDiagnosticsChanged = onLspDiagnosticsChanged

    // 订阅 EditorManager 的标签列表和活动标签 ID
    val managerTabs by editorManager.tabsFlow.collectAsStateWithLifecycle()
    val activeTabId by editorManager.activeTabIdFlow.collectAsStateWithLifecycle()

    // 同步 EditorManager 的标签列表到 EditorContainerState
    LaunchedEffect(managerTabs, activeTabId) {
        state.syncFromManager(managerTabs, activeTabId)
    }

    // 恢复之前的状态（仅初次）
    LaunchedEffect(Unit) {
        state.restoreFromManager()
    }

    // 监听主题变化，更新编辑器颜色方案
    LaunchedEffect(Unit) {
        ThemeManager.themeFlow.collect { _ ->
            state.updateEditorColorSchemes(context)
        }
    }

    // 监听“编辑器主题”变化（配色方案/插件主题），更新已打开的编辑器
    LaunchedEffect(Unit) {
        Prefs.editorThemeFlow.collect { _ ->
            state.updateEditorColorSchemes(context)
        }
    }

    // 监听插件主题索引变化：插件启用/禁用/更新后，若当前主题来自插件则需要刷新
    LaunchedEffect(Unit) {
        pluginThemeRegistry.themesFlow.collect { _ ->
            state.updateEditorColorSchemes(context)
        }
    }

    // 监听编辑器设置变化，更新已打开的编辑器
    LaunchedEffect(Unit) {
        Prefs.editorSettingsFlow.collect { _ ->
            state.updateEditorSettings(context)
        }
    }

    // 监听 LSP 辅助能力设置变化，对已打开的 LSP 编辑器即时生效
    LaunchedEffect(Unit) {
        Prefs.lspAssistSettingsFlow.collect { settings ->
            state.updateLspAssistSettings(settings)
        }
    }

    // 监听 LSP Folding Range 设置变化，对已打开的 LSP 编辑器即时生效
    LaunchedEffect(Unit) {
        Prefs.lspFoldingRangeEnabledFlow.collect { enabled ->
            state.updateLspFoldingRangeEnabled(enabled)
        }
    }

    // 监听开发者 LSP 测试开关，便于对比 Tree-sitter 与内置/外部 LSP 表现。
    LaunchedEffect(Unit) {
        Prefs.devEditorLspEnabledFlow.collect {
            state.refreshLspConnections()
        }
    }

    LaunchedEffect(Unit) {
        Prefs.devBuiltinCmakeLspEnabledFlow.collect {
            state.refreshLspConnections()
        }
    }

    // 监听依赖包变更，自动刷新已打开 C/C++ 编辑器（compile_commands + clangd）。
    LaunchedEffect(Unit) {
        PackageDependencyEvents.revision.collect { revision ->
            state.refreshOpenCxxEditorsForDependencyChange(revision)
        }
    }

    // 释放资源
    DisposableEffect(Unit) {
        onDispose {
            state.release()
        }
    }

    return state
}

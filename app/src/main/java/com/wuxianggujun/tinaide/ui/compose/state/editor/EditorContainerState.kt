package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.ai.api.MessageContext
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
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.lsp.WorkspaceSymbolItem
import com.wuxianggujun.tinaide.core.packages.PackageDependencyEvents
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.script.api.EditorSelectionPayload
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.search.SearchOptions
import com.wuxianggujun.tinaide.ui.compose.components.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorToolBarState
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.eclipse.lsp4j.WorkspaceEdit
import org.koin.core.context.GlobalContext
import timber.log.Timber

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
    private val projectRootPathProvider: () -> String?
) {
    data class TextEditOperation(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val newText: String
    )

    data class SelectionSnapshot(
        val text: String,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
    )

    data class CursorSnapshot(
        val line: Int,
        val column: Int
    )

    data class NavigationHistoryEntry(
        val filePath: String,
        val line: Int,
        val column: Int
    )

    data class ActiveEditableEditorSnapshot(
        val file: File,
        val text: String
    )

    data class ActiveFileContextSnapshot(
        val file: File,
        val language: String,
        val content: String
    )

    data class ActiveSelectedCodeContextSnapshot(
        val file: File,
        val language: String,
        val content: String,
        val startLine: Int,
        val endLine: Int
    )

    data class ActivePluginEditorContext(
        val tabId: String,
        val file: File,
        val languageId: String
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

    data class CodeEditorCallback(
        val goToPosition: (line: Int, column: Int) -> Boolean,
        val selectAll: () -> Boolean,
        val replaceSelection: (replacement: String) -> Boolean,
        val replaceWholeText: (newText: String) -> Boolean,
        val applyTextEdits: (edits: List<TextEditOperation>) -> Boolean,
        val toggleLineComment: (commentToken: String) -> Boolean,
        val replaceAll: (
            findText: String,
            replaceText: String,
            caseSensitive: Boolean,
            useRegex: Boolean
        ) -> Int,
        val undo: () -> Boolean,
        val redo: () -> Boolean,
        val insertTextAtCursor: (text: String) -> Unit,
        val cursorPosition: () -> CursorSnapshot,
        val setSelectionRange: (startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) -> Boolean,
        val readAllText: () -> String,
        val readSelection: () -> SelectionSnapshot?,
        val readPerformanceSnapshot: () -> EditorRenderPerformanceSnapshot? = { null },
        val applyEditorSettings: (settings: EditorSettings) -> Unit = {},
        val applyEditorColorScheme: (scheme: EditorColorScheme) -> Unit = {}
    )

    /**
     * 记录最近一次已处理的依赖变更 revision（实例字段，避免多实例间的静态变量竞争）。
     */
    private var lastHandledDependencyRevision: Long = 0L

    // ========== 子管理器 ==========

    private val lspEditorManager = LspEditorManager()
    private val searchStateManager = SearchStateManager()
    private val tabManager = EditorTabManager(context, editorManager)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val codeEditorCallbacks = mutableMapOf<String, CodeEditorCallback>()
    private val tabPaneMap = mutableStateMapOf<String, EditorPaneId>()
    private val activeTabIdByPane = mutableStateMapOf<EditorPaneId, String>()
    private val navigationBackStack = mutableStateListOf<NavigationHistoryEntry>()
    private val navigationForwardStack = mutableStateListOf<NavigationHistoryEntry>()
    private val maxNavigationHistorySize = 100
    private var pendingSaveAllNotificationTargets: List<ActiveSaveTarget> = emptyList()

    private val lspStatusesByTabId = mutableStateMapOf<String, EditorStatus>()
    private val diagnosticsByFilePath = mutableStateMapOf<String, List<Diagnostic>>()
    private var diagnosticsObserver: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null

    init {
        // 注入 LspPluginManager 到 LspEditorManager
        GlobalContext.getOrNull()?.getOrNull<LspPluginManager>()?.let {
            lspEditorManager.setLspPluginManager(it)
        }

        lspEditorManager.onDiagnosticsChanged = { fileUri, diagnostics ->
            val normalizedPath = fileUriToNormalizedPath(fileUri)
            if (normalizedPath != null) {
                diagnosticsByFilePath[normalizedPath] = diagnostics
            }
            PluginHostEventDispatcher.emitDiagnosticsChanged(fileUri, diagnostics)
            diagnosticsObserver?.invoke(fileUri, diagnostics)
        }

        lspEditorManager.onLspStatusChanged = { tabId, status ->
            lspStatusesByTabId[tabId] = status
        }

        // 设置标签关闭回调，清理状态
        tabManager.onTabClosed = { tabId, contentType ->
            if (contentType == ContentType.CODE || contentType == ContentType.JSON) {
                lspEditorManager.releaseLspEditor(tabId)
                lspStatusesByTabId.remove(tabId)
            }
            tabPaneMap.remove(tabId)
            activeTabIdByPane
                .filterValues { it == tabId }
                .keys
                .toList()
                .forEach(activeTabIdByPane::remove)
            normalizeEditorPaneState()
            codeEditorCallbacks.remove(tabId)
            searchStateManager.cleanupForTab(tabId)
            dismissPeekDefinitionPanel(tabId)
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

    var isSplitEditorEnabled by mutableStateOf(false)
        private set

    var focusedPane by mutableStateOf(EditorPaneId.PRIMARY)
        private set

    internal var peekDefinitionPanelState by mutableStateOf<PeekDefinitionPanelState?>(null)
        private set

    private fun resolveProjectRootPath(): String? = projectRootPathProvider()
        ?.takeIf { it.isNotBlank() }

    internal fun getEditorProjectRootPathOrNull(): String? = resolveProjectRootPath()

    internal fun showPeekDefinitionLoading(ownerTabId: String, title: String) {
        peekDefinitionPanelState = PeekDefinitionPanelState(
            ownerTabId = ownerTabId,
            title = title,
            locations = emptyList(),
            isLoading = true
        )
    }

    internal fun showPeekDefinitionResults(
        ownerTabId: String,
        title: String,
        locations: List<LocationItem>
    ) {
        peekDefinitionPanelState = PeekDefinitionPanelState(
            ownerTabId = ownerTabId,
            title = title,
            locations = locations,
            isLoading = false
        )
    }

    internal fun dismissPeekDefinitionPanel(ownerTabId: String? = null) {
        val current = peekDefinitionPanelState ?: return
        if (ownerTabId == null || current.ownerTabId == ownerTabId) {
            peekDefinitionPanelState = null
        }
    }

    internal fun getBookmarksProjectRootPathOrNull(): String? = resolveProjectRootPath()

    // ========== 诊断回调 ==========

    var onLspDiagnosticsChanged: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null
        set(value) {
            field = value
            diagnosticsObserver = value
        }

    private fun readDiagnosticsForFile(file: File): List<Diagnostic> {
        val normalizedPath = fileToNormalizedPath(file)
        return diagnosticsByFilePath[normalizedPath].orEmpty()
    }

    internal fun getDiagnosticsFlow(file: File): Flow<List<Diagnostic>> = snapshotFlow { readDiagnosticsForFile(file) }
        .distinctUntilChanged()

    // ========== LSP 导航回调 ==========

    /**
     * LSP 导航请求回调
     *
     * 当用户在上下文菜单中点击导航操作时触发。
     * 参数：tabId, navigationType（"definition"/"references"/"typeDefinition"/"implementation"/"callHierarchyIncoming"/"switchHeaderSource"）
     */
    var onLspNavigationRequested: ((tabId: String, navigationType: String) -> Unit)? = null

    /**
     * LSP Code Actions 请求回调
     *
     * 参数：tabId, startLine, startColumn, endLine, endColumn
     */
    var onLspCodeActionsRequested: ((tabId: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) -> Unit)? = null

    /**
     * LSP Rename 请求回调
     *
     * 参数：tabId, line, column, currentName
     */
    var onLspRenameRequested: ((tabId: String, line: Int, column: Int, currentName: String) -> Unit)? = null

    internal fun supportsBasicLspNavigation(file: File): Boolean = lspEditorManager.supportsBasicNavigation(file)

    internal fun supportsAdvancedLspNavigation(file: File): Boolean = lspEditorManager.supportsAdvancedNavigation(file)

    internal fun supportsActiveCallHierarchyIncoming(): Boolean {
        val tab = getActiveTab() ?: return false
        val status = getLspStatus(tab.id)
        if (!isInteractiveLspStatus(status)) return false
        return lspEditorManager.supportsCallHierarchyIncoming(tab.id, tab.file)
    }

    internal fun supportsLspRefactorActions(file: File): Boolean = lspEditorManager.supportsRefactorActions(file)

    internal fun supportsHeaderSourceSwitch(file: File): Boolean = lspEditorManager.supportsHeaderSourceSwitch(file)

    internal fun requestActiveLspNavigation(navigationType: String): Boolean {
        val tab = getActiveTab() ?: return false
        if (!supportsLspNavigationType(tab.id, tab.file, navigationType)) return false
        val callback = onLspNavigationRequested ?: return false
        callback(tab.id, navigationType)
        return true
    }

    internal fun requestActiveLspCodeActions(): Boolean {
        val tab = getActiveTab() ?: return false
        if (!supportsLspRefactorActions(tab.file)) return false
        val callback = onLspCodeActionsRequested ?: return false
        val cursor = getCursorPositionInActiveTab() ?: return false
        val selection = getSelectionSnapshotInActiveTab()
        val startLine = selection?.startLine ?: cursor.line
        val startColumn = selection?.startColumn ?: cursor.column
        val endLine = selection?.endLine ?: cursor.line
        val endColumn = selection?.endColumn ?: cursor.column

        callback(tab.id, startLine, startColumn, endLine, endColumn)
        return true
    }

    internal fun requestActiveLspRename(): Boolean {
        val tab = getActiveTab() ?: return false
        if (!supportsLspRefactorActions(tab.file)) return false
        val callback = onLspRenameRequested ?: return false
        val cursor = getCursorPositionInActiveTab() ?: return false
        val currentName = resolveIdentifierAroundActiveCursor(cursor)

        callback(tab.id, cursor.line, cursor.column, currentName)
        return true
    }

    private fun supportsLspNavigationType(tabId: String, file: File, navigationType: String): Boolean = when (navigationType) {
        "definition",
        "peekDefinition",
        "references" -> supportsBasicLspNavigation(file)
        "typeDefinition",
        "implementation" -> supportsAdvancedLspNavigation(file)
        "callHierarchyIncoming" -> lspEditorManager.supportsCallHierarchyIncoming(tabId, file)
        "switchHeaderSource" -> supportsHeaderSourceSwitch(file)
        else -> false
    }

    internal fun getLspStatus(tabId: String): EditorStatus = lspStatusesByTabId[tabId] ?: EditorStatus.NoLsp

    internal fun getLspStatusFlow(tabId: String): Flow<EditorStatus> = snapshotFlow {
        lspStatusesByTabId[tabId] ?: EditorStatus.NoLsp
    }
        .distinctUntilChanged()

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

    private fun getActiveLspTabIdOrNull(): String? {
        val tab = getActiveTab() ?: return null
        val status = getLspStatus(tab.id)
        return tab.id.takeIf { isInteractiveLspStatus(status) }
    }

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
        search: (String, SearchOptions) -> List<CodeSearchResult>,
        goToMatch: (CodeSearchResult) -> Unit,
        editorCallback: CodeEditorCallback
    ) {
        bindCodeViewerSearchCallback(
            tabId = tabId,
            search = search,
            goToMatch = goToMatch
        )
        registerCodeEditorCallback(tabId, editorCallback)
    }

    internal fun unbindCodeEditorCallbacks(tabId: String) {
        unbindCodeViewerSearchCallback(tabId)
        unregisterCodeEditorCallback(tabId)
    }

    internal fun registerCodeEditorCallback(tabId: String, callback: CodeEditorCallback) {
        codeEditorCallbacks[tabId] = callback
        // 新打开的 Editor 立即应用当前配置（避免等待 flow 下一次 emit）
        runCatching { callback.applyEditorSettings(Prefs.editorSettingsFlow.value) }
            .onFailure { t ->
                Timber.tag("EditorContainerState").w(t, "Failed to apply editor settings for tab=%s", tabId)
            }
        runCatching { callback.applyEditorColorScheme(resolveEditorColorScheme(context)) }
            .onFailure { t ->
                Timber.tag("EditorContainerState").w(t, "Failed to apply editor theme for tab=%s", tabId)
            }
    }

    internal fun unregisterCodeEditorCallback(tabId: String) {
        codeEditorCallbacks.remove(tabId)
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

    internal fun canUndoInActiveTab(): Boolean = getActiveTab()?.canUndo ?: false

    internal fun canRedoInActiveTab(): Boolean = getActiveTab()?.canRedo ?: false

    internal fun isActiveTabDirty(): Boolean = getActiveTab()?.isDirty ?: false

    private fun getActiveCodeEditorCallback(): CodeEditorCallback? {
        val tab = getActiveTab() ?: return null
        if (!hasAttachedCodeEditor(tab.id, tab.contentType)) return null
        return codeEditorCallbacks[tab.id]
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
                recordNavigationTransition(
                    source = source,
                    target = navigationEntryOf(activeEditor.file, line, column)
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

    internal fun canNavigateBack(): Boolean = navigationBackStack.isNotEmpty()

    internal fun canNavigateForward(): Boolean = navigationForwardStack.isNotEmpty()

    internal fun navigateBack(): Boolean = navigateHistory(
        sourceStack = navigationBackStack,
        destinationStack = navigationForwardStack
    )

    internal fun navigateForward(): Boolean = navigateHistory(
        sourceStack = navigationForwardStack,
        destinationStack = navigationBackStack
    )

    private fun navigateHistory(
        sourceStack: SnapshotStateList<NavigationHistoryEntry>,
        destinationStack: SnapshotStateList<NavigationHistoryEntry>
    ): Boolean {
        val current = snapshotActiveNavigationLocationOrNull() ?: return false
        while (sourceStack.isNotEmpty()) {
            val target = sourceStack.removeAt(sourceStack.lastIndex)
            if (target.isSameNavigationLocation(current)) continue

            val targetFile = File(target.filePath)
            if (!targetFile.exists() || targetFile.isDirectory) continue

            if (openFileAndGoToPosition(targetFile, target.line, target.column, recordHistory = false)) {
                pushNavigationEntry(destinationStack, current)
                return true
            }
        }
        return false
    }

    private fun snapshotActiveNavigationLocationOrNull(): NavigationHistoryEntry? {
        val file = getActiveFileOrNull() ?: return null
        val cursor = getCursorPositionInActiveTab() ?: return null
        return navigationEntryOf(file, cursor.line, cursor.column)
    }

    private fun navigationEntryOf(file: File, line: Int, column: Int): NavigationHistoryEntry = NavigationHistoryEntry(
        filePath = file.absolutePath,
        line = line.coerceAtLeast(0),
        column = column.coerceAtLeast(0)
    )

    private fun recordNavigationTransition(
        source: NavigationHistoryEntry?,
        target: NavigationHistoryEntry
    ) {
        if (source == null || source.isSameNavigationLocation(target)) return
        pushNavigationEntry(navigationBackStack, source)
        navigationForwardStack.clear()
    }

    private fun pushNavigationEntry(
        stack: SnapshotStateList<NavigationHistoryEntry>,
        entry: NavigationHistoryEntry
    ) {
        if (stack.lastOrNull()?.isSameNavigationLocation(entry) == true) return
        stack.add(entry)
        while (stack.size > maxNavigationHistorySize) {
            stack.removeAt(0)
        }
    }

    private fun NavigationHistoryEntry.isSameNavigationLocation(other: NavigationHistoryEntry): Boolean = normalizeOpenTabLookupPath(filePath) == normalizeOpenTabLookupPath(other.filePath) &&
        line == other.line &&
        column == other.column

    fun replaceAllInActiveTab(findText: String, replaceText: String): Int {
        if (findText.isEmpty()) return 0
        val callback = getActiveCodeEditorCallback() ?: return 0
        val searchState = currentSearchState
        val caseSensitive = searchState.caseSensitive
        val useRegex = searchState.useRegex
        return callback.replaceAll(findText, replaceText, caseSensitive, useRegex)
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
        val callback = codeEditorCallbacks[tabId] ?: return false
        return callback.applyTextEdits(edits)
    }

    fun applyTextEditsInActiveTab(edits: List<TextEditOperation>): Boolean {
        val activeTab = getActiveTab() ?: return false
        return applyTextEditsInTab(
            tabId = activeTab.id,
            edits = edits
        )
    }

    fun toggleLineCommentInActiveTab(commentToken: String): Boolean {
        val callback = getActiveCodeEditorCallback() ?: return false
        return callback.toggleLineComment(commentToken)
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
        val previousActiveTabId = getActiveTabId()
        tabManager.syncFromManager(managerTabs, activeTabId)
        normalizeEditorPaneState(preferredActiveTabId = activeTabId)
        val currentActiveTabId = getActiveTabId()
        if (previousActiveTabId != null && previousActiveTabId != currentActiveTabId) {
            releaseTinaLspForTab(previousActiveTabId)
        }
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
            recordNavigationTransition(
                source = source,
                target = navigationEntryOf(openedTab.file, line, column)
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

    fun openFileWithType(file: File, contentType: ContentType): Int {
        val existingTabIds = tabs.map { it.id }.toSet()
        val openedIndex = tabManager.openFileWithType(file, contentType)
        syncOpenedTabPane(openedIndex, existingTabIds)
        return openedIndex
    }

    fun requestCloseTab(index: Int) {
        val closedTabId = tabs.getOrNull(index)?.id
        tabManager.requestCloseTab(index)
        if (closedTabId != null && tabs.none { it.id == closedTabId }) {
            tabPaneMap.remove(closedTabId)
            activeTabIdByPane
                .filterValues { it == closedTabId }
                .keys
                .toList()
                .forEach(activeTabIdByPane::remove)
        }
        normalizeEditorPaneState()
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
        }
        return closed
    }

    fun confirmDiscardAndClose() {
        val hadPendingClose = pendingCloseTab != null
        tabManager.confirmDiscardAndClose()
        if (hadPendingClose) {
            normalizeEditorPaneState()
        }
    }

    fun cancelClose() = tabManager.cancelClose()

    fun consumeLastOpenError(): String? = tabManager.consumeLastOpenError()

    fun selectTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val pane = resolvePaneForTab(tab.id)
        selectTabInPane(pane, index)
    }

    fun closeOtherTabs(exceptIndex: Int) {
        val keptTabId = tabs.getOrNull(exceptIndex)?.id
        tabManager.closeOtherTabs(exceptIndex)
        if (keptTabId != null) {
            tabPaneMap.keys
                .filter { it != keptTabId }
                .toList()
                .forEach(tabPaneMap::remove)
            val keptPane = resolvePaneForTab(keptTabId)
            activeTabIdByPane.keys
                .filter { it != keptPane }
                .toList()
                .forEach(activeTabIdByPane::remove)
        }
        normalizeEditorPaneState(preferredActiveTabId = keptTabId)
    }

    fun closeOtherTabsForActiveTab(): Boolean {
        val activeIndex = activeTabIndex.takeIf { it in tabs.indices } ?: return false
        closeOtherTabs(activeIndex)
        return true
    }

    fun closeAllTabs() {
        tabManager.closeAllTabs()
        tabPaneMap.clear()
        activeTabIdByPane.clear()
        isSplitEditorEnabled = false
        focusedPane = EditorPaneId.PRIMARY
    }

    fun updateTabState(tabId: String, isDirty: Boolean, canUndo: Boolean, canRedo: Boolean) {
        tabManager.updateTabState(tabId, isDirty, canUndo, canRedo)
    }

    internal fun rememberDirtyTabsForSaveAllNotification() {
        pendingSaveAllNotificationTargets = tabs
            .asSequence()
            .filter { it.isDirty }
            .map { tab ->
                ActiveSaveTarget(
                    tabId = tab.id,
                    file = tab.file
                )
            }
            .toList()
    }

    internal fun resolveSuccessfulSaveAllNotificationTargets(
        results: List<SaveResult>
    ): List<ActiveSaveTarget> {
        val targets = pendingSaveAllNotificationTargets
        pendingSaveAllNotificationTargets = emptyList()
        if (targets.isEmpty() || results.isEmpty()) return emptyList()
        return targets.zip(results).mapNotNull { (target, result) ->
            target.takeIf { result is SaveResult.Success }
        }
    }

    internal fun notifySuccessfulSaveAllResults(
        results: List<SaveResult>,
        fullText: String = ""
    ) {
        resolveSuccessfulSaveAllNotificationTargets(results)
            .forEach { target ->
                notifyFileSaved(target.tabId, target.file, fullText)
            }
    }

    internal fun getTabToolbarStateFlow(tabId: String): Flow<TabToolbarState>? = editorManager.getSessionState(tabId)
        ?.map { docState ->
            TabToolbarState(
                isDirty = docState.isDirty,
                canUndo = docState.canUndo,
                canRedo = docState.canRedo,
                charsetName = docState.charsetName
            )
        }
        ?.distinctUntilChanged()

    internal fun getTabLastEditAtFlow(tabId: String): Flow<Long?>? = editorManager.getSessionState(tabId)
        ?.map { it.lastEditAt }
        ?.distinctUntilChanged()

    internal fun getActiveEditorSessionAlertFlow(): Flow<ActiveEditorSessionAlertState>? {
        val activeTab = getActiveTab() ?: return null
        return editorManager.getSessionState(activeTab.id)
            ?.map { docState ->
                ActiveEditorSessionAlertState(
                    tabId = activeTab.id,
                    file = activeTab.file,
                    hasExternalModification = docState.hasExternalModification,
                    lastError = docState.lastError
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                )
            }
            ?.distinctUntilChanged()
    }

    private fun getSession(tabId: String): DocumentSession? = editorManager.getSession(tabId)

    internal fun attachTabEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        getSession(tabId)?.attachEditor(binding)
    }

    internal fun detachTabEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        getSession(tabId)?.detachEditor(binding)
    }

    internal fun notifyTabEditorContentChanged(
        tabId: String,
        canUndo: Boolean,
        canRedo: Boolean,
        changeCausedByUndoManager: Boolean
    ) {
        getSession(tabId)?.notifyEditorContentChanged(
            canUndo = canUndo,
            canRedo = canRedo,
            changeCausedByUndoManager = changeCausedByUndoManager
        )
    }

    internal fun markTabEditorSnapshotClean(tabId: String, charset: Charset) {
        getSession(tabId)?.markEditorSnapshotClean(charset)
    }

    internal fun updateTabCursorPosition(tabId: String, line: Int, column: Int) {
        getSession(tabId)?.updateCursorPosition(line, column)
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
        getSession(tabId)?.updateScrollPosition(scrollX, scrollY)
    }

    fun getTabsForPane(pane: EditorPaneId): List<EditorTabState> {
        return tabs.filter { resolvePaneForTab(it.id) == pane }
    }

    fun getActiveIndexForPane(pane: EditorPaneId): Int {
        val activeTabId = activeTabIdByPane[pane]
        val activeIndex = activeTabId?.let { id -> tabs.indexOfFirst { it.id == id } } ?: -1
        if (activeIndex >= 0 && resolvePaneForTab(activeTabId!!) == pane) return activeIndex
        return tabs.indexOfFirst { resolvePaneForTab(it.id) == pane }
    }

    fun focusEditorPane(pane: EditorPaneId) {
        val targetPane = pane.takeIf { isSplitEditorEnabled || it == EditorPaneId.PRIMARY }
            ?: EditorPaneId.PRIMARY
        focusedPane = targetPane
        val activeIndex = getActiveIndexForPane(targetPane)
        if (activeIndex in tabs.indices) {
            tabManager.selectTab(activeIndex)
        }
    }

    fun selectTabInPane(pane: EditorPaneId, index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val targetPane = if (isSplitEditorEnabled) pane else EditorPaneId.PRIMARY
        if (isSplitEditorEnabled && resolvePaneForTab(tab.id) != targetPane) {
            return
        }
        focusedPane = targetPane
        tabPaneMap[tab.id] = targetPane
        activeTabIdByPane[targetPane] = tab.id
        tabManager.selectTab(index)
        normalizeEditorPaneState(preferredActiveTabId = tab.id)
    }

    fun toggleSplitEditor() {
        if (isSplitEditorEnabled) {
            closeSecondaryPane()
        } else {
            isSplitEditorEnabled = true
            if (tabs.isNotEmpty()) {
                val activeTab = getActiveTab()
                tabs.forEach { tab ->
                    tabPaneMap.putIfAbsent(tab.id, EditorPaneId.PRIMARY)
                }
                activeTab?.let {
                    focusedPane = EditorPaneId.PRIMARY
                    activeTabIdByPane[EditorPaneId.PRIMARY] = it.id
                }
            }
            normalizeEditorPaneState()
        }
    }

    fun closeSecondaryPane() {
        val activeTabId = getActiveTabId()
        tabPaneMap.keys.toList().forEach { tabPaneMap[it] = EditorPaneId.PRIMARY }
        activeTabIdByPane.clear()
        activeTabId?.let { activeTabIdByPane[EditorPaneId.PRIMARY] = it }
        isSplitEditorEnabled = false
        focusedPane = EditorPaneId.PRIMARY
        normalizeEditorPaneState(preferredActiveTabId = activeTabId)
    }

    fun canMoveActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        return !isSplitEditorEnabled || resolvePaneForTab(activeTab.id) != EditorPaneId.SECONDARY
    }

    fun moveActiveTabToSecondaryPane(): Boolean {
        val activeTab = getActiveTab() ?: return false
        if (isSplitEditorEnabled && resolvePaneForTab(activeTab.id) == EditorPaneId.SECONDARY) {
            return false
        }
        isSplitEditorEnabled = true
        tabPaneMap[activeTab.id] = EditorPaneId.SECONDARY
        activeTabIdByPane[EditorPaneId.SECONDARY] = activeTab.id
        focusedPane = EditorPaneId.SECONDARY
        normalizeEditorPaneState(preferredActiveTabId = activeTab.id)
        tabManager.findTabIndex(activeTab.id)
            .takeIf { it in tabs.indices }
            ?.let(tabManager::selectTab)
        return true
    }

    private fun assignOpenedTabToFocusedPane(openedIndex: Int) {
        val openedTab = tabs.getOrNull(openedIndex) ?: return
        val targetPane = if (isSplitEditorEnabled) focusedPane else EditorPaneId.PRIMARY
        tabPaneMap[openedTab.id] = targetPane
        activeTabIdByPane[targetPane] = openedTab.id
        focusedPane = targetPane
        normalizeEditorPaneState(preferredActiveTabId = openedTab.id)
    }

    private fun syncOpenedTabPane(openedIndex: Int, existingTabIds: Set<String>) {
        val openedTab = tabs.getOrNull(openedIndex) ?: return
        if (openedTab.id !in existingTabIds) {
            assignOpenedTabToFocusedPane(openedIndex)
            return
        }

        val targetPane = resolvePaneForTab(openedTab.id)
        focusedPane = targetPane
        activeTabIdByPane[targetPane] = openedTab.id
        normalizeEditorPaneState(preferredActiveTabId = openedTab.id)
    }

    private fun normalizeEditorPaneState(preferredActiveTabId: String? = getActiveTabId()) {
        val liveTabIds = tabs.map { it.id }.toSet()
        tabPaneMap.keys
            .filter { it !in liveTabIds }
            .toList()
            .forEach(tabPaneMap::remove)
        activeTabIdByPane
            .filterValues { it !in liveTabIds }
            .keys
            .toList()
            .forEach(activeTabIdByPane::remove)

        tabs.forEach { tab ->
            tabPaneMap.putIfAbsent(tab.id, EditorPaneId.PRIMARY)
        }

        if (!isSplitEditorEnabled) {
            tabPaneMap.keys.toList().forEach { tabPaneMap[it] = EditorPaneId.PRIMARY }
            focusedPane = EditorPaneId.PRIMARY
        }

        EditorPaneId.values().forEach { pane ->
            val activeTabId = activeTabIdByPane[pane]
            if (activeTabId == null || activeTabId !in liveTabIds || resolvePaneForTab(activeTabId) != pane) {
                getTabsForPaneInternal(pane).firstOrNull()?.let { activeTabIdByPane[pane] = it.id }
                    ?: activeTabIdByPane.remove(pane)
            }
        }

        if (isSplitEditorEnabled && getTabsForPaneInternal(focusedPane).isEmpty()) {
            focusedPane = EditorPaneId.values()
                .firstOrNull { getTabsForPaneInternal(it).isNotEmpty() }
                ?: EditorPaneId.PRIMARY
        }

        val targetTabId = preferredActiveTabId
            ?.takeIf { it in liveTabIds && resolvePaneForTab(it) == focusedPane }
            ?: activeTabIdByPane[focusedPane]
            ?: tabs.firstOrNull()?.id
            ?: return
        val targetIndex = tabs.indexOfFirst { it.id == targetTabId }
        if (targetIndex in tabs.indices && targetIndex != activeTabIndex) {
            tabManager.selectTab(targetIndex)
        }
    }

    private fun getTabsForPaneInternal(pane: EditorPaneId): List<EditorTabState> =
        tabs.filter { resolvePaneForTab(it.id) == pane }

    private fun resolvePaneForTab(tabId: String): EditorPaneId =
        if (isSplitEditorEnabled) {
            tabPaneMap[tabId] ?: EditorPaneId.PRIMARY
        } else {
            EditorPaneId.PRIMARY
        }

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

    private fun normalizeOpenTabLookupPath(path: String): String {
        val normalized = path.replace('\\', '/')
        return if (File.separatorChar == '\\') normalized.lowercase() else normalized
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

    // ========== AI 集成支持 ==========

    internal fun snapshotActiveFileContextOrNull(): ActiveFileContextSnapshot? {
        val activeFile = getActiveFileOrNull() ?: return null
        val content = readActiveTabText() ?: return null
        if (content.isBlank()) return null

        return ActiveFileContextSnapshot(
            file = activeFile,
            language = activeFile.extension,
            content = content
        )
    }

    internal fun snapshotActiveSelectedCodeContextOrNull(): ActiveSelectedCodeContextSnapshot? {
        val activeFile = getActiveFileOrNull() ?: return null
        val selection = getSelectionSnapshotInActiveTab() ?: return null
        if (selection.text.isBlank()) return null

        return ActiveSelectedCodeContextSnapshot(
            file = activeFile,
            language = activeFile.extension,
            content = selection.text,
            startLine = selection.startLine + 1,
            endLine = selection.endLine + 1
        )
    }

    fun snapshotCurrentFileContext(): MessageContext.CurrentFile? {
        val context = snapshotActiveFileContextOrNull() ?: return null

        return MessageContext.CurrentFile(
            fileName = context.file.name,
            language = context.language,
            content = context.content
        )
    }

    fun snapshotSelectedCodeContext(): MessageContext.SelectedCode? {
        val selection = snapshotActiveSelectedCodeContextOrNull() ?: return null

        return MessageContext.SelectedCode(
            fileName = selection.file.name,
            language = selection.language,
            content = selection.content,
            startLine = selection.startLine,
            endLine = selection.endLine
        )
    }

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
        lspStatusesByTabId.remove(tabId)
    }

    fun notifyTinaTextChanged(tabId: String, change: TextChange) {
        lspEditorManager.onTinaDocumentChanged(tabId, change)
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

    suspend fun requestLspSemanticTokens(
        tabId: String,
        visibleLines: IntRange,
        documentVersion: Long
    ): List<SemanticToken> = lspEditorManager.requestSemanticTokens(
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
        codeEditorCallbacks.forEach { (tabId, callback) ->
            runCatching { callback.applyEditorColorScheme(scheme) }
                .onFailure { t ->
                    Timber.tag("EditorContainerState").w(t, "Failed to apply editor theme for tab=%s", tabId)
                }
        }
    }

    fun updateEditorSettings(context: android.content.Context) {
        val settings = Prefs.editorSettingsFlow.value
        codeEditorCallbacks.forEach { (tabId, callback) ->
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

        val refreshCandidates = tabs.count { tab ->
            if (!hasAttachedCodeEditor(tab.id, tab.contentType)) return@count false
            tab.file.extension.lowercase() in CxxFileSupport.clangdSupportedExtensions
        }

        if (refreshCandidates <= 0) return
        lspEditorManager.refreshLspConnection(context)
        Timber.tag("EditorContainerState")
            .i("Dependency revision=%d detected, refreshed %d C/C++ tab(s)", revision, refreshCandidates)
    }

    fun restoreFromManager() = tabManager.restoreFromManager()

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
        codeEditorCallbacks.clear()
        navigationBackStack.clear()
        navigationForwardStack.clear()
        diagnosticsByFilePath.clear()
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

    private fun isInteractiveLspStatus(status: EditorStatus): Boolean = status == EditorStatus.Ready || status == EditorStatus.Busy

    private fun hasAttachedCodeEditor(tabId: String, contentType: ContentType): Boolean = isCodeEditableType(contentType) && codeEditorCallbacks.containsKey(tabId)

    private fun resolveActiveEditableEditorBindingResult(): ActiveEditableEditorBindingResult {
        val activeTab = getActiveTab() ?: return ActiveEditableEditorBindingResult.NoOpenFile
        if (!hasAttachedCodeEditor(activeTab.id, activeTab.contentType)) {
            return ActiveEditableEditorBindingResult.UnsupportedEditor
        }
        val callback = codeEditorCallbacks[activeTab.id]
            ?: return ActiveEditableEditorBindingResult.UnsupportedEditor
        return ActiveEditableEditorBindingResult.Available(
            file = activeTab.file,
            callback = callback
        )
    }

    private fun resolveIdentifierAroundActiveCursor(cursor: CursorSnapshot): String {
        val lineText = readActiveTabText()
            ?.lineSequence()
            ?.drop(cursor.line)
            ?.firstOrNull()
            ?: return ""
        if (lineText.isEmpty()) return ""

        var anchor = cursor.column.coerceIn(0, lineText.length)
        if (anchor >= lineText.length || !lineText[anchor].isEditorIdentifierChar()) {
            val leftIndex = (anchor - 1).coerceAtLeast(0)
            if (leftIndex >= lineText.length || !lineText[leftIndex].isEditorIdentifierChar()) {
                return ""
            }
            anchor = leftIndex
        }

        var start = anchor
        while (start > 0 && lineText[start - 1].isEditorIdentifierChar()) {
            start--
        }

        var end = anchor + 1
        while (end < lineText.length && lineText[end].isEditorIdentifierChar()) {
            end++
        }

        return lineText.substring(start, end)
    }

    private fun Char.isEditorIdentifierChar(): Boolean = isLetterOrDigit() || this == '_' || this == '~'
}

private fun EditorContainerState.SelectionSnapshot.toEventPayload(): EditorSelectionPayload = EditorSelectionPayload(
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
    val state = remember(editorManager, snippetManager, pluginThemeRegistry, projectSymbolIndexServiceProvider) {
        EditorContainerState(
            context,
            editorManager,
            snippetManager,
            pluginThemeRegistry,
            projectSymbolIndexServiceProvider,
            projectRootPathProvider
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

package com.wuxianggujun.tinaide.core.editorlsp

import android.content.Context
import com.wuxianggujun.tinaide.core.config.LspAssistSettings
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.lsp.CompileDatabaseProvider
import com.wuxianggujun.tinaide.core.lsp.ConnectionEvent
import com.wuxianggujun.tinaide.core.lsp.ConnectionState
import com.wuxianggujun.tinaide.core.lsp.ConnectionStateListener
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.lsp.LspClientSession
import com.wuxianggujun.tinaide.core.lsp.LspCodeActionService
import com.wuxianggujun.tinaide.core.lsp.LspConnectionProvider
import com.wuxianggujun.tinaide.core.lsp.LspDiagnosticsBridge
import com.wuxianggujun.tinaide.core.lsp.LspNavigationService
import com.wuxianggujun.tinaide.core.lsp.NativeClangdConnectionProvider
import com.wuxianggujun.tinaide.core.lsp.PRootClangdConnectionProvider
import com.wuxianggujun.tinaide.core.lsp.ProjectSyncManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfigManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionProvider
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMethod
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode
import com.wuxianggujun.tinaide.core.lsp.WorkspaceSymbolItem
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import com.wuxianggujun.tinaide.file.FileChangeListener
import com.wuxianggujun.tinaide.file.FileWatchRegistration
import com.wuxianggujun.tinaide.file.IFileWatchService
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInfo
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginReadinessDiagnostic
import com.wuxianggujun.tinaide.plugin.lsp.LspServerConfig
import com.wuxianggujun.tinaide.plugin.lsp.PluginLspConnectionProvider
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import timber.log.Timber

@Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
private typealias ProtocolMarkedString = org.eclipse.lsp4j.MarkedString

data class PluginLspDependencyNotReadyEvent(
    val pluginId: String,
    val pluginName: String,
    val message: String
)

sealed interface SemanticTokensRequestResult {
    data class Success(val tokens: List<SemanticToken>) : SemanticTokensRequestResult
    data object Unavailable : SemanticTokensRequestResult
}

class LspEditorManager(
    private val fileWatchService: IFileWatchService? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) {

    companion object {
        private const val TAG = "LspEditorManager"
        private const val COMPLETION_TIMEOUT_SECONDS = 6L
        private const val HOVER_TIMEOUT_SECONDS = 6L
        private const val SIGNATURE_HELP_TIMEOUT_SECONDS = 6L
        private const val SEMANTIC_TOKENS_TIMEOUT_SECONDS = 6L
        private const val FOLDING_RANGE_TIMEOUT_SECONDS = 6L
        private const val SEMANTIC_PREFETCH_MARGIN_LINES = 80
        private const val SEMANTIC_MAX_PREFETCH_SPAN_LINES = 480
        private const val SHARED_CXX_IDLE_SHUTDOWN_MS = 20_000L
    }

    private enum class SessionKind { CXX, PLUGIN, CMAKE, MAKE }

    private data class TabBinding(
        val tabId: String,
        val kind: SessionKind,
        val file: File,
        val projectRootPath: String?,
        val textProvider: () -> String,
    )

    private data class TabSession(
        val tabId: String,
        val file: File,
        val kind: SessionKind,
        val documentUri: String,
        val lspSession: LspClientSession? = null,
        val builtinSession: BuiltinLanguageServiceSession? = null,
    ) {
        val isConnected: Boolean
            get() = when {
                builtinSession?.isConnected == true -> true
                lspSession != null -> lspSession.isConnected && lspSession.isCurrentDocument(documentUri)
                else -> false
            }
    }

    private data class SemanticTokensCache(
        val documentVersion: Long,
        val cachedLines: IntRange,
        val tokens: List<SemanticToken>
    )

    private data class FoldingRangesCache(
        val documentVersion: Long,
        val regions: List<FoldRegion>
    )

    private val stateLock = Any()
    private val tabRequestTracker = LspTabRequestTracker(stateLock)
    private val lspScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sharedCxxSessionMutex = Mutex()
    private val compileSetupCache = LspCompileSetupCache(
        scope = lspScope,
        linuxEnvironmentProvider = linuxEnvironmentProvider,
        resolveRunMode = ::resolveClangdRunMode,
        resolveLanguageId = ::languageIdForFile,
    )

    private var lspProjectRoot: String? = null
    private var lspCompileCommandsDirOverride: String? = null
    private var isUsingRemoteLsp: Boolean = false
    private var currentSyncMode: RemoteLspSyncMode? = null

    private val tabSessions = mutableMapOf<String, TabSession>()
    private val tabBindings = mutableMapOf<String, TabBinding>()
    private val attachTokenCache = mutableMapOf<String, Any>()
    private val lspKnownUris = mutableSetOf<String>()
    private val remoteSyncedProjects = mutableSetOf<String>()
    private val semanticTokensCache = mutableMapOf<String, SemanticTokensCache>()
    private val foldingRangesCache = mutableMapOf<String, FoldingRangesCache>()
    private val documentVersions = mutableMapOf<String, Long>()
    private val builtinDiagnosticsRequestTokens = mutableMapOf<String, Any>()
    private val builtinDiagnosticsJobs = mutableMapOf<String, Job>()
    private val completionWarmupTabIds = mutableSetOf<String>()
    private var sharedCxxSession: LspClientSession? = null
    private var sharedCxxShutdownJob: Job? = null

    @Volatile
    private var foldingRangeEnabled: Boolean = false

    private var lspPluginManager: LspPluginManager? = null

    // 文件监听（workspace/didChangeWatchedFiles）
    private var workspaceFileWatcher: FileWatchRegistration? = null
    private val watchedPatterns = mutableListOf<Pair<String, List<LspFileWatchPattern>>>()

    @Volatile
    private var assistSettings: LspAssistSettings = LspAssistSettings(
        signatureHelpEnabled = true,
        inlayHintsEnabled = true,
        semanticTokensEnabled = true,
    )

    var onDiagnosticsChanged: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null
    var onLspStatusChanged: ((tabId: String, status: EditorStatus) -> Unit)? = null
    var onPluginLspDependencyNotReady: ((PluginLspDependencyNotReadyEvent) -> Unit)? = null

    val codeActionService = LspCodeActionService()
    val navigationService = LspNavigationService()

    private val lspSupportedExtensions: Set<String> = CxxFileSupport.clangdSupportedExtensions

    fun setLspPluginManager(manager: LspPluginManager) {
        lspPluginManager = manager
    }

    fun isDocumentVersionCurrent(tabId: String, expectedVersion: Int): Boolean {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return false
        return tabSession.lspSession?.currentDocumentVersion(tabSession.documentUri) == expectedVersion
    }

    fun isLspSupported(file: File): Boolean = resolveAttachmentRoute(file) != LspAttachmentRoute.NONE

    fun supportsBasicNavigation(file: File): Boolean = when (resolveAttachmentRoute(file)) {
        LspAttachmentRoute.CXX,
        LspAttachmentRoute.PLUGIN,
        LspAttachmentRoute.BUILTIN_CMAKE,
        LspAttachmentRoute.BUILTIN_MAKE -> true
        LspAttachmentRoute.NONE -> false
    }

    fun supportsAdvancedNavigation(file: File): Boolean = when (resolveAttachmentRoute(file)) {
        LspAttachmentRoute.CXX,
        LspAttachmentRoute.PLUGIN -> true
        LspAttachmentRoute.BUILTIN_CMAKE,
        LspAttachmentRoute.BUILTIN_MAKE,
        LspAttachmentRoute.NONE -> false
    }

    fun supportsCallHierarchyIncoming(tabId: String, file: File): Boolean = when (resolveAttachmentRoute(file)) {
        LspAttachmentRoute.CXX,
        LspAttachmentRoute.PLUGIN -> {
            val session = synchronized(stateLock) { tabSessions[tabId]?.lspSession }
            session?.isConnected == true && session.supportsCallHierarchy
        }
        LspAttachmentRoute.BUILTIN_CMAKE,
        LspAttachmentRoute.BUILTIN_MAKE,
        LspAttachmentRoute.NONE -> false
    }

    fun supportsRefactorActions(file: File): Boolean = when (resolveAttachmentRoute(file)) {
        LspAttachmentRoute.CXX,
        LspAttachmentRoute.PLUGIN -> true
        LspAttachmentRoute.BUILTIN_CMAKE,
        LspAttachmentRoute.BUILTIN_MAKE,
        LspAttachmentRoute.NONE -> false
    }

    fun supportsHeaderSourceSwitch(file: File): Boolean = resolveAttachmentRoute(file) == LspAttachmentRoute.CXX

    fun attachTinaLsp(
        context: Context,
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean {
        val attached = when (resolveAttachmentRoute(file)) {
            LspAttachmentRoute.CXX -> {
                attachCxxLsp(context, file, tabId, projectRootPath, textProvider)
            }
            LspAttachmentRoute.BUILTIN_CMAKE -> {
                attachBuiltinCmakeLsp(file, tabId, projectRootPath, textProvider)
            }
            LspAttachmentRoute.BUILTIN_MAKE -> {
                attachBuiltinMakeLsp(file, tabId, projectRootPath, textProvider)
            }
            LspAttachmentRoute.PLUGIN -> {
                attachPluginLspInternal(context, file, tabId, projectRootPath, textProvider)
            }
            LspAttachmentRoute.NONE -> false
        }
        if (!attached) {
            updateLspStatus(tabId, EditorStatus.NoLsp)
            releaseSession(tabId, clearBinding = false)
            return false
        }
        return true
    }

    private fun resolveAttachmentRoute(file: File): LspAttachmentRoute = LspRoutingSupport.resolveAttachmentRoute(
        file = file,
        editorLspEnabled = Prefs.devEditorLspEnabled,
        builtinCmakeLspEnabled = Prefs.devBuiltinCmakeLspEnabled,
        cxxExtensions = lspSupportedExtensions,
        hasPluginServer = lspPluginManager?.getServerConfigForFile(file, file.resolveLspLanguageId()) != null
    )

    fun onTinaDocumentChanged(tabId: String, change: TextChange, documentVersion: Long) {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return
        if (!tabSession.isConnected) return
        synchronized(stateLock) {
            semanticTokensCache.remove(tabId)
            foldingRangesCache.remove(tabId)
            documentVersions[tabId] = maxOf(documentVersions[tabId] ?: Long.MIN_VALUE, documentVersion)
        }
        tabSession.builtinSession?.didChange(change)
        if (tabSession.builtinSession != null) {
            scheduleBuiltinDiagnostics(tabSession)
        }
        tabSession.lspSession?.let { session ->
            runCatching {
                session.didChange(
                    startLine = change.startLine,
                    startColumn = change.startColumn,
                    endLine = change.endLine,
                    endColumn = change.endColumn,
                    oldTextLength = change.oldText.length,
                    newText = change.newText
                )
            }.onFailure { Timber.tag(TAG).d("didChange failed: ${it.message}") }
        }
    }

    /**
     * 文件保存后调用：
     * 1. 向 LSP server 发送 textDocument/didSave 通知
     * 2. 若保存的是 CMakeLists.txt / .cmake / compile_commands.json，刷新 compile_commands 并重启 clangd
     */
    fun onFileSaved(context: Context, tabId: String, file: File, fullText: String) {
        // 1. 向当前 tab 的 LSP session 发送 didSave
        val tabSession = synchronized(stateLock) { tabSessions[tabId] }
        tabSession?.builtinSession?.takeIf { it.isConnected }?.let {
            it.didSave(fullText.ifEmpty { null })
            scheduleBuiltinDiagnostics(tabSession)
        }
        tabSession?.takeIf { it.isConnected }?.lspSession?.let {
            runCatching { it.didSave(fullText.ifEmpty { null }) }
                .onFailure { e -> Timber.tag(TAG).w(e, "didSave failed for %s", file.name) }
        }

        val isCmakeFile = file.name.equals("CMakeLists.txt", ignoreCase = true) ||
            file.extension.equals("cmake", ignoreCase = true)
        val isCompileCommandsFile = file.name.equals("compile_commands.json", ignoreCase = true)

        // 2. 配置文件 / compile_commands 保存 → 刷新 compile_commands.json 并重载 clangd
        if (isCmakeFile || isCompileCommandsFile) {
            val reason = if (isCompileCommandsFile) "compile_commands" else "CMake"
            Timber.tag(TAG).i("%s file saved (%s), scheduling LSP reload", reason, file.name)
            lspScope.launch(Dispatchers.IO) {
                val binding = synchronized(stateLock) {
                    tabBindings.values.firstOrNull { it.kind == SessionKind.CXX }
                } ?: return@launch
                val compileProvider = compileSetupCache.getProvider(context)
                compileSetupCache.invalidateForProject(binding.file, binding.projectRootPath)
                if (isCompileCommandsFile) {
                    compileProvider.prepareProvidedCompileCommandsForLsp(file, binding.projectRootPath)
                        ?: return@launch
                } else {
                    val prepared = compileProvider.prepare(binding.file, binding.projectRootPath)
                        ?: return@launch
                    val metaFile = java.io.File(prepared.compileCommandsDir, "compile_commands.tina.meta.properties")
                    metaFile.delete()
                    compileProvider.ensureWithResult(prepared)
                }
                withContext(Dispatchers.Main) {
                    Timber.tag(TAG).i("Restarting clangd after %s file change: %s", reason, file.name)
                    refreshLspConnection(context)
                }
            }
        }
    }

    suspend fun requestSemanticTokens(
        tabId: String,
        visibleLines: IntRange,
        documentVersion: Long
    ): SemanticTokensRequestResult = withContext(Dispatchers.IO) {
        if (!assistSettings.semanticTokensEnabled) {
            synchronized(stateLock) { semanticTokensCache.remove(tabId) }
            return@withContext SemanticTokensRequestResult.Success(emptyList())
        }
        if (visibleLines.isEmpty()) return@withContext SemanticTokensRequestResult.Success(emptyList())
        val versionAccepted = synchronized(stateLock) {
            val latestVersion = documentVersions[tabId]
            if (latestVersion != null && documentVersion < latestVersion) {
                false
            } else {
                documentVersions[tabId] = documentVersion
                true
            }
        }
        if (!versionAccepted) return@withContext SemanticTokensRequestResult.Unavailable
        val normalizedVisibleLines = normalizeVisibleLines(visibleLines)

        val cached = synchronized(stateLock) { semanticTokensCache[tabId] }
        if (cached != null &&
            cached.documentVersion == documentVersion &&
            cached.cachedLines.containsRange(normalizedVisibleLines)
        ) {
            return@withContext SemanticTokensRequestResult.Success(
                cached.tokens.filterToVisibleLines(normalizedVisibleLines)
            )
        }

        val tabSession = synchronized(stateLock) { tabSessions[tabId] }
            ?: return@withContext SemanticTokensRequestResult.Unavailable
        if (!tabSession.isConnected) return@withContext SemanticTokensRequestResult.Unavailable
        tabSession.builtinSession?.let { session ->
            val tokens = session.requestSemanticTokens()
            val cachedSuccessfully = synchronized(stateLock) {
                if (documentVersions[tabId] != documentVersion ||
                    tabSessions[tabId]?.builtinSession !== session
                ) {
                    false
                } else {
                    semanticTokensCache[tabId] = SemanticTokensCache(
                        documentVersion = documentVersion,
                        cachedLines = 0..Int.MAX_VALUE,
                        tokens = tokens
                    )
                    true
                }
            }
            if (!cachedSuccessfully) return@withContext SemanticTokensRequestResult.Unavailable
            return@withContext SemanticTokensRequestResult.Success(
                tokens.filterToVisibleLines(normalizedVisibleLines)
            )
        }
        val session = tabSession.lspSession ?: return@withContext SemanticTokensRequestResult.Unavailable
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val requestedLines = expandSemanticRequestLines(normalizedVisibleLines)

        val rangeRequest = SemanticTokensRangeParams().apply {
            textDocument = TextDocumentIdentifier(tabSession.documentUri)
            range = org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(requestedLines.first, 0),
                org.eclipse.lsp4j.Position((requestedLines.last + 1).coerceAtLeast(requestedLines.first + 1), 0)
            )
        }
        val fullRequest = SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(tabSession.documentUri)
        }

        // 策略（最多两次请求，避免最坏 18 秒阻塞）：
        //   缓存未命中（首次打开）→ 优先请求全量（full），可覆盖整个文档，一次填满缓存；
        //   缓存命中但区间不覆盖（滚动到新区域）→ 请求可见区间（range），延迟更低；
        //   range 失败 → fallback 全量。
        val preferFullFetch = cached == null
        val fullRawFirst = if (preferFullFetch) {
            awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.semanticTokensFull(fullRequest),
                timeoutSeconds = SEMANTIC_TOKENS_TIMEOUT_SECONDS,
                operation = "semanticTokens/full(${fileNameForLog(tabSession.file)})"
            )
        } else {
            null
        }
        // 若 full 未命中（缓存已存在走 range 路径）或 full 失败，则请求 range
        val rangeRaw = if (fullRawFirst == null) {
            awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.semanticTokensRange(rangeRequest),
                timeoutSeconds = SEMANTIC_TOKENS_TIMEOUT_SECONDS,
                operation = "semanticTokens/range(${fileNameForLog(tabSession.file)})"
            )
        } else {
            null
        }
        val raw = fullRawFirst ?: rangeRaw ?: return@withContext SemanticTokensRequestResult.Unavailable
        val fetchedFullDocument = fullRawFirst != null
        if (!isTabRequestStillValid(requestTicket)) return@withContext SemanticTokensRequestResult.Unavailable

        val decoded = LspSemanticTokenDecoder.decode(
            rawData = raw.data.orEmpty(),
            tokenTypes = session.semanticTokenLegendTypes(),
            tokenModifiers = session.semanticTokenLegendModifiers()
        ).asSequence()
            .filter { token ->
                token.length > 0 && (fetchedFullDocument || token.line in requestedLines)
            }
            .toList()
        val cachedLines = if (fetchedFullDocument) {
            0..Int.MAX_VALUE
        } else {
            requestedLines
        }

        val cachedSuccessfully = synchronized(stateLock) {
            if (documentVersions[tabId] != documentVersion || tabSessions[tabId] !== tabSession) {
                false
            } else {
                semanticTokensCache[tabId] = SemanticTokensCache(
                    documentVersion = documentVersion,
                    cachedLines = cachedLines,
                    tokens = decoded
                )
                true
            }
        }
        if (!cachedSuccessfully) return@withContext SemanticTokensRequestResult.Unavailable
        return@withContext SemanticTokensRequestResult.Success(
            decoded.filterToVisibleLines(normalizedVisibleLines)
        )
    }

    suspend fun requestCompletion(
        tabId: String,
        position: Position,
        triggerChar: Char?
    ): CompletionFetchResult = withContext(Dispatchers.IO) {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] }
            ?: return@withContext CompletionFetchResult.TransientFailure(reason = "missing_tab_session")
        if (!tabSession.isConnected) {
            return@withContext CompletionFetchResult.TransientFailure(reason = "session_not_connected")
        }
        tabSession.builtinSession?.let { session ->
            return@withContext session.requestCompletion(position, triggerChar)
        }
        val session = tabSession.lspSession
            ?: return@withContext CompletionFetchResult.TransientFailure(reason = "missing_lsp_session")
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)

        val params = buildCompletionParams(tabSession.documentUri, position, triggerChar)
        val future = session.completion(params)
            ?: return@withContext CompletionFetchResult.TransientFailure(reason = "lsp_no_future")
        val result = awaitTrackedTabFuture(
            ticket = requestTicket,
            future = future,
            timeoutSeconds = COMPLETION_TIMEOUT_SECONDS,
            operation = "completion(${fileNameForLog(tabSession.file)})"
        ) ?: return@withContext CompletionFetchResult.TransientFailure(reason = "lsp_completion_timeout")

        val lspItems = when {
            result.isLeft -> result.left.orEmpty()
            result.isRight -> result.right?.items.orEmpty()
            else -> emptyList()
        }

        CompletionFetchResult.Success(
            lspItems.mapNotNull { item ->
                val mainTextEdit = if (item.textEdit == null) {
                    null
                } else {
                    normalizeMainCompletionTextEdit(item) ?: return@mapNotNull null
                }
                val additionalTextEdits = normalizeAdditionalCompletionTextEdits(item)
                    ?: return@mapNotNull null
                CompletionItem(
                    label = item.label.orEmpty(),
                    kind = mapCompletionKind(item.kind),
                    detail = item.detail,
                    documentation = extractDocumentation(item.documentation),
                    insertText = normalizeInsertText(item),
                    textEdit = mainTextEdit,
                    additionalTextEdits = additionalTextEdits,
                    sortText = item.sortText,
                    filterText = item.filterText,
                    source = CompletionSource.LSP,
                    snippetText = extractSnippetText(item)
                )
            }
        )
    }

    fun releaseLspEditor(tabId: String) = releaseSession(tabId, true)

    fun hasActiveLspConnection(tabId: String): Boolean = synchronized(stateLock) { tabSessions[tabId]?.isConnected == true }

    private fun hasTabBinding(tabId: String, file: File): Boolean = synchronized(stateLock) {
        tabBindings[tabId]?.file?.absolutePath == file.absolutePath
    }

    suspend fun requestCodeActions(
        tabId: String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): List<LspCodeActionService.CodeActionItem> = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = codeActionService.requestCodeActions(
            documentUri = tabSession.documentUri,
            startLine = startLine,
            startColumn = startColumn,
            endLine = endLine,
            endColumn = endColumn,
            codeActionRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.codeAction(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "codeAction(${fileNameForLog(tabSession.file)})"
                )
            }
        )
        if (isTabRequestStillValid(requestTicket)) result else emptyList()
    } ?: emptyList()

    suspend fun executeCodeAction(
        tabId: String,
        action: LspCodeActionService.CodeActionItem,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ) = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = codeActionService.executeCodeAction(
            item = action,
            resolveCodeActionRequest = { unresolved, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.resolveCodeAction(unresolved),
                    timeoutSeconds = timeoutSeconds,
                    operation = "resolveCodeAction(${fileNameForLog(tabSession.file)})"
                )
            },
            executeCommandRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.executeCommand(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "executeCodeAction(${fileNameForLog(tabSession.file)})"
                )
            },
            onApplyEdit = { edit ->
                if (isTabRequestStillValid(requestTicket)) {
                    onApplyEdit(edit)
                } else {
                    false
                }
            }
        )
        result && isTabRequestStillValid(requestTicket)
    } ?: false

    suspend fun prepareRename(tabId: String, line: Int, column: Int) = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = codeActionService.prepareRename(
            documentUri = tabSession.documentUri,
            line = line,
            column = column,
            prepareRenameRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.prepareRename(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "prepareRename(${fileNameForLog(tabSession.file)})"
                )
            }
        )
        result.takeIf { isTabRequestStillValid(requestTicket) }
    }

    suspend fun rename(
        tabId: String,
        line: Int,
        column: Int,
        newName: String,
        onApplyEdit: suspend (WorkspaceEdit) -> Boolean
    ): LspCodeActionService.RenameResult {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] }
            ?: return LspCodeActionService.RenameResult(false, error = Strings.lsp_error_editor_not_found.str())
        val session = tabSession.lspSession
            ?: return LspCodeActionService.RenameResult(false, error = Strings.lsp_error_not_connected.str())
        if (!tabSession.isConnected) {
            return LspCodeActionService.RenameResult(false, error = Strings.lsp_error_not_connected.str())
        }
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = codeActionService.rename(
            documentUri = tabSession.documentUri,
            line = line,
            column = column,
            newName = newName,
            renameRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.rename(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "rename(${fileNameForLog(tabSession.file)})"
                )
            },
            onApplyEdit = { edit ->
                if (isTabRequestStillValid(requestTicket)) {
                    onApplyEdit(edit)
                } else {
                    false
                }
            }
        )
        return if (isTabRequestStillValid(requestTicket)) {
            result
        } else {
            LspCodeActionService.RenameResult(false, error = Strings.lsp_error_not_connected.str())
        }
    }

    suspend fun gotoDefinition(tabId: String, line: Int, column: Int): List<LocationItem> {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return emptyList()
        if (!tabSession.isConnected) return emptyList()
        tabSession.builtinSession?.let { session ->
            return withContext(Dispatchers.Default) {
                session.gotoDefinition(Position(line, column))
            }
        }
        return withLspTabSession(tabId) { currentTabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, currentTabSession.documentUri)
            val result = navigationService.gotoDefinition(
                documentUri = currentTabSession.documentUri,
                line = line,
                column = column,
                definitionRequest = { params, timeoutSeconds ->
                    awaitTrackedTabFuture(
                        ticket = requestTicket,
                        future = session.definition(params),
                        timeoutSeconds = timeoutSeconds,
                        operation = "definition(${fileNameForLog(currentTabSession.file)})"
                    )
                }
            )
            if (isTabRequestStillValid(requestTicket)) result else emptyList()
        } ?: emptyList()
    }

    suspend fun findReferences(tabId: String, line: Int, column: Int): List<LocationItem> {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return emptyList()
        if (!tabSession.isConnected) return emptyList()
        tabSession.builtinSession?.let { session ->
            return withContext(Dispatchers.Default) {
                session.findReferences(Position(line, column))
            }
        }
        return withLspTabSession(tabId) { currentTabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, currentTabSession.documentUri)
            val result = navigationService.findReferences(
                documentUri = currentTabSession.documentUri,
                line = line,
                column = column,
                includeDeclaration = true,
                referencesRequest = { params, timeoutSeconds ->
                    awaitTrackedTabFuture(
                        ticket = requestTicket,
                        future = session.references(params),
                        timeoutSeconds = timeoutSeconds,
                        operation = "references(${fileNameForLog(currentTabSession.file)})"
                    )
                }
            )
            if (isTabRequestStillValid(requestTicket)) result else emptyList()
        } ?: emptyList()
    }

    suspend fun gotoTypeDefinition(tabId: String, line: Int, column: Int): List<LocationItem> = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = navigationService.gotoTypeDefinition(
            documentUri = tabSession.documentUri,
            line = line,
            column = column,
            typeDefinitionRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.typeDefinition(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "typeDefinition(${fileNameForLog(tabSession.file)})"
                )
            }
        )
        if (isTabRequestStillValid(requestTicket)) result else emptyList()
    } ?: emptyList()

    suspend fun gotoImplementation(tabId: String, line: Int, column: Int): List<LocationItem> = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = navigationService.gotoImplementation(
            documentUri = tabSession.documentUri,
            line = line,
            column = column,
            implementationRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.implementation(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "implementation(${fileNameForLog(tabSession.file)})"
                )
            }
        )
        if (isTabRequestStillValid(requestTicket)) result else emptyList()
    } ?: emptyList()

    suspend fun callHierarchyIncomingCalls(tabId: String, line: Int, column: Int): List<LocationItem> = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = navigationService.callHierarchyIncomingCalls(
            documentUri = tabSession.documentUri,
            line = line,
            column = column,
            prepareRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.prepareCallHierarchy(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "prepareCallHierarchy(${fileNameForLog(tabSession.file)})"
                )
            },
            incomingCallsRequest = { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.callHierarchyIncomingCalls(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "callHierarchy/incomingCalls(${fileNameForLog(tabSession.file)})"
                )
            }
        )
        if (isTabRequestStillValid(requestTicket)) result else emptyList()
    } ?: emptyList()
    suspend fun switchSourceHeader(tabId: String): String? {
        val resultUri = withLspTabSession(tabId) { tabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
            val result = navigationService.switchSourceHeader(
                documentUri = tabSession.documentUri,
                customRequest = { method, params, timeoutSeconds ->
                    awaitTrackedTabFuture(
                        ticket = requestTicket,
                        future = session.customRequest(method, params),
                        timeoutSeconds = timeoutSeconds,
                        operation = "switchSourceHeader/request(${fileNameForLog(tabSession.file)})"
                    )
                },
                executeCommandRequest = { params, timeoutSeconds ->
                    awaitTrackedTabFuture(
                        ticket = requestTicket,
                        future = session.executeCommand(params),
                        timeoutSeconds = timeoutSeconds,
                        operation = "switchSourceHeader/execute(${fileNameForLog(tabSession.file)})"
                    )
                }
            )
            result.takeIf { isTabRequestStillValid(requestTicket) }
        } ?: return null
        return runCatching { File(URI(resultUri)).absolutePath }.getOrElse { resultUri.removePrefix("file://") }
    }

    suspend fun workspaceSymbol(tabId: String, query: String): List<WorkspaceSymbolItem> = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = navigationService.workspaceSymbol(query) { params, timeoutSeconds ->
            awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.symbol(params),
                timeoutSeconds = timeoutSeconds,
                operation = "workspaceSymbol(${fileNameForLog(tabSession.file)})"
            )
        }
        if (isTabRequestStillValid(requestTicket)) result else emptyList()
    } ?: emptyList()

    suspend fun resolveWorkspaceSymbol(tabId: String, item: WorkspaceSymbolItem): WorkspaceSymbolItem? = withLspTabSession(tabId) { tabSession, session ->
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)
        val result = navigationService.resolveWorkspaceSymbol(item) { unresolved, timeoutSeconds ->
            awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.resolveWorkspaceSymbol(unresolved),
                timeoutSeconds = timeoutSeconds,
                operation = "workspaceSymbol/resolve(${fileNameForLog(tabSession.file)})"
            )
        }
        result.takeIf { isTabRequestStillValid(requestTicket) }
    }

    suspend fun documentSymbols(tabId: String): List<DocumentSymbolItem> {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return emptyList()
        if (!tabSession.isConnected) return emptyList()
        tabSession.builtinSession?.let { session ->
            return withContext(Dispatchers.Default) { session.documentSymbols() }
        }
        return withLspTabSession(tabId) { currentTabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, currentTabSession.documentUri)
            val result = navigationService.documentSymbols(currentTabSession.documentUri) { params, timeoutSeconds ->
                awaitTrackedTabFuture(
                    ticket = requestTicket,
                    future = session.documentSymbol(params),
                    timeoutSeconds = timeoutSeconds,
                    operation = "documentSymbols(${fileNameForLog(currentTabSession.file)})"
                )
            }
            if (isTabRequestStillValid(requestTicket)) result else emptyList()
        } ?: emptyList()
    }

    suspend fun requestHoverMarkdown(tabId: String, line: Int, column: Int): String? {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return null
        if (!tabSession.isConnected) return null
        tabSession.builtinSession?.let { session ->
            return withContext(Dispatchers.Default) {
                session.hover(Position(line, column))
            }
        }
        return withLspTabSession(tabId) { currentTabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, currentTabSession.documentUri)
            val params = HoverParams().apply {
                textDocument = TextDocumentIdentifier(currentTabSession.documentUri)
                position = org.eclipse.lsp4j.Position(line, column)
            }
            val result = awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.hover(params),
                timeoutSeconds = HOVER_TIMEOUT_SECONDS,
                operation = "hover(${fileNameForLog(currentTabSession.file)})"
            ) ?: return@withLspTabSession null
            result.toMarkdown()
        }
    }

    suspend fun requestSignatureHelp(tabId: String, line: Int, column: Int): SignatureHelpResult? {
        if (!assistSettings.signatureHelpEnabled) return null
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return null
        if (!tabSession.isConnected) return null
        tabSession.builtinSession?.let { session ->
            return withContext(Dispatchers.Default) {
                session.requestSignatureHelp(Position(line, column))
            }
        }
        return withLspTabSession(tabId) { currentTabSession, session ->
            val requestTicket = createTabRequestTicket(tabId, currentTabSession.documentUri)
            val params = SignatureHelpParams().apply {
                textDocument = TextDocumentIdentifier(currentTabSession.documentUri)
                position = org.eclipse.lsp4j.Position(line, column)
            }
            val result = awaitTrackedTabFuture(
                ticket = requestTicket,
                future = session.signatureHelp(params),
                timeoutSeconds = SIGNATURE_HELP_TIMEOUT_SECONDS,
                operation = "signatureHelp(${fileNameForLog(currentTabSession.file)})"
            ) ?: return@withLspTabSession null
            val signatures = result.signatures.orEmpty()
                .mapNotNull { info -> info.label?.takeIf { it.isNotBlank() } }
            if (signatures.isEmpty()) return@withLspTabSession null
            SignatureHelpResult(
                signatures = signatures,
                activeSignature = (result.activeSignature?.toInt() ?: 0).coerceIn(0, signatures.lastIndex),
                activeParameter = (result.activeParameter?.toInt() ?: 0).coerceAtLeast(0)
            )
        }
    }

    fun setAssistSettings(settings: LspAssistSettings) {
        val previous = assistSettings
        assistSettings = settings
        if (previous.semanticTokensEnabled && !settings.semanticTokensEnabled) {
            synchronized(stateLock) { semanticTokensCache.clear() }
        }
    }

    fun onLspFoldingRangeEnabledChanged(enabled: Boolean) {
        foldingRangeEnabled = enabled
        if (!enabled) {
            synchronized(stateLock) { foldingRangesCache.clear() }
        }
    }

    suspend fun requestFoldingRanges(
        tabId: String,
        documentVersion: Long
    ): List<FoldRegion>? = withContext(Dispatchers.IO) {
        if (!foldingRangeEnabled) {
            synchronized(stateLock) { foldingRangesCache.remove(tabId) }
            return@withContext null
        }

        val cached = synchronized(stateLock) { foldingRangesCache[tabId] }
        if (cached != null && cached.documentVersion == documentVersion) {
            return@withContext cached.regions
        }

        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return@withContext null
        if (!tabSession.isConnected) return@withContext null
        if (tabSession.builtinSession != null) return@withContext null
        val session = tabSession.lspSession ?: return@withContext null
        val requestTicket = createTabRequestTicket(tabId, tabSession.documentUri)

        val params = FoldingRangeRequestParams().apply {
            textDocument = TextDocumentIdentifier(tabSession.documentUri)
        }

        val raw = awaitTrackedTabFuture(
            ticket = requestTicket,
            future = session.foldingRange(params),
            timeoutSeconds = FOLDING_RANGE_TIMEOUT_SECONDS,
            operation = "foldingRange(${fileNameForLog(tabSession.file)})"
        ) ?: return@withContext null

        val regions = raw.asSequence()
            .mapNotNull { range ->
                val startLine = range.startLine
                val endLine = range.endLine
                if (endLine <= startLine) null else FoldRegion(startLine = startLine, endLine = endLine)
            }
            .distinct()
            .sortedWith(compareBy<FoldRegion> { it.startLine }.thenByDescending { it.endLine })
            .toList()

        synchronized(stateLock) {
            foldingRangesCache[tabId] = FoldingRangesCache(
                documentVersion = documentVersion,
                regions = regions
            )
        }
        return@withContext regions
    }

    /**
     * 让 compile setup 内存缓存失效（不重连）。
     *
     * 用于“项目开着但当前没有活跃 C/C++ 标签页”时发生依赖变更的场景：
     * 此时无编辑器可重连，但残留的缓存会让下一次打开 C/C++ 文件直接命中旧 setup，
     * 绕过 CompileDatabaseProvider 的包指纹校验，导致头文件假错。
     */
    /**
     * 让内存中的 compile setup 缓存整体失效。
     *
     * 依赖包安装/卸载后调用：即使当前没有打开的 C/C++ 编辑器，也需要清除缓存，
     * 确保下次 attach 时重新走 [CompileDatabaseProvider.prepare] 的包指纹校验，
     * 避免复用过时的 compile_commands.json 导致头文件假错。
     */
    fun invalidateCompileSetupCache() {
        compileSetupCache.clear()
    }

    fun refreshLspConnection(context: Context) {
        val bindings = synchronized(stateLock) { tabBindings.toMap() }
        if (bindings.isEmpty()) return
        compileSetupCache.clear()
        disposeProject()
        bindings.values.forEach { binding ->
            attachTinaLsp(
                context = context,
                file = binding.file,
                tabId = binding.tabId,
                projectRootPath = binding.projectRootPath,
                textProvider = binding.textProvider
            )
        }
    }

    fun findCompileCommandsJson(): String? {
        val dir = lspCompileCommandsDirOverride ?: return null
        val file = File(dir, "compile_commands.json")
        return file.absolutePath.takeIf { file.isFile }
    }

    fun release() {
        disposeProject()
        lspScope.cancel()
    }

    private fun attachCxxLsp(
        context: Context,
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean {
        registerBinding(TabBinding(tabId, SessionKind.CXX, file, projectRootPath, textProvider))
        return if (RemoteLspConfigManager.config.isValid()) {
            attachRemoteCxxLsp(file, tabId, projectRootPath, textProvider)
        } else {
            attachLocalCxxLsp(context, file, tabId, projectRootPath, textProvider)
        }
    }

    private fun attachLocalCxxLsp(
        context: Context,
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean {
        // resolveClangdRunMode() 已在 Linux 环境不可用时回退 NATIVE，与编译链路一致。
        val runMode = resolveClangdRunMode()
        Timber.tag(TAG).i("attachLocalCxxLsp: file=%s, runMode=%s, projectRoot=%s", file.name, runMode, projectRootPath)
        if (runMode == LinuxRunModePolicy.RunMode.NATIVE) {
            val toolchain = AndroidNativeToolchainManager(context)
            val sysroot = AndroidSysrootManager(context)
            if (!toolchain.isInstalled() || !sysroot.isInstalled()) {
                Timber.tag(TAG).i("attachLocalCxxLsp: toolchain installed=%b, sysroot installed=%b — will auto-install", toolchain.isInstalled(), sysroot.isInstalled())
                updateLspStatus(tabId, EditorStatus.Connecting)
                lspScope.launch {
                    val toolchainResult = withContext(Dispatchers.IO) {
                        if (toolchain.isInstalled()) Result.success(Unit) else toolchain.install { }
                    }
                    val sysrootResult = withContext(Dispatchers.IO) {
                        if (sysroot.isInstalled()) Result.success(Unit) else sysroot.install { }
                    }
                    if (toolchainResult.isSuccess && sysrootResult.isSuccess) {
                        Timber.tag(TAG).i("attachLocalCxxLsp: auto-install success, retrying attach")
                        attachLocalCxxLsp(context, file, tabId, projectRootPath, textProvider)
                    } else {
                        Timber.tag(TAG).w(
                            "attachLocalCxxLsp: auto-install failed — toolchain=%s, sysroot=%s",
                            toolchainResult.exceptionOrNull()?.message,
                            sysrootResult.exceptionOrNull()?.message
                        )
                        updateLspStatus(tabId, EditorStatus.Error)
                    }
                }
                return true
            }
        }

        updateLspStatus(tabId, EditorStatus.Connecting)
        lspScope.launch {
            val compileSetup = resolveCompileSetup(context, file, projectRootPath)
            if (!hasTabBinding(tabId, file)) {
                return@launch
            }
            if (compileSetup == null) {
                Timber.tag(TAG).w("attachLocalCxxLsp: compile setup unavailable, setting NoLsp")
                updateLspStatus(tabId, EditorStatus.NoLsp)
                releaseSession(tabId, clearBinding = true)
                return@launch
            }

            val prepared = compileSetup.prepared
            val workspaceRoot = prepared.workspaceRoot.absolutePath
            val compileCommandsDir = compileSetup.compileCommandsDir.absolutePath
            if (lspProjectRoot != workspaceRoot || isUsingRemoteLsp || lspCompileCommandsDirOverride != compileCommandsDir) {
                disposeProject(clearBindings = false)
                lspProjectRoot = workspaceRoot
                lspCompileCommandsDirOverride = compileCommandsDir
                isUsingRemoteLsp = false
            }

            startSharedCxxAttach(
                tabId = tabId,
                file = file,
                workspaceRoot = workspaceRoot,
                languageId = languageIdForFile(file),
                textProvider = textProvider,
                warmupCompletionOnReady = prepared.projectType != CompileDatabaseProvider.ProjectType.CMAKE_PROJECT
            ) {
                when (resolveClangdRunMode()) {
                    LinuxRunModePolicy.RunMode.NATIVE ->
                        NativeClangdConnectionProvider(
                            context,
                            workspaceRoot,
                            compileSetup.compileCommandsDir.absolutePath
                        )

                    LinuxRunModePolicy.RunMode.PROOT ->
                        PRootClangdConnectionProvider(
                            context,
                            workspaceRoot,
                            compileSetup.compileCommandsDir.absolutePath,
                            linuxEnvironmentOverride = linuxEnvironmentProvider.get()
                        )
                }
            }
        }
        return true
    }

    private fun attachRemoteCxxLsp(
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean {
        val cfg = RemoteLspConfigManager.config
        val projectRoot = projectRootPath ?: file.parentFile?.absolutePath ?: return false
        if (lspProjectRoot != projectRoot || !isUsingRemoteLsp) {
            disposeProject(clearBindings = false)
            lspProjectRoot = projectRoot
            lspCompileCommandsDirOverride = null
            isUsingRemoteLsp = true
        }

        return startSharedCxxAttach(
            tabId = tabId,
            file = file,
            workspaceRoot = projectRoot,
            languageId = languageIdForFile(file),
            textProvider = textProvider,
            remote = true
        ) {
            val provider = createRemoteProvider(
                cfg.getNormalizedHostForConnection(),
                cfg.port,
                file.extension.lowercase()
            )
            provider.setClientWorkspaceRootUri(File(projectRoot).toURI().toString())
            provider.setRemoteWorkspaceRootUri(cfg.remoteWorkspaceRootUri.takeIf { it.isNotBlank() })

            val effectiveMode = when (cfg.syncMode) {
                RemoteLspSyncMode.AUTO -> {
                    val (mode, reason) = ProjectSyncManager.detectSyncMode(File(projectRoot))
                    RemoteLspConfigManager.updateDetectedSyncMode(mode, reason)
                    mode
                }

                else -> cfg.syncMode
            }
            currentSyncMode = effectiveMode

            if (effectiveMode == RemoteLspSyncMode.PROJECT &&
                cfg.syncMethod == RemoteLspSyncMethod.BUILTIN &&
                synchronized(stateLock) { projectRoot !in remoteSyncedProjects }
            ) {
                val started = provider.startAsync()
                if (started.isFailure) error(started.exceptionOrNull()?.message ?: "remote start failed")
                syncProjectToRemote(File(projectRoot), provider)
                synchronized(stateLock) { remoteSyncedProjects.add(projectRoot) }
            }
            provider
        }
    }

    private fun attachBuiltinCmakeLsp(
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean = attachBuiltinLanguageSession(
        file = file,
        tabId = tabId,
        projectRootPath = projectRootPath,
        textProvider = textProvider,
        kind = SessionKind.CMAKE
    ) { documentUri ->
        CMakeLanguageServiceSession(
            file = file,
            documentUri = documentUri,
            textProvider = textProvider
        )
    }

    private fun attachBuiltinMakeLsp(
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean = attachBuiltinLanguageSession(
        file = file,
        tabId = tabId,
        projectRootPath = projectRootPath,
        textProvider = textProvider,
        kind = SessionKind.MAKE
    ) { documentUri ->
        MakeLanguageServiceSession(
            file = file,
            documentUri = documentUri,
            textProvider = textProvider,
            caseSensitiveProvider = { Prefs.completionCaseSensitive }
        )
    }

    private fun attachBuiltinLanguageSession(
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
        kind: SessionKind,
        sessionFactory: (documentUri: String) -> BuiltinLanguageServiceSession,
    ): Boolean {
        registerBinding(TabBinding(tabId, kind, file, projectRootPath, textProvider))
        releaseSession(tabId, clearBinding = false)
        updateLspStatus(tabId, EditorStatus.Connecting)
        val documentUri = file.toURI().toString()
        val session = sessionFactory(documentUri)
        synchronized(stateLock) {
            tabSessions[tabId] = TabSession(
                tabId = tabId,
                file = file,
                kind = kind,
                documentUri = documentUri,
                builtinSession = session
            )
        }
        updateLspStatus(tabId, EditorStatus.Ready)
        scheduleBuiltinDiagnostics(
            TabSession(
                tabId = tabId,
                file = file,
                kind = kind,
                documentUri = documentUri,
                builtinSession = session
            )
        )
        return true
    }

    private fun attachPluginLspInternal(
        context: Context,
        file: File,
        tabId: String,
        projectRootPath: String?,
        textProvider: () -> String,
    ): Boolean {
        val pluginManager = lspPluginManager ?: return false
        val ext = file.extension.lowercase()
        val detectedLanguageId = languageIdForFile(file)
        val (pluginInfo, serverConfig) = pluginManager.getServerConfigForFile(file, detectedLanguageId) ?: return false
        val readiness = pluginManager.inspectPluginReadiness(pluginInfo.pluginId)
        if (!readiness.ready) {
            val message = readiness.toPluginReadinessMessage()
            pluginManager.markServerStartupFailed(pluginInfo.pluginId, message)
            onPluginLspDependencyNotReady?.invoke(
                PluginLspDependencyNotReadyEvent(
                    pluginId = pluginInfo.pluginId,
                    pluginName = pluginInfo.pluginName,
                    message = message,
                )
            )
            logPluginLspEvent(
                context = context,
                pluginInfo = pluginInfo,
                serverConfig = serverConfig,
                file = file,
                level = PluginLogLevel.WARN,
                message = message,
                eventCode = "lsp.toolchain.not_ready",
            )
            return false
        }
        val projectRoot = projectRootPath ?: file.parentFile?.absolutePath ?: return false
        registerBinding(TabBinding(tabId, SessionKind.PLUGIN, file, projectRootPath, textProvider))

        return startAttach(
            tabId = tabId,
            file = file,
            kind = SessionKind.PLUGIN,
            workspaceRoot = projectRoot,
            languageId = serverConfig.resolveDocumentLanguageId(detectedLanguageId, ext),
            textProvider = textProvider,
            initializationOptions = serverConfig.initializationOptions,
            onAttachSuccess = {
                pluginManager.markServerStartupSucceeded(pluginInfo.pluginId)
                logPluginLspEvent(
                    context = context,
                    pluginInfo = pluginInfo,
                    serverConfig = serverConfig,
                    file = file,
                    level = PluginLogLevel.INFO,
                    message = Strings.lsp_plugin_log_server_ready.str(serverConfig.name),
                    eventCode = "lsp.server.ready",
                )
            },
            onAttachFailure = { error ->
                val message = Strings.lsp_plugin_log_server_failed.str(
                    serverConfig.name,
                    error.message ?: error.javaClass.simpleName,
                )
                pluginManager.markServerStartupFailed(pluginInfo.pluginId, message)
                logPluginLspEvent(
                    context = context,
                    pluginInfo = pluginInfo,
                    serverConfig = serverConfig,
                    file = file,
                    level = PluginLogLevel.ERROR,
                    message = message,
                    stackTrace = error.stackTraceToString(),
                    eventCode = "lsp.server.start_failed",
                )
            }
        ) { onOwnerStopped ->
            PluginLspConnectionProvider(
                config = serverConfig,
                ownerPluginId = pluginInfo.pluginId,
                workingDir = projectRoot,
                projectRoot = projectRoot,
                linuxEnvironmentProvider = linuxEnvironmentProvider,
                onStderrLine = { line ->
                    val message = if (line.contains("command not found", ignoreCase = true)) {
                        Strings.lsp_plugin_error_server_command_not_found.str(
                            serverConfig.server.command ?: serverConfig.id,
                        )
                    } else {
                        Strings.lsp_plugin_log_server_stderr.str(line)
                    }
                    logPluginLspEvent(
                        context = context,
                        pluginInfo = pluginInfo,
                        serverConfig = serverConfig,
                        file = file,
                        level = PluginLogLevel.WARN,
                        message = message,
                        eventCode = "lsp.server.stderr",
                    )
                },
                onUnexpectedExit = { message ->
                    pluginManager.reportUnexpectedServerExit(pluginInfo.pluginId, message)
                },
                onOwnerStopped = onOwnerStopped,
            )
        }
    }

    private fun LspPluginReadinessDiagnostic.toPluginReadinessMessage(): String = when {
        failedRequiredToolchains.isNotEmpty() -> Strings.lsp_plugin_error_required_toolchains_failed.str(
            failedRequiredToolchains.joinToString()
        )
        missingRequiredToolchains.isNotEmpty() -> Strings.lsp_plugin_error_required_toolchains_missing.str(
            missingRequiredToolchains.joinToString()
        )
        !lastError.isNullOrBlank() -> lastError.orEmpty()
        else -> Strings.lsp_plugin_error_required_toolchains_missing.str(Strings.error_unknown.str())
    }

    private fun logPluginLspEvent(
        context: Context,
        pluginInfo: LspPluginInfo,
        serverConfig: LspServerConfig,
        file: File,
        level: PluginLogLevel,
        message: String,
        eventCode: String,
        stackTrace: String? = null,
    ) {
        PluginLogManager.getInstance(context.applicationContext).log(
            pluginId = pluginInfo.pluginId,
            pluginName = pluginInfo.pluginName,
            level = level,
            message = message,
            stackTrace = stackTrace,
            eventCode = eventCode,
            attributes = mapOf(
                "serverId" to serverConfig.id,
                "serverName" to serverConfig.name,
                "file" to file.name,
                "path" to file.absolutePath,
            ),
        )
    }

    private fun createLspClientSession(
        provider: LspConnectionProvider,
        workspaceRoot: String,
        file: File,
    ): LspClientSession {
        val diagnosticsBridge = LspDiagnosticsBridge { uri, diagnostics ->
            synchronized(stateLock) { lspKnownUris.add(uri) }
            onDiagnosticsChanged?.invoke(uri, diagnostics)
        }
        return LspClientSession(
            connectionProvider = provider,
            documentUri = file.toURI().toString(),
            workspaceRootUri = File(workspaceRoot).toURI().toString(),
            diagnosticsConsumer = { uri, diagnostics ->
                val fileName = runCatching { File(URI(uri)).name }.getOrElse { file.name }
                diagnosticsBridge.publish(uri, fileName, diagnostics)
            },
            registrationConsumer = { registrations -> onCapabilityRegistered(registrations) },
            unregistrationConsumer = { unregistrations -> onCapabilityUnregistered(unregistrations) },
            protocolFailureConsumer = { error ->
                (provider as? PluginLspConnectionProvider)?.reportProtocolFailure(error)
            },
        )
    }

    private fun cancelPendingSharedCxxShutdown() {
        synchronized(stateLock) {
            sharedCxxShutdownJob?.cancel()
            sharedCxxShutdownJob = null
        }
    }

    private fun scheduleSharedCxxShutdownIfIdle() {
        val session = synchronized(stateLock) {
            if (tabBindings.values.any { it.kind == SessionKind.CXX }) {
                sharedCxxShutdownJob?.cancel()
                sharedCxxShutdownJob = null
                return
            }
            val current = sharedCxxSession ?: return
            sharedCxxShutdownJob?.cancel()
            current
        }

        val job = lspScope.launch {
            delay(SHARED_CXX_IDLE_SHUTDOWN_MS)
            val sessionToClose = synchronized(stateLock) {
                if (tabBindings.values.any { it.kind == SessionKind.CXX }) {
                    sharedCxxShutdownJob = null
                    null
                } else if (sharedCxxSession === session) {
                    sharedCxxSession = null
                    sharedCxxShutdownJob = null
                    session
                } else {
                    sharedCxxShutdownJob = null
                    null
                }
            } ?: return@launch

            runCatchingPreservingCancellation {
                withContext(Dispatchers.IO) { sessionToClose.close() }
            }.onFailure { error ->
                Timber.tag(TAG).d(error, "shared clangd idle shutdown failed")
            }
            Timber.tag(TAG).i("shared clangd released after %dms idle", SHARED_CXX_IDLE_SHUTDOWN_MS)
            val projectIsStillIdle = stopFileWatcherIfCxxIdle()
            if (projectIsStillIdle && isUsingRemoteLsp) {
                RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.DISCONNECTED)
            }
        }

        synchronized(stateLock) {
            if (sharedCxxSession === session && !tabBindings.values.any { it.kind == SessionKind.CXX }) {
                sharedCxxShutdownJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun nextRequestGeneration(tabId: String): List<CompletableFuture<*>> =
        tabRequestTracker.invalidateTab(tabId)

    private fun createTabRequestTicket(tabId: String, documentUri: String): LspTabRequestTicket =
        tabRequestTracker.createTicket(tabId, documentUri)

    private fun isTabRequestStillValid(ticket: LspTabRequestTicket): Boolean =
        tabRequestTracker.isStillValid(ticket) { tabId ->
            tabSessions[tabId]?.let { tabSession ->
                LspTrackedTabRequestState(
                    documentUri = tabSession.documentUri,
                    isConnected = tabSession.isConnected
                )
            }
        }

    private fun trackTabFuture(tabId: String, future: CompletableFuture<*>) {
        tabRequestTracker.trackFuture(tabId, future)
    }

    private fun untrackTabFuture(tabId: String, future: CompletableFuture<*>) {
        tabRequestTracker.untrackFuture(tabId, future)
    }

    private suspend fun resolveCompileSetup(
        context: Context,
        file: File,
        projectRootPath: String?,
    ): LspCompileSetupCache.Setup? = compileSetupCache.resolve(context, file, projectRootPath)

    private fun startSharedCxxAttach(
        tabId: String,
        file: File,
        workspaceRoot: String,
        languageId: String,
        textProvider: () -> String,
        remote: Boolean = false,
        initializationOptions: Any? = null,
        warmupCompletionOnReady: Boolean = false,
        providerFactory: suspend () -> LspConnectionProvider,
    ): Boolean {
        Timber.tag(TAG).i(
            "startSharedCxxAttach: file=%s, languageId=%s, remote=%b",
            file.name,
            languageId,
            remote
        )
        cancelPendingSharedCxxShutdown()
        releaseSession(tabId, clearBinding = false)
        cancelPendingSharedCxxShutdown()
        val token = Any()
        synchronized(stateLock) { attachTokenCache[tabId] = token }
        updateLspStatus(tabId, EditorStatus.Connecting)
        if (remote) RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.CONNECTING)

        lspScope.launch {
            val attachStartedAt = System.nanoTime()
            runCatchingPreservingCancellation {
                val snapshot = runCatching { textProvider() }.getOrDefault("")
                val documentUri = file.toURI().toString()
                sharedCxxSessionMutex.withLock {
                    val existing = synchronized(stateLock) { sharedCxxSession }
                    if (existing != null && existing.isConnected) {
                        Timber.tag(TAG).d("startSharedCxxAttach: reusing shared clangd for %s", file.name)
                        withContext(Dispatchers.IO) {
                            existing.connect(
                                documentUri = documentUri,
                                languageId = languageId,
                                initialText = snapshot,
                                initializationOptions = initializationOptions
                            ).getOrThrow()
                        }
                        existing
                    } else {
                        Timber.tag(TAG).d("startSharedCxxAttach: creating shared clangd session...")
                        val provider = providerFactory()
                        val session = createLspClientSession(provider, workspaceRoot, file)
                        try {
                            withContext(Dispatchers.IO) {
                                session.connect(
                                    languageId = languageId,
                                    initialText = snapshot,
                                    initializationOptions = initializationOptions,
                                ).getOrThrow()
                            }
                        } catch (error: Throwable) {
                            withContext(NonCancellable + Dispatchers.IO) {
                                runCatching { session.close() }
                            }
                            throw error
                        }
                        synchronized(stateLock) { sharedCxxSession = session }
                        session
                    }
                }
            }.onSuccess { session ->
                val committed = synchronized(stateLock) {
                    if (attachTokenCache[tabId] !== token) {
                        false
                    } else {
                        tabSessions[tabId] = TabSession(
                            tabId = tabId,
                            file = file,
                            kind = SessionKind.CXX,
                            documentUri = file.toURI().toString(),
                            lspSession = session,
                        )
                        updateLspStatus(tabId, EditorStatus.Ready)
                        if (remote) {
                            RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.CONNECTED)
                        }
                        true
                    }
                }
                if (!committed) {
                    Timber.tag(TAG).w(
                        "startSharedCxxAttach: token stale after activation, discarding result for %s",
                        file.name
                    )
                    scheduleSharedCxxShutdownIfIdle()
                    return@onSuccess
                }
                if (!hasWorkspaceFileWatcher()) {
                    startFileWatcher(workspaceRoot)
                }
                Timber.tag(TAG).i(
                    "startSharedCxxAttach: shared clangd ready for %s in %dms",
                    file.name,
                    elapsedMillis(attachStartedAt)
                )
                if (warmupCompletionOnReady) {
                    scheduleCompletionWarmup(tabId, file)
                }
            }.onFailure { e ->
                Timber.tag(
                    TAG
                ).w(e, "startSharedCxxAttach: activation failed for %s — %s: %s", file.name, e.javaClass.simpleName, e.message)
                releaseSession(
                    tabId = tabId,
                    clearBinding = false,
                    expectedAttachToken = token,
                    onCurrentAttachment = {
                        updateLspStatus(tabId, EditorStatus.Error)
                        if (remote) {
                            RemoteLspConfigManager.updateConnectionState(
                                RemoteLspConnectionState.ERROR,
                                e.message ?: Strings.lsp_error_connection_failed.str(),
                            )
                        }
                    },
                )
            }
        }
        return true
    }

    private fun startAttach(
        tabId: String,
        file: File,
        kind: SessionKind,
        workspaceRoot: String,
        languageId: String,
        textProvider: () -> String,
        remote: Boolean = false,
        initializationOptions: Any? = null,
        warmupCompletionOnReady: Boolean = false,
        onAttachSuccess: (() -> Unit)? = null,
        onAttachFailure: ((Throwable) -> Unit)? = null,
        providerFactory: suspend (onOwnerStopped: () -> Unit) -> LspConnectionProvider,
    ): Boolean {
        Timber.tag(TAG).i("startAttach: file=%s, kind=%s, languageId=%s, remote=%b", file.name, kind, languageId, remote)
        releaseSession(tabId, clearBinding = false)
        val token = Any()
        synchronized(stateLock) { attachTokenCache[tabId] = token }
        updateLspStatus(tabId, EditorStatus.Connecting)
        if (remote) RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.CONNECTING)
        val ownerStopHandler = PluginLspOwnerStopHandler(
            transitionIfCurrent = {
                releaseSession(
                    tabId = tabId,
                    clearBinding = false,
                    expectedAttachToken = token,
                    onCurrentAttachment = {
                        updateLspStatus(tabId, EditorStatus.NoLsp)
                    },
                )
            },
        )

        val onOwnerStopped: () -> Unit = {
            lspScope.launch {
                if (ownerStopHandler.handle()) {
                    Timber.tag(TAG).i("Plugin LSP owner stopped; releasing session for %s", file.name)
                }
            }
            Unit
        }

        lspScope.launch {
            runCatchingPreservingCancellation {
                Timber.tag(TAG).d("startAttach: creating connection provider...")
                val provider = providerFactory(onOwnerStopped)
                Timber.tag(TAG).d("startAttach: provider created: %s", provider.javaClass.simpleName)
                val session = createLspClientSession(provider, workspaceRoot, file)
                val snapshot = runCatching { textProvider() }.getOrDefault("")
                Timber.tag(TAG).d("startAttach: calling session.connect(languageId=%s, textLen=%d)...", languageId, snapshot.length)
                try {
                    withContext(Dispatchers.IO) {
                        session.connect(languageId, snapshot, initializationOptions).getOrThrow()
                    }
                } catch (error: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { session.close() }
                    }
                    throw error
                }
                Timber.tag(TAG).i("startAttach: session.connect() succeeded for %s", file.name)
                session
            }.onSuccess { session ->
                val committed = synchronized(stateLock) {
                    if (attachTokenCache[tabId] !== token) {
                        false
                    } else {
                        tabSessions[tabId] = TabSession(
                            tabId = tabId,
                            file = file,
                            kind = kind,
                            documentUri = file.toURI().toString(),
                            lspSession = session,
                        )
                        updateLspStatus(tabId, EditorStatus.Ready)
                        if (remote) {
                            RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.CONNECTED)
                        }
                        onAttachSuccess?.invoke()
                        true
                    }
                }
                if (!committed) {
                    Timber.tag(TAG).w("startAttach: token stale after connect, discarding session for %s", file.name)
                    runCatching { session.close() }
                    return@onSuccess
                }
                // CXX session 建立后启动文件监听（只需一个 watcher 覆盖整个 workspace）
                if (kind == SessionKind.CXX && !hasWorkspaceFileWatcher()) {
                    startFileWatcher(workspaceRoot)
                }
                Timber.tag(TAG).i("startAttach: LSP ready for %s", file.name)
                if (warmupCompletionOnReady) {
                    scheduleCompletionWarmup(tabId, file)
                }
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "startAttach: LSP attach failed for %s — %s: %s", file.name, e.javaClass.simpleName, e.message)
                releaseSession(
                    tabId = tabId,
                    clearBinding = false,
                    expectedAttachToken = token,
                    onCurrentAttachment = {
                        onAttachFailure?.invoke(e)
                        updateLspStatus(tabId, EditorStatus.Error)
                        if (remote) {
                            RemoteLspConfigManager.updateConnectionState(
                                RemoteLspConnectionState.ERROR,
                                e.message ?: Strings.lsp_error_connection_failed.str(),
                            )
                        }
                    },
                )
            }
        }
        return true
    }

    private fun createRemoteProvider(host: String, port: Int, ext: String): RemoteLspConnectionProvider = RemoteLspConnectionProvider(host, port, autoReconnect = true, maxReconnectAttempts = 5)
        .also { provider ->
            provider.addStateListener(object : ConnectionStateListener {
                override fun onStateChanged(state: ConnectionState) {
                    val mapped = when (state) {
                        ConnectionState.DISCONNECTED -> RemoteLspConnectionState.DISCONNECTED
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
                            RemoteLspConnectionState.CONNECTING

                        ConnectionState.CONNECTED -> RemoteLspConnectionState.CONNECTED
                        ConnectionState.FAILED -> RemoteLspConnectionState.ERROR
                    }
                    RemoteLspConfigManager.updateConnectionState(mapped)
                }

                override fun onEvent(event: ConnectionEvent) {
                    when (event) {
                        is ConnectionEvent.Connected -> Timber.tag(TAG).i("Remote connected: $ext")
                        is ConnectionEvent.Disconnected -> Timber.tag(TAG).i("Remote disconnected: $ext")
                        is ConnectionEvent.Reconnecting ->
                            RemoteLspConfigManager.updateReconnectAttempt(event.attempt)

                        is ConnectionEvent.Error ->
                            RemoteLspConfigManager.updateConnectionState(
                                RemoteLspConnectionState.ERROR,
                                event.message
                            )

                        is ConnectionEvent.LatencyUpdate ->
                            RemoteLspConfigManager.updateLatency(event.latencyMs)
                    }
                }
            })
        }

    private suspend fun syncProjectToRemote(projectRoot: File, provider: RemoteLspConnectionProvider) {
        if (RemoteLspConfigManager.config.syncMethod != RemoteLspSyncMethod.BUILTIN) return
        val files = ProjectSyncManager.scanProject(projectRoot)
        if (files.isEmpty()) return
        provider.syncProject(projectRoot.name, files) { _, _ -> }
    }

    private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun resolveClangdRunMode(): LinuxRunModePolicy.RunMode = LinuxRunModePolicy.resolve(Prefs.clangdRunMode, linuxEnvironmentProvider.get().isAvailable())

    private fun releaseSession(
        tabId: String,
        clearBinding: Boolean,
        expectedAttachToken: Any? = null,
        onCurrentAttachment: (() -> Unit)? = null,
    ): Boolean {
        val (releaseState, cancelledRequests) = synchronized(stateLock) {
            if (expectedAttachToken != null && attachTokenCache[tabId] !== expectedAttachToken) {
                return false
            }
            onCurrentAttachment?.invoke()
            val requests = nextRequestGeneration(tabId)
            attachTokenCache.remove(tabId)
            semanticTokensCache.remove(tabId)
            documentVersions.remove(tabId)
            completionWarmupTabIds.remove(tabId)
            if (clearBinding) tabBindings.remove(tabId)
            val removed = tabSessions.remove(tabId)
            removed?.let { tabSession ->
                lspKnownUris.remove(tabSession.documentUri)
                onDiagnosticsChanged?.invoke(tabSession.documentUri, emptyList())
            }
            Triple(
                sharedCxxSession,
                builtinDiagnosticsJobs.remove(tabId).also {
                    builtinDiagnosticsRequestTokens.remove(tabId)
                },
                removed,
            ) to requests
        }
        val (sharedSession, diagnosticsJob, removed) = releaseState
        cancelledRequests.forEach { future -> future.cancel(true) }
        diagnosticsJob?.cancel()
        removed?.let { tabSession ->
            tabSession.builtinSession?.close()
            tabSession.lspSession?.let { session ->
                if (tabSession.kind == SessionKind.CXX && session === sharedSession) {
                    lspScope.launch(Dispatchers.IO) {
                        runCatching { session.closeDocument(tabSession.documentUri) }
                    }
                } else {
                    lspScope.launch(Dispatchers.IO) { runCatching { session.close() } }
                }
            }
        }
        scheduleSharedCxxShutdownIfIdle()
        return true
    }

    private fun registerBinding(binding: TabBinding) {
        synchronized(stateLock) { tabBindings[binding.tabId] = binding }
    }

    private fun disposeProject(clearBindings: Boolean = true) {
        cancelPendingSharedCxxShutdown()
        clearDiagnosticsInUi()
        val diagnosticsJobs = synchronized(stateLock) {
            builtinDiagnosticsRequestTokens.clear()
            builtinDiagnosticsJobs.values.toList().also { builtinDiagnosticsJobs.clear() }
        }
        diagnosticsJobs.forEach { job -> job.cancel() }
        val (sessions, sharedSession, inflightRequests) = synchronized(stateLock) {
            attachTokenCache.clear()
            semanticTokensCache.clear()
            documentVersions.clear()
            completionWarmupTabIds.clear()
            val inflight = tabRequestTracker.drainAll()
            val shared = sharedCxxSession
            sharedCxxSession = null
            if (clearBindings) tabBindings.clear()
            val all = tabSessions.values.toList()
            tabSessions.clear()
            Triple(all, shared, inflight)
        }
        inflightRequests.forEach { future -> future.cancel(true) }
        val uniqueLspSessions = buildSet {
            sessions.mapNotNullTo(this) { it.lspSession }
            sharedSession?.let { add(it) }
        }
        sessions.forEach { tabSession ->
            tabSession.builtinSession?.close()
        }
        uniqueLspSessions.forEach { session -> runCatching { session.close() } }
        stopFileWatcher()
        lspProjectRoot = null
        lspCompileCommandsDirOverride = null
        currentSyncMode = null
        remoteSyncedProjects.clear()
        if (isUsingRemoteLsp) {
            RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.DISCONNECTED)
            ProjectSyncManager.reset()
        }
        isUsingRemoteLsp = false
    }

    private fun clearDiagnosticsInUi() {
        val callback = onDiagnosticsChanged ?: return
        val uris = synchronized(stateLock) { lspKnownUris.toList() }
        uris.forEach { callback(it, emptyList()) }
        synchronized(stateLock) { lspKnownUris.clear() }
    }

    private fun updateLspStatus(tabId: String, status: EditorStatus) {
        onLspStatusChanged?.invoke(tabId, status)
    }

    private fun scheduleBuiltinDiagnostics(tabSession: TabSession) {
        val builtinSession = tabSession.builtinSession ?: return
        val requestToken = Any()
        val previousJob = synchronized(stateLock) {
            builtinDiagnosticsRequestTokens[tabSession.tabId] = requestToken
            builtinDiagnosticsJobs.remove(tabSession.tabId)
        }
        previousJob?.cancel()

        val job = lspScope.launch(Dispatchers.Default) {
            val diagnostics = runCatchingPreservingCancellation { builtinSession.currentDiagnostics() }
                .getOrElse { error ->
                    Timber.tag(TAG).w(error, "builtin diagnostics failed for %s", tabSession.file.name)
                    emptyList()
                }
            val stillActive = synchronized(stateLock) {
                val current = tabSessions[tabSession.tabId]
                if (current?.builtinSession === builtinSession &&
                    builtinDiagnosticsRequestTokens[tabSession.tabId] === requestToken
                ) {
                    lspKnownUris.add(tabSession.documentUri)
                    true
                } else {
                    false
                }
            }
            if (stillActive) {
                onDiagnosticsChanged?.invoke(tabSession.documentUri, diagnostics)
            }
        }
        synchronized(stateLock) {
            if (builtinDiagnosticsRequestTokens[tabSession.tabId] === requestToken) {
                builtinDiagnosticsJobs[tabSession.tabId] = job
            } else {
                job.cancel()
            }
        }
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (builtinDiagnosticsJobs[tabSession.tabId] === job) {
                    builtinDiagnosticsJobs.remove(tabSession.tabId)
                }
            }
        }
    }

    private fun scheduleCompletionWarmup(tabId: String, file: File) {
        val scheduled = synchronized(stateLock) {
            tabSessions.containsKey(tabId) && completionWarmupTabIds.add(tabId)
        }
        if (!scheduled) return

        lspScope.launch(Dispatchers.IO) {
            try {
                when (val result = requestCompletion(tabId, Position(0, 0), triggerChar = null)) {
                    is CompletionFetchResult.Success -> {
                        Timber.tag(TAG).d(
                            "completion warmup finished for %s, candidates=%d",
                            file.name,
                            result.items.size
                        )
                    }

                    is CompletionFetchResult.TransientFailure -> {
                        Timber.tag(TAG).d(
                            "completion warmup skipped for %s: %s",
                            file.name,
                            result.reason ?: "unknown"
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.tag(TAG).d(t, "completion warmup failed for %s", file.name)
            } finally {
                synchronized(stateLock) { completionWarmupTabIds.remove(tabId) }
            }
        }
    }

    private fun buildCompletionParams(
        documentUri: String,
        position: Position,
        triggerChar: Char?
    ): CompletionParams = CompletionParams().apply {
        textDocument = TextDocumentIdentifier(documentUri)
        this.position = org.eclipse.lsp4j.Position(position.line, position.column)
        context = CompletionContext().apply {
            if (triggerChar == null) {
                triggerKind = CompletionTriggerKind.Invoked
            } else {
                triggerKind = CompletionTriggerKind.TriggerCharacter
                triggerCharacter = triggerChar.toString()
            }
        }
    }

    private suspend fun <T> awaitTrackedTabFuture(
        ticket: LspTabRequestTicket,
        future: CompletableFuture<T>?,
        timeoutSeconds: Long,
        operation: String
    ): T? {
        future ?: return null
        if (!isTabRequestStillValid(ticket)) {
            future.cancel(true)
            return null
        }

        val startedAt = System.nanoTime()
        trackTabFuture(ticket.tabId, future)
        return try {
            val result = awaitLspFuture(future, timeoutSeconds, operation)
            if (!isTabRequestStillValid(ticket)) {
                future.cancel(true)
                Timber.tag(TAG).d("%s discarded after tab switch", operation)
                null
            } else {
                Timber.tag(TAG).d("%s finished in %dms", operation, elapsedMillis(startedAt))
                result
            }
        } finally {
            untrackTabFuture(ticket.tabId, future)
        }
    }

    private suspend fun <T> awaitLspFuture(
        future: CompletableFuture<T>?,
        timeoutSeconds: Long,
        operation: String
    ): T? {
        future ?: return null
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                future.awaitCancellable()
            }
        } catch (timeout: TimeoutCancellationException) {
            future.cancel(true)
            Timber.tag(TAG).w("%s timed out after %ds", operation, timeoutSeconds)
            null
        } catch (cancelled: CancellationException) {
            future.cancel(true)
            throw cancelled
        } catch (t: Throwable) {
            future.cancel(true)
            Timber.tag(TAG).w(t, "%s failed", operation)
            null
        }
    }

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (!continuation.isActive) return@whenComplete
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun fileNameForLog(file: File): String = file.name.ifBlank { file.absolutePath }

    private fun Hover.toMarkdown(): String? {
        val payload = contents ?: return null
        return when {
            payload.isRight -> payload.right?.value?.trim().takeIf { !it.isNullOrBlank() }
            else -> payload.left.orEmpty()
                .mapNotNull { it.toMarkdownSection() }
                .joinToString("\n\n")
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }

    @Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
    private fun Either<String, ProtocolMarkedString>.toMarkdownSection(): String? = if (isLeft) {
        left?.trim().takeIf { !it.isNullOrBlank() }
    } else {
        right?.toMarkdownSection()
    }

    @Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
    private fun ProtocolMarkedString.toMarkdownSection(): String? {
        val body = value?.trim().orEmpty()
        if (body.isBlank()) return null
        val safeLanguage = language?.trim().orEmpty()
        return if (safeLanguage.isBlank()) {
            body
        } else {
            "```$safeLanguage\n$body\n```"
        }
    }

    // ========== 文件监听（workspace/didChangeWatchedFiles）==========

    private fun startFileWatcher(workspaceRoot: String) {
        val watchService = fileWatchService
        if (watchService == null) {
            Timber.tag(TAG).w("Workspace file watcher unavailable for: %s", workspaceRoot)
            return
        }
        val rootFile = File(workspaceRoot)
        if (!rootFile.isDirectory) return

        val registration = runCatching {
            watchService.addFileWatcher(workspaceRoot, object : FileChangeListener {
                override fun onFileCreated(file: File) {
                    notifyWorkspaceFileChange(file, FileChangeType.Created)
                }

                override fun onFileModified(file: File) {
                    notifyWorkspaceFileChange(file, FileChangeType.Changed)
                }

                override fun onFileDeleted(file: File) {
                    notifyWorkspaceFileChange(file, FileChangeType.Deleted)
                }

                override fun onFileRenamed(oldFile: File, newFile: File) {
                    notifyWorkspaceFileChange(oldFile, FileChangeType.Deleted)
                    notifyWorkspaceFileChange(newFile, FileChangeType.Created)
                }
            })
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Workspace file watcher failed to start for: %s", workspaceRoot)
        }.getOrNull() ?: return

        val previous = synchronized(stateLock) {
            workspaceFileWatcher.also { workspaceFileWatcher = registration }
        }
        previous?.dispose()
        Timber.tag(TAG).i("Workspace file watcher started for: %s", workspaceRoot)
    }

    private fun notifyWorkspaceFileChange(file: File, eventType: FileChangeType) {
        val path = file.absolutePath
        val patterns = synchronized(stateLock) { watchedPatterns.toList() }
        val eventMask = when (eventType) {
            FileChangeType.Created -> LspFileWatchPattern.CREATE_EVENT
            FileChangeType.Changed -> LspFileWatchPattern.CHANGE_EVENT
            FileChangeType.Deleted -> LspFileWatchPattern.DELETE_EVENT
        }
        val matched = patterns.any { (_, globs) -> globs.any { it.matches(path, eventMask) } }
        if (!matched) return

        val changes = listOf(FileEvent(file.toURI().toString(), eventType))
        val sessions = synchronized(stateLock) {
            buildSet {
                tabSessions.values.mapNotNullTo(this) { it.lspSession }
                sharedCxxSession?.let { add(it) }
            }
        }.toList()
        sessions.forEach { session ->
            runCatching { session.didChangeWatchedFiles(changes) }
                .onFailure { error -> Timber.tag(TAG).w(error, "didChangeWatchedFiles failed: %s", path) }
        }
        Timber.tag(TAG).d("didChangeWatchedFiles: %s (%s)", path, eventType)
    }

    private fun hasWorkspaceFileWatcher(): Boolean = synchronized(stateLock) {
        workspaceFileWatcher != null
    }

    private fun stopFileWatcher() {
        val registration = synchronized(stateLock) {
            watchedPatterns.clear()
            workspaceFileWatcher.also { workspaceFileWatcher = null }
        }
        registration?.dispose()
    }

    private fun stopFileWatcherIfCxxIdle(): Boolean {
        val registration = synchronized(stateLock) {
            if (sharedCxxSession != null || tabBindings.values.any { it.kind == SessionKind.CXX }) {
                return false
            }
            watchedPatterns.clear()
            workspaceFileWatcher.also { workspaceFileWatcher = null }
        }
        registration?.dispose()
        return true
    }

    private fun onCapabilityRegistered(registrations: List<Registration>) {
        val fileWatcherRegs = registrations.filter { it.method == "workspace/didChangeWatchedFiles" }
        if (fileWatcherRegs.isEmpty()) return
        synchronized(stateLock) {
            fileWatcherRegs.forEach { reg ->
                val options = reg.registerOptions
                if (options is DidChangeWatchedFilesRegistrationOptions) {
                    val globs = options.watchers.mapNotNull { watcher ->
                        LspFileWatchPattern.fromWatcher(watcher, lspProjectRoot)
                    }
                    if (globs.isNotEmpty()) {
                        watchedPatterns.add(reg.id to globs)
                        Timber.tag(TAG).i("Registered file watchers [%s]: %s", reg.id, globs)
                    }
                }
            }
        }
    }

    private fun onCapabilityUnregistered(unregistrations: List<Unregistration>) {
        val ids = unregistrations.map { it.id }.toSet()
        synchronized(stateLock) { watchedPatterns.removeAll { (id, _) -> id in ids } }
    }

    private fun normalizeVisibleLines(visibleLines: IntRange): IntRange {
        if (visibleLines.isEmpty()) return visibleLines
        val start = visibleLines.first.coerceAtLeast(0)
        val end = visibleLines.last.coerceAtLeast(start)
        return start..end
    }

    private fun expandSemanticRequestLines(visibleLines: IntRange): IntRange {
        val normalized = normalizeVisibleLines(visibleLines)
        if (normalized.isEmpty()) return normalized
        val start = (normalized.first - SEMANTIC_PREFETCH_MARGIN_LINES).coerceAtLeast(0)
        val maxEnd = start + SEMANTIC_MAX_PREFETCH_SPAN_LINES
        val targetEnd = normalized.last + SEMANTIC_PREFETCH_MARGIN_LINES
        val end = targetEnd.coerceAtMost(maxEnd).coerceAtLeast(start)
        return start..end
    }

    private fun IntRange.containsRange(other: IntRange): Boolean {
        if (this.isEmpty() || other.isEmpty()) return false
        return this.first <= other.first && this.last >= other.last
    }

    private fun List<SemanticToken>.filterToVisibleLines(visibleLines: IntRange): List<SemanticToken> {
        if (isEmpty() || visibleLines.isEmpty()) return emptyList()
        return asSequence()
            .filter { token -> token.line in visibleLines && token.length > 0 }
            .toList()
    }

    private suspend fun <T> withLspTabSession(
        tabId: String,
        block: suspend (TabSession, LspClientSession) -> T
    ): T? {
        val tabSession = synchronized(stateLock) { tabSessions[tabId] } ?: return null
        val session = tabSession.lspSession ?: return null
        if (!tabSession.isConnected) return null
        return block(tabSession, session)
    }

    private fun mapCompletionKind(kind: org.eclipse.lsp4j.CompletionItemKind?): CompletionItemKind = when (kind) {
        org.eclipse.lsp4j.CompletionItemKind.Method -> CompletionItemKind.METHOD
        org.eclipse.lsp4j.CompletionItemKind.Function -> CompletionItemKind.FUNCTION
        org.eclipse.lsp4j.CompletionItemKind.Constructor -> CompletionItemKind.CONSTRUCTOR
        org.eclipse.lsp4j.CompletionItemKind.Field -> CompletionItemKind.FIELD
        org.eclipse.lsp4j.CompletionItemKind.Variable -> CompletionItemKind.VARIABLE
        org.eclipse.lsp4j.CompletionItemKind.Class -> CompletionItemKind.CLASS
        org.eclipse.lsp4j.CompletionItemKind.Interface -> CompletionItemKind.INTERFACE
        org.eclipse.lsp4j.CompletionItemKind.Module -> CompletionItemKind.MODULE
        org.eclipse.lsp4j.CompletionItemKind.Property -> CompletionItemKind.PROPERTY
        org.eclipse.lsp4j.CompletionItemKind.Unit -> CompletionItemKind.UNIT
        org.eclipse.lsp4j.CompletionItemKind.Value -> CompletionItemKind.VALUE
        org.eclipse.lsp4j.CompletionItemKind.Enum -> CompletionItemKind.ENUM
        org.eclipse.lsp4j.CompletionItemKind.Keyword -> CompletionItemKind.KEYWORD
        org.eclipse.lsp4j.CompletionItemKind.Snippet -> CompletionItemKind.SNIPPET
        org.eclipse.lsp4j.CompletionItemKind.Color -> CompletionItemKind.COLOR
        org.eclipse.lsp4j.CompletionItemKind.File -> CompletionItemKind.FILE
        org.eclipse.lsp4j.CompletionItemKind.Reference -> CompletionItemKind.REFERENCE
        org.eclipse.lsp4j.CompletionItemKind.Folder -> CompletionItemKind.FOLDER
        org.eclipse.lsp4j.CompletionItemKind.EnumMember -> CompletionItemKind.ENUM_MEMBER
        org.eclipse.lsp4j.CompletionItemKind.Constant -> CompletionItemKind.CONSTANT
        org.eclipse.lsp4j.CompletionItemKind.Struct -> CompletionItemKind.STRUCT
        org.eclipse.lsp4j.CompletionItemKind.Event -> CompletionItemKind.EVENT
        org.eclipse.lsp4j.CompletionItemKind.Operator -> CompletionItemKind.OPERATOR
        org.eclipse.lsp4j.CompletionItemKind.TypeParameter -> CompletionItemKind.TYPE_PARAMETER
        else -> CompletionItemKind.TEXT
    }

    private fun extractDocumentation(doc: Either<String, MarkupContent>?): String? = when {
        doc == null -> null
        doc.isLeft -> doc.left
        doc.isRight -> doc.right?.value
        else -> null
    }

    private fun normalizeInsertText(item: org.eclipse.lsp4j.CompletionItem): String? {
        val rawText = extractRawInsertText(item)
        return normalizeCompletionPayloadText(rawText, item.insertTextFormat)
    }

    private fun extractRawInsertText(item: org.eclipse.lsp4j.CompletionItem): String = item.insertText
        ?: item.textEdit?.let { textEdit ->
            when {
                textEdit.isLeft -> textEdit.left?.newText
                textEdit.isRight -> textEdit.right?.newText
                else -> null
            }
        }
        ?: item.label

    private fun extractSnippetText(item: org.eclipse.lsp4j.CompletionItem): String? {
        if (item.insertTextFormat != org.eclipse.lsp4j.InsertTextFormat.Snippet) return null
        val raw = extractRawInsertText(item)
        if (!raw.contains('$')) return null
        return raw
    }

    private fun normalizeMainCompletionTextEdit(item: org.eclipse.lsp4j.CompletionItem): CompletionTextEdit? {
        val textEdit = item.textEdit ?: return null
        return when {
            textEdit.isLeft -> normalizeCompletionTextEdit(
                textEdit = textEdit.left ?: return null,
                insertTextFormat = item.insertTextFormat
            )

            textEdit.isRight -> {
                val insertReplace = textEdit.right ?: return null
                val targetRange = chooseCompletionRange(insertReplace) ?: return null
                normalizeCompletionTextEdit(
                    textEdit = TextEdit(targetRange, insertReplace.newText),
                    insertTextFormat = item.insertTextFormat
                )
            }

            else -> null
        }
    }

    private fun normalizeAdditionalCompletionTextEdits(
        item: org.eclipse.lsp4j.CompletionItem
    ): List<CompletionTextEdit>? {
        val rawEdits = item.additionalTextEdits.orEmpty()
        if (rawEdits.isEmpty()) return emptyList()
        val normalizedEdits = ArrayList<CompletionTextEdit>(rawEdits.size)
        rawEdits.forEach { textEdit ->
            normalizedEdits += normalizeCompletionTextEdit(textEdit, item.insertTextFormat) ?: return null
        }
        return normalizedEdits
    }

    private fun normalizeCompletionTextEdit(
        textEdit: TextEdit,
        insertTextFormat: org.eclipse.lsp4j.InsertTextFormat?
    ): CompletionTextEdit? {
        val range = textEdit.range ?: return null
        val start = range.start ?: return null
        val end = range.end ?: return null
        val startLine = start.line
        val startColumn = start.character
        val endLine = end.line
        val endColumn = end.character
        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) return null
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) return null
        val normalizedText = normalizeCompletionPayloadText(
            text = textEdit.newText.orEmpty(),
            insertTextFormat = insertTextFormat
        )
        return CompletionTextEdit(
            startLine = startLine,
            startColumn = startColumn,
            endLine = endLine,
            endColumn = endColumn,
            newText = normalizedText
        )
    }

    private fun chooseCompletionRange(edit: InsertReplaceEdit): org.eclipse.lsp4j.Range? {
        // TinaEditor 当前只有“替换”模式，默认优先使用 replace range，避免中间补全残留后缀。
        return edit.replace ?: edit.insert
    }

    private fun normalizeCompletionPayloadText(
        text: String,
        insertTextFormat: org.eclipse.lsp4j.InsertTextFormat?
    ): String {
        if (insertTextFormat != org.eclipse.lsp4j.InsertTextFormat.Snippet) {
            return text
        }
        // snippet 文本直接返回原文，由编辑器 snippet 引擎（SnippetParser + SnippetSession）处理
        return text
    }

    private fun languageIdForFile(file: File): String = file.resolveLspLanguageId()
}

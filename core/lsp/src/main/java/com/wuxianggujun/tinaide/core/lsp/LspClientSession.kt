package com.wuxianggujun.tinaide.core.lsp

import android.os.Process
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import kotlin.math.max
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import timber.log.Timber

/**
 * 基于 lsp4j Launcher 的原生 LSP 会话实现。
 *
 * 职责：
 * - 管理 initialize / initialized / shutdown / exit 生命周期；
 * - 管理当前文档的 didOpen / didChange / didClose；
 * - 暴露 LSP 请求能力给上层（导航、代码操作、补全、折叠等）。
 */
class LspClientSession(
    private val connectionProvider: LspConnectionProvider,
    documentUri: String,
    private val workspaceRootUri: String?,
    private val diagnosticsConsumer: (fileUri: String, diagnostics: List<org.eclipse.lsp4j.Diagnostic>) -> Unit,
    private val registrationConsumer: (registrations: List<Registration>) -> Unit = {},
    private val unregistrationConsumer: (unregistrations: List<Unregistration>) -> Unit = {},
    private val protocolFailureConsumer: (Throwable) -> Unit = {},
    private val tag: String = "LspClientSession",
) : LspSession,
    AutoCloseable {

    companion object {
        private const val INITIALIZE_TIMEOUT_SECONDS = 15L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val METHOD_PREPARE_CALL_HIERARCHY = "textDocument/prepareCallHierarchy"
    }

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lsp-client-${System.currentTimeMillis()}").apply { isDaemon = true }
    }

    private var launcher: Launcher<LanguageServer>? = null
    private var languageServer: LanguageServer? = null
    private var remoteEndpoint: Endpoint? = null
    private var listenFuture: Future<*>? = null
    private var currentDocumentUri: String = documentUri
    private var version: Int = 0
    private var semanticTokenTypes: List<String> = emptyList()
    private var semanticTokenModifiers: List<String> = emptyList()

    @Volatile
    private var staticServerSupportsCallHierarchy: Boolean = false
    private val capabilityLock = Any()
    private val dynamicCallHierarchyRegistrationIds = mutableSetOf<String>()

    override val documentUri: String
        get() = synchronized(lock) { currentDocumentUri }

    override val supportsCallHierarchy: Boolean
        get() = staticServerSupportsCallHierarchy ||
            synchronized(capabilityLock) {
                dynamicCallHierarchyRegistrationIds.isNotEmpty()
            }

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(
        documentUri: String,
        languageId: String,
        initialText: String,
        initializationOptions: Any? = null,
    ): Result<Unit> {
        if (closed.get()) return Result.failure(IllegalStateException("Session already closed"))

        return runCatching {
            synchronized(lock) {
                require(documentUri.isNotBlank()) { "documentUri must not be blank" }
                if (isConnected) {
                    activateDocumentLocked(documentUri, languageId, initialText)
                    return@runCatching Unit
                }

                Timber.tag(tag).i("connect: starting connectionProvider...")
                connectionProvider.start()
                Timber.tag(tag).i("connect: connectionProvider started, creating launcher...")
                val client = SessionLanguageClient(
                    diagnosticsConsumer = diagnosticsConsumer,
                    onRegisterCapability = { registrations ->
                        registerDynamicServerCapabilities(registrations)
                        registrationConsumer(registrations)
                    },
                    onUnregisterCapability = { unregistrations ->
                        unregisterDynamicServerCapabilities(unregistrations)
                        unregistrationConsumer(unregistrations)
                    },
                )
                val createdLauncher = LSPLauncher.createClientLauncher(
                    client,
                    connectionProvider.inputStream,
                    connectionProvider.outputStream,
                    executor,
                    Function { messageConsumer -> messageConsumer }
                )
                val server = createdLauncher.remoteProxy

                launcher = createdLauncher
                languageServer = server
                remoteEndpoint = createdLauncher.remoteEndpoint
                listenFuture = createdLauncher.startListening().also(::monitorProtocolListener)
                Timber.tag(tag).i("connect: launcher listening, sending initialize request...")

                val initializeParams = InitializeParams().apply {
                    processId = Process.myPid()
                    @Suppress("DEPRECATION")
                    rootUri = workspaceRootUri
                    capabilities = buildClientCapabilities()
                    this.initializationOptions = initializationOptions

                    workspaceRootUri?.let { root ->
                        val folderName = runCatching { File(java.net.URI(root)).name }
                            .getOrElse { File(root).name }
                            .ifBlank { "workspace" }
                        workspaceFolders = listOf(WorkspaceFolder(root, folderName))
                    }
                }

                staticServerSupportsCallHierarchy = false
                synchronized(capabilityLock) {
                    dynamicCallHierarchyRegistrationIds.clear()
                }

                val initializeResult = server.initialize(initializeParams)
                    .get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Timber.tag(tag).i(
                    "connect: initialize response received, capabilities=%s",
                    initializeResult.capabilities?.completionProvider != null
                )
                val serverCapabilities = initializeResult.capabilities
                staticServerSupportsCallHierarchy = serverCapabilities.supportsCallHierarchyProvider()

                server.initialized(InitializedParams())

                val legend = serverCapabilities
                    ?.semanticTokensProvider
                    ?.legend
                semanticTokenTypes = legend?.tokenTypes.orEmpty()
                semanticTokenModifiers = legend?.tokenModifiers.orEmpty()

                openDocumentLocked(
                    server = server,
                    documentUri = documentUri,
                    languageId = languageId,
                    text = initialText
                )
                isConnected = true
                Timber.tag(tag).i("connect: LSP session fully connected (languageId=%s)", languageId)
            }
        }.onFailure { error ->
            Timber.tag(tag).w(error, "Failed to connect LSP session")
            close()
        }
    }

    fun connect(
        languageId: String,
        initialText: String,
        initializationOptions: Any? = null,
    ): Result<Unit> = connect(
        documentUri = this.documentUri,
        languageId = languageId,
        initialText = initialText,
        initializationOptions = initializationOptions
    )

    fun didChange(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        oldTextLength: Int,
        newText: String,
    ) {
        val server = synchronized(lock) { languageServer } ?: return
        if (!isConnected) return

        val newVersion = synchronized(lock) {
            val currentUri = currentDocumentUri.ifBlank { return }
            version = max(1, version + 1)
            currentUri to version
        }

        val contentChange = TextDocumentContentChangeEvent().apply {
            range = Range(
                Position(startLine, startColumn),
                Position(endLine, endColumn)
            )
            @Suppress("DEPRECATION")
            rangeLength = oldTextLength
            text = newText
        }

        val params = DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(newVersion.first, newVersion.second)
            contentChanges = listOf(contentChange)
        }
        server.textDocumentService.didChange(params)
    }

    fun didChangeFull(text: String) {
        val server = synchronized(lock) { languageServer } ?: return
        if (!isConnected) return

        val document = synchronized(lock) {
            val currentUri = currentDocumentUri.ifBlank { return }
            version = max(1, version + 1)
            currentUri to version
        }

        val params = DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(document.first, document.second)
            contentChanges = listOf(TextDocumentContentChangeEvent(text))
        }
        server.textDocumentService.didChange(params)
    }

    fun didSave(text: String? = null) {
        val server = synchronized(lock) { languageServer } ?: return
        if (!isConnected) return
        val document = synchronized(lock) { currentDocumentUri.ifBlank { return } }
        val params = DidSaveTextDocumentParams().apply {
            textDocument = TextDocumentIdentifier(document)
            text?.let { this.text = it }
        }
        server.textDocumentService.didSave(params)
    }

    fun closeDocument(documentUri: String? = null) {
        val server = synchronized(lock) { languageServer } ?: return
        if (!isConnected) return
        synchronized(lock) {
            closeCurrentDocumentLocked(server, documentUri)
        }
    }

    fun isCurrentDocument(documentUri: String): Boolean = synchronized(lock) {
        isConnected && currentDocumentUri == documentUri
    }

    fun currentDocumentVersion(documentUri: String? = null): Int? = synchronized(lock) {
        if (!isConnected || version <= 0) return@synchronized null
        if (documentUri != null && currentDocumentUri != documentUri) return@synchronized null
        version
    }

    fun didChangeWatchedFiles(changes: List<FileEvent>) {
        val server = synchronized(lock) { languageServer } ?: return
        if (!isConnected) return
        server.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(changes))
    }

    fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.completion(params)
    }

    fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.foldingRange(params)
    }

    fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.semanticTokensFull(params)
    }

    fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.semanticTokensRange(params)
    }

    fun semanticTokenLegendTypes(): List<String> = synchronized(lock) {
        semanticTokenTypes
    }

    fun semanticTokenLegendModifiers(): List<String> = synchronized(lock) {
        semanticTokenModifiers
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.hover(params)
    }

    fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.signatureHelp(params)
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.definition(params)
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location?>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.references(params)
    }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.typeDefinition(params)
    }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.implementation(params)
    }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.prepareCallHierarchy(params)
    }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<List<CallHierarchyIncomingCall>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.callHierarchyIncomingCalls(params)
    }

    private fun registerDynamicServerCapabilities(registrations: List<Registration>) {
        val ids = registrations
            .filter { it.method == METHOD_PREPARE_CALL_HIERARCHY }
            .map { it.id }
        if (ids.isEmpty()) return

        synchronized(capabilityLock) {
            dynamicCallHierarchyRegistrationIds.addAll(ids)
        }
    }

    private fun unregisterDynamicServerCapabilities(unregistrations: List<Unregistration>) {
        val ids = unregistrations
            .filter { it.method == METHOD_PREPARE_CALL_HIERARCHY }
            .map { it.id }
        if (ids.isEmpty()) return

        synchronized(capabilityLock) {
            dynamicCallHierarchyRegistrationIds.removeAll(ids.toSet())
        }
    }

    private fun org.eclipse.lsp4j.ServerCapabilities?.supportsCallHierarchyProvider(): Boolean {
        val provider = this?.callHierarchyProvider ?: return false
        return when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol?>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.workspaceService.symbol(params)
    }

    override fun resolveWorkspaceSymbol(unresolved: WorkspaceSymbol): CompletableFuture<WorkspaceSymbol>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.workspaceService.resolveWorkspaceSymbol(unresolved)
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.documentSymbol(params)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.codeAction(params)
    }

    override fun resolveCodeAction(unresolved: CodeAction): CompletableFuture<CodeAction>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.resolveCodeAction(unresolved)
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<*>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.workspaceService.executeCommand(params)
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<*>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.prepareRename(params)
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit>? {
        val server = synchronized(lock) { languageServer } ?: return null
        if (!isConnected) return null
        return server.textDocumentService.rename(params)
    }

    override fun customRequest(method: String, params: Any): CompletableFuture<*>? {
        if (!isConnected) return null
        return remoteEndpoint?.request(method, params)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val server = synchronized(lock) { languageServer }
        if (isConnected && server != null) {
            runCatching { synchronized(lock) { closeCurrentDocumentLocked(server) } }
            runCatching { server.shutdown().get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            runCatching { server.exit() }
        }

        isConnected = false
        runCatching { listenFuture?.cancel(true) }
        listenFuture = null
        launcher = null
        languageServer = null
        remoteEndpoint = null
        semanticTokenTypes = emptyList()
        semanticTokenModifiers = emptyList()
        staticServerSupportsCallHierarchy = false
        synchronized(capabilityLock) {
            dynamicCallHierarchyRegistrationIds.clear()
        }

        runCatching { connectionProvider.close() }
        executor.shutdownNow()
    }

    private fun monitorProtocolListener(future: Future<*>) {
        (future as? CompletableFuture<*>)?.whenComplete { _, error ->
            if (error == null || closed.get()) return@whenComplete
            val cause = (error as? CompletionException)?.cause ?: error
            runCatching { protocolFailureConsumer(cause) }
                .onFailure { callbackError ->
                    Timber.tag(tag).w(callbackError, "LSP protocol failure callback failed")
                }
        }
    }

    private fun activateDocumentLocked(
        documentUri: String,
        languageId: String,
        text: String,
    ) {
        val server = languageServer ?: return
        if (currentDocumentUri == documentUri) {
            sendDidChangeFullLocked(server, documentUri, text)
            return
        }
        closeCurrentDocumentLocked(server)
        openDocumentLocked(server, documentUri, languageId, text)
    }

    private fun openDocumentLocked(
        server: LanguageServer,
        documentUri: String,
        languageId: String,
        text: String,
    ) {
        currentDocumentUri = documentUri
        version = 1
        server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(documentUri, languageId, version, text)
            )
        )
    }

    private fun sendDidChangeFullLocked(
        server: LanguageServer,
        documentUri: String,
        text: String,
    ) {
        version = max(1, version + 1)
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams().apply {
                textDocument = VersionedTextDocumentIdentifier(documentUri, version)
                contentChanges = listOf(TextDocumentContentChangeEvent(text))
            }
        )
    }

    private fun closeCurrentDocumentLocked(
        server: LanguageServer,
        expectedDocumentUri: String? = null,
    ) {
        val current = currentDocumentUri.takeIf { it.isNotBlank() } ?: return
        if (expectedDocumentUri != null && current != expectedDocumentUri) return
        server.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(current))
        )
        currentDocumentUri = ""
        version = 0
    }

    private fun buildClientCapabilities(): ClientCapabilities {
        val symbolKinds = org.eclipse.lsp4j.SymbolKind.values().toList()
        val semanticTokenTypes = listOf(
            org.eclipse.lsp4j.SemanticTokenTypes.Namespace,
            org.eclipse.lsp4j.SemanticTokenTypes.Type,
            org.eclipse.lsp4j.SemanticTokenTypes.Class,
            org.eclipse.lsp4j.SemanticTokenTypes.Enum,
            org.eclipse.lsp4j.SemanticTokenTypes.Interface,
            org.eclipse.lsp4j.SemanticTokenTypes.Struct,
            org.eclipse.lsp4j.SemanticTokenTypes.TypeParameter,
            org.eclipse.lsp4j.SemanticTokenTypes.Parameter,
            org.eclipse.lsp4j.SemanticTokenTypes.Variable,
            org.eclipse.lsp4j.SemanticTokenTypes.Property,
            org.eclipse.lsp4j.SemanticTokenTypes.EnumMember,
            org.eclipse.lsp4j.SemanticTokenTypes.Event,
            org.eclipse.lsp4j.SemanticTokenTypes.Function,
            org.eclipse.lsp4j.SemanticTokenTypes.Method,
            org.eclipse.lsp4j.SemanticTokenTypes.Macro,
            org.eclipse.lsp4j.SemanticTokenTypes.Keyword,
            org.eclipse.lsp4j.SemanticTokenTypes.Comment,
            org.eclipse.lsp4j.SemanticTokenTypes.String,
            org.eclipse.lsp4j.SemanticTokenTypes.Number,
            org.eclipse.lsp4j.SemanticTokenTypes.Operator
        )
        val semanticTokenModifiers = listOf(
            org.eclipse.lsp4j.SemanticTokenModifiers.Declaration,
            org.eclipse.lsp4j.SemanticTokenModifiers.Definition,
            org.eclipse.lsp4j.SemanticTokenModifiers.Readonly,
            org.eclipse.lsp4j.SemanticTokenModifiers.Static,
            org.eclipse.lsp4j.SemanticTokenModifiers.Deprecated,
            org.eclipse.lsp4j.SemanticTokenModifiers.Abstract,
            org.eclipse.lsp4j.SemanticTokenModifiers.Async,
            org.eclipse.lsp4j.SemanticTokenModifiers.Modification,
            org.eclipse.lsp4j.SemanticTokenModifiers.Documentation,
            org.eclipse.lsp4j.SemanticTokenModifiers.DefaultLibrary
        )

        val workspaceCapabilities = org.eclipse.lsp4j.WorkspaceClientCapabilities().apply {
            applyEdit = true
            workspaceFolders = true
            configuration = true
            workspaceEdit = org.eclipse.lsp4j.WorkspaceEditCapabilities().apply {
                documentChanges = true
                resourceOperations = listOf(
                    org.eclipse.lsp4j.ResourceOperationKind.Create,
                    org.eclipse.lsp4j.ResourceOperationKind.Rename,
                    org.eclipse.lsp4j.ResourceOperationKind.Delete
                )
                failureHandling = org.eclipse.lsp4j.FailureHandlingKind.TextOnlyTransactional
            }
            symbol = org.eclipse.lsp4j.SymbolCapabilities().apply {
                symbolKind = org.eclipse.lsp4j.SymbolKindCapabilities(symbolKinds)
                resolveSupport = org.eclipse.lsp4j.WorkspaceSymbolResolveSupportCapabilities(
                    listOf("location.range.start", "location.range.end")
                )
            }
            executeCommand = org.eclipse.lsp4j.ExecuteCommandCapabilities()
            didChangeWatchedFiles = DidChangeWatchedFilesCapabilities().apply {
                dynamicRegistration = true
            }
        }

        val textDocumentCapabilities = org.eclipse.lsp4j.TextDocumentClientCapabilities().apply {
            synchronization = org.eclipse.lsp4j.SynchronizationCapabilities().apply {
                didSave = true
                willSave = false
                willSaveWaitUntil = false
            }
            completion = org.eclipse.lsp4j.CompletionCapabilities().apply {
                contextSupport = true
                completionItem = org.eclipse.lsp4j.CompletionItemCapabilities().apply {
                    snippetSupport = true
                    commitCharactersSupport = true
                    documentationFormat = listOf(
                        org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                        org.eclipse.lsp4j.MarkupKind.PLAINTEXT
                    )
                    deprecatedSupport = true
                    preselectSupport = true
                    insertReplaceSupport = true
                    labelDetailsSupport = true
                }
            }
            hover = org.eclipse.lsp4j.HoverCapabilities().apply {
                contentFormat = listOf(
                    org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                    org.eclipse.lsp4j.MarkupKind.PLAINTEXT
                )
            }
            definition = org.eclipse.lsp4j.DefinitionCapabilities()
            references = org.eclipse.lsp4j.ReferencesCapabilities()
            typeDefinition = org.eclipse.lsp4j.TypeDefinitionCapabilities()
            implementation = org.eclipse.lsp4j.ImplementationCapabilities()
            callHierarchy = org.eclipse.lsp4j.CallHierarchyCapabilities().apply {
                dynamicRegistration = true
            }
            foldingRange = org.eclipse.lsp4j.FoldingRangeCapabilities()
            documentSymbol = org.eclipse.lsp4j.DocumentSymbolCapabilities().apply {
                hierarchicalDocumentSymbolSupport = true
                symbolKind = org.eclipse.lsp4j.SymbolKindCapabilities(symbolKinds)
            }
            publishDiagnostics = org.eclipse.lsp4j.PublishDiagnosticsCapabilities().apply {
                relatedInformation = true
                versionSupport = true
                codeDescriptionSupport = true
                dataSupport = true
            }
            codeAction = org.eclipse.lsp4j.CodeActionCapabilities().apply {
                isPreferredSupport = true
                disabledSupport = true
                dataSupport = true
                codeActionLiteralSupport = org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities(
                    org.eclipse.lsp4j.CodeActionKindCapabilities(
                        listOf(
                            org.eclipse.lsp4j.CodeActionKind.QuickFix,
                            org.eclipse.lsp4j.CodeActionKind.Refactor,
                            org.eclipse.lsp4j.CodeActionKind.RefactorExtract,
                            org.eclipse.lsp4j.CodeActionKind.RefactorInline,
                            org.eclipse.lsp4j.CodeActionKind.RefactorRewrite,
                            org.eclipse.lsp4j.CodeActionKind.Source,
                            org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports,
                            org.eclipse.lsp4j.CodeActionKind.SourceFixAll
                        )
                    )
                )
            }
            rename = org.eclipse.lsp4j.RenameCapabilities().apply {
                prepareSupport = true
            }
            semanticTokens = org.eclipse.lsp4j.SemanticTokensCapabilities().apply {
                requests = org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests().apply {
                    setFull(
                        org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequestsFull().apply {
                            delta = true
                        }
                    )
                    setRange(true)
                }
                tokenTypes = semanticTokenTypes
                tokenModifiers = semanticTokenModifiers
                formats = listOf(org.eclipse.lsp4j.TokenFormat.Relative)
                overlappingTokenSupport = true
                multilineTokenSupport = true
            }
        }

        return ClientCapabilities().apply {
            workspace = workspaceCapabilities
            textDocument = textDocumentCapabilities
        }
    }

    private class SessionLanguageClient(
        private val diagnosticsConsumer: (fileUri: String, diagnostics: List<org.eclipse.lsp4j.Diagnostic>) -> Unit,
        private val onRegisterCapability: (registrations: List<Registration>) -> Unit,
        private val onUnregisterCapability: (unregistrations: List<Unregistration>) -> Unit,
    ) : LanguageClient {
        override fun telemetryEvent(`object`: Any?) = Unit

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            diagnosticsConsumer(diagnostics.uri, diagnostics.diagnostics.orEmpty())
        }

        override fun showMessage(messageParams: MessageParams) = Unit

        override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(MessageActionItem("OK"))

        override fun logMessage(message: MessageParams) = Unit

        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
            onRegisterCapability(params.registrations.orEmpty())
            return CompletableFuture.completedFuture(null)
        }

        override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
            onUnregisterCapability(params.unregisterations.orEmpty())
            return CompletableFuture.completedFuture(null)
        }
    }
}

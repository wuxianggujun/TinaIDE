package com.wuxianggujun.tinaide.core.lsp

import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import timber.log.Timber

/**
 * LSP 导航服务
 *
 * 封装 Go to Definition、Find References、Go to Type Definition、
 * Go to Implementation、Switch Header/Source 等导航功能。
 */
class LspNavigationService {

    companion object {
        private const val TAG = "LspNavigation"
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_WORKSPACE_SYMBOLS = 200
        private const val RESOLVE_TIMEOUT_SECONDS = 3L
        private const val MAX_LOCATION_PREVIEWS = 120
        private const val MAX_PREVIEW_FILE_BYTES = 2L * 1024 * 1024 // 2MB
        private const val MAX_PREVIEW_TEXT_LENGTH = 200
    }

    /**
     * 跳转到定义
     */
    suspend fun gotoDefinition(
        documentUri: String,
        line: Int,
        column: Int,
        definitionRequest: suspend (DefinitionParams, Long) -> Either<List<Location>, List<LocationLink>>?,
    ): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val params = DefinitionParams(
                TextDocumentIdentifier(documentUri),
                Position(line, column)
            )
            val result = definitionRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            attachLinePreviews(parseLocationEither(result))
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to go to definition: ${e.message}")
            emptyList()
        }
    }

    /**
     * 查找引用
     */
    suspend fun findReferences(
        documentUri: String,
        line: Int,
        column: Int,
        includeDeclaration: Boolean = true,
        referencesRequest: suspend (ReferenceParams, Long) -> List<Location?>?,
    ): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val params = ReferenceParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                position = Position(line, column)
                context = ReferenceContext(includeDeclaration)
            }
            val result = referencesRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            attachLinePreviews(result.filterNotNull().map { it.toLocationItem() })
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to find references: ${e.message}")
            emptyList()
        }
    }

    /**
     * 跳转到类型定义
     */
    suspend fun gotoTypeDefinition(
        documentUri: String,
        line: Int,
        column: Int,
        typeDefinitionRequest: suspend (TypeDefinitionParams, Long) -> Either<List<Location>, List<LocationLink>>?,
    ): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val params = TypeDefinitionParams(
                TextDocumentIdentifier(documentUri),
                Position(line, column)
            )
            val result = typeDefinitionRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            attachLinePreviews(parseLocationEither(result))
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to go to type definition: ${e.message}")
            emptyList()
        }
    }

    /**
     * 跳转到实现
     */
    suspend fun gotoImplementation(
        documentUri: String,
        line: Int,
        column: Int,
        implementationRequest: suspend (ImplementationParams, Long) -> Either<List<Location>, List<LocationLink>>?,
    ): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val params = ImplementationParams(
                TextDocumentIdentifier(documentUri),
                Position(line, column)
            )
            val result = implementationRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            attachLinePreviews(parseLocationEither(result))
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to go to implementation: ${e.message}")
            emptyList()
        }
    }

    /**
     * 切换头文件/源文件（clangd 扩展协议 textDocument/switchSourceHeader）
     *
     * @return 目标文件的 URI 字符串，如果不可用则返回 null
     */
    suspend fun switchSourceHeader(
        documentUri: String,
        customRequest: suspend (method: String, params: Any, timeoutSeconds: Long) -> Any?,
        executeCommandRequest: suspend (ExecuteCommandParams, Long) -> Any?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // clangd 的 textDocument/switchSourceHeader 是一个自定义请求
            // 参数：TextDocumentIdentifier，返回：String?（目标文件 URI）
            val params = TextDocumentIdentifier(documentUri)
            val resultFromExtension = customRequest(
                "textDocument/switchSourceHeader",
                params,
                TIMEOUT_SECONDS,
            )

            extractUriString(resultFromExtension) ?: run {
                Timber.tag(TAG).w("switchSourceHeader extension request returned null/invalid, trying executeCommand fallback")
                // 备选：通过 executeCommand 发送 clangd.switchSourceHeader（部分客户端使用该命令代替自定义请求）
                val result = executeCommandRequest(
                    ExecuteCommandParams().apply {
                        command = "clangd.switchSourceHeader"
                        arguments = listOf(params)
                    },
                    TIMEOUT_SECONDS
                )
                extractUriString(result)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to switch source/header: ${e.message}")
            null
        }
    }

    /**
     * Workspace Symbols（workspace/symbol）
     *
     * 用于项目级符号搜索（跨文件）。
     */
    suspend fun workspaceSymbol(
        query: String,
        symbolRequest: suspend (WorkspaceSymbolParams, Long) -> Either<List<SymbolInformation>, List<WorkspaceSymbol?>>?,
    ): List<WorkspaceSymbolItem> = withContext(Dispatchers.IO) {
        try {
            val params = WorkspaceSymbolParams().apply {
                this.query = query
            }
            val result = symbolRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            parseWorkspaceSymbolEither(result)
                .distinctBy { it.stableId }
                .take(MAX_WORKSPACE_SYMBOLS)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to workspace symbol: ${e.message}")
            emptyList()
        }
    }

    /**
     * 当 workspace/symbol 返回 [WorkspaceSymbolLocation]（只有 uri 无 range）时，
     * 尝试通过 workspaceSymbol/resolve 补齐 range。
     *
     * 说明：该方法用于“懒 resolve”（例如点击条目时触发），避免在搜索阶段阻塞 UI。
     */
    suspend fun resolveWorkspaceSymbol(
        item: WorkspaceSymbolItem,
        resolveWorkspaceSymbolRequest: suspend (WorkspaceSymbol, Long) -> WorkspaceSymbol?,
    ): WorkspaceSymbolItem? = withContext(Dispatchers.IO) {
        if (!item.requiresResolve) return@withContext item

        try {
            @Suppress("DEPRECATION")
            val unresolved = WorkspaceSymbol().apply {
                name = item.name
                kind = item.kindValue?.let { SymbolKind.forValue(it) }
                containerName = item.containerName
                location = Either.forRight(WorkspaceSymbolLocation(item.uri))
                data = item.lspData
            }
            val resolved = resolveWorkspaceSymbolRequest(unresolved, RESOLVE_TIMEOUT_SECONDS)
                ?: return@withContext null

            val resolvedItem = resolved.toWorkspaceSymbolItemOrNull() ?: return@withContext null
            resolvedItem.copy(
                stableId = item.stableId,
                requiresResolve = false,
                lspData = item.lspData,
                kindValue = item.kindValue,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).d("Failed to resolve workspace symbol: ${e.message}")
            null
        }
    }

    /**
     * Document Symbols（textDocument/documentSymbol）
     *
     * 用于当前文件符号大纲（结构树）。
     */
    suspend fun documentSymbols(
        documentUri: String,
        documentSymbolRequest: suspend (DocumentSymbolParams, Long) -> List<Either<SymbolInformation, DocumentSymbol>>?,
    ): List<DocumentSymbolItem> = withContext(Dispatchers.IO) {
        try {
            val fileUri = documentUri
            val params = DocumentSymbolParams().apply {
                textDocument = TextDocumentIdentifier(fileUri)
            }
            val result = documentSymbolRequest(params, TIMEOUT_SECONDS)
                ?: return@withContext emptyList()
            parseDocumentSymbolEither(fileUri, result)
                .distinctBy { it.composeStableKey() }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to documentSymbols: ${e.message}")
            emptyList()
        }
    }

    /**
     * Call Hierarchy：查询当前符号的入向调用（谁调用了它）。
     */
    suspend fun callHierarchyIncomingCalls(
        documentUri: String,
        line: Int,
        column: Int,
        prepareRequest: suspend (CallHierarchyPrepareParams, Long) -> List<CallHierarchyItem>?,
        incomingCallsRequest: suspend (CallHierarchyIncomingCallsParams, Long) -> List<CallHierarchyIncomingCall>?,
    ): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val prepareParams = CallHierarchyPrepareParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                position = Position(line, column)
            }
            val preparedItems = prepareRequest(prepareParams, TIMEOUT_SECONDS).orEmpty()
            if (preparedItems.isEmpty()) return@withContext emptyList()

            val locations = preparedItems.flatMap { item ->
                val params = CallHierarchyIncomingCallsParams(item)
                incomingCallsRequest(params, TIMEOUT_SECONDS).orEmpty()
                    .flatMap { call -> call.toLocationItems() }
            }

            attachLinePreviews(
                locations.distinctBy { location ->
                    "${location.filePath}:${location.line}:${location.column}:${location.endLine}:${location.endColumn}"
                }
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to query call hierarchy incoming calls: ${e.message}")
            throw e
        }
    }

    // ---- 内部工具方法 ----

    /**
     * 将 Either<List<Location>, List<LocationLink>> 统一转换为 List<LocationItem>
     */
    private fun parseLocationEither(
        either: Either<List<Location>, List<LocationLink>>
    ): List<LocationItem> = when {
        either.isLeft -> either.left.map { it.toLocationItem() }
        either.isRight -> either.right.map { it.toLocationItem() }
        else -> emptyList()
    }

    private fun Location.toLocationItem(): LocationItem {
        val filePath = uriToFilePath(uri)
        return LocationItem(
            uri = uri,
            filePath = filePath,
            fileName = File(filePath).name,
            line = range.start.line,
            column = range.start.character,
            endLine = range.end.line,
            endColumn = range.end.character
        )
    }

    private fun LocationLink.toLocationItem(): LocationItem {
        val filePath = uriToFilePath(targetUri)
        return LocationItem(
            uri = targetUri,
            filePath = filePath,
            fileName = File(filePath).name,
            line = targetSelectionRange.start.line,
            column = targetSelectionRange.start.character,
            endLine = targetSelectionRange.end.line,
            endColumn = targetSelectionRange.end.character
        )
    }

    private fun CallHierarchyIncomingCall.toLocationItems(): List<LocationItem> {
        val caller = from ?: return emptyList()
        val callerUri = caller.uri ?: return emptyList()
        val filePath = uriToFilePath(callerUri)
        val ranges = fromRanges?.takeIf { it.isNotEmpty() } ?: listOfNotNull(caller.selectionRange)
        return ranges.map { range ->
            LocationItem(
                uri = callerUri,
                filePath = filePath,
                fileName = File(filePath).name,
                line = range.start.line,
                column = range.start.character,
                endLine = range.end.line,
                endColumn = range.end.character
            )
        }
    }
    private fun attachLinePreviews(items: List<LocationItem>): List<LocationItem> {
        if (items.isEmpty()) return items

        val candidates = mutableListOf<Pair<Int, LocationItem>>()
        for (index in items.indices) {
            val item = items[index]
            if (item.line < 0) continue
            candidates.add(index to item)
            if (candidates.size >= MAX_LOCATION_PREVIEWS) break
        }
        if (candidates.isEmpty()) return items

        val previewByIndex = HashMap<Int, String>(candidates.size)
        val byFile = candidates.groupBy(
            keySelector = { it.second.filePath },
            valueTransform = { it.first to it.second.line },
        )

        for ((filePath, indexAndLineList) in byFile) {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) continue
            if (file.length() > MAX_PREVIEW_FILE_BYTES) continue

            val lineToIndices = HashMap<Int, MutableList<Int>>()
            for ((idx, line) in indexAndLineList) {
                if (line < 0) continue
                lineToIndices.getOrPut(line) { mutableListOf() }.add(idx)
            }
            val targetLines = lineToIndices.keys.sorted()
            if (targetLines.isEmpty()) continue

            try {
                file.bufferedReader().use { reader ->
                    var currentLine = 0
                    var targetPos = 0
                    var nextTarget = targetLines[targetPos]
                    while (true) {
                        val textLine = reader.readLine() ?: break
                        if (currentLine == nextTarget) {
                            val preview = textLine.trim().take(MAX_PREVIEW_TEXT_LENGTH)
                            if (preview.isNotBlank()) {
                                val indices = lineToIndices[nextTarget]
                                if (indices != null) {
                                    for (idx in indices) {
                                        previewByIndex[idx] = preview
                                    }
                                }
                            }
                            targetPos++
                            if (targetPos >= targetLines.size) break
                            nextTarget = targetLines[targetPos]
                        }
                        currentLine++
                    }
                }
            } catch (_: Exception) {
                // ignore preview failures
            }
        }

        if (previewByIndex.isEmpty()) return items
        return items.mapIndexed { idx, item ->
            val preview = previewByIndex[idx] ?: return@mapIndexed item
            item.copy(previewText = preview)
        }
    }

    private fun uriToFilePath(uri: String): String = try {
        File(URI(uri)).absolutePath
    } catch (e: Exception) {
        // File(URI) 拒绝 UNC 等形式；退回到解码后的路径，避免把 %XX 当成文件名。
        lspDocumentUriToFilePath(uri) ?: uri.removePrefix("file://")
    }

    private fun extractUriString(result: Any?): String? {
        if (result == null) return null
        return when (result) {
            is String -> result
            is URI -> result.toString()
            is TextDocumentIdentifier -> result.uri
            is Map<*, *> -> {
                (result["uri"] ?: result["targetUri"] ?: result["result"])?.toString()
            }
            is List<*> -> result.firstOrNull()?.toString()
            else -> result.toString()
        }?.takeIf { it.isNotBlank() }
    }

    private fun parseWorkspaceSymbolEither(
        either: Either<List<SymbolInformation>, List<WorkspaceSymbol?>>,
    ): List<WorkspaceSymbolItem> = when {
        either.isLeft -> either.left.mapNotNull { it.toWorkspaceSymbolItemOrNull() }
        either.isRight -> either.right.mapNotNull { it?.toWorkspaceSymbolItemOrNull() }
        else -> emptyList()
    }

    private fun parseDocumentSymbolEither(
        fileUri: String,
        symbols: List<Either<SymbolInformation, DocumentSymbol>>,
    ): List<DocumentSymbolItem> {
        val filePath = uriToFilePath(fileUri)
        val fileName = File(filePath).name
        val items = mutableListOf<DocumentSymbolItem>()

        fun appendDocumentSymbol(symbol: DocumentSymbol, containerName: String?, level: Int) {
            val name = symbol.name ?: return
            val kindName = symbol.kind?.name ?: "Unknown"
            val navRange = symbol.selectionRange ?: symbol.range ?: return

            items.add(
                DocumentSymbolItem(
                    name = name,
                    kind = kindName,
                    containerName = containerName,
                    uri = fileUri,
                    filePath = filePath,
                    fileName = fileName,
                    line = navRange.start.line,
                    column = navRange.start.character,
                    endLine = navRange.end.line,
                    endColumn = navRange.end.character,
                    level = level,
                )
            )

            val children = symbol.children ?: return
            for (child in children) {
                appendDocumentSymbol(child, name, level + 1)
            }
        }

        for (either in symbols) {
            when {
                either.isLeft -> {
                    val item = either.left?.toDocumentSymbolItemOrNull() ?: continue
                    items.add(item)
                }

                either.isRight -> {
                    val symbol = either.right ?: continue
                    appendDocumentSymbol(symbol, containerName = null, level = 0)
                }
            }
        }

        return items
    }

    @Suppress("DEPRECATION")
    private fun SymbolInformation.toDocumentSymbolItemOrNull(): DocumentSymbolItem? {
        val symbolName = name ?: return null
        val kindName = kind?.name ?: "Unknown"
        val loc = location ?: return null
        val locPath = uriToFilePath(loc.uri)
        return DocumentSymbolItem(
            name = symbolName,
            kind = kindName,
            containerName = containerName,
            uri = loc.uri,
            filePath = locPath,
            fileName = File(locPath).name,
            line = loc.range.start.line,
            column = loc.range.start.character,
            endLine = loc.range.end.line,
            endColumn = loc.range.end.character,
            level = 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun SymbolInformation.toWorkspaceSymbolItemOrNull(): WorkspaceSymbolItem? {
        val loc = location ?: return null
        val name = name ?: return null
        val kindValue = kind?.value
        val kindName = kind?.name ?: "Unknown"
        val filePath = uriToFilePath(loc.uri)
        val stableId = buildWorkspaceSymbolStableId(
            filePath = filePath,
            name = name,
            kindValue = kindValue,
            containerName = containerName,
            line = loc.range.start.line,
            column = loc.range.start.character,
            data = null,
        )
        return WorkspaceSymbolItem(
            stableId = stableId,
            name = name,
            kind = kindName,
            kindValue = kindValue,
            containerName = containerName,
            uri = loc.uri,
            filePath = filePath,
            fileName = File(filePath).name,
            line = loc.range.start.line,
            column = loc.range.start.character,
            endLine = loc.range.end.line,
            endColumn = loc.range.end.character,
            requiresResolve = false,
            lspData = null,
        )
    }

    @Suppress("DEPRECATION")
    private fun WorkspaceSymbol.toWorkspaceSymbolItemOrNull(): WorkspaceSymbolItem? {
        val name = name ?: return null
        val kindValue = kind?.value
        val kindName = kind?.name ?: "Unknown"

        return when {
            location.isLeft -> {
                val loc = location.left ?: return null
                val filePath = uriToFilePath(loc.uri)
                val stableId = buildWorkspaceSymbolStableId(
                    filePath = filePath,
                    name = name,
                    kindValue = kindValue,
                    containerName = containerName,
                    line = loc.range.start.line,
                    column = loc.range.start.character,
                    data = data,
                )
                WorkspaceSymbolItem(
                    stableId = stableId,
                    name = name,
                    kind = kindName,
                    kindValue = kindValue,
                    containerName = containerName,
                    uri = loc.uri,
                    filePath = filePath,
                    fileName = File(filePath).name,
                    line = loc.range.start.line,
                    column = loc.range.start.character,
                    endLine = loc.range.end.line,
                    endColumn = loc.range.end.character,
                    requiresResolve = false,
                    lspData = data,
                )
            }

            location.isRight -> {
                val wsLoc = location.right ?: return null
                val filePath = uriToFilePath(wsLoc.uri)
                val stableId = buildWorkspaceSymbolStableId(
                    filePath = filePath,
                    name = name,
                    kindValue = kindValue,
                    containerName = containerName,
                    line = null,
                    column = null,
                    data = data,
                )
                WorkspaceSymbolItem(
                    stableId = stableId,
                    name = name,
                    kind = kindName,
                    kindValue = kindValue,
                    containerName = containerName,
                    uri = wsLoc.uri,
                    filePath = filePath,
                    fileName = File(filePath).name,
                    line = -1,
                    column = -1,
                    endLine = -1,
                    endColumn = -1,
                    requiresResolve = true,
                    lspData = data,
                )
            }

            else -> null
        }
    }
}

/**
 * 统一的位置结果模型
 */
data class LocationItem(
    val uri: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val previewText: String? = null
)

/**
 * Workspace Symbols 结果模型
 */
data class WorkspaceSymbolItem(
    val stableId: String,
    val name: String,
    val kind: String,
    val kindValue: Int? = null,
    val containerName: String? = null,
    val uri: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val requiresResolve: Boolean = false,
    val lspData: Any? = null,
)

/**
 * Document Symbols 结果模型（用于 Outline/结构树）。
 */
data class DocumentSymbolItem(
    val name: String,
    val kind: String,
    val containerName: String? = null,
    val uri: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val level: Int = 0,
)

private fun WorkspaceSymbolItem.composeStableKey(): String = stableId

private fun buildWorkspaceSymbolStableId(
    filePath: String,
    name: String,
    kindValue: Int?,
    containerName: String?,
    line: Int?,
    column: Int?,
    data: Any?,
): String = buildString {
    append("ws|")
    append(filePath)
    append("|")
    append(name)
    append("|")
    append(kindValue ?: -1)
    append("|")
    append(containerName ?: "")
    append("|")
    if (line != null && column != null) {
        append("@")
        append(line)
        append(":")
        append(column)
    } else {
        append("data#")
        append(data?.hashCode() ?: 0)
    }
}

private fun DocumentSymbolItem.composeStableKey(): String = buildString {
    append(filePath)
    append("|")
    append(line)
    append("|")
    append(column)
    append("|")
    append(level)
    append("|")
    append(name)
    append("|")
    append(kind)
    append("|")
    append(containerName ?: "")
}

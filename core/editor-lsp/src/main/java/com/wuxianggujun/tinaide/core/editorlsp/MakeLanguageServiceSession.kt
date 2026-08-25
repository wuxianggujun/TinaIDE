package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import java.io.File

class MakeLanguageServiceSession(
    val file: File,
    val documentUri: String,
    private val textProvider: () -> String,
    private val caseSensitiveProvider: () -> Boolean = { false }
) : BuiltinLanguageServiceSession {
    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var diagnosticsSnapshot: DiagnosticsSnapshot? = null

    @Volatile
    private var documentSymbolsSnapshot: DocumentSymbolsSnapshot? = null

    override val isConnected: Boolean
        get() = !closed

    override fun didChange(@Suppress("UNUSED_PARAMETER") change: TextChange) {
        diagnosticsSnapshot = null
        documentSymbolsSnapshot = null
    }

    override fun didSave(@Suppress("UNUSED_PARAMETER") fullText: String?) {
        diagnosticsSnapshot = null
        documentSymbolsSnapshot = null
    }

    override suspend fun requestCompletion(
        position: Position,
        @Suppress("UNUSED_PARAMETER") triggerChar: Char?
    ): CompletionFetchResult {
        if (closed) {
            return CompletionFetchResult.TransientFailure(reason = "session_closed")
        }

        val source = currentSource()
        val offset = MakeLanguageSupport.positionToOffset(source, position)
        val prefix = MakeLanguageSupport.extractWordPrefix(source, offset)
        val items = MakeLanguageSupport.buildCompletionItems(
            source = source,
            prefix = prefix,
            caseSensitive = caseSensitiveProvider(),
            completionSource = CompletionSource.LSP
        )
        return CompletionFetchResult.Success(items)
    }

    override suspend fun requestSemanticTokens(): List<SemanticToken> {
        if (closed) return emptyList()
        return MakeLanguageSupport.buildSemanticTokens(currentSource())
    }

    override fun currentDiagnostics(): List<Diagnostic> {
        if (closed) return emptyList()
        val source = currentSource()
        val cached = diagnosticsSnapshot
        if (cached != null && cached.source == source) {
            return cached.diagnostics
        }
        val diagnostics = MakeLanguageSupport.buildDiagnostics(
            file = file,
            documentUri = documentUri,
            source = source
        )
        diagnosticsSnapshot = DiagnosticsSnapshot(source = source, diagnostics = diagnostics)
        return diagnostics
    }

    override fun documentSymbols(): List<DocumentSymbolItem> {
        if (closed) return emptyList()
        val source = currentSource()
        val cached = documentSymbolsSnapshot
        if (cached != null && cached.source == source) {
            return cached.symbols
        }
        val symbols = MakeLanguageSupport.buildDocumentSymbols(
            file = file,
            documentUri = documentUri,
            source = source
        )
        documentSymbolsSnapshot = DocumentSymbolsSnapshot(source = source, symbols = symbols)
        return symbols
    }

    override fun gotoDefinition(position: Position): List<LocationItem> {
        if (closed) return emptyList()
        return MakeLanguageSupport.buildDefinitionLocations(
            file = file,
            documentUri = documentUri,
            source = currentSource(),
            position = position
        )
    }

    override fun findReferences(position: Position): List<LocationItem> {
        if (closed) return emptyList()
        return MakeLanguageSupport.buildReferenceLocations(
            file = file,
            documentUri = documentUri,
            source = currentSource(),
            position = position
        )
    }

    override fun hover(position: Position): String? {
        if (closed) return null
        return MakeLanguageSupport.buildHoverMarkdown(
            file = file,
            documentUri = documentUri,
            source = currentSource(),
            position = position
        )
    }

    override fun close() {
        closed = true
        diagnosticsSnapshot = null
        documentSymbolsSnapshot = null
    }

    private fun currentSource(): String = runCatching(textProvider).getOrDefault("")

    private data class DiagnosticsSnapshot(
        val source: String,
        val diagnostics: List<Diagnostic>
    )

    private data class DocumentSymbolsSnapshot(
        val source: String,
        val symbols: List<DocumentSymbolItem>
    )
}

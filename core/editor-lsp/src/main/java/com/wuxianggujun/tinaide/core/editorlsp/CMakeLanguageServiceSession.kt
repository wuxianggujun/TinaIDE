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

class CMakeLanguageServiceSession(
    val file: File,
    val documentUri: String,
    private val textProvider: () -> String
) : BuiltinLanguageServiceSession {
    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var semanticSnapshot: SemanticSnapshot? = null

    @Volatile
    private var diagnosticsSnapshot: DiagnosticsSnapshot? = null

    @Volatile
    private var documentSymbolsSnapshot: DocumentSymbolsSnapshot? = null

    override val isConnected: Boolean
        get() = !closed

    override fun didChange(@Suppress("UNUSED_PARAMETER") change: TextChange) {
        semanticSnapshot = null
        diagnosticsSnapshot = null
        documentSymbolsSnapshot = null
    }

    override fun didSave(@Suppress("UNUSED_PARAMETER") fullText: String?) {
        semanticSnapshot = null
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
        val offset = CMakeLanguageSupport.positionToOffset(source, position)
        val prefix = CMakeLanguageSupport.extractWordPrefix(source, offset)
        val items = CMakeLanguageSupport.buildCompletionItems(
            source = source,
            prefix = prefix,
            completionSource = CompletionSource.LSP
        )
        return CompletionFetchResult.Success(items)
    }

    override suspend fun requestSemanticTokens(): List<SemanticToken> {
        if (closed) return emptyList()
        val source = currentSource()
        val cached = semanticSnapshot
        if (cached != null && cached.source == source) {
            return cached.tokens
        }
        val tokens = CMakeLanguageSupport.buildSemanticTokens(source)
        semanticSnapshot = SemanticSnapshot(source = source, tokens = tokens)
        return tokens
    }

    override fun currentDiagnostics(): List<Diagnostic> {
        if (closed) return emptyList()
        val source = currentSource()
        val cached = diagnosticsSnapshot
        if (cached != null && cached.source == source) {
            return cached.diagnostics
        }
        val diagnostics = CMakeLanguageSupport.buildDiagnostics(
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
        val symbols = CMakeLanguageSupport.buildDocumentSymbols(
            file = file,
            documentUri = documentUri,
            source = source
        )
        documentSymbolsSnapshot = DocumentSymbolsSnapshot(source = source, symbols = symbols)
        return symbols
    }

    override fun gotoDefinition(position: Position): List<LocationItem> {
        if (closed) return emptyList()
        val source = currentSource()
        return CMakeLanguageSupport.buildDefinitionLocations(
            file = file,
            documentUri = documentUri,
            source = source,
            position = position
        )
    }

    override fun findReferences(position: Position): List<LocationItem> {
        if (closed) return emptyList()
        val source = currentSource()
        return CMakeLanguageSupport.buildReferenceLocations(
            file = file,
            documentUri = documentUri,
            source = source,
            position = position
        )
    }

    override fun hover(position: Position): String? {
        if (closed) return null
        val source = currentSource()
        return CMakeLanguageSupport.buildHoverMarkdown(
            file = file,
            documentUri = documentUri,
            source = source,
            position = position
        )
    }

    override fun close() {
        closed = true
        semanticSnapshot = null
        diagnosticsSnapshot = null
        documentSymbolsSnapshot = null
    }

    private fun currentSource(): String = runCatching(textProvider).getOrDefault("")

    private data class SemanticSnapshot(
        val source: String,
        val tokens: List<SemanticToken>
    )

    private data class DiagnosticsSnapshot(
        val source: String,
        val diagnostics: List<Diagnostic>
    )

    private data class DocumentSymbolsSnapshot(
        val source: String,
        val symbols: List<DocumentSymbolItem>
    )
}

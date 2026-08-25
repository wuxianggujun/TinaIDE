package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange

/**
 * 内建语言服务统一抽象，避免不同语言在 LSP 管理器里继续复制分支逻辑。
 */
interface BuiltinLanguageServiceSession {
    val isConnected: Boolean

    fun didChange(change: TextChange)

    fun didSave(fullText: String?)

    suspend fun requestCompletion(
        position: Position,
        triggerChar: Char?
    ): CompletionFetchResult

    suspend fun requestSemanticTokens(): List<SemanticToken>

    fun currentDiagnostics(): List<Diagnostic>

    fun documentSymbols(): List<DocumentSymbolItem>

    fun gotoDefinition(position: Position): List<LocationItem>

    fun findReferences(position: Position): List<LocationItem>

    fun hover(position: Position): String?

    suspend fun requestSignatureHelp(position: Position): SignatureHelpResult? = null

    fun close()
}

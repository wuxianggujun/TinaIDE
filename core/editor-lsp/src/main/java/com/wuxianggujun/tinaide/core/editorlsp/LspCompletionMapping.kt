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
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode
import com.wuxianggujun.tinaide.core.lsp.WorkspaceSymbolItem
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
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

/**
 * LSP completion / hover payload mapping helpers.
 */

internal fun mapCompletionKind(kind: org.eclipse.lsp4j.CompletionItemKind?): CompletionItemKind = when (kind) {
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

internal fun extractDocumentation(doc: Either<String, MarkupContent>?): String? = when {
    doc == null -> null
    doc.isLeft -> doc.left
    doc.isRight -> doc.right?.value
    else -> null
}

internal fun normalizeInsertText(item: org.eclipse.lsp4j.CompletionItem): String? {
    val rawText = extractRawInsertText(item)
    return normalizeCompletionPayloadText(rawText, item.insertTextFormat)
}

internal fun extractRawInsertText(item: org.eclipse.lsp4j.CompletionItem): String = item.insertText
    ?: item.textEdit?.let { textEdit ->
        when {
            textEdit.isLeft -> textEdit.left?.newText
            textEdit.isRight -> textEdit.right?.newText
            else -> null
        }
    }
    ?: item.label

internal fun extractSnippetText(item: org.eclipse.lsp4j.CompletionItem): String? {
    if (item.insertTextFormat != org.eclipse.lsp4j.InsertTextFormat.Snippet) return null
    val raw = extractRawInsertText(item)
    if (!raw.contains('$')) return null
    return raw
}

internal fun normalizeMainCompletionTextEdit(item: org.eclipse.lsp4j.CompletionItem): CompletionTextEdit? {
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

internal fun normalizeAdditionalCompletionTextEdits(
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

internal fun normalizeCompletionTextEdit(
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

internal fun chooseCompletionRange(edit: InsertReplaceEdit): org.eclipse.lsp4j.Range? {
    // TinaEditor 当前只有“替换”模式，默认优先使用 replace range，避免中间补全残留后缀。
    return edit.replace ?: edit.insert
}

internal fun normalizeCompletionPayloadText(
    text: String,
    insertTextFormat: org.eclipse.lsp4j.InsertTextFormat?
): String {
    if (insertTextFormat != org.eclipse.lsp4j.InsertTextFormat.Snippet) {
        return text
    }
    // snippet 文本直接返回原文，由编辑器 snippet 引擎（SnippetParser + SnippetSession）处理
    return text
}


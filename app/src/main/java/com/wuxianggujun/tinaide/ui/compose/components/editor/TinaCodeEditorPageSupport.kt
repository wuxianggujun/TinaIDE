package com.wuxianggujun.tinaide.ui.compose.components.editor

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.debug.BreakpointStore
import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItem
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItemKind
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.CompletionTextEdit
import com.wuxianggujun.tinaide.core.editorlsp.DefaultCompletionProvider
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken as LspSemanticToken
import com.wuxianggujun.tinaide.core.editorview.DiagnosticSeverity
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionFetchResult
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionItem
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionKind
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionTextEdit
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorDiagnostic
import com.wuxianggujun.tinaide.core.editorview.EditorRenderPerformanceSnapshot
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.GutterDecoration
import com.wuxianggujun.tinaide.core.editorview.SemanticToken as EditorSemanticToken
import com.wuxianggujun.tinaide.core.editorview.SemanticTokenModifier
import com.wuxianggujun.tinaide.core.editorview.SemanticTokenType
import com.wuxianggujun.tinaide.core.editorview.TinaEditor
import com.wuxianggujun.tinaide.core.font.AppFontManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextChangeListener
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.search.CodeSearchEngine
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.core.editorlsp.CMakeLanguageSupport
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.MakeLanguageSupport
import com.wuxianggujun.tinaide.core.editorlsp.SemanticTokensRequestResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorCallback
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorDocumentBinding
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorRuntime
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorStateBinding
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.SelectionSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.TextEditOperation
import com.wuxianggujun.tinaide.ui.compose.state.editor.TinaTextContentProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * TinaCodeEditorPage private helpers: bindings, completions, diagnostics/semantic adapters.
 */

internal data class ActiveTabLspAttachmentState(
    val isActive: Boolean,
    val loading: Boolean,
    val loadError: String?
)

internal class RuntimeEditorStateBinding(
    private val onAttach: () -> Unit,
    private val onDetach: () -> Unit
) : CodeEditorStateBinding {
    override fun attach() = onAttach()

    override fun detach() = onDetach()
}

internal class TextBufferSessionBinding(
    private val tabId: String,
    private val state: EditorContainerState,
    private val buffer: RopeTextBuffer,
    private val editorState: EditorState,
    private val textSnapshot: VersionedBufferTextSnapshot,
    private val onBufferEdited: (
        canUndo: Boolean,
        canRedo: Boolean,
        documentVersion: Long,
        change: TextChange
    ) -> Unit
) : CodeEditorDocumentBinding,
    TextChangeListener {

    private val suppressNotifyDepth = AtomicInteger()

    override fun attach() {
        buffer.addChangeListener(this)
        state.attachTabEditorBinding(tabId, this)
    }

    override fun detach() {
        buffer.removeChangeListener(this)
        state.detachTabEditorBinding(tabId, this)
    }

    /**
     * 在 block 执行期间抑制用户编辑回调（dirty、undo/redo 与 LSP didChange），
     * 用于初次加载 / 重载这类非用户触发的 buffer 写入。
     */
    override suspend fun <R> withSuppressed(block: suspend () -> R): R {
        suppressNotifyDepth.incrementAndGet()
        try {
            return block()
        } finally {
            suppressNotifyDepth.decrementAndGet()
        }
    }

    override fun onTextChanged(change: TextChange) {
        if (suppressNotifyDepth.get() > 0) return
        val canUndo = buffer.canUndo()
        val canRedo = buffer.canRedo()
        state.notifyTabEditorContentChanged(
            tabId = tabId,
            canUndo = canUndo,
            canRedo = canRedo,
            changeCausedByUndoManager = change.fromUndoRedo
        )
        onBufferEdited(canUndo, canRedo, buffer.version, change)
    }

    override fun readText(): String = textSnapshot.readText()

    override fun readSnapshot(): DocumentSession.EditorContentSnapshot {
        val snapshot = textSnapshot.readSnapshot()
        return DocumentSession.EditorContentSnapshot(
            text = snapshot.text,
            documentVersion = snapshot.version
        )
    }

    override fun setText(text: CharSequence) {
        suppressNotifyDepth.incrementAndGet()
        try {
            buffer.replaceAll(text.toString())
        } finally {
            suppressNotifyDepth.decrementAndGet()
        }
    }

    override fun textLength(): Int = buffer.length

    override fun canUndo(): Boolean = buffer.canUndo()

    override fun canRedo(): Boolean = buffer.canRedo()

    override fun undo() {
        editorState.undo()
    }

    override fun redo() {
        editorState.redo()
    }

    override fun currentDocumentVersion(): Long = buffer.version

    override fun currentViewState(): EditorViewState = EditorViewState(
        cursorLine = editorState.cursorPosition.line,
        cursorColumn = editorState.cursorPosition.column,
        scrollX = editorState.scrollOffsetXPx.roundToInt(),
        scrollY = editorState.scrollOffsetPx.roundToInt()
    )
}

internal class VersionedBufferTextSnapshot(
    private val buffer: RopeTextBuffer
) {
    private companion object {
        private const val MAX_SNAPSHOT_READ_ATTEMPTS = 8
    }

    data class Snapshot(
        val text: String,
        val version: Long
    )

    private val lock = Any()
    private var cachedVersion = Long.MIN_VALUE
    private var cachedText = ""

    fun readText(): String = readSnapshot().text

    fun readSnapshot(): Snapshot {
        var latestSnapshot = ""
        repeat(MAX_SNAPSHOT_READ_ATTEMPTS) {
            val versionBefore = buffer.version
            synchronized(lock) {
                if (cachedVersion == versionBefore) {
                    return Snapshot(cachedText, cachedVersion)
                }
            }

            latestSnapshot = buffer.toString()
            val versionAfter = buffer.version
            if (versionBefore != versionAfter) {
                return@repeat
            }

            synchronized(lock) {
                if (cachedVersion == versionAfter) {
                    return Snapshot(cachedText, cachedVersion)
                }
                cachedVersion = versionAfter
                cachedText = latestSnapshot
                return Snapshot(latestSnapshot, versionAfter)
            }
        }
        return Snapshot(latestSnapshot, DocumentSession.UNSTABLE_DOCUMENT_VERSION)
    }
}

internal fun applyBreakpoints(editorState: EditorState, lines: Set<Int>) {
    val linesWithOldBreakpoint = editorState.gutterDecorations
        .filterValues { it.breakpoint }
        .keys
        .toList()

    linesWithOldBreakpoint.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: return@forEach
        if (line in lines) return@forEach
        val updated = existing.copy(breakpoint = false)
        if (updated.bookmark || updated.hasDiagnostic || updated.foldable) {
            editorState.gutterDecorations[line] = updated
        } else {
            editorState.gutterDecorations.remove(line)
        }
    }

    lines.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: GutterDecoration()
        editorState.gutterDecorations[line] = existing.copy(breakpoint = true)
    }
}

internal fun applyBookmarks(editorState: EditorState, lines: Set<Int>) {
    val linesWithOldBookmark = editorState.gutterDecorations
        .filterValues { it.bookmark }
        .keys
        .toList()

    linesWithOldBookmark.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: return@forEach
        if (line in lines) return@forEach
        val updated = existing.copy(bookmark = false)
        if (updated.breakpoint || updated.hasDiagnostic || updated.foldable) {
            editorState.gutterDecorations[line] = updated
        } else {
            editorState.gutterDecorations.remove(line)
        }
    }

    lines.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: GutterDecoration()
        editorState.gutterDecorations[line] = existing.copy(bookmark = true)
    }
}

internal fun resolveMarkerLine(buffer: RopeTextBuffer, requestedLine: Int): Int? = com.wuxianggujun.tinaide.ui.compose.state.editor.resolveMarkerLine(
    requestedLine = requestedLine,
    lineCount = buffer.lineCount,
    lineTextAt = { line -> buffer.getLine(line) }
)

internal fun applyDiagnostics(
    editorState: EditorState,
    diagnostics: List<Diagnostic>
) {
    val lineCount = editorState.textBuffer.lineCount
    if (lineCount <= 0) {
        editorState.diagnostics = emptyList()
        editorState.diagnosticsByLine = emptyMap()
        val linesWithOldDiagnostic = editorState.gutterDecorations
            .filterValues { it.hasDiagnostic }
            .keys
            .toList()
        linesWithOldDiagnostic.forEach { line ->
            val existing = editorState.gutterDecorations[line] ?: return@forEach
            val updated = existing.copy(hasDiagnostic = false)
            if (updated.breakpoint || updated.bookmark || updated.foldable) {
                editorState.gutterDecorations[line] = updated
            } else {
                editorState.gutterDecorations.remove(line)
            }
        }
        return
    }
    val lastLineIndex = lineCount - 1
    val mappedDiagnostics = diagnostics.asSequence().flatMap { diagnostic ->
        val startLine = diagnostic.line.coerceAtLeast(0)
        if (startLine > lastLineIndex) return@flatMap emptySequence()
        val clampedStartLine = startLine.coerceAtMost(lastLineIndex)
        val clampedEndLine = diagnostic.endLine
            .coerceAtLeast(clampedStartLine)
            .coerceAtMost(lastLineIndex)
        val startColumn = diagnostic.column.coerceAtLeast(0)
        val endColumn = diagnostic.endColumn.coerceAtLeast(0)
        val severity = diagnostic.severity.toEditorSeverity()
        (clampedStartLine..clampedEndLine).asSequence().mapNotNull { line ->
            val lineLength = editorState.textBuffer.getLine(line).length
            val rawSegmentStart = if (line == clampedStartLine) startColumn else 0
            val rawSegmentEnd = when {
                line == clampedStartLine && line == clampedEndLine -> endColumn
                line == clampedEndLine -> endColumn
                else -> lineLength
            }
            val segmentStart = rawSegmentStart.coerceIn(0, lineLength)
            val minEndExclusive = if (lineLength > segmentStart) segmentStart + 1 else segmentStart
            val segmentEnd = rawSegmentEnd.coerceIn(minEndExclusive, lineLength)
            if (segmentEnd <= segmentStart) {
                null
            } else {
                EditorDiagnostic(
                    line = line,
                    startColumn = segmentStart,
                    endColumn = segmentEnd,
                    message = diagnostic.message,
                    severity = severity
                )
            }
        }
    }.distinctBy { mapped ->
        buildString {
            append(mapped.line)
            append(':')
            append(mapped.startColumn)
            append(':')
            append(mapped.endColumn)
            append(':')
            append(mapped.severity.name)
            append(':')
            append(mapped.message)
        }
    }.toList()
    editorState.diagnostics = mappedDiagnostics
    editorState.diagnosticsByLine = mappedDiagnostics.groupBy { it.line }

    val diagnosticLines = mappedDiagnostics.asSequence()
        .map { it.line }
        .toSet()

    val linesWithOldDiagnostic = editorState.gutterDecorations
        .filterValues { it.hasDiagnostic }
        .keys
        .toList()

    linesWithOldDiagnostic.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: return@forEach
        if (line in diagnosticLines) return@forEach
        val updated = existing.copy(hasDiagnostic = false)
        if (updated.breakpoint || updated.bookmark || updated.foldable) {
            editorState.gutterDecorations[line] = updated
        } else {
            editorState.gutterDecorations.remove(line)
        }
    }

    diagnosticLines.forEach { line ->
        val existing = editorState.gutterDecorations[line] ?: GutterDecoration()
        editorState.gutterDecorations[line] = existing.copy(hasDiagnostic = true)
    }
}

internal data class FoldingDriverKey(
    val enabled: Boolean,
    val preferLsp: Boolean
)

internal data class FoldingComputeRequest(
    val documentVersion: Long,
    val preferLsp: Boolean
)

internal data class SemanticTokenRequestKey(
    val firstLine: Int,
    val lastLine: Int,
    val documentVersion: Long,
    val semanticTokensEnabled: Boolean,
    val lspReady: Boolean,
)

internal fun resolveSelectedRangeOrCursor(
    buffer: RopeTextBuffer,
    editorState: EditorState
): Pair<Position, Position> {
    val selection = editorState.selectionRange
    if (selection == null || selection.isEmpty) {
        val cursor = editorState.cursorPosition
        return cursor to cursor
    }
    return buffer.offsetToPosition(selection.start) to buffer.offsetToPosition(selection.end)
}

internal fun resolveIdentifierAroundCursor(
    buffer: RopeTextBuffer,
    line: Int,
    column: Int
): String {
    if (line !in 0 until buffer.lineCount) return ""
    val lineText = buffer.getLine(line)
    if (lineText.isEmpty()) return ""

    var anchor = column.coerceIn(0, lineText.length)
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

internal fun Char.isEditorIdentifierChar(): Boolean = isLetterOrDigit() || this == '_' || this == '~'

internal fun applySemanticTokens(
    editorState: EditorState,
    tokens: List<LspSemanticToken>,
    requestedVisibleLines: IntRange?
) {
    val mapped = tokens.mapNotNull { token -> token.toEditorSemanticTokenOrNull() }
    if (requestedVisibleLines == null) {
        editorState.replaceSemanticTokens(mapped)
        return
    }

    editorState.replaceSemanticTokensInLines(requestedVisibleLines, mapped)
}

internal fun LspSemanticToken.toEditorSemanticTokenOrNull(): EditorSemanticToken? {
    if (line < 0 || startColumn < 0 || length <= 0) return null
    val mappedType = tokenType.toEditorSemanticTokenTypeOrNull() ?: return null
    return EditorSemanticToken(
        line = line,
        startColumn = startColumn,
        length = length,
        tokenType = mappedType,
        tokenModifiers = tokenModifiers.mapNotNull { modifier ->
            modifier.toEditorSemanticTokenModifierOrNull()
        }.toSet()
    )
}

internal fun String.toEditorSemanticTokenTypeOrNull(): SemanticTokenType? = when (trim().lowercase().replace('-', '_')) {
    "namespace" -> SemanticTokenType.NAMESPACE
    "type" -> SemanticTokenType.TYPE
    "class" -> SemanticTokenType.CLASS
    "enum" -> SemanticTokenType.ENUM
    "interface" -> SemanticTokenType.INTERFACE
    "struct" -> SemanticTokenType.STRUCT
    "typeparameter", "type_parameter" -> SemanticTokenType.TYPE_PARAMETER
    "parameter" -> SemanticTokenType.PARAMETER
    "variable" -> SemanticTokenType.VARIABLE
    "property" -> SemanticTokenType.PROPERTY
    "enummember", "enum_member" -> SemanticTokenType.ENUM_MEMBER
    "event" -> SemanticTokenType.EVENT
    "function" -> SemanticTokenType.FUNCTION
    "method" -> SemanticTokenType.METHOD
    "macro" -> SemanticTokenType.MACRO
    "keyword" -> SemanticTokenType.KEYWORD
    "modifier" -> SemanticTokenType.MODIFIER
    "comment" -> SemanticTokenType.COMMENT
    "string" -> SemanticTokenType.STRING
    "number" -> SemanticTokenType.NUMBER
    "regexp", "regex" -> SemanticTokenType.REGEXP
    "operator" -> SemanticTokenType.OPERATOR
    else -> null
}

internal suspend fun ensureTreeSitterPrepared(
    runtime: CodeEditorRuntime,
    editorState: EditorState,
    syntaxHighlighter: TreeSitterHighlighter?,
    textSnapshot: VersionedBufferTextSnapshot
) {
    if (runtime.isTreeSitterSnapshotReady) return
    val highlighter = syntaxHighlighter ?: return
    try {
        refreshTreeSitterAfterBufferLoad(
            editorState = editorState,
            syntaxHighlighter = highlighter,
            textSnapshot = textSnapshot
        )
        runtime.isTreeSitterSnapshotReady = true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Timber.tag("TinaCodeEditor").w(error, "Tree-sitter initialization failed; continuing without syntax highlighting")
        runtime.isTreeSitterSnapshotReady = false
    }
}

private suspend fun refreshTreeSitterAfterBufferLoad(
    editorState: EditorState,
    syntaxHighlighter: TreeSitterHighlighter,
    textSnapshot: VersionedBufferTextSnapshot
) {
    val text = textSnapshot.readText()
    // 阻塞直到首个渲染快照就位：首帧不再闪默认色。
    withContext(Dispatchers.IO) { syntaxHighlighter.openDocumentBlocking(text) }
    editorState.notifyHighlightChanged()
}

internal fun restoreEditorViewState(editorState: EditorState, viewState: EditorViewState?) {
    if (viewState == null) return
    editorState.gotoLine(viewState.cursorLine, viewState.cursorColumn)
    editorState.scrollOffsetXPx = viewState.scrollX.coerceAtLeast(0).toFloat()
    editorState.scrollOffsetPx = viewState.scrollY.coerceAtLeast(0).toFloat()
}

internal fun String.toEditorSemanticTokenModifierOrNull(): SemanticTokenModifier? = when (trim().lowercase().replace('-', '_')) {
    "declaration" -> SemanticTokenModifier.DECLARATION
    "definition" -> SemanticTokenModifier.DEFINITION
    "readonly", "read_only" -> SemanticTokenModifier.READONLY
    "static" -> SemanticTokenModifier.STATIC
    "deprecated" -> SemanticTokenModifier.DEPRECATED
    "abstract" -> SemanticTokenModifier.ABSTRACT
    "async" -> SemanticTokenModifier.ASYNC
    "modification" -> SemanticTokenModifier.MODIFICATION
    "documentation" -> SemanticTokenModifier.DOCUMENTATION
    "defaultlibrary", "default_library" -> SemanticTokenModifier.DEFAULT_LIBRARY
    else -> null
}

internal fun CompletionItemKind.toEditorCompletionKind(): EditorCompletionKind = when (this) {
    CompletionItemKind.TEXT -> EditorCompletionKind.TEXT
    CompletionItemKind.METHOD -> EditorCompletionKind.METHOD
    CompletionItemKind.FUNCTION -> EditorCompletionKind.FUNCTION
    CompletionItemKind.CONSTRUCTOR -> EditorCompletionKind.CONSTRUCTOR
    CompletionItemKind.FIELD -> EditorCompletionKind.FIELD
    CompletionItemKind.VARIABLE -> EditorCompletionKind.VARIABLE
    CompletionItemKind.CLASS -> EditorCompletionKind.CLASS
    CompletionItemKind.INTERFACE -> EditorCompletionKind.INTERFACE
    CompletionItemKind.MODULE -> EditorCompletionKind.MODULE
    CompletionItemKind.PROPERTY -> EditorCompletionKind.PROPERTY
    CompletionItemKind.UNIT -> EditorCompletionKind.UNIT
    CompletionItemKind.VALUE -> EditorCompletionKind.VALUE
    CompletionItemKind.ENUM -> EditorCompletionKind.ENUM
    CompletionItemKind.KEYWORD -> EditorCompletionKind.KEYWORD
    CompletionItemKind.SNIPPET -> EditorCompletionKind.SNIPPET
    CompletionItemKind.COLOR -> EditorCompletionKind.COLOR
    CompletionItemKind.FILE -> EditorCompletionKind.FILE
    CompletionItemKind.REFERENCE -> EditorCompletionKind.REFERENCE
    CompletionItemKind.FOLDER -> EditorCompletionKind.FOLDER
    CompletionItemKind.ENUM_MEMBER -> EditorCompletionKind.ENUM_MEMBER
    CompletionItemKind.CONSTANT -> EditorCompletionKind.CONSTANT
    CompletionItemKind.STRUCT -> EditorCompletionKind.STRUCT
    CompletionItemKind.EVENT -> EditorCompletionKind.EVENT
    CompletionItemKind.OPERATOR -> EditorCompletionKind.OPERATOR
    CompletionItemKind.TYPE_PARAMETER -> EditorCompletionKind.TYPE_PARAMETER
}

internal fun CompletionTextEdit.toEditorCompletionTextEdit(): EditorCompletionTextEdit = EditorCompletionTextEdit(
    startLine = startLine,
    startColumn = startColumn,
    endLine = endLine,
    endColumn = endColumn,
    newText = newText
)

internal fun Diagnostic.Severity.toEditorSeverity(): DiagnosticSeverity = when (this) {
    Diagnostic.Severity.ERROR -> DiagnosticSeverity.ERROR
    Diagnostic.Severity.WARNING -> DiagnosticSeverity.WARNING
    Diagnostic.Severity.INFO -> DiagnosticSeverity.INFO
    Diagnostic.Severity.HINT -> DiagnosticSeverity.HINT
}

internal fun replaceWholeText(
    buffer: RopeTextBuffer,
    editorState: EditorState,
    textSnapshot: VersionedBufferTextSnapshot,
    newText: String
): Boolean {
    val original = textSnapshot.readText()
    if (original == newText) return false

    val cursorBefore = editorState.cursorPosition
    return buffer.editTransaction(
        cursorBefore = editorState.cursorOffset,
        cursorAfter = { editorState.cursorOffset }
    ) {
        buffer.replace(0, buffer.length, newText)
        restoreCursor(editorState, buffer, cursorBefore.line, cursorBefore.column)
        true
    }
}

internal fun applyTextEdits(
    buffer: RopeTextBuffer,
    editorState: EditorState,
    edits: List<TextEditOperation>
): Boolean {
    if (edits.isEmpty()) return false

    val cursorBefore = editorState.cursorPosition
    var changed = false
    val resolvedEdits = resolveTextEdits(buffer, edits) ?: return false

    return buffer.editTransaction(
        cursorBefore = editorState.cursorOffset,
        cursorAfter = { editorState.cursorOffset }
    ) {
        resolvedEdits.forEach { edit ->
            val oldText = buffer.substring(edit.startOffset, edit.endOffset)
            if (oldText == edit.newText) return@forEach

            buffer.replace(edit.startOffset, edit.endOffset, edit.newText)
            changed = true
        }

        if (changed) {
            restoreCursor(editorState, buffer, cursorBefore.line, cursorBefore.column)
        }
        changed
    }
}

internal data class ResolvedTextEdit(
    val startOffset: Int,
    val endOffset: Int,
    val newText: String
)

internal fun resolveTextEdits(
    buffer: RopeTextBuffer,
    edits: List<TextEditOperation>
): List<ResolvedTextEdit>? {
    if (edits.isEmpty()) return null
    val sortedEdits = edits.sortedWith(
        compareByDescending<TextEditOperation> { it.startLine }
            .thenByDescending { it.startColumn }
            .thenByDescending { it.endLine }
            .thenByDescending { it.endColumn }
    )
    var nextStartOffset = buffer.length
    return buildList(sortedEdits.size) {
        sortedEdits.forEach { edit ->
            val startOffset = buffer.strictOffset(edit.startLine, edit.startColumn) ?: return null
            val endOffset = buffer.strictOffset(edit.endLine, edit.endColumn) ?: return null
            if (endOffset < startOffset || endOffset > nextStartOffset) return null
            add(ResolvedTextEdit(startOffset, endOffset, edit.newText))
            nextStartOffset = startOffset
        }
    }
}

internal fun restoreCursor(
    editorState: EditorState,
    buffer: RopeTextBuffer,
    line: Int,
    column: Int
) {
    val safeLine = line.coerceIn(0, (buffer.lineCount - 1).coerceAtLeast(0))
    val safeColumn = column.coerceIn(0, buffer.getLine(safeLine).length)
    editorState.moveCursorTo(buffer.positionToOffset(safeLine, safeColumn))
}

internal fun RopeTextBuffer.strictOffset(line: Int, column: Int): Int? {
    if (line !in 0 until lineCount) return null
    val lineText = getLine(line)
    val logicalLineLength = if (lineText.endsWith('\r')) lineText.length - 1 else lineText.length
    if (column !in 0..logicalLineLength) return null
    return positionToOffset(line, column)
}

internal fun buildLocalCompletions(
    state: EditorContainerState,
    buffer: RopeTextBuffer,
    file: File,
    position: Position,
    triggerChar: Char?,
    localCompletionCache: LocalCompletionCache,
    textSnapshot: VersionedBufferTextSnapshot
): List<CompletionItem> {
    val startNs = System.nanoTime()
    val offset = buffer.positionToOffset(position.line, position.column)
    val prefix = extractWordPrefix(buffer, offset)
    val caseSensitive = Prefs.completionCaseSensitive
    if (prefix.isEmpty() && triggerChar != '.' && triggerChar != '_' && triggerChar?.isLetterOrDigit() != true) {
        return emptyList()
    }

    val languageItems = buildLanguageCompletionItems(file, buffer, textSnapshot, prefix, caseSensitive)

    val keywordCandidates = if (languageItems.isNotEmpty()) emptySet() else languageKeywordCandidates(file)
    val identifiers = localCompletionCache.identifiersNear(buffer, offset)

    val genericCandidates = linkedSetOf<String>()
    genericCandidates.addAll(keywordCandidates)
    genericCandidates.addAll(identifiers)

    val filtered = if (prefix.isBlank()) {
        genericCandidates.take(120)
    } else {
        genericCandidates.asSequence()
            .filter {
                it.startsWith(prefix, ignoreCase = !caseSensitive) &&
                    !it.equals(prefix, ignoreCase = !caseSensitive)
            }
            .take(120)
            .toList()
    }

    val genericItems = filtered.map { label ->
        CompletionItem(
            label = label,
            kind = CompletionItemKind.TEXT,
            detail = "Local",
            insertText = label,
            source = CompletionSource.LOCAL
        )
    }

    val snippetItems = state.requestSnippetCompletion(file, prefix)
    val result = (snippetItems + languageItems + genericItems)
        .distinctBy { it.label.lowercase() }
        .take(160)
    val durationMs = (System.nanoTime() - startNs) / 1_000_000L
    if (durationMs > SLOW_LOCAL_COMPLETION_THRESHOLD_MS) {
        Timber.tag("EditorPerf").w(
            "Slow local completion: %dms, file=%s, prefixLen=%d, candidates=%d",
            durationMs,
            file.name,
            prefix.length,
            result.size
        )
    }
    return result
}

internal fun buildLanguageCompletionItems(
    file: File,
    buffer: RopeTextBuffer,
    textSnapshot: VersionedBufferTextSnapshot,
    prefix: String,
    caseSensitive: Boolean
): List<CompletionItem> {
    val name = file.name.lowercase()
    val ext = file.extension.lowercase()

    return when {
        name == "cmakelists.txt" || ext == "cmake" -> buildCMakeCompletionItems(buffer, textSnapshot, prefix)
        MakeLanguageSupport.isMakefile(file) -> buildMakefileCompletionItems(buffer, textSnapshot, prefix, caseSensitive)
        ext == "c" || ext in CxxFileSupport.cxxSourceExtensions || ext in CxxFileSupport.headerExtensions ->
            buildCxxCompletionItems(prefix, caseSensitive)
        else -> emptyList()
    }
}

internal fun buildCxxCompletionItems(prefix: String, caseSensitive: Boolean): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()

    CXX_LANGUAGE_KEYWORDS.asSequence()
        .filter { it.matchesPrefix(prefix, caseSensitive) }
        .mapTo(items) { keyword ->
            CompletionItem(
                label = keyword,
                kind = CompletionItemKind.KEYWORD,
                detail = "C/C++",
                insertText = keyword,
                source = CompletionSource.LOCAL
            )
        }

    CXX_TYPE_KEYWORDS.asSequence()
        .filter { it.matchesPrefix(prefix, caseSensitive) }
        .mapTo(items) { typeName ->
            CompletionItem(
                label = typeName,
                kind = CompletionItemKind.KEYWORD,
                detail = "C/C++ type",
                insertText = typeName,
                source = CompletionSource.LOCAL
            )
        }

    CXX_PREPROCESSOR_ITEMS.asSequence()
        .filter { it.label.matchesPrefix(prefix, caseSensitive) }
        .mapTo(items) { directive ->
            CompletionItem(
                label = directive.label,
                kind = CompletionItemKind.KEYWORD,
                detail = "Preprocessor",
                insertText = directive.insertText,
                source = CompletionSource.LOCAL
            )
        }

    return items
}

internal fun buildCMakeCompletionItems(
    buffer: RopeTextBuffer,
    textSnapshot: VersionedBufferTextSnapshot,
    prefix: String
): List<CompletionItem> = CMakeLanguageSupport.buildCompletionItems(
    source = if (buffer.length > MAX_PARSE_SIZE) null else textSnapshot.readText(),
    prefix = prefix,
    completionSource = CompletionSource.LOCAL
)

internal fun buildMakefileCompletionItems(
    buffer: RopeTextBuffer,
    textSnapshot: VersionedBufferTextSnapshot,
    prefix: String,
    caseSensitive: Boolean
): List<CompletionItem> = MakeLanguageSupport.buildCompletionItems(
    source = if (buffer.length > MAX_PARSE_SIZE) null else textSnapshot.readText(),
    prefix = prefix,
    caseSensitive = caseSensitive,
    completionSource = CompletionSource.LOCAL
)

internal fun String.matchesPrefix(prefix: String, caseSensitive: Boolean): Boolean {
    if (prefix.isBlank()) return true
    return startsWith(prefix, ignoreCase = !caseSensitive)
}

internal fun extractWordPrefix(buffer: RopeTextBuffer, offset: Int): String {
    val safeOffset = offset.coerceIn(0, buffer.length)
    val start = max(0, safeOffset - 128)
    val window = buffer.substring(start, safeOffset)
    var index = window.length - 1
    while (index >= 0) {
        if (!isWordChar(window[index])) break
        index--
    }
    return window.substring(index + 1)
}

internal fun extractIdentifierCandidates(buffer: RopeTextBuffer, offset: Int): Set<String> {
    val length = buffer.length
    if (length <= 0) return emptySet()

    val scanWindow = adaptiveCompletionScanWindow(length)
    val halfWindow = scanWindow / 2
    var windowStart = max(0, offset - halfWindow)
    var windowEnd = min(length, windowStart + scanWindow)
    if (windowEnd - windowStart < scanWindow) {
        windowStart = max(0, windowEnd - scanWindow)
    }
    if (windowStart >= windowEnd) return emptySet()

    val content = buffer.substring(windowStart, windowEnd)
    return collectIdentifiers(content, maxCount = MAX_LOCAL_IDENTIFIER_CANDIDATES)
}

internal class LocalCompletionCache {
    private var cachedVersion: Long = -1L
    private var cachedWindowStart: Int = -1
    private var cachedWindowEnd: Int = -1
    private var cachedAtMs: Long = 0L
    private var cachedIdentifiers: Set<String> = emptySet()

    fun identifiersNear(buffer: RopeTextBuffer, offset: Int): Set<String> {
        val length = buffer.length
        if (length <= 0) return emptySet()

        val scanWindow = adaptiveCompletionScanWindow(length)
        val halfWindow = scanWindow / 2
        var windowStart = max(0, offset - halfWindow)
        var windowEnd = min(length, windowStart + scanWindow)
        if (windowEnd - windowStart < scanWindow) {
            windowStart = max(0, windowEnd - scanWindow)
        }
        if (windowStart >= windowEnd) return emptySet()

        val now = SystemClock.uptimeMillis()
        val currentVersion = buffer.version
        val inCachedWindow = cachedWindowStart >= 0 &&
            cachedWindowEnd >= cachedWindowStart &&
            offset in cachedWindowStart..cachedWindowEnd
        val canReuseSameVersion = inCachedWindow && cachedVersion == currentVersion
        val canReuseDuringBurst = inCachedWindow &&
            cachedVersion != currentVersion &&
            now - cachedAtMs <= COMPLETION_CACHE_BURST_WINDOW_MS
        if (canReuseSameVersion || canReuseDuringBurst) {
            return cachedIdentifiers
        }

        val identifiers = extractIdentifierCandidates(buffer, offset)
        cachedVersion = currentVersion
        cachedWindowStart = windowStart
        cachedWindowEnd = windowEnd
        cachedAtMs = now
        cachedIdentifiers = identifiers
        return identifiers
    }
}

internal fun adaptiveCompletionScanWindow(documentLength: Int): Int = when {
    documentLength <= 80_000 -> 80_000
    documentLength <= 300_000 -> 120_000
    documentLength <= 1_000_000 -> 160_000
    else -> 220_000
}

internal fun collectIdentifiers(content: String, maxCount: Int): Set<String> {
    if (content.isEmpty() || maxCount <= 0) return emptySet()
    val identifiers = LinkedHashSet<String>(maxCount * 2)
    var index = 0
    while (index < content.length && identifiers.size < maxCount) {
        val ch = content[index]
        if (isIdentifierStart(ch)) {
            val start = index
            index++
            while (index < content.length && isWordChar(content[index])) {
                index++
            }
            if (index - start >= 2) {
                identifiers.add(content.substring(start, index))
            }
        } else {
            index++
        }
    }
    return identifiers
}

internal fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

internal fun languageKeywordCandidates(file: File): Set<String> {
    val name = file.name.lowercase()
    val ext = file.extension.lowercase()

    if (name == "cmakelists.txt" || ext == "cmake") return CMakeLanguageSupport.keywords
    if (MakeLanguageSupport.isMakefile(file)) return MakeLanguageSupport.keywords

    return when (ext) {
        "kt", "kts" -> setOf(
            "fun", "val", "var", "class", "object", "interface", "when", "if", "else",
            "for", "while", "return", "suspend", "override", "import", "package", "null",
            "true", "false", "data", "sealed", "enum", "companion"
        )
        "java" -> setOf(
            "class", "interface", "enum", "public", "private", "protected", "static",
            "final", "void", "int", "long", "boolean", "if", "else", "for", "while",
            "switch", "case", "return", "new", "null", "true", "false", "import", "package"
        )
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> setOf(
            "int", "long", "short", "char", "float", "double", "void", "struct", "class",
            "namespace", "public", "private", "protected", "if", "else", "for", "while",
            "switch", "case", "return", "include", "define", "nullptr", "const", "static"
        )
        "py" -> setOf(
            "def", "class", "import", "from", "if", "elif", "else", "for", "while", "return",
            "None", "True", "False", "try", "except", "finally", "with", "as", "pass", "yield"
        )
        "js", "ts" -> setOf(
            "function", "const", "let", "var", "class", "interface", "type", "if", "else",
            "for", "while", "return", "import", "export", "null", "undefined", "true", "false"
        )
        else -> setOf(
            "if", "else", "for", "while", "return", "class", "function", "true", "false", "null"
        )
    }
}

internal data class LocalDirectiveCompletion(
    val label: String,
    val insertText: String
)

private val CXX_LANGUAGE_KEYWORDS: Set<String> = linkedSetOf(
    "if", "else", "switch", "case", "default", "for", "while", "do", "break", "continue", "return",
    "goto", "try", "catch", "throw", "noexcept", "typedef", "using", "namespace", "template",
    "typename", "decltype", "sizeof", "alignof", "constexpr", "consteval", "constinit", "const",
    "volatile", "mutable", "static", "inline", "extern", "register", "thread_local",
    "virtual", "override", "final", "friend", "operator", "new", "delete", "this",
    "public", "private", "protected", "enum", "struct", "class", "union",
    "nullptr", "true", "false"
)

private val CXX_TYPE_KEYWORDS: Set<String> = linkedSetOf(
    "void", "bool", "char", "wchar_t", "char8_t", "char16_t", "char32_t",
    "short", "int", "long", "float", "double", "signed", "unsigned",
    "size_t", "ptrdiff_t", "auto"
)

private val CXX_PREPROCESSOR_ITEMS: List<LocalDirectiveCompletion> = listOf(
    LocalDirectiveCompletion("#include", "#include <>"),
    LocalDirectiveCompletion("#define", "#define "),
    LocalDirectiveCompletion("#ifdef", "#ifdef "),
    LocalDirectiveCompletion("#ifndef", "#ifndef "),
    LocalDirectiveCompletion("#if", "#if "),
    LocalDirectiveCompletion("#elif", "#elif "),
    LocalDirectiveCompletion("#else", "#else"),
    LocalDirectiveCompletion("#endif", "#endif"),
    LocalDirectiveCompletion("#pragma", "#pragma "),
    LocalDirectiveCompletion("#undef", "#undef "),
    LocalDirectiveCompletion("#error", "#error "),
    LocalDirectiveCompletion("#line", "#line ")
)

private const val MAX_PARSE_SIZE = 500_000

internal fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

private const val MAX_LOCAL_IDENTIFIER_CANDIDATES = 1000
private const val COMPLETION_CACHE_BURST_WINDOW_MS = 180L
private const val SLOW_LOCAL_COMPLETION_THRESHOLD_MS = 20L

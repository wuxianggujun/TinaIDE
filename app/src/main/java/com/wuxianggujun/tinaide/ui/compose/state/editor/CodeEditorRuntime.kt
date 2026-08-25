package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.core.config.EditorSettings
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.editorview.EditorRenderPerformanceSnapshot
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChangeListener
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import java.io.File
import kotlinx.coroutines.sync.Mutex

data class TextEditOperation(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String,
)

data class SelectionSnapshot(
    val text: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

data class CursorSnapshot(
    val line: Int,
    val column: Int,
)

data class CodeEditorCallback(
    val goToPosition: (line: Int, column: Int) -> Boolean,
    val selectAll: () -> Boolean,
    val replaceSelection: (replacement: String) -> Boolean,
    val replaceWholeText: (newText: String) -> Boolean,
    val applyTextEdits: (edits: List<TextEditOperation>) -> Boolean,
    val validateTextEdits: (edits: List<TextEditOperation>) -> Boolean = { true },
    val documentVersion: () -> Long? = { null },
    val toggleLineComment: (commentToken: String) -> Boolean,
    val replaceAll: (
        findText: String,
        replaceText: String,
        caseSensitive: Boolean,
        useRegex: Boolean,
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
    val applyEditorColorScheme: (scheme: EditorColorScheme) -> Unit = {},
)

internal interface CodeEditorDocumentBinding : DocumentSession.EditorBinding {
    fun attach()

    fun detach()

    suspend fun <R> withSuppressed(block: suspend () -> R): R
}

internal interface CodeEditorStateBinding {
    fun attach()

    fun detach()
}

/**
 * 单标签编辑器运行时：文本缓冲、语法高亮、折叠与 document/state 绑定引用计数。
 * 从 [EditorContainerState] 抽出，降低容器状态体量。
 */
class CodeEditorRuntime(
    val buffer: RopeTextBuffer,
    val editorState: EditorState,
    var syntaxHighlighter: TreeSitterHighlighter? = null,
    var isTreeSitterSnapshotReady: Boolean = false,
    var foldingProvider: TreeSitterFoldingProvider? = null,
    var isContentLoaded: Boolean = false,
) {
    val contentLoadMutex = Mutex()

    private val stateSyncListener = TextChangeListener { change ->
        editorState.applyTextBufferChange(change)
        syntaxHighlighter?.applyTextChange(change)
    }
    private var documentBinding: CodeEditorDocumentBinding? = null
    private var documentBindingReferences: Int = 0
    private data class StateBindingRecord(
        val binding: CodeEditorStateBinding,
        var references: Int = 0,
    )
    private val stateBindings = mutableMapOf<String, StateBindingRecord>()

    init {
        buffer.addChangeListener(stateSyncListener)
    }

    internal fun getOrCreateDocumentBinding(
        factory: () -> CodeEditorDocumentBinding,
    ): CodeEditorDocumentBinding = documentBinding ?: factory().also { documentBinding = it }

    internal fun acquireDocumentBinding(binding: CodeEditorDocumentBinding) {
        check(documentBinding === binding) { "Document binding does not belong to this editor runtime" }
        if (documentBindingReferences == 0) {
            binding.attach()
        }
        documentBindingReferences++
    }

    internal fun releaseDocumentBinding(binding: CodeEditorDocumentBinding) {
        if (documentBinding !== binding || documentBindingReferences == 0) return
        documentBindingReferences--
        if (documentBindingReferences == 0) {
            binding.detach()
        }
    }

    internal fun installSyntaxHighlighter(highlighter: TreeSitterHighlighter?) {
        if (syntaxHighlighter === highlighter) return
        syntaxHighlighter?.setOnStateUpdated(null)
        syntaxHighlighter = highlighter
        editorState.highlighter = highlighter
        highlighter?.setOnStateUpdated(editorState::notifyHighlightChanged)
    }

    internal fun getOrCreateStateBinding(
        key: String,
        factory: () -> CodeEditorStateBinding,
    ): CodeEditorStateBinding = stateBindings.getOrPut(key) {
        StateBindingRecord(factory())
    }.binding

    internal fun acquireStateBinding(key: String, binding: CodeEditorStateBinding) {
        val record = stateBindings[key]
        check(record?.binding === binding) { "State binding does not belong to this editor runtime" }
        if (record.references == 0) {
            binding.attach()
        }
        record.references++
    }

    internal fun releaseStateBinding(key: String, binding: CodeEditorStateBinding) {
        val record = stateBindings[key] ?: return
        if (record.binding !== binding || record.references == 0) return
        record.references--
        if (record.references == 0) {
            binding.detach()
        }
    }

    internal fun clearLanguageServices() {
        syntaxHighlighter?.setOnStateUpdated(null)
        if (editorState.highlighter === syntaxHighlighter) {
            editorState.highlighter = null
        }
        syntaxHighlighter?.dispose()
        foldingProvider?.dispose()
        syntaxHighlighter = null
        foldingProvider = null
        isTreeSitterSnapshotReady = false
    }

    internal fun resetDocumentBinding() {
        val binding = documentBinding
        if (binding != null && documentBindingReferences > 0) {
            binding.detach()
        }
        documentBinding = null
        documentBindingReferences = 0
    }

    internal fun resetStateBindings() {
        stateBindings.values.forEach { record ->
            if (record.references > 0) {
                record.binding.detach()
            }
        }
        stateBindings.clear()
    }

    internal fun retargetFile(newFile: File) {
        if (editorState.file?.absolutePath == newFile.absolutePath) return

        resetDocumentBinding()
        resetStateBindings()
        clearLanguageServices()
        editorState.clearSemanticTokens()
        editorState.clearFoldRegions()
        editorState.retargetFile(newFile)
    }

    internal fun dispose() {
        resetDocumentBinding()
        resetStateBindings()
        clearLanguageServices()
        buffer.removeChangeListener(stateSyncListener)
    }
}

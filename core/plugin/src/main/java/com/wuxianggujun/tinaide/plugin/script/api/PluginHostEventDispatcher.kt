package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 宿主到插件事件总线的轻量分发器。
 *
 * app 层在合适的生命周期节点调用这里，避免到处直接依赖
 * `PluginEventBus.emit()` 的挂起接口。
 */
object PluginHostEventDispatcher {
    // Reserve space for JSON escaping and envelope fields below the 256 KiB Binder payload limit.
    internal const val MAX_SELECTION_TEXT_CHARS = 16 * 1024
    internal const val MAX_DIAGNOSTICS_PER_EVENT = 32
    internal const val MAX_DIAGNOSTIC_URI_CHARS = 256
    internal const val MAX_DIAGNOSTIC_FILE_NAME_CHARS = 96
    internal const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 384
    internal const val MAX_DIAGNOSTIC_METADATA_CHARS = 48

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun emit(eventId: String, data: Map<String, Any?> = emptyMap()) {
        scope.launch {
            PluginEventBus.emit(eventId, data)
        }
    }

    fun emitToPlugin(
        pluginId: String,
        eventId: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            PluginEventBus.emit(
                eventId = eventId,
                data = data,
                targetPluginId = pluginId,
            )
        }
    }

    fun emitProjectOpened(rootPath: String) {
        emit(
            eventId = PluginEvent.PROJECT_OPENED.id,
            data = mapOf(
                "rootPath" to rootPath,
                "projectName" to File(rootPath).name
            )
        )
    }

    fun emitProjectClosed(rootPath: String) {
        emit(
            eventId = PluginEvent.PROJECT_CLOSED.id,
            data = mapOf(
                "rootPath" to rootPath,
                "projectName" to File(rootPath).name
            )
        )
    }

    fun emitEditorSaved(tabId: String, file: File) {
        emit(
            eventId = PluginEvent.EDITOR_SAVED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name
            )
        )
    }

    fun emitEditorOpened(tabId: String, file: File, contentType: String) {
        emit(
            eventId = PluginEvent.EDITOR_OPENED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType
            )
        )
    }

    fun emitEditorClosed(tabId: String, file: File, contentType: String) {
        emit(
            eventId = PluginEvent.EDITOR_CLOSED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType
            )
        )
    }

    fun emitEditorActiveChanged(tabId: String, file: File, contentType: String, isDirty: Boolean) {
        emit(
            eventId = PluginEvent.EDITOR_ACTIVE_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType,
                "isDirty" to isDirty
            )
        )
    }

    fun emitEditorSelectionChanged(
        tabId: String,
        file: File,
        selection: EditorSelectionPayload?
    ) {
        emit(
            eventId = PluginEvent.EDITOR_SELECTION_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "hasSelection" to (selection != null),
                "selection" to selection?.toMap()
            )
        )
    }

    fun emitEditorDirtyChanged(tabId: String, file: File, isDirty: Boolean) {
        emit(
            eventId = PluginEvent.EDITOR_DIRTY_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirty" to isDirty
            )
        )
    }

    fun emitDiagnosticsChanged(fileUri: String, diagnostics: List<Diagnostic>) {
        emit(
            eventId = PluginEvent.DIAGNOSTICS_CHANGED.id,
            data = diagnosticsPayload(fileUri, diagnostics),
        )
    }

    internal fun diagnosticsPayload(fileUri: String, diagnostics: List<Diagnostic>): Map<String, Any?> {
        val errors = diagnostics.count { it.severity == Diagnostic.Severity.ERROR }
        val warnings = diagnostics.count { it.severity == Diagnostic.Severity.WARNING }
        val infos = diagnostics.count { it.severity == Diagnostic.Severity.INFO }
        val hints = diagnostics.count { it.severity == Diagnostic.Severity.HINT }
        return mapOf(
            "fileUri" to fileUri.take(MAX_DIAGNOSTIC_URI_CHARS),
            "fileName" to diagnostics.firstOrNull()?.fileName?.take(MAX_DIAGNOSTIC_FILE_NAME_CHARS),
            "totalCount" to diagnostics.size,
            "errorCount" to errors,
            "warningCount" to warnings,
            "infoCount" to infos,
            "hintCount" to hints,
            "diagnosticsTruncated" to (diagnostics.size > MAX_DIAGNOSTICS_PER_EVENT),
            "diagnostics" to diagnostics.take(MAX_DIAGNOSTICS_PER_EVENT).map { it.toEventMap() },
        )
    }

    fun emitFileCreated(file: File) {
        emit(
            eventId = PluginEvent.FILE_CREATED.id,
            data = mapOf(
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirectory" to file.isDirectory
            )
        )
    }

    fun emitFileDeleted(file: File, wasDirectory: Boolean) {
        emit(
            eventId = PluginEvent.FILE_DELETED.id,
            data = mapOf(
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirectory" to wasDirectory
            )
        )
    }

    fun emitFileRenamed(oldFile: File, newFile: File, isDirectory: Boolean) {
        emit(
            eventId = PluginEvent.FILE_RENAMED.id,
            data = mapOf(
                "oldPath" to oldFile.absolutePath,
                "oldName" to oldFile.name,
                "newPath" to newFile.absolutePath,
                "newName" to newFile.name,
                "isDirectory" to isDirectory
            )
        )
    }

    fun emitBuildStarted(rootPath: String?) {
        emit(
            eventId = PluginEvent.BUILD_STARTED.id,
            data = mapOf("rootPath" to rootPath)
        )
    }

    fun emitBuildFinished(rootPath: String?) {
        emit(
            eventId = PluginEvent.BUILD_FINISHED.id,
            data = mapOf("rootPath" to rootPath)
        )
    }

    fun emitConfigChanged(
        pluginId: String,
        key: String,
        value: Any?,
        previousValue: Any?,
    ) {
        emitToPlugin(
            pluginId = pluginId,
            eventId = PluginEvent.CONFIG_CHANGED.id,
            data = mapOf(
                "pluginId" to pluginId,
                "key" to key,
                "value" to value,
                "previousValue" to previousValue,
            )
        )
    }
}

data class EditorSelectionPayload(
    val text: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "text" to text.take(PluginHostEventDispatcher.MAX_SELECTION_TEXT_CHARS),
        "textTruncated" to (text.length > PluginHostEventDispatcher.MAX_SELECTION_TEXT_CHARS),
        "startLine" to startLine,
        "startColumn" to startColumn,
        "endLine" to endLine,
        "endColumn" to endColumn
    )
}

private fun Diagnostic.toEventMap(): Map<String, Any?> = mapOf(
    "fileUri" to fileUri.take(PluginHostEventDispatcher.MAX_DIAGNOSTIC_URI_CHARS),
    "fileName" to fileName.take(PluginHostEventDispatcher.MAX_DIAGNOSTIC_FILE_NAME_CHARS),
    "line" to line,
    "column" to column,
    "endLine" to endLine,
    "endColumn" to endColumn,
    "message" to message.take(PluginHostEventDispatcher.MAX_DIAGNOSTIC_MESSAGE_CHARS),
    "severity" to severity.name.lowercase(),
    "source" to source?.take(PluginHostEventDispatcher.MAX_DIAGNOSTIC_METADATA_CHARS),
    "code" to code?.take(PluginHostEventDispatcher.MAX_DIAGNOSTIC_METADATA_CHARS)
)

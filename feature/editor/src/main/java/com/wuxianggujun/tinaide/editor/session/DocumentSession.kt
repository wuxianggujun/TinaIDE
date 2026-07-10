package com.wuxianggujun.tinaide.editor.session

import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.io.AtomicTextFileWriter
import com.wuxianggujun.tinaide.editor.io.FileCharsetDetector
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
data class DocumentSessionState(
    val tabId: String,
    val file: File,
    val title: String,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val lastSavedAt: Long? = null,
    val lastEditAt: Long? = null,
    val lastError: String? = null,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val hasExternalModification: Boolean = false,
    val charsetName: String = Charsets.UTF_8.name()
)

enum class SaveReason {
    MANUAL,
    AUTO,
    CLOSE
}

sealed class SaveResult {
    data class Success(
        val timestamp: Long,
        val reason: SaveReason,
        val target: SaveTarget? = null
    ) : SaveResult()
    data class Failure(val message: String) : SaveResult()
    data object NoOp : SaveResult()
}

data class SaveTarget(
    val tabId: String,
    val file: File
)

data class EditorViewState(
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

data class DetachedEditorSnapshot(
    val text: String,
    val viewState: EditorViewState,
    val isDirty: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val documentVersion: Long,
    val charsetName: String
)

class DocumentSession(
    private val context: Context,
    val tabId: String,
    file: File,
    private val projectSymbolIndexServiceProvider: () -> ProjectSymbolIndexService? = { null },
    initialViewState: EditorViewState? = null,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val DUPLICATE_FILE_EVENT_WINDOW_MS = 250L
        private const val INTERNAL_WRITE_SUPPRESS_WINDOW_MS = 1500L
        private const val FILE_WATCH_EVENTS =
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM
        private const val MAX_SNAPSHOT_READ_ATTEMPTS = 8
        const val UNSTABLE_DOCUMENT_VERSION = Long.MIN_VALUE
    }

    data class EditorContentSnapshot(
        val text: String,
        val documentVersion: Long
    )

    interface EditorBinding {
        fun readText(): String
        fun readSnapshot(): EditorContentSnapshot {
            var latestText = ""
            repeat(MAX_SNAPSHOT_READ_ATTEMPTS) {
                val versionBefore = currentDocumentVersion()
                latestText = readText()
                val versionAfter = currentDocumentVersion()
                if (versionBefore == versionAfter) {
                    return EditorContentSnapshot(latestText, versionAfter)
                }
            }
            return EditorContentSnapshot(latestText, UNSTABLE_DOCUMENT_VERSION)
        }
        fun setText(text: CharSequence)
        fun textLength(): Int
        fun canUndo(): Boolean
        fun canRedo(): Boolean
        fun undo()
        fun redo()
        fun currentDocumentVersion(): Long
        fun currentViewState(): EditorViewState? = null
    }

    private data class SaveSnapshot(
        val text: String,
        val timestamp: Long,
        val documentVersion: Long,
        val fingerprint: TextFingerprint,
        val fileMarker: FileWriteMarker,
        val isUpToDate: Boolean,
    )

    private data class TextFingerprint(
        val length: Int,
        val hash: Long
    )

    private data class FileWriteMarker(
        val modifiedAt: Long,
        val fileSize: Long,
        val observedAt: Long,
        val fileKey: String?,
    )

    private data class EditorBindingState(
        val isDirty: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean
    )

    private enum class BaselineState {
        INITIAL_LOADING,
        READY
    }

    var file: File = file
        private set

    private val editorBinding = AtomicReference<EditorBinding?>()
    private val saveMutex = Mutex()
    private val initialFileCharset = FileCharsetDetector.detect(file)

    @Volatile
    private var fileCharset: Charset = initialFileCharset

    @Volatile
    private var cleanVersion: Long = -1L

    @Volatile
    private var cleanFingerprint: TextFingerprint? = null
    private var lastEditTimestamp: Long? = null

    @Volatile
    private var baselineState: BaselineState = BaselineState.INITIAL_LOADING

    @Volatile
    private var detachedEditorSnapshot: DetachedEditorSnapshot? = null

    // FileObserver 相关字段
    private var fileObserver: FileObserver? = null

    @Volatile
    private var isSavingInternally: Boolean = false

    @Volatile
    private var lastInternalWriteMarker: FileWriteMarker? = readCurrentWriteMarker()

    @Volatile
    private var lastObservedWriteMarker: FileWriteMarker? = readCurrentWriteMarker()

    private val _state = MutableStateFlow(
        DocumentSessionState(
            tabId = tabId,
            file = file,
            title = file.name.ifBlank { "Untitled" },
            cursorLine = initialViewState?.cursorLine ?: 0,
            cursorColumn = initialViewState?.cursorColumn ?: 0,
            scrollX = initialViewState?.scrollX ?: 0,
            scrollY = initialViewState?.scrollY ?: 0,
            charsetName = initialFileCharset.name()
        )
    )
    val state: StateFlow<DocumentSessionState> = _state.asStateFlow()

    init {
        startFileWatcher()
    }
    fun attachEditor(binding: EditorBinding) {
        editorBinding.set(binding)
        val snapshot = if (cleanVersion < 0L || cleanFingerprint == null) {
            binding.readSnapshot()
        } else {
            null
        }
        if (cleanVersion < 0L) {
            cleanVersion = snapshot!!.documentVersion
        }
        if (cleanFingerprint == null) {
            cleanFingerprint = buildTextFingerprint(snapshot!!.text)
        }
        syncStateFromBinding(binding, changeCausedByUndoManager = false, forceCompare = true)
    }

    fun detachEditor(binding: EditorBinding) {
        if (!editorBinding.compareAndSet(binding, null)) return
        if (baselineState == BaselineState.INITIAL_LOADING && !_state.value.isDirty) return

        val viewState = binding.currentViewState() ?: currentViewState()
        val contentSnapshot = binding.readSnapshot()
        val dirty = cleanFingerprint?.let { baseline ->
            buildTextFingerprint(contentSnapshot.text) != baseline
        } ?: _state.value.isDirty
        val snapshot = DetachedEditorSnapshot(
            text = contentSnapshot.text,
            viewState = viewState,
            isDirty = dirty,
            canUndo = binding.canUndo(),
            canRedo = binding.canRedo(),
            documentVersion = contentSnapshot.documentVersion,
            charsetName = fileCharset.name()
        )
        detachedEditorSnapshot = snapshot
        _state.update {
            it.copy(
                isDirty = snapshot.isDirty,
                canUndo = snapshot.canUndo,
                canRedo = snapshot.canRedo,
                cursorLine = snapshot.viewState.cursorLine,
                cursorColumn = snapshot.viewState.cursorColumn,
                scrollX = snapshot.viewState.scrollX,
                scrollY = snapshot.viewState.scrollY,
                charsetName = snapshot.charsetName,
                lastError = null
            )
        }
    }

    fun detachedEditorSnapshot(): DetachedEditorSnapshot? = detachedEditorSnapshot

    fun markDetachedEditorSnapshotRestored(snapshot: DetachedEditorSnapshot) {
        if (detachedEditorSnapshot === snapshot) {
            detachedEditorSnapshot = null
        }
        fileCharset = runCatching { Charset.forName(snapshot.charsetName) }.getOrDefault(fileCharset)
        baselineState = BaselineState.READY
        val dirty = cleanFingerprint?.let { buildTextFingerprint(snapshot.text) != it } ?: snapshot.isDirty
        _state.update {
            it.copy(
                isDirty = dirty,
                canUndo = false,
                canRedo = false,
                cursorLine = snapshot.viewState.cursorLine,
                cursorColumn = snapshot.viewState.cursorColumn,
                scrollX = snapshot.viewState.scrollX,
                scrollY = snapshot.viewState.scrollY,
                charsetName = fileCharset.name(),
                lastError = null
            )
        }
    }

    fun markEditorSnapshotClean(charset: Charset? = null) {
        val binding = editorBinding.get() ?: return
        val effectiveCharset = charset ?: fileCharset
        fileCharset = effectiveCharset
        val snapshot = binding.readSnapshot()
        cleanVersion = snapshot.documentVersion
        cleanFingerprint = buildTextFingerprint(snapshot.text)
        baselineState = BaselineState.READY
        val marker = readCurrentWriteMarker()
        if (marker != null) {
            lastInternalWriteMarker = marker
            lastObservedWriteMarker = marker
        }
        _state.update {
            it.copy(
                isDirty = false,
                canUndo = binding.canUndo(),
                canRedo = binding.canRedo(),
                lastError = null,
                charsetName = effectiveCharset.name()
            )
        }
        detachedEditorSnapshot = null
    }

    fun notifyEditorContentChanged(
        canUndo: Boolean,
        canRedo: Boolean,
        changeCausedByUndoManager: Boolean = false
    ) {
        lastEditTimestamp = System.currentTimeMillis()
        val binding = editorBinding.get()
        if (binding == null) {
            _state.update {
                it.copy(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    lastEditAt = lastEditTimestamp,
                    lastError = null
                )
            }
            return
        }
        detachedEditorSnapshot = null

        syncStateFromBinding(
            binding = binding,
            changeCausedByUndoManager = changeCausedByUndoManager,
            forceCompare = false,
            lastEditAt = lastEditTimestamp
        )
    }

    internal fun refreshDirtyStateForSave(): Boolean {
        val binding = editorBinding.get()
        if (binding != null) {
            return syncStateFromBinding(
                binding = binding,
                changeCausedByUndoManager = false,
                forceCompare = true
            )
        }

        val dirty = detachedEditorSnapshot?.isDirty ?: _state.value.isDirty

        _state.update { current ->
            if (current.isDirty == dirty) current else current.copy(isDirty = dirty)
        }
        return dirty
    }

    fun updateCursorPosition(line: Int, column: Int) {
        _state.update { current ->
            if (current.cursorLine == line && current.cursorColumn == column) {
                current
            } else {
                current.copy(cursorLine = line, cursorColumn = column)
            }
        }
    }

    fun updateScrollPosition(scrollX: Int, scrollY: Int) {
        _state.update { current ->
            if (current.scrollX == scrollX && current.scrollY == scrollY) {
                current
            } else {
                current.copy(scrollX = scrollX, scrollY = scrollY)
            }
        }
    }

    fun updateViewState(state: EditorViewState) {
        _state.update { current ->
            val targetCursorLine = state.cursorLine
            val targetCursorColumn = state.cursorColumn
            val targetScrollX = state.scrollX
            val targetScrollY = state.scrollY
            if (
                current.cursorLine == targetCursorLine &&
                current.cursorColumn == targetCursorColumn &&
                current.scrollX == targetScrollX &&
                current.scrollY == targetScrollY
            ) {
                current
            } else {
                current.copy(
                    cursorLine = targetCursorLine,
                    cursorColumn = targetCursorColumn,
                    scrollX = targetScrollX,
                    scrollY = targetScrollY
                )
            }
        }
    }

    fun retargetFile(newFile: File) {
        if (file.absolutePath == newFile.absolutePath) return

        stopFileWatcher()
        file = newFile
        val marker = readCurrentWriteMarker()
        lastInternalWriteMarker = marker
        lastObservedWriteMarker = marker
        _state.update {
            it.copy(
                file = newFile,
                title = newFile.name.ifBlank { "Untitled" },
                hasExternalModification = false,
                lastError = null
            )
        }
        startFileWatcher()
    }

    private fun currentViewState(): EditorViewState {
        val state = _state.value
        return EditorViewState(
            cursorLine = state.cursorLine,
            cursorColumn = state.cursorColumn,
            scrollX = state.scrollX,
            scrollY = state.scrollY
        )
    }

    private fun readBindingState(
        binding: EditorBinding,
        changeCausedByUndoManager: Boolean,
        forceCompare: Boolean
    ): EditorBindingState = EditorBindingState(
        isDirty = computeDirty(binding, changeCausedByUndoManager, forceCompare),
        canUndo = binding.canUndo(),
        canRedo = binding.canRedo()
    )

    private fun syncStateFromBinding(
        binding: EditorBinding,
        changeCausedByUndoManager: Boolean,
        forceCompare: Boolean,
        lastEditAt: Long? = null
    ): Boolean {
        val bindingState = readBindingState(binding, changeCausedByUndoManager, forceCompare)
        _state.update { current ->
            current.copy(
                isDirty = bindingState.isDirty,
                canUndo = bindingState.canUndo,
                canRedo = bindingState.canRedo,
                lastEditAt = lastEditAt ?: current.lastEditAt,
                lastError = null
            )
        }
        return bindingState.isDirty
    }

    private fun refreshState() {
        val binding = editorBinding.get()
        if (binding != null) {
            syncStateFromBinding(binding, changeCausedByUndoManager = false, forceCompare = true)
        }
    }
    fun requestUndo() {
        editorBinding.get()?.let {
            it.undo()
            refreshState()
        }
    }

    fun requestRedo() {
        editorBinding.get()?.let {
            it.redo()
            refreshState()
        }
    }
    suspend fun save(reason: SaveReason): SaveResult {
        var saveStarted = false
        return try {
            saveMutex.withLock {
                if (_state.value.hasExternalModification) {
                    return@withLock SaveResult.Failure(
                        Strings.editor_conflict_message.strOr(context, file.name)
                    )
                }
                saveStarted = true
                _state.update { it.copy(isSaving = true, lastError = null) }
                isSavingInternally = true
                try {
                    val binding = editorBinding.get()
                    val detachedSnapshot = if (binding == null) detachedEditorSnapshot else null
                    if (binding == null && detachedSnapshot == null) {
                        _state.update { it.copy(isSaving = false) }
                        return@withLock SaveResult.Failure(Strings.editor_error_not_initialized.strOr(context))
                    }

                    val targetFile = file
                    val snapshot = withContext(Dispatchers.IO) {
                        val contentSnapshot = binding?.readSnapshot() ?: detachedSnapshot!!.let {
                            EditorContentSnapshot(
                                text = it.text,
                                documentVersion = it.documentVersion
                            )
                        }
                        val text = contentSnapshot.text
                        val snapshotVersion = contentSnapshot.documentVersion
                        val fingerprint = buildTextFingerprint(text)
                        writeFileSafely(targetFile, text)
                        val versionAfterWrite = binding?.currentDocumentVersion() ?: snapshotVersion
                        val timestamp = System.currentTimeMillis()
                        val fileMarker = readWriteMarker(targetFile, timestamp)
                            ?: FileWriteMarker(
                                modifiedAt = targetFile.lastModified(),
                                fileSize = targetFile.length(),
                                observedAt = timestamp,
                                fileKey = null,
                            )
                        SaveSnapshot(
                            text = text,
                            timestamp = timestamp,
                            documentVersion = snapshotVersion,
                            fingerprint = fingerprint,
                            fileMarker = fileMarker,
                            isUpToDate = versionAfterWrite == snapshotVersion,
                        )
                    }

                    cleanVersion = snapshot.documentVersion
                    cleanFingerprint = snapshot.fingerprint
                    baselineState = BaselineState.READY
                    val internalMarker = snapshot.fileMarker
                    lastInternalWriteMarker = internalMarker
                    lastObservedWriteMarker = internalMarker
                    if (fileObserver == null) {
                        startFileWatcher()
                    }

                    val currentBinding = editorBinding.get()
                    val activeBindingDirty = currentBinding?.let { activeBinding ->
                        val activeSnapshot = activeBinding.readSnapshot()
                        activeSnapshot.documentVersion == UNSTABLE_DOCUMENT_VERSION ||
                            activeBinding.currentDocumentVersion() != activeSnapshot.documentVersion ||
                            buildTextFingerprint(activeSnapshot.text) != snapshot.fingerprint
                    }
                    val detachedDirty = if (currentBinding != null) {
                        detachedEditorSnapshot = null
                        false
                    } else {
                        detachedEditorSnapshot?.let { detached ->
                            val isDirty = buildTextFingerprint(detached.text) != snapshot.fingerprint
                            detachedEditorSnapshot = detached.copy(
                                isDirty = isDirty,
                                charsetName = fileCharset.name()
                            )
                            isDirty
                        } ?: false
                    }
                    val isDirtyAfterSave = activeBindingDirty
                        ?: (detachedDirty || !snapshot.isUpToDate)
                    _state.update {
                        it.copy(
                            isDirty = isDirtyAfterSave,
                            isSaving = false,
                            lastSavedAt = snapshot.timestamp,
                            lastError = null,
                            charsetName = fileCharset.name()
                        )
                    }

                    // 保存后更新项目级符号索引（只影响当前文件，后台异步处理）
                    projectSymbolIndexServiceProvider()?.onFileSaved(targetFile, snapshot.text)
                    SaveResult.Success(
                        timestamp = snapshot.timestamp,
                        reason = reason,
                        target = SaveTarget(tabId = tabId, file = targetFile)
                    )
                } finally {
                    isSavingInternally = false
                }
            }
        } catch (cancellation: CancellationException) {
            if (saveStarted) {
                _state.update { it.copy(isSaving = false) }
            }
            throw cancellation
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false, lastError = e.message) }
            SaveResult.Failure(e.message ?: Strings.editor_error_save_failed.strOr(context))
        }
    }
    private fun writeFileSafely(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException(Strings.editor_error_cannot_resolve_dir.strOr(context))
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException(Strings.editor_error_cannot_create_dir.strOr(context, parent.absolutePath))
        }
        AtomicTextFileWriter.write(targetFile, content, fileCharset)
    }

    fun lastEditAt(): Long? = lastEditTimestamp

    // ========== FileObserver 相关方法 ==========

    private fun startFileWatcher() {
        if (!file.exists() || file.isDirectory) return
        val watchDirectory = file.parentFile?.takeIf { it.isDirectory } ?: return

        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(watchDirectory, FILE_WATCH_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(watchDirectory.absolutePath, FILE_WATCH_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, path)
                }
            }
        }
        fileObserver?.startWatching()
    }

    internal fun handleFileEvent(event: Int, eventPath: String?) {
        if (event and FILE_WATCH_EVENTS == 0) return
        if (!eventPath.isNullOrBlank()) {
            val normalized = eventPath.replace('\\', '/')
            val fileName = file.name.replace('\\', '/')
            val fullPath = file.absolutePath.replace('\\', '/')
            if (normalized != fileName && normalized != fullPath) {
                return
            }
        }

        if (isSavingInternally) return

        if (event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0) {
            val replacementMarker = readCurrentWriteMarker()
            if (replacementMarker != null && shouldIgnoreInternalWrite(replacementMarker)) return
            markExternalModification()
            return
        }

        val marker = readCurrentWriteMarker() ?: return
        if (shouldIgnoreInternalWrite(marker)) return
        if (isDuplicateObservedWrite(marker)) return

        markExternalModification()
    }

    internal fun markExternalModification() {
        _state.update { current ->
            if (current.hasExternalModification) {
                current
            } else {
                current.copy(hasExternalModification = true)
            }
        }
    }

    fun stopFileWatcher() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    fun acknowledgeExternalModification() {
        val marker = readCurrentWriteMarker()
        if (marker != null) {
            lastObservedWriteMarker = marker
        }
        _state.update { it.copy(hasExternalModification = false) }
    }

    suspend fun forceOverwrite(reason: SaveReason): SaveResult {
        val restoreConflictOnFailure = _state.value.hasExternalModification
        acknowledgeExternalModification()
        val result = save(reason)
        if (restoreConflictOnFailure && result is SaveResult.Failure) {
            markExternalModification()
        }
        return result
    }

    suspend fun reloadFromDisk(): Boolean {
        return try {
            val binding = editorBinding.get() ?: return false
            val (charset, newContent) = withContext(Dispatchers.IO) {
                val detectedCharset = FileCharsetDetector.detect(file)
                detectedCharset to file.readText(detectedCharset)
            }

            binding.setText(newContent)
            fileCharset = charset
            val snapshot = binding.readSnapshot()
            cleanFingerprint = buildTextFingerprint(snapshot.text)
            cleanVersion = snapshot.documentVersion
            baselineState = BaselineState.READY
            val marker = readCurrentWriteMarker()
            if (marker != null) {
                lastInternalWriteMarker = marker
                lastObservedWriteMarker = marker
            }

            acknowledgeExternalModification()
            _state.update {
                it.copy(
                    isDirty = false,
                    canUndo = binding.canUndo(),
                    canRedo = binding.canRedo(),
                    lastError = null,
                    charsetName = charset.name()
                )
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.message) }
            false
        }
    }

    private fun computeDirty(
        binding: EditorBinding,
        changeCausedByUndoManager: Boolean,
        forceCompare: Boolean
    ): Boolean {
        if (binding.currentDocumentVersion() == cleanVersion) {
            return false
        }

        val baseline = cleanFingerprint
        if (baseline == null) {
            val text = binding.readText()
            cleanFingerprint = buildTextFingerprint(text)
            return false
        }

        val shouldCompare = forceCompare ||
            changeCausedByUndoManager ||
            baselineState == BaselineState.INITIAL_LOADING
        if (!shouldCompare) {
            return true
        }

        val currentFingerprint = buildTextFingerprint(binding.readSnapshot().text)
        return currentFingerprint != baseline
    }

    private fun buildTextFingerprint(text: String): TextFingerprint {
        var hash = -0x340d631b8c4675d9L // FNV-1a 64-bit offset basis
        val prime = 0x100000001b3L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return TextFingerprint(length = text.length, hash = hash)
    }

    private fun readCurrentWriteMarker(): FileWriteMarker? =
        readWriteMarker(file, System.currentTimeMillis())

    private fun readWriteMarker(targetFile: File, observedAt: Long): FileWriteMarker? {
        if (!targetFile.exists() || !targetFile.isFile) return null
        val attributes = runCatching {
            Files.readAttributes(targetFile.toPath(), BasicFileAttributes::class.java)
        }.getOrNull()
        return FileWriteMarker(
            modifiedAt = attributes?.lastModifiedTime()?.toMillis() ?: targetFile.lastModified(),
            fileSize = attributes?.size() ?: targetFile.length(),
            observedAt = observedAt,
            fileKey = attributes?.fileKey()?.toString(),
        )
    }

    private fun isDuplicateObservedWrite(marker: FileWriteMarker): Boolean {
        val previous = lastObservedWriteMarker
        if (previous != null &&
            previous.hasSameFileSignature(marker) &&
            marker.observedAt - previous.observedAt in 0..DUPLICATE_FILE_EVENT_WINDOW_MS
        ) {
            return true
        }
        lastObservedWriteMarker = marker
        return false
    }

    private fun shouldIgnoreInternalWrite(marker: FileWriteMarker): Boolean {
        val internal = lastInternalWriteMarker ?: return false
        if (!internal.hasSameFileSignature(marker)) return false
        val delta = marker.observedAt - internal.observedAt
        return delta in 0..INTERNAL_WRITE_SUPPRESS_WINDOW_MS
    }

    private fun FileWriteMarker.hasSameFileSignature(other: FileWriteMarker): Boolean {
        if (modifiedAt != other.modifiedAt || fileSize != other.fileSize) return false
        return fileKey == null || other.fileKey == null || fileKey == other.fileKey
    }
}

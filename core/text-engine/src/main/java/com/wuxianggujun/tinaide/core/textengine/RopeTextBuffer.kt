package com.wuxianggujun.tinaide.core.textengine

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class RopeTextBuffer(
    initialText: String = "",
    private val history: EditHistory = DefaultEditHistory()
) : TextBuffer {
    private companion object {
        private const val IO_CHAR_BUFFER_SIZE = 16 * 1024
        private const val MAX_INITIAL_STRING_CAPACITY = 4 * 1024 * 1024
        private const val SLOW_LOAD_THRESHOLD_MS = 120L
        private const val SLOW_SAVE_THRESHOLD_MS = 120L
        private const val POSITION_CACHE_SIZE = 8
    }

    private val lock = ReentrantReadWriteLock()
    private val rope = Rope()
    private val lineIndex = LineIndex()
    private val listeners = CopyOnWriteArrayList<TextChangeListener>()
    private val versionCounter = AtomicLong(0L)
    private val _versionFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val versionFlow: kotlinx.coroutines.flow.StateFlow<Long> = _versionFlow

    // 小型 offset→Position cache：EditorState.cursorPosition 已经做过 textVersion 级的 cache，
    // 但 BracketSnapshot / 选区渲染 / LSP 映射等路径在同一帧内会对若干个不同 offset 调用 offsetToPosition，
    // 此处再加一个 K 槽 ring buffer，避免每次都走 lineIndex.offsetToLine + getLineStart。
    // 版本号不匹配时整表失效；命中时直接返回。
    private val positionCacheLock = Any()
    private val positionCacheOffsets = IntArray(POSITION_CACHE_SIZE) { Int.MIN_VALUE }
    private val positionCacheLines = IntArray(POSITION_CACHE_SIZE)
    private val positionCacheColumns = IntArray(POSITION_CACHE_SIZE)
    private var positionCacheVersion: Long = -1L
    private var positionCacheWriteIndex: Int = 0
    private var editTransactionDepth: Int = 0
    private var deferredTransactionChanges: MutableList<TextChange>? = null
    private val dispatchQueueLock = Any()
    private val pendingDispatchChanges = ArrayDeque<TextChange>()
    private val dispatchInProgress = AtomicBoolean(false)

    init {
        rope.setText(initialText)
        lineIndex.rebuild(initialText)
    }

    override val length: Int
        get() = lock.read { rope.length }

    override val lineCount: Int
        get() = lock.read { lineIndex.lineCount }

    override val version: Long
        get() = versionCounter.get()

    override fun insert(
        offset: Int,
        text: String,
        historyCursor: TextEditCursorSnapshot?
    ) {
        val shouldDrain = lock.write {
            applyInsert(
                offset = offset,
                text = text,
                recordHistory = true,
                fromUndoRedo = false,
                historyCursor = historyCursor
            ).queueForDispatch()
        }
        if (shouldDrain) drainDispatchQueue()
    }

    override fun delete(
        start: Int,
        end: Int,
        historyCursor: TextEditCursorSnapshot?
    ) {
        val shouldDrain = lock.write {
            applyDelete(
                start = start,
                end = end,
                recordHistory = true,
                fromUndoRedo = false,
                historyCursor = historyCursor
            ).queueForDispatch()
        }
        if (shouldDrain) drainDispatchQueue()
    }

    override fun replace(
        start: Int,
        end: Int,
        text: String,
        historyCursor: TextEditCursorSnapshot?
    ) {
        val shouldDrain = lock.write {
            applyReplace(
                start = start,
                end = end,
                text = text,
                recordHistory = true,
                fromUndoRedo = false,
                historyCursor = historyCursor
            ).queueForDispatch()
        }
        if (shouldDrain) drainDispatchQueue()
    }

    override fun beginCompoundEdit(
        cursorBefore: Int?,
        selectionBefore: TextSelectionSnapshot?
    ): CompoundEditToken = lock.write {
        history.beginCompoundEdit(cursorBefore, selectionBefore)
    }

    override fun endCompoundEdit(
        token: CompoundEditToken,
        cursorAfter: Int?,
        selectionAfter: TextSelectionSnapshot?
    ) {
        lock.write {
            history.endCompoundEdit(token, cursorAfter, selectionAfter)
        }
    }

    override fun isCompoundEditActive(token: CompoundEditToken): Boolean = lock.read {
        history.isCompoundEditActive(token)
    }

    override fun <T> editTransaction(
        cursorBefore: Int?,
        cursorAfter: (() -> Int?)?,
        selectionBefore: TextSelectionSnapshot?,
        selectionAfter: (() -> TextSelectionSnapshot?)?,
        block: TextBuffer.() -> T
    ): T {
        var shouldDrain = false
        return try {
            lock.write {
                val isOutermostTransaction = editTransactionDepth == 0
                if (isOutermostTransaction) {
                    deferredTransactionChanges = mutableListOf()
                }
                val compoundToken = try {
                    history.beginCompoundEdit(cursorBefore, selectionBefore)
                } catch (error: Throwable) {
                    if (isOutermostTransaction) deferredTransactionChanges = null
                    throw error
                }
                editTransactionDepth++

                var completed = false
                try {
                    block(this@RopeTextBuffer).also { completed = true }
                } finally {
                    try {
                        history.finishCompoundEdit(
                            compoundToken,
                            completed,
                            cursorAfter,
                            selectionAfter
                        )
                    } finally {
                        editTransactionDepth--
                        if (isOutermostTransaction) {
                            val changes = deferredTransactionChanges?.toList().orEmpty()
                            deferredTransactionChanges = null
                            shouldDrain = queueChangesForDispatch(changes)
                        }
                    }
                }
            }
        } finally {
            if (shouldDrain) drainDispatchQueue()
        }
    }

    fun replaceAll(text: String) {
        val shouldDrain = lock.write {
            val previousLength = rope.length
            val contentUnchanged = rope.contentEquals(text)
            history.clear()
            if (contentUnchanged) {
                null
            } else {
                val previousEndPos = offsetToPositionInternal(previousLength)
                val previousLineBreakCount = (lineIndex.lineCount - 1).coerceAtLeast(0)
                val previousEndsWithLineBreak =
                    previousLength > 0 && rope.charAt(previousLength - 1) == '\n'
                rope.setText(text)
                lineIndex.rebuild(text)
                versionCounter.incrementAndGet()
                TextChange(
                    startOffset = 0,
                    endOffset = previousLength,
                    oldText = "",
                    newText = text,
                    startLine = 0,
                    startColumn = 0,
                    endLine = previousEndPos.line,
                    endColumn = previousEndPos.column,
                    fromUndoRedo = false,
                    oldTextLength = previousLength,
                    oldLineBreakCount = previousLineBreakCount,
                    oldTextEndsWithLineBreak = previousEndsWithLineBreak,
                    hasCompleteOldText = false
                ).queueForDispatch()
            }
        }
        if (shouldDrain) drainDispatchQueue()
    }

    override fun substring(start: Int, end: Int): String = lock.read {
        rope.substring(start, end)
    }

    override fun charAt(offset: Int): Char? = lock.read {
        if (offset < 0 || offset >= rope.length) {
            null
        } else {
            rope.charAt(offset)
        }
    }

    override fun getLine(line: Int): String = lock.read {
        val start = lineIndex.getLineStart(line)
        val end = logicalLineEnd(line)
        rope.substring(start, end)
    }

    override fun getLineStart(line: Int): Int = lock.read {
        lineIndex.getLineStart(line)
    }

    override fun getLineEnd(line: Int): Int = lock.read {
        logicalLineEnd(line)
    }

    override fun offsetToLine(offset: Int): Int = lock.read {
        lineIndex.offsetToLine(offset.coerceIn(0, rope.length))
    }

    override fun positionToOffset(line: Int, column: Int): Int = lock.read {
        val start = lineIndex.getLineStart(line)
        val end = logicalLineEnd(line)
        start + column.coerceIn(0, end - start)
    }

    override fun offsetToPosition(offset: Int): Position = lock.read {
        val safeOffset = offset.coerceIn(0, rope.length)
        val currentVersion = versionCounter.get()
        synchronized(positionCacheLock) {
            if (positionCacheVersion == currentVersion) {
                for (i in 0 until POSITION_CACHE_SIZE) {
                    if (positionCacheOffsets[i] == safeOffset) {
                        return@read Position(positionCacheLines[i], positionCacheColumns[i])
                    }
                }
            }
        }
        val computed = offsetToPositionInternal(safeOffset)
        synchronized(positionCacheLock) {
            if (positionCacheVersion != currentVersion) {
                positionCacheOffsets.fill(Int.MIN_VALUE)
                positionCacheVersion = currentVersion
                positionCacheWriteIndex = 0
            }
            val slot = positionCacheWriteIndex
            positionCacheOffsets[slot] = safeOffset
            positionCacheLines[slot] = computed.line
            positionCacheColumns[slot] = computed.column
            positionCacheWriteIndex = (slot + 1) % POSITION_CACHE_SIZE
        }
        computed
    }

    override fun addChangeListener(listener: TextChangeListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeChangeListener(listener: TextChangeListener) {
        listeners.remove(listener)
    }

    override fun canUndo(): Boolean = lock.read { history.canUndo() }

    override fun canRedo(): Boolean = lock.read { history.canRedo() }

    override fun undo(): UndoRedoResult? {
        val result = lock.write {
            if (!history.canUndo()) return@write null
            val operation = history.undo() ?: return@write null
            val changes = applyUndoOperation(operation)
            UndoRedoResult(
                changes = changes,
                cursorOffset = operation.cursorBeforeOrDefault(changes),
                selection = (operation as? EditOperation.Compound)?.selectionBefore
            ).also { queueChangesForDispatch(changes) }
        }
        if (result != null) drainDispatchQueue()
        return result
    }

    override fun redo(): UndoRedoResult? {
        val result = lock.write {
            if (!history.canRedo()) return@write null
            val operation = history.redo() ?: return@write null
            val changes = applyRedoOperation(operation)
            UndoRedoResult(
                changes = changes,
                cursorOffset = operation.cursorAfterOrDefault(changes),
                selection = (operation as? EditOperation.Compound)?.selectionAfter
            ).also { queueChangesForDispatch(changes) }
        }
        if (result != null) drainDispatchQueue()
        return result
    }

    // A public edit may join two previously unpaired surrogate halves. Replaying that exact edit
    // can therefore cross a boundary that is a valid pair only in the post-edit state.
    private fun applyUndoOperation(operation: EditOperation): List<TextChange> = when (operation) {
        is EditOperation.Insert -> listOfNotNull(
            applyDelete(
                start = operation.offset,
                end = operation.offset + operation.text.length,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Delete -> listOfNotNull(
            applyInsert(
                offset = operation.offset,
                text = operation.text,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Replace -> listOfNotNull(
            applyReplace(
                start = operation.offset,
                end = operation.offset + operation.newText.length,
                text = operation.oldText,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Compound -> operation.operations.asReversed().flatMap(::applyUndoOperation)
    }

    private fun applyRedoOperation(operation: EditOperation): List<TextChange> = when (operation) {
        is EditOperation.Insert -> listOfNotNull(
            applyInsert(
                offset = operation.offset,
                text = operation.text,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Delete -> listOfNotNull(
            applyDelete(
                start = operation.offset,
                end = operation.offset + operation.text.length,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Replace -> listOfNotNull(
            applyReplace(
                start = operation.offset,
                end = operation.offset + operation.oldText.length,
                text = operation.newText,
                recordHistory = false,
                fromUndoRedo = true,
                enforceCodePointBoundaries = false
            )
        )
        is EditOperation.Compound -> operation.operations.flatMap(::applyRedoOperation)
    }

    private fun EditOperation.cursorBeforeOrDefault(changes: List<TextChange>): Int {
        val recordedCursor = when (this) {
            is EditOperation.Insert -> cursorSnapshot?.cursorBefore
            is EditOperation.Delete -> cursorSnapshot?.cursorBefore
            is EditOperation.Replace -> cursorSnapshot?.cursorBefore
            is EditOperation.Compound -> cursorBefore
        }
        return recordedCursor ?: changes.defaultCursorOffset()
    }

    private fun EditOperation.cursorAfterOrDefault(changes: List<TextChange>): Int {
        val recordedCursor = when (this) {
            is EditOperation.Insert -> cursorSnapshot?.cursorAfter
            is EditOperation.Delete -> cursorSnapshot?.cursorAfter
            is EditOperation.Replace -> cursorSnapshot?.cursorAfter
            is EditOperation.Compound -> cursorAfter
        }
        return recordedCursor ?: changes.defaultCursorOffset()
    }

    private fun List<TextChange>.defaultCursorOffset(): Int {
        val lastChange = lastOrNull() ?: return 0
        return lastChange.startOffset + lastChange.newText.length
    }

    override suspend fun loadFromFile(file: File, charset: Charset): Result<Unit> {
        return try {
            val startNs = System.nanoTime()
            val text = withContext(Dispatchers.IO) {
                readFileTextOptimized(file, charset)
            }
            replaceAll(text)
            logSlowLoadIfNeeded(
                file = file,
                loadedChars = text.length,
                durationMs = (System.nanoTime() - startNs) / 1_000_000L
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override suspend fun saveToFile(file: File, charset: Charset): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val startNs = System.nanoTime()
            file.parentFile?.mkdirs()
            val writtenChars = lock.read {
                // 流式写：按 rope 内部 4KB 分片逐块 encode，不再把整份文档先拼成 String。
                // 对 50MB 文件可省 100MB 堆分配 + 一次 writeText 的再拷贝。
                var total = 0
                file.outputStream().use { out ->
                    out.writer(charset).use { writer ->
                        rope.forEachChunk { chunk ->
                            writer.append(chunk)
                            total += chunk.length
                        }
                    }
                }
                total
            }
            logSlowSaveIfNeeded(
                file = file,
                writtenChars = writtenChars,
                durationMs = (System.nanoTime() - startNs) / 1_000_000L
            )
        }
    }

    override fun toString(): String = lock.read { rope.substring(0, rope.length) }

    private fun applyInsert(
        offset: Int,
        text: String,
        recordHistory: Boolean,
        fromUndoRedo: Boolean,
        historyCursor: TextEditCursorSnapshot? = null,
        enforceCodePointBoundaries: Boolean = true
    ): TextChange? {
        if (text.isEmpty()) return null

        require(offset in 0..rope.length) { "Invalid offset: $offset" }
        if (enforceCodePointBoundaries) requireCodePointBoundary(offset)
        val startPos = offsetToPositionInternal(offset)

        rope.insert(offset, text)
        lineIndex.applyChange(offset, oldText = "", newText = text)
        if (recordHistory) {
            history.record(EditOperation.Insert(offset, text, historyCursor))
        }
        versionCounter.incrementAndGet()

        return TextChange(
            startOffset = offset,
            endOffset = offset,
            oldText = "",
            newText = text,
            startLine = startPos.line,
            startColumn = startPos.column,
            endLine = startPos.line,
            endColumn = startPos.column,
            fromUndoRedo = fromUndoRedo
        )
    }

    private fun applyDelete(
        start: Int,
        end: Int,
        recordHistory: Boolean,
        fromUndoRedo: Boolean,
        historyCursor: TextEditCursorSnapshot? = null,
        enforceCodePointBoundaries: Boolean = true
    ): TextChange? {
        if (start == end) return null
        require(start in 0..rope.length && end in start..rope.length) {
            "Invalid range: [$start, $end)"
        }
        if (enforceCodePointBoundaries) {
            requireCodePointBoundary(start)
            requireCodePointBoundary(end)
        }

        val oldText = rope.substring(start, end)
        if (oldText.isEmpty()) return null

        val startPos = offsetToPositionInternal(start)
        val endPos = offsetToPositionInternal(end)

        rope.delete(start, end)
        lineIndex.applyChange(start, oldText = oldText, newText = "")
        if (recordHistory) {
            history.record(EditOperation.Delete(start, oldText, historyCursor))
        }
        versionCounter.incrementAndGet()

        return TextChange(
            startOffset = start,
            endOffset = end,
            oldText = oldText,
            newText = "",
            startLine = startPos.line,
            startColumn = startPos.column,
            endLine = endPos.line,
            endColumn = endPos.column,
            fromUndoRedo = fromUndoRedo
        )
    }

    private fun applyReplace(
        start: Int,
        end: Int,
        text: String,
        recordHistory: Boolean,
        fromUndoRedo: Boolean,
        historyCursor: TextEditCursorSnapshot? = null,
        enforceCodePointBoundaries: Boolean = true
    ): TextChange? {
        require(start in 0..rope.length) { "start out of bounds: $start (length=${rope.length})" }
        require(end in start..rope.length) { "end out of bounds: $end (start=$start, length=${rope.length})" }
        if (start == end && text.isEmpty()) return null
        if (enforceCodePointBoundaries) {
            requireCodePointBoundary(start)
            requireCodePointBoundary(end)
        }

        val oldText = if (start < end) rope.substring(start, end) else ""
        if (oldText == text) return null

        val startPos = offsetToPositionInternal(start)
        val endPos = if (start < end) offsetToPositionInternal(end) else startPos

        if (start < end) {
            rope.delete(start, end)
        }
        if (text.isNotEmpty()) {
            rope.insert(start, text)
        }
        lineIndex.applyChange(start, oldText = oldText, newText = text)
        if (recordHistory) {
            // 原子记录一条 Replace：undo 一次即可恢复原文。
            // 保持对纯删除 / 纯插入的降级：避免往 undoStack 里塞无意义的空字符串 op。
            val op: EditOperation = when {
                oldText.isEmpty() -> EditOperation.Insert(start, text, historyCursor)
                text.isEmpty() -> EditOperation.Delete(start, oldText, historyCursor)
                else -> EditOperation.Replace(
                    offset = start,
                    oldText = oldText,
                    newText = text,
                    cursorSnapshot = historyCursor
                )
            }
            history.record(op)
        }
        versionCounter.incrementAndGet()

        return TextChange(
            startOffset = start,
            endOffset = end,
            oldText = oldText,
            newText = text,
            startLine = startPos.line,
            startColumn = startPos.column,
            endLine = endPos.line,
            endColumn = endPos.column,
            fromUndoRedo = fromUndoRedo
        )
    }

    private fun offsetToPositionInternal(offset: Int): Position {
        val line = lineIndex.offsetToLine(offset)
        val lineStart = lineIndex.getLineStart(line)
        return Position(line, offset.coerceAtMost(logicalLineEnd(line)) - lineStart)
    }

    private fun logicalLineEnd(line: Int): Int {
        val start = lineIndex.getLineStart(line)
        val indexedEnd = lineIndex.getLineEnd(line, rope.length)
        val followedByLineFeed = indexedEnd < rope.length && rope.charAt(indexedEnd) == '\n'
        return if (followedByLineFeed && indexedEnd > start && rope.charAt(indexedEnd - 1) == '\r') {
            indexedEnd - 1
        } else {
            indexedEnd
        }
    }

    private fun requireCodePointBoundary(offset: Int) {
        if (offset <= 0 || offset >= rope.length) return
        require(!(rope.charAt(offset - 1).isHighSurrogate() && rope.charAt(offset).isLowSurrogate())) {
            "Offset $offset splits a UTF-16 surrogate pair"
        }
    }

    private fun TextChange?.queueForDispatch(): Boolean {
        val change = this ?: return false
        if (editTransactionDepth > 0) {
            checkNotNull(deferredTransactionChanges).add(change)
            return false
        }
        return queueChangesForDispatch(listOf(change))
    }

    private fun queueChangesForDispatch(changes: List<TextChange>): Boolean {
        if (changes.isEmpty()) return false
        synchronized(dispatchQueueLock) {
            pendingDispatchChanges.addAll(changes)
        }
        return true
    }

    private fun drainDispatchQueue() {
        while (true) {
            if (!dispatchInProgress.compareAndSet(false, true)) return
            try {
                while (true) {
                    val change = synchronized(dispatchQueueLock) {
                        pendingDispatchChanges.removeFirstOrNull()
                    } ?: break
                    dispatchChangeNow(change)
                }
            } finally {
                dispatchInProgress.set(false)
            }

            val hasPendingChanges = synchronized(dispatchQueueLock) {
                pendingDispatchChanges.isNotEmpty()
            }
            if (!hasPendingChanges) return
        }
    }

    private fun dispatchChangeNow(change: TextChange) {
        // 主动推进 versionFlow —— 订阅方可以直接 collect 而不用自己维护 callbackFlow + addChangeListener。
        _versionFlow.value = versionCounter.get()
        listeners.forEach { listener ->
            runCatching { listener.onTextChanged(change) }
                .onFailure { Timber.tag("RopeTextBuffer").w(it, "TextChangeListener failed") }
        }
    }

    private fun readFileTextOptimized(file: File, charset: Charset): String {
        val estimatedCapacity = file.length()
            .coerceIn(0L, MAX_INITIAL_STRING_CAPACITY.toLong())
            .toInt()
        val builder = StringBuilder(estimatedCapacity)
        InputStreamReader(file.inputStream(), charset).use { reader ->
            val buffer = CharArray(IO_CHAR_BUFFER_SIZE)
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                builder.append(buffer, 0, count)
            }
        }
        return builder.toString()
    }

    private fun logSlowLoadIfNeeded(file: File, loadedChars: Int, durationMs: Long) {
        if (durationMs <= SLOW_LOAD_THRESHOLD_MS) return
        Timber.tag("EditorPerf").w(
            "Slow load: %dms, file=%s, fileSize=%dKB, loadedChars=%d",
            durationMs,
            file.name,
            file.length() / 1024L,
            loadedChars
        )
    }

    private fun logSlowSaveIfNeeded(file: File, writtenChars: Int, durationMs: Long) {
        if (durationMs <= SLOW_SAVE_THRESHOLD_MS) return
        Timber.tag("EditorPerf").w(
            "Slow save: %dms, file=%s, fileSize=%dKB, writtenChars=%d",
            durationMs,
            file.name,
            file.length() / 1024L,
            writtenChars
        )
    }
}

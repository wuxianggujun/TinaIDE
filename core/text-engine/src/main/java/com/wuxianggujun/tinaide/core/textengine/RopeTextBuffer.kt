package com.wuxianggujun.tinaide.core.textengine

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
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
    override val history: EditHistory = DefaultEditHistory()
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

    override fun insert(offset: Int, text: String) {
        val change = lock.write { applyInsert(offset, text, recordHistory = true, fromUndoRedo = false) }
        if (change != null) dispatchChange(change)
    }

    override fun delete(start: Int, end: Int) {
        val change = lock.write { applyDelete(start, end, recordHistory = true, fromUndoRedo = false) }
        if (change != null) dispatchChange(change)
    }

    override fun replace(start: Int, end: Int, text: String) {
        val change = lock.write { applyReplace(start, end, text, recordHistory = true, fromUndoRedo = false) }
        if (change != null) dispatchChange(change)
    }

    override fun <T> editTransaction(
        cursorBefore: Int?,
        cursorAfter: (() -> Int?)?,
        block: TextBuffer.() -> T
    ): T = lock.write {
        history.beginCompoundEdit(cursorBefore)
        var completed = false
        try {
            block(this@RopeTextBuffer).also { completed = true }
        } finally {
            history.finishCompoundEdit(completed, cursorAfter)
        }
    }

    fun replaceAll(text: String) {
        val change = lock.write {
            val previous = rope.substring(0, rope.length)
            history.clear()
            if (previous == text) {
                null
            } else {
                val previousEndPos = offsetToPositionInternal(previous.length)
                rope.setText(text)
                lineIndex.rebuild(text)
                versionCounter.incrementAndGet()
                TextChange(
                    startOffset = 0,
                    endOffset = previous.length,
                    oldText = previous,
                    newText = text,
                    startLine = 0,
                    startColumn = 0,
                    endLine = previousEndPos.line,
                    endColumn = previousEndPos.column,
                    fromUndoRedo = false
                )
            }
        }
        if (change != null) dispatchChange(change)
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
        val end = lineIndex.getLineEnd(line, rope.length)
        rope.substring(start, end)
    }

    override fun getLineStart(line: Int): Int = lock.read {
        lineIndex.getLineStart(line)
    }

    override fun getLineEnd(line: Int): Int = lock.read {
        lineIndex.getLineEnd(line, rope.length)
    }

    override fun offsetToLine(offset: Int): Int = lock.read {
        lineIndex.offsetToLine(offset.coerceIn(0, rope.length))
    }

    override fun positionToOffset(line: Int, column: Int): Int = lock.read {
        lineIndex.positionToOffset(line, column, rope.length)
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

    override fun undo(): UndoRedoResult? = lock.write {
        val operation = history.undo() ?: return@write null
        val changes = applyUndoOperation(operation)
        UndoRedoResult(
            changes = changes,
            cursorOffset = operation.cursorBeforeOrDefault(changes)
        )
    }

    override fun redo(): UndoRedoResult? = lock.write {
        val operation = history.redo() ?: return@write null
        val changes = applyRedoOperation(operation)
        UndoRedoResult(
            changes = changes,
            cursorOffset = operation.cursorAfterOrDefault(changes)
        )
    }

    private fun applyUndoOperation(operation: EditOperation): List<TextChange> = when (operation) {
        is EditOperation.Insert -> listOfNotNull(
            applyDelete(
                start = operation.offset,
                end = operation.offset + operation.text.length,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Delete -> listOfNotNull(
            applyInsert(
                offset = operation.offset,
                text = operation.text,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Replace -> listOfNotNull(
            applyReplace(
                start = operation.offset,
                end = operation.offset + operation.newText.length,
                text = operation.oldText,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Compound -> operation.operations.asReversed().flatMap(::applyUndoOperation)
    }

    private fun applyRedoOperation(operation: EditOperation): List<TextChange> = when (operation) {
        is EditOperation.Insert -> listOfNotNull(
            applyInsert(
                offset = operation.offset,
                text = operation.text,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Delete -> listOfNotNull(
            applyDelete(
                start = operation.offset,
                end = operation.offset + operation.text.length,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Replace -> listOfNotNull(
            applyReplace(
                start = operation.offset,
                end = operation.offset + operation.oldText.length,
                text = operation.newText,
                recordHistory = false,
                fromUndoRedo = true
            ).also(::dispatchChangeIfPresent)
        )
        is EditOperation.Compound -> operation.operations.flatMap(::applyRedoOperation)
    }

    private fun EditOperation.cursorBeforeOrDefault(changes: List<TextChange>): Int {
        val recordedCursor = (this as? EditOperation.Compound)?.cursorBefore
        return recordedCursor ?: changes.defaultCursorOffset()
    }

    private fun EditOperation.cursorAfterOrDefault(changes: List<TextChange>): Int {
        val recordedCursor = (this as? EditOperation.Compound)?.cursorAfter
        return recordedCursor ?: changes.defaultCursorOffset()
    }

    private fun List<TextChange>.defaultCursorOffset(): Int {
        val lastChange = lastOrNull() ?: return 0
        return lastChange.startOffset + lastChange.newText.length
    }

    private fun dispatchChangeIfPresent(change: TextChange?) {
        if (change != null) dispatchChange(change)
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
        fromUndoRedo: Boolean
    ): TextChange? {
        if (text.isEmpty()) return null

        require(offset in 0..rope.length) { "Invalid offset: $offset" }
        val startPos = offsetToPositionInternal(offset)

        rope.insert(offset, text)
        lineIndex.applyChange(offset, oldText = "", newText = text)
        if (recordHistory) {
            history.record(EditOperation.Insert(offset, text))
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
        fromUndoRedo: Boolean
    ): TextChange? {
        if (start == end) return null
        require(start in 0..rope.length && end in start..rope.length) {
            "Invalid range: [$start, $end)"
        }

        val oldText = rope.substring(start, end)
        if (oldText.isEmpty()) return null

        val startPos = offsetToPositionInternal(start)
        val endPos = offsetToPositionInternal(end)

        rope.delete(start, end)
        lineIndex.applyChange(start, oldText = oldText, newText = "")
        if (recordHistory) {
            history.record(EditOperation.Delete(start, oldText))
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
        fromUndoRedo: Boolean
    ): TextChange? {
        require(start in 0..rope.length) { "start out of bounds: $start (length=${rope.length})" }
        require(end in start..rope.length) { "end out of bounds: $end (start=$start, length=${rope.length})" }
        if (start == end && text.isEmpty()) return null

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
                oldText.isEmpty() -> EditOperation.Insert(start, text)
                text.isEmpty() -> EditOperation.Delete(start, oldText)
                else -> EditOperation.Replace(offset = start, oldText = oldText, newText = text)
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
        return Position(line, offset - lineStart)
    }

    private fun dispatchChange(change: TextChange) {
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

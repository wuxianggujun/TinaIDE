package com.wuxianggujun.tinaide.core.treesitter

import android.os.Handler
import android.os.Looper
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSTree
import com.wuxianggujun.tinaide.core.textengine.TextChange
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

internal class IncrementalTreeSitterHighlightState(
    private val parser: TSParser,
    private val query: TSQuery,
    private val captureTypeByIndex: Array<HighlightType>,
    private val onClosed: (() -> Unit)? = null,
    private val predicateEvaluator: TreeSitterQueryPredicateEvaluator = TreeSitterQueryPredicateEvaluator(query),
) : AutoCloseable {

    private companion object {
        // LRU 下限：小文件也保证 16k 行 entry。
        private const val MIN_LINE_CACHE_SIZE = 16_384

        // LRU 硬上限：按内存估算 (lineCount * ~200B)，100k 行≈20MB，覆盖 Chromium / Linux kernel 级单文件。
        private const val MAX_LINE_CACHE_HARD_LIMIT = 100_000
        private const val DEFAULT_OPEN_BLOCKING_TIMEOUT_MS = 5000L

        private fun maxCacheSizeFor(lineCount: Int): Int {
            if (lineCount <= 0) return MIN_LINE_CACHE_SIZE
            return maxOf(MIN_LINE_CACHE_SIZE, lineCount).coerceAtMost(MAX_LINE_CACHE_HARD_LIMIT)
        }
    }

    private data class RenderSnapshot(
        val text: String,
        val lineStarts: IntArray,
        val safeTree: SafeTsTree,
        val lineCache: LinkedHashMap<Int, List<HighlightLineSegment>>
    ) {
        val lineCount: Int
            get() = lineStarts.size

        fun lineStart(line: Int): Int = lineStarts.lineStartOffset(line)

        fun lineEndExclusive(line: Int): Int = lineStarts.lineEndOffsetExclusive(line, text.length)

        fun applyTextChange(change: TextChange, newText: String): RenderSnapshot {
            val updated = HighlightLineCacheUpdater.applyTextChange(lineCache, HighlightLineCacheChange.from(change))
            return RenderSnapshot(
                text = newText,
                lineStarts = buildLineStartOffsets(newText),
                safeTree = safeTree,
                lineCache = LinkedHashMap<Int, List<HighlightLineSegment>>(updated.size, 0.75f, true).apply {
                    putAll(updated)
                }
            )
        }
    }

    private data class PendingParseRequest(
        val revision: Long,
        val sessionId: Long,
        val change: TextChange?,
        val reset: Boolean
    )

    private data class ParseRequest(
        val revision: Long,
        val sessionId: Long,
        val change: TextChange?,
        val reset: Boolean,
        val text: String
    )

    private data class ParseResult(
        val text: String,
        val renderTree: TSTree,
        val changedLineRanges: List<DirtyLineRange>,
        val canReuseLineCache: Boolean,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TreeSitterHighlightWorker").apply { isDaemon = true }
    }

    private var disposed = false
    private var revision = 0L
    private var sessionId = 0L
    private var renderSnapshot: RenderSnapshot? = null
    private var workerTree: TSTree? = null
    private var pending: PendingParseRequest? = null
    private var workerScheduled = false
    private var onStateUpdated: (() -> Unit)? = null
    private var updateNotificationPosted = false
    private var pendingDirtyLineRanges: List<DirtyLineRange> = emptyList()
    private var currentText: StringBuilder? = null
    private var currentLineCount = 0
    private var renderSnapshotStale = false

    // 视口提示：bulk prewarm 以此为中心螺旋扩展分块顺序，保证可视区最先着色。
    // 外部可多次刷新，读写争用低；用 @Volatile 让 worker 线程读到最新值。
    @Volatile
    private var viewportHintLine: Int = 0

    fun setOnStateUpdated(callback: (() -> Unit)?) {
        synchronized(lock) {
            onStateUpdated = callback
        }
    }

    fun setViewportHint(firstVisibleLine: Int) {
        viewportHintLine = firstVisibleLine.coerceAtLeast(0)
    }

    fun openDocument(text: String) {
        if (text.isEmpty()) {
            resetToEmptyDocument()
            return
        }
        var shouldSchedule = false
        var oldSnapshot: SafeTsTree? = null
        var oldWorkerTree: TSTree? = null
        synchronized(lock) {
            if (disposed) return
            revision++
            sessionId++
            currentText = StringBuilder(text)
            currentLineCount = buildLineStartOffsets(text).size
            renderSnapshotStale = true
            pendingDirtyLineRanges = emptyList()
            pending = PendingParseRequest(
                revision = revision,
                sessionId = sessionId,
                change = null,
                reset = true
            )
            oldSnapshot = renderSnapshot?.safeTree
            renderSnapshot = null
            oldWorkerTree = workerTree
            workerTree = null
            if (!workerScheduled) {
                workerScheduled = true
                shouldSchedule = true
            }
        }
        oldSnapshot?.close()
        enqueueWorkerTreeCleanup(oldWorkerTree)
        if (shouldSchedule) {
            worker.submit(::drain)
        }
    }

    /**
     * 同步打开文档：阻塞调用线程直到 tree-sitter parse 完成且首个渲染快照就位。
     * 调用方必须在 IO/Default 线程调用，主线程调用会抛出以避免死锁（mainHandler barrier）。
     * 超时退化为异步行为，不会 hang 住调用方。
     */
    fun openDocumentBlocking(text: String, timeoutMs: Long = DEFAULT_OPEN_BLOCKING_TIMEOUT_MS) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "openDocumentBlocking must not run on main thread (would deadlock on mainHandler)"
        }
        openDocument(text)
        if (text.isEmpty()) return

        // ① 等 worker 队列跑完：submit 一个空任务再 get()，保证 drain 已消费完 pending。
        runCatching {
            worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.onFailure { error ->
            Timber.tag("TreeSitter").d(error, "openDocumentBlocking worker barrier timeout")
        }

        // ② 等 mainHandler 队列跑完：applyResult 会 post 到 mainHandler，
        //    再 post 一个 barrier 就能确保 renderSnapshot 已装配、bulk prewarm 已 submit 到 worker。
        val latch = CountDownLatch(1)
        if (!mainHandler.post { latch.countDown() }) {
            return
        }
        runCatching {
            val awaited = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Timber.tag("TreeSitter").d("openDocumentBlocking main handler barrier timeout")
            }
        }

        // ③ 再等一次 worker：这次 barrier 排在 bulk prewarm 之后，
        //    返回时 lineCache 已全量就绪，首帧渲染直接整份上色。
        runCatching {
            worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.onFailure { error ->
            Timber.tag("TreeSitter").d(error, "openDocumentBlocking prewarm barrier timeout")
        }
    }

    fun applyTextChange(change: TextChange) {
        applyTextChangeInternal(change = change, forcedText = null)
    }

    fun applyTextChange(change: TextChange, newText: String) {
        applyTextChangeInternal(change = change, forcedText = newText)
    }

    fun getLineSegments(line: Int): List<HighlightLineSegment> {
        val (snapshot, staleAndDirty) = synchronized(lock) {
            if (line !in 0 until currentLineCount) return emptyList()
            val stale = renderSnapshotStale
            val dirty = stale && pendingDirtyLineRanges.any { line in it.startLine..it.endLine }
            renderSnapshot to dirty
        }
        snapshot ?: return emptyList()

        synchronized(snapshot.lineCache) {
            snapshot.lineCache[line]?.let { return it }
        }

        // If the snapshot is stale AND this line is in the pending dirty range,
        // captureLine would index into snapshot.text (pre-edit) / snapshot.safeTree
        // but the current line content has changed — the resulting spans would be
        // misaligned. Return empty (TextRenderer will paint default color) rather
        // than risk wrong colors. HighlightLineCacheUpdater's shifted entries have
        // already covered single-line edits via the cache lookup above.
        //
        // For stale-but-unchanged lines and for fresh snapshots, captureLine is
        // internally consistent and safe to use.
        if (staleAndDirty) return emptyList()
        if (line >= snapshot.lineCount) return emptyList()

        val spans = captureLine(snapshot, line) ?: return emptyList()
        synchronized(snapshot.lineCache) {
            snapshot.lineCache.remove(line)
            snapshot.lineCache[line] = spans
            val maxSize = maxCacheSizeFor(snapshot.lineCount)
            while (snapshot.lineCache.size > maxSize) {
                val eldest = snapshot.lineCache.entries.firstOrNull()?.key ?: break
                snapshot.lineCache.remove(eldest)
            }
        }
        return spans
    }

    fun readSnapshot(text: String): SafeTsTree? = synchronized(lock) { renderSnapshot?.takeIf { it.text == text }?.safeTree }

    override fun close() {
        val snapshot: SafeTsTree?
        val tree: TSTree?
        synchronized(lock) {
            if (disposed) return
            disposed = true
            revision++
            sessionId++
            snapshot = renderSnapshot?.safeTree
            tree = workerTree
            renderSnapshot = null
            workerTree = null
            pending = null
            onStateUpdated = null
            pendingDirtyLineRanges = emptyList()
            currentText = null
            currentLineCount = 0
            renderSnapshotStale = false
        }
        snapshot?.close()
        enqueueWorkerTreeCleanup(tree, shutdownWorker = true)
    }

    private fun resetToEmptyDocument() {
        val snapshot: SafeTsTree?
        val tree: TSTree?
        synchronized(lock) {
            if (disposed) return
            revision++
            sessionId++
            snapshot = renderSnapshot?.safeTree
            tree = workerTree
            renderSnapshot = null
            workerTree = null
            pending = null
            pendingDirtyLineRanges = emptyList()
            currentText = StringBuilder()
            currentLineCount = 1
            renderSnapshotStale = false
        }
        snapshot?.close()
        enqueueWorkerTreeCleanup(tree)
    }

    private fun applyTextChangeInternal(change: TextChange, forcedText: String?) {
        var shouldSchedule = false
        var shouldClear = false
        synchronized(lock) {
            if (disposed) return
            val updatedLength = when {
                forcedText != null -> {
                    currentText = StringBuilder(forcedText)
                    forcedText.length
                }

                currentText != null -> {
                    applyChangeToText(currentText!!, change)
                    currentText!!.length
                }

                else -> return
            }
            if (updatedLength == 0) {
                shouldClear = true
                return@synchronized
            }

            if (forcedText != null) {
                currentLineCount = buildLineStartOffsets(forcedText).size
            } else {
                val lineDelta = change.lineDelta
                currentLineCount = (currentLineCount + lineDelta).coerceAtLeast(1)
            }

            revision++
            renderSnapshot = renderSnapshot?.copy(
                lineCache = renderSnapshot!!.lineCache.applyTextChange(change)
            )
            pendingDirtyLineRanges = mergeDirtyLineRanges(
                current = pendingDirtyLineRanges,
                added = change.toDirtyLineRange()
            )
            val resetParse = pending != null || workerTree == null || renderSnapshot == null
            pending = PendingParseRequest(
                revision = revision,
                sessionId = sessionId,
                change = change,
                reset = resetParse
            )
            renderSnapshotStale = true
            if (!workerScheduled) {
                workerScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldClear) {
            resetToEmptyDocument()
            return
        }
        if (shouldSchedule) {
            worker.submit(::drain)
        }
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                if (next == null) {
                    workerScheduled = false
                    return
                }
                val snapshotText = currentText?.toString()
                if (snapshotText == null) {
                    pending = null
                    workerScheduled = false
                    return
                }
                pending = null
                ParseRequest(
                    revision = next.revision,
                    sessionId = next.sessionId,
                    change = next.change,
                    reset = next.reset,
                    text = snapshotText
                )
            }
            val result = runCatching { parseRequest(request) }
                .onFailure { Timber.tag("TreeSitter").d(it, "Incremental parse failed") }
                .getOrNull() ?: continue
            mainHandler.post { applyResult(request, result) }
        }
    }

    private fun parseRequest(request: ParseRequest): ParseResult? {
        val previous = synchronized(lock) {
            if (disposed || request.sessionId != sessionId || !query.canAccess()) return null
            workerTree
        }
        val snapshotText = request.text
        var changedLineRanges: List<DirtyLineRange> = emptyList()
        var canReuseLineCache = !request.reset && previous != null
        val next = when {
            request.reset || previous == null -> {
                parser.reset()
                parser.parseString(snapshotText)
            }

            else -> {
                val edit = request.change?.toTsInputEdit() ?: return null
                previous.edit(edit)
                parser.parseString(previous, snapshotText)?.also { parsedTree ->
                    val changedRanges = runCatching {
                        parsedTree.getChangedRanges(previous)
                    }.onFailure { error ->
                        Timber.tag("TreeSitter").d(error, "Failed to resolve changed syntax ranges")
                    }
                    if (changedRanges.isFailure) {
                        canReuseLineCache = false
                    } else {
                        changedLineRanges = changedRanges.getOrThrow().fold(emptyList()) { ranges, changedRange ->
                            mergeDirtyLineRanges(
                                current = ranges,
                                added = DirtyLineRange(
                                    startLine = changedRange.startPoint.row.coerceAtLeast(0),
                                    endLine = changedRange.endPoint.row.coerceAtLeast(changedRange.startPoint.row),
                                ),
                            )
                        }
                    }
                }
            }
        } ?: return null

        synchronized(lock) {
            if (disposed) {
                if (next !== previous) {
                    runCatching { next.close() }
                }
                return null
            }
            if (request.sessionId != sessionId) {
                if (next !== previous) {
                    runCatching { next.close() }
                }
                return null
            }
            workerTree = next
        }
        if (previous != null && previous !== next) {
            runCatching { previous.close() }
        }
        val renderTree = runCatching { next.copy() }
            .onFailure { Timber.tag("TreeSitter").d(it, "Failed to copy render tree") }
            .getOrNull()
            ?: return null
        return ParseResult(
            text = snapshotText,
            renderTree = renderTree,
            changedLineRanges = changedLineRanges,
            canReuseLineCache = canReuseLineCache,
        )
    }

    private fun applyResult(request: ParseRequest, result: ParseResult) {
        val oldSnapshot: SafeTsTree?
        val callback: (() -> Unit)?
        val prewarmSessionId: Long
        val fullDocumentPrewarm: Boolean
        synchronized(lock) {
            if (disposed || request.revision != revision) {
                runCatching { result.renderTree.close() }
                return
            }
            oldSnapshot = renderSnapshot?.safeTree
            fullDocumentPrewarm = request.change == null || oldSnapshot == null

            // 抢救旧 snapshot 的 lineCache：未落在文本或语法树变化范围内的行，
            // 其 segments 用的是列相对坐标，在新 snapshot 里仍然有效。
            // 这样"打字一个字符后滚动"不会因为 applyResult 清空缓存而看到默认色。
            // 初始容量直接跟随 previousCache.size —— 绝大多数 tree update 只有几行变脏，
            // 新 cache 规模几乎等于旧 cache，省掉默认 128 槽下的 rehash/扩容开销。
            val previousCache = renderSnapshot?.lineCache
            val carriedInitialCapacity = previousCache?.let { cache ->
                synchronized(cache) { (cache.size * 2).coerceAtLeast(64) }
            } ?: 128
            val carriedCache = LinkedHashMap<Int, List<HighlightLineSegment>>(
                carriedInitialCapacity,
                0.75f,
                true
            )
            val dirtyRanges = result.changedLineRanges.fold(pendingDirtyLineRanges) { ranges, changedRange ->
                mergeDirtyLineRanges(ranges, changedRange)
            }
            if (result.canReuseLineCache && previousCache != null) {
                synchronized(previousCache) {
                    previousCache.forEach { (line, segments) ->
                        val isDirty = dirtyRanges.any { line in it.startLine..it.endLine }
                        if (!isDirty) {
                            carriedCache[line] = segments
                        }
                    }
                }
            }

            renderSnapshot = RenderSnapshot(
                text = result.text,
                lineStarts = buildLineStartOffsets(result.text),
                safeTree = SafeTsTree(result.renderTree),
                lineCache = carriedCache
            )
            pendingDirtyLineRanges = emptyList()
            currentLineCount = renderSnapshot?.lineCount ?: 0
            renderSnapshotStale = false
            callback = onStateUpdated
            prewarmSessionId = sessionId
        }
        oldSnapshot?.close()
        callback?.invoke()

        // 首次打开用全文 bulk query 建立快照；编辑后只预热视口附近，避免输入时扫描整份文档。
        // bulk 方式比逐行 captureLine 快 10-50×；openDocumentBlocking 的第三 barrier 仍会等待首开预热。
        runCatching {
            worker.execute {
                prewarmLineCacheBulk(
                    expectedSessionId = prewarmSessionId,
                    expectedRevision = request.revision,
                    fullDocument = fullDocumentPrewarm
                )
            }
        }.onFailure { error ->
            Timber.tag("TreeSitter").d(error, "Prewarm submit failed")
        }
    }

    private fun prewarmLineCacheBulk(expectedSessionId: Long, expectedRevision: Long, fullDocument: Boolean) {
        val snapshot = synchronized(lock) {
            if (disposed || sessionId != expectedSessionId || revision != expectedRevision || renderSnapshotStale) return
            renderSnapshot ?: return
        }
        val lineCount = snapshot.lineCount
        if (lineCount <= 0) return
        val textLength = snapshot.text.length
        if (textLength <= 0) return

        // C1：worker 取 render tree 的独立 copy，后续所有 chunk 的 query exec 在这份 copy 上跑。
        // 走 copy 不再抢 SafeTsTree 的锁，主线程 tryAccessTree / captureLine 在 prewarm 期间永远
        // 秒拿；即便 copy 期间 SafeTsTree 被 close（新文档打开），也会通过 sessionId 检查被丢弃。
        val privateTree = snapshot.safeTree.tryAccessTree { tree ->
            runCatching { tree.copy() }.getOrNull()
        }
        if (privateTree == null) {
            Timber.tag("TreeSitter").d("Bulk prewarm: could not clone render tree, skip this cycle")
            return
        }

        try {
            val lineStarts = snapshot.lineStarts
            val maxCacheSize = maxCacheSizeFor(lineCount)
            val prewarmRanges = TreeSitterPrewarmPlan.ranges(lineCount, viewportHintLine, fullDocument)

            // 跨 chunk 复用同一个 HashMap：每 chunk 开始前 clear() 清掉 key，
            // 被 publish 出去的 ArrayList 引用还在 cache 里，不会被误释放；
            // 新 chunk 的 line key 会通过 getOrPut { ArrayList(4) } 新建 list，
            // 这部分分配本来就无法避免（被长期持有）。
            val reusableBucket = HashMap<Int, ArrayList<HighlightLineSegment>>(64)

            for (range in prewarmRanges) {
                val chunkStart = range.first
                val chunkEnd = range.last + 1
                val byteStart = lineStarts.lineStartOffset(chunkStart)
                val byteEndExclusive = if (chunkEnd < lineCount) {
                    lineStarts.lineStartOffset(chunkEnd)
                } else {
                    textLength
                }
                if (byteEndExclusive <= byteStart) continue

                val chunkActive = synchronized(lock) {
                    !disposed && sessionId == expectedSessionId && revision == expectedRevision &&
                        renderSnapshot === snapshot && !renderSnapshotStale
                }
                if (!chunkActive) return

                val chunkSpans = runCatching {
                    captureHighlightSpans(
                        query = query,
                        captureTypeByIndex = captureTypeByIndex,
                        rootNode = privateTree.rootNode,
                        sourceText = snapshot.text,
                        predicateEvaluator = predicateEvaluator,
                        visibleRange = byteStart..(byteEndExclusive - 1)
                    )
                }.onFailure { error ->
                    Timber.tag("TreeSitter").d(error, "Bulk prewarm chunk exec failed")
                }.getOrNull() ?: continue

                // 分桶：每行只归属一个块（块按行对齐），跨块 span 会在对应块的 query 中重新出现。
                val bucket = reusableBucket
                bucket.clear()
                for (span in chunkSpans) {
                    val rawStartLine = lineStarts.findLineForOffset(span.start)
                    val rawEndLine = lineStarts.findLineForOffset((span.end - 1).coerceAtLeast(span.start))
                    val startLine = rawStartLine.coerceIn(chunkStart, chunkEnd - 1)
                    val endLine = rawEndLine.coerceIn(startLine, chunkEnd - 1)
                    if (startLine == endLine) {
                        val base = lineStarts.lineStartOffset(startLine)
                        val lineEnd = lineStarts.lineEndOffsetExclusive(startLine, textLength)
                        val s = maxOf(span.start, base)
                        val e = minOf(span.end, lineEnd)
                        val startCol = (s - base).coerceAtLeast(0)
                        val endCol = (e - base).coerceAtLeast(startCol)
                        if (endCol > startCol) {
                            bucket.getOrPut(startLine) { ArrayList(4) }.add(
                                HighlightLineSegment(startCol, endCol, span.type, span.priority)
                            )
                        }
                    } else {
                        var line = startLine
                        while (line <= endLine) {
                            val base = lineStarts.lineStartOffset(line)
                            val end = lineStarts.lineEndOffsetExclusive(line, textLength)
                            val s = maxOf(span.start, base)
                            val e = minOf(span.end, end)
                            if (e > s) {
                                bucket.getOrPut(line) { ArrayList(4) }.add(
                                    HighlightLineSegment(s - base, e - base, span.type, span.priority)
                                )
                            }
                            line++
                        }
                    }
                }

                bucket.values.forEach { segments ->
                    if (segments.size > 1) {
                        segments.sortWith(
                            compareBy<HighlightLineSegment> { it.startColumn }
                                .thenByDescending { it.endColumn - it.startColumn }
                                .thenBy { it.priority }
                        )
                    }
                }

                val publishActive = synchronized(lock) {
                    !disposed && sessionId == expectedSessionId && revision == expectedRevision &&
                        renderSnapshot === snapshot && !renderSnapshotStale
                }
                if (!publishActive) return

                val cacheChanged = synchronized(snapshot.lineCache) {
                    val cache = snapshot.lineCache
                    var changed = false
                    // 有 span 的行：写入分桶结果（若主线程 lazy 已填过则保留原值）。
                    // 同时「A 的 stale fallback」也可能已经占了位，一并保留——避免覆盖用户已经看到的色。
                    bucket.forEach { (line, segments) ->
                        if (!cache.containsKey(line)) {
                            cache[line] = segments
                            changed = true
                        }
                    }
                    // 无 span 的行：补空列表避免后续 miss 再跑 captureLine。
                    var line = chunkStart
                    while (line < chunkEnd && cache.size < maxCacheSize) {
                        if (!cache.containsKey(line)) {
                            cache[line] = emptyList()
                            changed = true
                        }
                        line++
                    }
                    while (cache.size > maxCacheSize) {
                        val eldest = cache.entries.firstOrNull()?.key ?: break
                        cache.remove(eldest)
                    }
                    changed
                }

                if (cacheChanged) postPrewarmUpdate(expectedSessionId, expectedRevision)
            }
        } finally {
            runCatching { privateTree.close() }
        }
    }

    private fun postPrewarmUpdate(expectedSessionId: Long, expectedRevision: Long) {
        synchronized(lock) {
            if (disposed || sessionId != expectedSessionId || revision != expectedRevision ||
                onStateUpdated == null || updateNotificationPosted
            ) return
            updateNotificationPosted = true
        }
        // One pending notification describes the latest live snapshot, including a newer session.
        val posted = mainHandler.post {
            val callback = synchronized(lock) {
                updateNotificationPosted = false
                if (!disposed && !renderSnapshotStale) onStateUpdated else null
            }
            callback?.invoke()
        }
        if (!posted) synchronized(lock) { updateNotificationPosted = false }
    }

    private fun captureLine(snapshot: RenderSnapshot, line: Int): List<HighlightLineSegment>? {
        val start = snapshot.lineStart(line)
        val end = snapshot.lineEndExclusive(line)
        if (end <= start) return emptyList()

        val spans = snapshot.safeTree.tryAccessTree { tree ->
            captureHighlightSpans(
                query = query,
                captureTypeByIndex = captureTypeByIndex,
                rootNode = tree.rootNode,
                sourceText = snapshot.text,
                predicateEvaluator = predicateEvaluator,
                visibleRange = start..(end - 1)
            )
        } ?: return null

        if (spans.isEmpty()) return emptyList()
        val segments = ArrayList<HighlightLineSegment>(spans.size)
        spans.forEach { span ->
            val startColumn = (span.start - start).coerceAtLeast(0)
            val endColumn = (span.end - start).coerceIn(startColumn, end - start)
            if (endColumn > startColumn) {
                segments.add(HighlightLineSegment(startColumn, endColumn, span.type, span.priority))
            }
        }
        if (segments.size > 1) {
            segments.sortWith(
                compareBy<HighlightLineSegment> { it.startColumn }
                    .thenByDescending { it.endColumn - it.startColumn }
                    .thenBy { it.priority }
            )
        }
        return segments
    }

    private fun enqueueWorkerTreeCleanup(
        tree: TSTree?,
        shutdownWorker: Boolean = false
    ) {
        // native tree/parser/query 可能仍被当前 parse 使用，必须把清理排到 worker 队尾。
        val cleanup = Runnable {
            closeTreeQuietly(tree)
            if (shutdownWorker) {
                onClosed?.invoke()
            }
        }
        val submitted = runCatching {
            worker.execute(cleanup)
            true
        }.getOrElse { error ->
            Timber.tag("TreeSitter").d(error, "Queue Tree-sitter cleanup failed")
            false
        }
        if (submitted && shutdownWorker) {
            worker.shutdown()
            return
        }
        if (!submitted) {
            closeTreeQuietly(tree)
            if (shutdownWorker) {
                onClosed?.invoke()
            }
        }
    }

    private fun closeTreeQuietly(tree: TSTree?) {
        if (tree == null) return
        val accessible = runCatching { tree.canAccess() }.getOrDefault(true)
        if (!accessible) return
        runCatching { tree.close() }
    }
}

private fun LinkedHashMap<Int, List<HighlightLineSegment>>.applyTextChange(
    change: TextChange
): LinkedHashMap<Int, List<HighlightLineSegment>> = synchronized(this) {
    HighlightLineCacheUpdater.applyTextChange(this, HighlightLineCacheChange.from(change))
}

private fun applyChangeToText(builder: StringBuilder, change: TextChange) {
    val safeStart = change.startOffset.coerceIn(0, builder.length)
    val safeEnd = change.endOffset.coerceIn(safeStart, builder.length)
    builder.replace(safeStart, safeEnd, change.newText)
}

private data class DirtyLineRange(
    val startLine: Int,
    val endLine: Int
) {
    fun contains(line: Int): Boolean = line in startLine..endLine

    fun merge(other: DirtyLineRange): DirtyLineRange? {
        if (other.endLine + 1 < startLine || endLine + 1 < other.startLine) {
            return null
        }
        return DirtyLineRange(
            startLine = minOf(startLine, other.startLine),
            endLine = maxOf(endLine, other.endLine)
        )
    }
}

private fun TextChange.toDirtyLineRange(): DirtyLineRange {
    val startLine = startLine.coerceAtLeast(0)
    val lineDelta = this.lineDelta
    val newChangedEndLine = maxOf(startLine, endLine + lineDelta)
    return DirtyLineRange(
        startLine = startLine,
        endLine = newChangedEndLine.coerceAtLeast(startLine)
    )
}

private fun mergeDirtyLineRanges(
    current: List<DirtyLineRange>,
    added: DirtyLineRange
): List<DirtyLineRange> {
    if (current.isEmpty()) return listOf(added)

    val pending = ArrayList<DirtyLineRange>(current.size + 1)
    pending.addAll(current)
    pending.add(added)
    pending.sortBy { it.startLine }

    val merged = ArrayList<DirtyLineRange>(pending.size)
    pending.forEach { range ->
        val last = merged.lastOrNull()
        if (last == null) {
            merged.add(range)
            return@forEach
        }
        val mergedRange = last.merge(range)
        if (mergedRange != null) {
            merged[merged.lastIndex] = mergedRange
        } else {
            merged.add(range)
        }
    }
    return merged
}

internal fun captureHighlightSpans(
    query: TSQuery,
    captureTypeByIndex: Array<HighlightType>,
    rootNode: TSNode,
    sourceText: String,
    predicateEvaluator: TreeSitterQueryPredicateEvaluator,
    visibleRange: IntRange
): List<HighlightSpan> {
    if (visibleRange.isEmpty()) return emptyList()

    val textLength = sourceText.length
    val clampedStart = visibleRange.first.coerceIn(0, textLength)
    val clampedEndExclusive = (visibleRange.last + 1).coerceIn(clampedStart, textLength)
    if (clampedEndExclusive <= clampedStart) return emptyList()

    TSQueryCursor.create().use { cursor ->
        cursor.setByteRange(clampedStart shl 1, clampedEndExclusive shl 1)
        val allowChangedNodes = runCatching { rootNode.hasChanges() }.getOrDefault(false)
        if (allowChangedNodes) {
            // 渲染侧拿到的是只读快照树；即便节点仍然带 changed 标记，也要避免直接崩溃。
            cursor.setAllowChangedNodes(true)
        }
        val execSucceeded = runCatching {
            cursor.exec(query, rootNode)
        }.onFailure { error ->
            Timber.tag("TreeSitter").d(
                error,
                "Highlight query exec failed: allowChangedNodes=%s visible=%d..%d",
                allowChangedNodes,
                clampedStart,
                clampedEndExclusive
            )
        }.isSuccess
        if (!execSucceeded) return emptyList()
        val spans = ArrayList<HighlightSpan>(96)
        var match = cursor.nextMatch()
        while (match != null) {
            val predicateEvaluation = predicateEvaluator.evaluate(match, sourceText)
            if (!predicateEvaluation.accepted) {
                match = cursor.nextMatch()
                continue
            }
            val priority = predicateEvaluation.priority
            match.captures.forEach { capture ->
                val type = captureTypeByIndex.getOrElse(capture.index) { HighlightType.DEFAULT }
                // `@spell` / `@none` 这类辅助 capture 不能生成可绘制 span，
                // 否则会把同一区间更具体的注释/字符串颜色重新刷回默认色。
                if (!type.shouldRenderOverlay()) return@forEach
                val node = capture.node
                var start = node.startByte shr 1
                var end = node.endByte shr 1
                if (end <= start || end <= clampedStart || start >= clampedEndExclusive) return@forEach
                start = start.coerceIn(clampedStart, clampedEndExclusive)
                end = end.coerceIn(start, clampedEndExclusive)
                if (end > start) {
                    spans.add(HighlightSpan(start, end, type, priority))
                }
            }
            match = cursor.nextMatch()
        }
        if (spans.size > 1) {
            // 排序约定：同一 start 下，体积大的靠前（外层），体积相同则低 priority 靠前。
            // 下游 resolveOverlayColor 走 "last wins"，于是高 priority / 更具体的 span 就会赢。
            spans.sortWith(
                compareBy<HighlightSpan> { it.start }
                    .thenByDescending { it.end - it.start }
                    .thenBy { it.priority }
            )
        }
        return spans
    }
}

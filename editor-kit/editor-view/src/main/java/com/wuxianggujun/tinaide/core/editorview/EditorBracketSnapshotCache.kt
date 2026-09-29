package com.wuxianggujun.tinaide.core.editorview

import androidx.collection.SparseArrayCompat
import com.wuxianggujun.tinaide.core.textengine.BracketGuideSpanScanResult
import com.wuxianggujun.tinaide.core.textengine.BracketPairScanResult
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import com.wuxianggujun.tinaide.core.textengine.VisibleLineBracketScanResult
import java.util.LinkedHashMap

/**
 * 共享 bracket 相关的可见区快照，避免 guide/highlight/rainbow
 * 分别重复扫描同一段 prefix 文本。
 */
internal class EditorBracketSnapshotCache {

    data class GuideSpan(
        val openLine: Int,
        val openColumn: Int,
        val closeLine: Int,
        val depth: Int
    )

    data class BracketMatch(
        val openLine: Int,
        val openColumn: Int,
        val closeLine: Int,
        val closeColumn: Int,
        val depth: Int
    )

    private data class SnapshotKey(
        val version: Long,
        val lineCount: Int,
        val scanVisibleStartLine: Int,
        val scanVisibleEndLine: Int,
        val includeOpenSpansAtEnd: Boolean
    )

    private data class PairEntry(
        val mateOffset: Int,
        val depth: Int,
        val isOpen: Boolean
    )

    private data class VisibleSnapshot(
        val prefixEndOffset: Int,
        val guides: List<GuideSpan>,
        val pairIndex: SparseArrayCompat<PairEntry>,
        val lineBrackets: Map<Int, List<RainbowBracketComputer.BracketInfo>>
    )

    private data class MatchCacheKey(
        val version: Long,
        val primaryAnchorOffset: Int,
        val secondaryAnchorOffset: Int
    )

    private data class ResolvedBracketMatch(
        val match: BracketMatch,
        val primaryAnchorOffset: Int,
        val secondaryAnchorOffset: Int
    )

    private data class FallbackSnapshotKey(
        val version: Long,
        val scanStart: Int,
        val scanEnd: Int
    )

    private data class FallbackSnapshot(
        val pairIndex: SparseArrayCompat<PairEntry>
    )

    private data class FallbackScanRange(
        val scanStart: Int,
        val scanEnd: Int
    )

    private val depthCache = BracketDepthCache()
    private val visibleSnapshots =
        LinkedHashMap<SnapshotKey, VisibleSnapshot>(4, 0.75f, true)
    private val fallbackSnapshots =
        LinkedHashMap<FallbackSnapshotKey, FallbackSnapshot>(4, 0.75f, true)
    private var cachedMatchKey: MatchCacheKey? = null
    private var cachedMatch: BracketMatch? = null

    fun resolveVisibleGuides(
        textBuffer: TextBuffer,
        visibleLines: IntRange
    ): List<GuideSpan> {
        val normalizedVisibleLines =
            normalizeVisibleLines(textBuffer.lineCount, visibleLines) ?: return emptyList()
        return ensureVisibleSnapshot(textBuffer, normalizedVisibleLines).guides
    }

    fun resolveMatchingBracket(
        textBuffer: TextBuffer,
        visibleLines: IntRange,
        cursorOffset: Int
    ): BracketMatch? {
        if (textBuffer.length <= 0 || textBuffer.lineCount <= 0) return null

        val safeCursorOffset = cursorOffset.coerceIn(0, textBuffer.length)
        val anchorOffset = resolveBracketAnchorOffset(textBuffer, safeCursorOffset)
        if (isCachedMatchHit(textBuffer.version, anchorOffset)) {
            return cachedMatch
        }
        var resolvedAnchorPosition: Position? = null
        fun anchorPosition(offset: Int): Position {
            val cached = resolvedAnchorPosition
            if (cached != null) return cached
            return textBuffer.offsetToPosition(offset).also { resolvedAnchorPosition = it }
        }

        val normalizedVisibleLines = normalizeVisibleLines(textBuffer.lineCount, visibleLines)
        val snapshot = when {
            normalizedVisibleLines == null -> null
            else -> {
                findCachedVisibleSnapshot(textBuffer, normalizedVisibleLines)
                    ?: anchorOffset?.takeIf {
                        shouldBuildVisibleSnapshotForAnchor(
                            anchorLine = anchorPosition(it).line,
                            visibleLines = normalizedVisibleLines
                        )
                    }?.let {
                        ensureVisibleSnapshot(textBuffer, normalizedVisibleLines)
                    }
            }
        }
        val resolvedMatch = when {
            anchorOffset == null -> null
            snapshot != null -> {
                resolveMatchingBracketFromSnapshot(
                    textBuffer = textBuffer,
                    snapshot = snapshot,
                    anchorOffset = anchorOffset,
                    anchorPosition = anchorPosition(anchorOffset)
                ) ?: normalizedVisibleLines?.let { nearbyVisibleLines ->
                    resolveMatchingBracketFallback(
                        textBuffer = textBuffer,
                        anchorOffset = anchorOffset,
                        anchorPosition = anchorPosition(anchorOffset),
                        nearbyVisibleLines = nearbyVisibleLines
                    )
                } ?: resolveMatchingBracketFallback(
                    textBuffer = textBuffer,
                    anchorOffset = anchorOffset,
                    anchorPosition = anchorPosition(anchorOffset),
                    nearbyVisibleLines = null
                )
            }

            else -> resolveMatchingBracketFallback(
                textBuffer = textBuffer,
                anchorOffset = anchorOffset,
                anchorPosition = anchorPosition(anchorOffset),
                nearbyVisibleLines = normalizedVisibleLines
            )
        }

        updateMatchCache(
            currentVersion = textBuffer.version,
            anchorOffset = anchorOffset,
            match = resolvedMatch
        )
        return resolvedMatch?.match
    }

    fun resolveVisibleLineBrackets(
        textBuffer: TextBuffer,
        visibleLines: IntRange
    ): Map<Int, List<RainbowBracketComputer.BracketInfo>> {
        val normalizedVisibleLines =
            normalizeVisibleLines(textBuffer.lineCount, visibleLines) ?: return emptyMap()
        return ensureVisibleSnapshot(textBuffer, normalizedVisibleLines).lineBrackets
    }

    fun applyTextChange(change: TextChange, currentVersion: Long, currentLineCount: Int) {
        visibleSnapshots.clear()
        fallbackSnapshots.clear()
        cachedMatchKey = null
        cachedMatch = null
        depthCache.applyTextChange(change, currentVersion, currentLineCount)
    }

    fun invalidate() {
        visibleSnapshots.clear()
        fallbackSnapshots.clear()
        cachedMatchKey = null
        cachedMatch = null
        depthCache.invalidate()
    }

    private fun ensureVisibleSnapshot(
        textBuffer: TextBuffer,
        visibleLines: IntRange
    ): VisibleSnapshot {
        val scanVisibleLines = resolveVisibleSnapshotScanLines(
            lineCount = textBuffer.lineCount,
            visibleLines = visibleLines
        ) ?: return VisibleSnapshot(
            prefixEndOffset = 0,
            guides = emptyList(),
            pairIndex = emptyPairIndex(),
            lineBrackets = emptyMap()
        )
        val lineCount = textBuffer.lineCount
        val includeOpenSpansAtEnd = scanVisibleLines.last < lineCount - 1
        val key = SnapshotKey(
            version = textBuffer.version,
            lineCount = lineCount,
            scanVisibleStartLine = scanVisibleLines.first,
            scanVisibleEndLine = scanVisibleLines.last,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
        visibleSnapshots[key]?.let { cached ->
            return cached
        }

        val prefixEndOffset = textBuffer.getLineEnd(scanVisibleLines.last).coerceIn(0, textBuffer.length)
        val prefixText = if (prefixEndOffset <= 0) {
            ""
        } else {
            textBuffer.substring(0, prefixEndOffset)
        }
        val scannedSnapshot = if (prefixText.isEmpty()) {
            null
        } else {
            TextScanKernel.scanBracketSnapshot(
                startDepth = 0,
                text = prefixText,
                visibleStartLine = scanVisibleLines.first,
                visibleEndLine = scanVisibleLines.last,
                includeOpenSpansAtEnd = includeOpenSpansAtEnd
            )
        }
        if (scannedSnapshot != null && scannedSnapshot.lineBoundaryDepths.isNotEmpty()) {
            depthCache.seedPrefixBoundaryDepths(
                currentVersion = textBuffer.version,
                currentLineCount = textBuffer.lineCount,
                startLine = 0,
                targetLine = scanVisibleLines.last,
                boundaryDepths = scannedSnapshot.lineBoundaryDepths
            )
        }

        val snapshot = VisibleSnapshot(
            prefixEndOffset = prefixEndOffset,
            guides = scannedSnapshot?.let { buildGuideSpans(it.guides) }.orEmpty(),
            pairIndex = scannedSnapshot?.let { buildPairIndex(it.pairs) } ?: emptyPairIndex(),
            lineBrackets = scannedSnapshot?.let {
                buildVisibleLineBrackets(it.visibleLineBrackets)
            }.orEmpty()
        )
        visibleSnapshots[key] = snapshot
        trimVisibleSnapshots()
        return snapshot
    }

    private fun findCachedVisibleSnapshot(
        textBuffer: TextBuffer,
        visibleLines: IntRange
    ): VisibleSnapshot? {
        val scanVisibleLines = resolveVisibleSnapshotScanLines(
            lineCount = textBuffer.lineCount,
            visibleLines = visibleLines
        ) ?: return null
        val includeOpenSpansAtEnd = scanVisibleLines.last < textBuffer.lineCount - 1
        val expectedKey = SnapshotKey(
            version = textBuffer.version,
            lineCount = textBuffer.lineCount,
            scanVisibleStartLine = scanVisibleLines.first,
            scanVisibleEndLine = scanVisibleLines.last,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
        return visibleSnapshots[expectedKey]
    }

    private fun trimVisibleSnapshots() {
        while (visibleSnapshots.size > MAX_CACHED_VISIBLE_SNAPSHOTS) {
            val eldestKey = visibleSnapshots.entries.iterator().next().key
            visibleSnapshots.remove(eldestKey)
        }
    }

    private fun buildGuideSpans(
        rawGuides: List<BracketGuideSpanScanResult>
    ): List<GuideSpan> {
        val guides = ArrayList<GuideSpan>(rawGuides.size)
        rawGuides.forEach { guide ->
            if (guide.closeLine > guide.openLine) {
                guides += GuideSpan(
                    openLine = guide.openLine,
                    openColumn = guide.openColumn,
                    closeLine = guide.closeLine,
                    depth = guide.depth
                )
            }
        }
        return guides
    }

    private fun buildPairIndex(
        pairs: List<BracketPairScanResult>
    ): SparseArrayCompat<PairEntry> = buildPairIndex(
        pairs = pairs,
        offsetBase = 0
    )

    private fun buildPairIndex(
        pairs: List<BracketPairScanResult>,
        offsetBase: Int
    ): SparseArrayCompat<PairEntry> {
        if (pairs.isEmpty()) return emptyPairIndex()

        // 用 SparseArrayCompat 替代 HashMap<Int, PairEntry>：
        // Int key 不再装箱成 Integer，减少 GC 压力；热路径 `pairIndex[offset]` 查表仍是 O(log n) 二分。
        val pairIndex = SparseArrayCompat<PairEntry>(pairs.size * 2)
        pairs.forEach { pair ->
            val openOffset = offsetBase + pair.openOffset
            val closeOffset = offsetBase + pair.closeOffset
            pairIndex.put(
                openOffset,
                PairEntry(
                    mateOffset = closeOffset,
                    depth = pair.depth,
                    isOpen = true
                )
            )
            pairIndex.put(
                closeOffset,
                PairEntry(
                    mateOffset = openOffset,
                    depth = pair.depth,
                    isOpen = false
                )
            )
        }
        return pairIndex
    }

    private fun emptyPairIndex(): SparseArrayCompat<PairEntry> = SparseArrayCompat(0)

    private fun buildVisibleLineBrackets(
        rawLineBrackets: List<VisibleLineBracketScanResult>
    ): Map<Int, List<RainbowBracketComputer.BracketInfo>> {
        if (rawLineBrackets.isEmpty()) return emptyMap()

        val lineBrackets = LinkedHashMap<Int, MutableList<RainbowBracketComputer.BracketInfo>>()
        rawLineBrackets.forEach { bracket ->
            lineBrackets.getOrPut(bracket.line) { ArrayList(4) }.add(
                RainbowBracketComputer.BracketInfo(
                    column = bracket.column,
                    depth = bracket.depth,
                    isOpen = bracket.isOpen
                )
            )
        }
        return lineBrackets
    }

    private fun resolveMatchingBracketFromSnapshot(
        textBuffer: TextBuffer,
        snapshot: VisibleSnapshot,
        anchorOffset: Int,
        anchorPosition: Position
    ): ResolvedBracketMatch? {
        if (anchorOffset < 0 || anchorOffset >= snapshot.prefixEndOffset) return null

        val pair = snapshot.pairIndex[anchorOffset] ?: return null
        return buildBracketMatch(textBuffer, anchorOffset, anchorPosition, pair)
    }

    private fun resolveMatchingBracketFallback(
        textBuffer: TextBuffer,
        anchorOffset: Int,
        anchorPosition: Position,
        nearbyVisibleLines: IntRange?
    ): ResolvedBracketMatch? {
        val scanRange = resolveFallbackScanRange(
            textBuffer = textBuffer,
            anchorLine = anchorPosition.line,
            anchorOffset = anchorOffset,
            visibleLines = nearbyVisibleLines
        ) ?: return null
        val fallbackSnapshot = ensureFallbackSnapshot(textBuffer, scanRange)
        val pair = fallbackSnapshot.pairIndex[anchorOffset] ?: return null
        return buildBracketMatch(textBuffer, anchorOffset, anchorPosition, pair)
    }

    private fun buildBracketMatch(
        textBuffer: TextBuffer,
        anchorOffset: Int,
        anchorPosition: Position,
        pair: PairEntry
    ): ResolvedBracketMatch {
        val mateOffset = pair.mateOffset
        val matePosition = textBuffer.offsetToPosition(mateOffset)
        val match = if (pair.isOpen) {
            BracketMatch(
                openLine = anchorPosition.line,
                openColumn = anchorPosition.column,
                closeLine = matePosition.line,
                closeColumn = matePosition.column,
                depth = pair.depth
            )
        } else {
            BracketMatch(
                openLine = matePosition.line,
                openColumn = matePosition.column,
                closeLine = anchorPosition.line,
                closeColumn = anchorPosition.column,
                depth = pair.depth
            )
        }
        return ResolvedBracketMatch(
            match = match,
            primaryAnchorOffset = if (pair.isOpen) anchorOffset else mateOffset,
            secondaryAnchorOffset = if (pair.isOpen) mateOffset else anchorOffset
        )
    }

    private fun isCachedMatchHit(
        currentVersion: Long,
        anchorOffset: Int?
    ): Boolean {
        val cachedKey = cachedMatchKey ?: return false
        if (cachedKey.version != currentVersion) return false

        val safeAnchorOffset = anchorOffset ?: NO_BRACKET_ANCHOR
        return safeAnchorOffset == cachedKey.primaryAnchorOffset ||
            safeAnchorOffset == cachedKey.secondaryAnchorOffset
    }

    private fun updateMatchCache(
        currentVersion: Long,
        anchorOffset: Int?,
        match: ResolvedBracketMatch?
    ) {
        cachedMatchKey = when {
            anchorOffset == null -> {
                MatchCacheKey(
                    version = currentVersion,
                    primaryAnchorOffset = NO_BRACKET_ANCHOR,
                    secondaryAnchorOffset = NO_BRACKET_ANCHOR
                )
            }

            match == null -> {
                MatchCacheKey(
                    version = currentVersion,
                    primaryAnchorOffset = anchorOffset,
                    secondaryAnchorOffset = anchorOffset
                )
            }

            else -> {
                MatchCacheKey(
                    version = currentVersion,
                    primaryAnchorOffset = match.primaryAnchorOffset,
                    secondaryAnchorOffset = match.secondaryAnchorOffset
                )
            }
        }
        cachedMatch = match?.match
    }

    private fun ensureFallbackSnapshot(
        textBuffer: TextBuffer,
        scanRange: FallbackScanRange
    ): FallbackSnapshot {
        val key = FallbackSnapshotKey(
            version = textBuffer.version,
            scanStart = scanRange.scanStart,
            scanEnd = scanRange.scanEnd
        )
        fallbackSnapshots[key]?.let { cached ->
            return cached
        }

        val scanText = textBuffer.substring(scanRange.scanStart, scanRange.scanEnd)
        val scanStartPosition = if (scanRange.scanStart <= 0) {
            Position(line = 0, column = 0)
        } else {
            textBuffer.offsetToPosition(scanRange.scanStart)
        }
        val startDepth = depthCache.depthAt(
            textBuffer = textBuffer,
            line = scanStartPosition.line,
            column = scanStartPosition.column
        )
        val scanResult = TextScanKernel.scanBracketSnapshot(
            startDepth = startDepth,
            text = scanText,
            visibleStartLine = 0,
            visibleEndLine = 0,
            includeOpenSpansAtEnd = false
        )
        val pairIndex = buildPairIndex(
            pairs = scanResult.pairs,
            offsetBase = scanRange.scanStart
        )
        val snapshot = FallbackSnapshot(
            pairIndex = pairIndex
        )
        fallbackSnapshots[key] = snapshot
        trimFallbackSnapshots()
        return snapshot
    }

    private fun trimFallbackSnapshots() {
        while (fallbackSnapshots.size > MAX_CACHED_FALLBACK_SNAPSHOTS) {
            val eldestKey = fallbackSnapshots.entries.iterator().next().key
            fallbackSnapshots.remove(eldestKey)
        }
    }

    private fun resolveFallbackScanRange(
        textBuffer: TextBuffer,
        anchorLine: Int,
        anchorOffset: Int,
        visibleLines: IntRange?
    ): FallbackScanRange? {
        if (textBuffer.length <= 0 || textBuffer.lineCount <= 0) return null

        val safeAnchorOffset = anchorOffset.coerceIn(0, (textBuffer.length - 1).coerceAtLeast(0))
        val anchorBlockStart = alignDown(safeAnchorOffset, FALLBACK_SCAN_BLOCK_CHARS)
        val anchorBlockCharEndExclusive = (anchorBlockStart + FALLBACK_SCAN_BLOCK_CHARS)
            .coerceAtMost(textBuffer.length)
        var scanStart = (anchorBlockStart - MAX_SCAN_CHARS).coerceAtLeast(0)
        var scanEnd = (anchorBlockCharEndExclusive + MAX_SCAN_CHARS).coerceAtMost(textBuffer.length)

        val normalizedVisibleLines = visibleLines ?: return if (scanEnd > scanStart) {
            FallbackScanRange(scanStart = scanStart, scanEnd = scanEnd)
        } else {
            null
        }
        val maxLine = textBuffer.lineCount - 1
        if (maxLine < 0) return null

        val safeAnchorLine = anchorLine.coerceIn(0, maxLine)
        val expandedStartLine =
            (normalizedVisibleLines.first - FALLBACK_VISIBLE_MARGIN_LINES).coerceAtLeast(0)
        val expandedEndLine = (normalizedVisibleLines.last + FALLBACK_VISIBLE_MARGIN_LINES)
            .coerceAtMost(maxLine)
        if (safeAnchorLine !in expandedStartLine..expandedEndLine) {
            return if (scanEnd > scanStart) {
                FallbackScanRange(scanStart = scanStart, scanEnd = scanEnd)
            } else {
                null
            }
        }

        val anchorBlockStartLine = alignDown(safeAnchorLine, FALLBACK_VISIBLE_BLOCK_LINES)
        val anchorBlockLineEndExclusive = (anchorBlockStartLine + FALLBACK_VISIBLE_BLOCK_LINES)
            .coerceAtMost(textBuffer.lineCount)
        val scanStartLine = (anchorBlockStartLine - FALLBACK_VISIBLE_MARGIN_LINES).coerceAtLeast(0)
        val scanEndExclusive = (anchorBlockLineEndExclusive + FALLBACK_VISIBLE_MARGIN_LINES)
            .coerceAtMost(textBuffer.lineCount)
        val scanEndLine = (scanEndExclusive - 1).coerceAtLeast(scanStartLine)
        scanStart = minOf(scanStart, textBuffer.getLineStart(scanStartLine))
        scanEnd = maxOf(scanEnd, textBuffer.getLineEnd(scanEndLine).coerceAtMost(textBuffer.length))
        return if (scanEnd > scanStart) {
            FallbackScanRange(
                scanStart = scanStart,
                scanEnd = scanEnd
            )
        } else {
            null
        }
    }

    private fun resolveVisibleSnapshotScanLines(
        lineCount: Int,
        visibleLines: IntRange
    ): IntRange? {
        val normalizedVisibleLines = normalizeVisibleLines(lineCount, visibleLines) ?: return null
        val alignedStartLine = alignDown(
            value = normalizedVisibleLines.first,
            blockSize = VISIBLE_SNAPSHOT_BLOCK_LINES
        )
        val alignedEndExclusive = alignUpExclusive(
            value = normalizedVisibleLines.last + 1,
            blockSize = VISIBLE_SNAPSHOT_BLOCK_LINES
        ).coerceAtMost(lineCount)
        val alignedEndLine = (alignedEndExclusive - 1).coerceAtLeast(alignedStartLine)
        return alignedStartLine..alignedEndLine
    }

    private fun alignDown(value: Int, blockSize: Int): Int {
        if (blockSize <= 1) return value
        return (value / blockSize) * blockSize
    }

    private fun alignUpExclusive(value: Int, blockSize: Int): Int {
        if (blockSize <= 1) return value
        return ((value + blockSize - 1) / blockSize) * blockSize
    }

    private fun resolveBracketAnchorOffset(
        textBuffer: TextBuffer,
        cursorOffset: Int
    ): Int? {
        val atCursor = textBuffer.charAt(cursorOffset)
        val beforeCursor = textBuffer.charAt(cursorOffset - 1)
        return when {
            atCursor != null && atCursor.isOpenBracket() -> cursorOffset
            beforeCursor != null && beforeCursor.isCloseBracket() -> cursorOffset - 1
            atCursor != null && atCursor.isCloseBracket() -> cursorOffset
            beforeCursor != null && beforeCursor.isOpenBracket() -> cursorOffset - 1
            else -> null
        }
    }

    private fun normalizeVisibleLines(
        lineCount: Int,
        visibleLines: IntRange
    ): IntRange? {
        if (lineCount <= 0 || visibleLines.first > visibleLines.last) return null

        val maxLine = lineCount - 1
        val visibleStart = visibleLines.first.coerceIn(0, maxLine)
        val visibleEnd = visibleLines.last.coerceIn(visibleStart, maxLine)
        return visibleStart..visibleEnd
    }

    private fun shouldBuildVisibleSnapshotForAnchor(
        anchorLine: Int,
        visibleLines: IntRange
    ): Boolean = anchorLine in visibleLines

    private fun Char.isOpenBracket(): Boolean = this == '(' || this == '[' || this == '{'

    private fun Char.isCloseBracket(): Boolean = this == ')' || this == ']' || this == '}'

    private companion object {
        const val VISIBLE_SNAPSHOT_BLOCK_LINES = 16
        const val MAX_CACHED_VISIBLE_SNAPSHOTS = 2
        const val FALLBACK_VISIBLE_MARGIN_LINES = 32
        const val FALLBACK_VISIBLE_BLOCK_LINES = 16
        const val FALLBACK_SCAN_BLOCK_CHARS = 4_096
        const val MAX_CACHED_FALLBACK_SNAPSHOTS = 2
        const val MAX_SCAN_CHARS = 100_000
        const val NO_BRACKET_ANCHOR = -1
    }
}

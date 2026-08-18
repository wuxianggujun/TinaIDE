package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.HighlightLineSegment
import java.util.LinkedHashMap
import timber.log.Timber

// 直接复用 TreeSitter 的 HighlightLineSegment 作为渲染侧 cache 元素。
// 字段完全重合（startColumn / endColumn / type），多出的 priority 渲染侧不用但也无代价。
// 旧实现每帧 cache miss 都要 `segments.map { LineHighlightSegment(...) }`，再分配一整轮 ArrayList + data class 实例，
// 新实现直接把 `List<HighlightLineSegment>` 原样放进 cache。
internal typealias LineHighlightSegment = HighlightLineSegment

internal class TextRenderer {
    internal data class FrameCacheStats(
        val hits: Int,
        val misses: Int
    )

    private data class LineLookupResult(
        val text: String,
        val fromCache: Boolean
    )

    private data class CachedLine(
        val text: String
    )

    // 直接复用 TreeSitter 的 HighlightLineSegment（字段完全重合 + 多一个 priority），
    // 避免每帧 cache miss 对每个片段再 new 一个等价的副本。见文件底部 typealias。

    private data class LineSemanticSegment(
        val startColumn: Int,
        val endColumn: Int,
        val tokenType: SemanticTokenType,
        val tokenModifiers: Set<SemanticTokenModifier>
    )

    private data class VisibleHighlightCacheKey(
        val version: Long,
        val windowFirstLine: Int,
        val windowLastLine: Int,
        val highlightVersion: Long
    )

    private data class VisibleSemanticCacheKey(
        val version: Long,
        val windowFirstLine: Int,
        val windowLastLine: Int,
        val semanticTokensVersion: Long
    )

    private companion object {
        private const val DEFAULT_MAX_CACHE_SIZE = 512
        private val EMPTY_INLAY_HINT_COLUMNS = IntArray(0)

        // 预取窗口调到 200 行：与 IncrementalTreeSitterHighlightState 的 lineCache 预热配合，
        // 快速滚动时远距离行也能在到达可见区之前就命中缓存，消除"滚动追指"。
        private const val HIGHLIGHT_CACHE_MARGIN_LINES = 200
    }

    private val cacheLock = Any()
    private val lineCache = LinkedHashMap<Int, CachedLine>(256, 0.75f, true)
    private var cacheVersion: Long = -1L
    private var visibleHighlightCacheKey: VisibleHighlightCacheKey? = null

    // 复用同一个 HashMap：避免每帧 cache miss 都分配新 map 导致 GC stall。
    // 使失效路径只清空 key（不动 map 内容），避免非主线程失效时与主线程 paint 的迭代发生 CME。
    // 下一次 main-thread resolve 命中 key=null 会先 clear 再填充，paint 此时还未开始迭代新结果。
    private val visibleHighlightCache: HashMap<Int, List<LineHighlightSegment>> = HashMap(256)
    private var visibleSemanticCacheKey: VisibleSemanticCacheKey? = null

    // 对称复用：与 visibleHighlightCache 同样的策略，失效路径只清 key 不动 map 内容。
    private val visibleSemanticCache: HashMap<Int, List<LineSemanticSegment>> = HashMap(128)
    private val reusableSyntaxOverlays = ArrayList<TextRenderOverlay>(32)
    private val reusableSemanticOverlays = ArrayList<TextRenderOverlay>(16)
    private val rainbowBracketComputer = RainbowBracketComputer()
    private val foldBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val foldGlyphBounds = android.graphics.Rect()
    private var cachedBadgeTextSize = 0f
    var badgeMargin = 0f
        private set
    var badgeWidth = 0f
        private set

    fun ensureBadgeMetrics(textPaint: Paint) {
        if (textPaint.textSize != cachedBadgeTextSize) {
            cachedBadgeTextSize = textPaint.textSize
            badgeMargin = textPaint.textSize * 0.25f
            val hPad = textPaint.textSize * 0.35f
            badgeWidth = hPad + textPaint.measureText("\u2026") + hPad
        }
    }

    fun drawText(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textPaint: Paint,
        textStartX: Float,
        lineLayoutCache: EditorLineLayoutCache
    ): FrameCacheStats {
        val state = frameContext.state
        val textScanCache = frameContext.textScanCache
        val drawTextStartNs = System.nanoTime()
        ensureCacheVersion(frameContext.textVersion)
        val originalColor = textPaint.color
        var cacheHits = 0
        var cacheMisses = 0

        val scheme = state.colorScheme
        val visibleVisualLines = state.visibleLines
        val visibleDocLines = state.visibleDocumentLines
        val rainbowColors = scheme.rainbowBracketColors
        val rainbowEnabled = rainbowColors.isNotEmpty() &&
            rainbowBracketComputer.isEnabled(state.config, state.textBuffer.lineCount)
        val rainbowColorsArgb = if (rainbowEnabled) {
            IntArray(rainbowColors.size) { rainbowColors[it].toArgb() }
        } else {
            IntArray(0)
        }
        val visibleBracketInfos = if (rainbowEnabled) {
            rainbowBracketComputer.computeVisibleLineBrackets(
                frameContext = frameContext,
                visibleLines = visibleDocLines,
                config = state.config
            )
        } else {
            emptyMap()
        }
        val highlightPrepStartNs = System.nanoTime()
        val highlightSegmentsByLine = resolveDrawHighlightSegmentsForVisibleWindow(
            state = state,
            visibleLines = visibleDocLines
        )
        val semanticSegmentsByLine = resolveVisibleSemanticSegments(
            state = state,
            visibleLines = visibleDocLines
        )
        val highlightPrepMs = (System.nanoTime() - highlightPrepStartNs) / 1_000_000L

        drawScope.drawIntoCanvas { canvas ->
            ensureBadgeMetrics(textPaint)
            visibleVisualLines.forEach { visualLine ->
                val line = state.docLineForVisualLine(visualLine)
                if (line >= state.textBuffer.lineCount) return@forEach
                val visualStartColumn = state.visualLineStartColumn(visualLine)
                val visualEndColumn = state.visualLineEndColumn(visualLine).coerceAtLeast(visualStartColumn)
                val yTop = state.visualLineTopInViewport(visualLine)
                val baselineY = yTop + state.lineHeightPx * 0.78f
                val lookup = getOrCacheLineText(
                    state = state,
                    line = line
                )
                if (lookup.fromCache) {
                    cacheHits++
                } else {
                    cacheMisses++
                }

                val xPos = textStartX

                val segments = highlightSegmentsByLine[line].orEmpty()
                val semanticSegments = semanticSegmentsByLine[line].orEmpty()
                val tabColumns = textScanCache.getTabColumns(
                    line = line,
                    lineText = lookup.text,
                    textVersion = state.textBuffer.version
                )
                val containsTab = tabColumns.isNotEmpty()
                val foldEndLine = if (visualEndColumn >= lookup.text.length) {
                    state.collapsedFoldEndLine(line)
                } else {
                    -1
                }
                val hasInlayHints =
                    state.inlayHintsDocumentVersion == frameContext.textVersion &&
                        state.inlayHintsByLine[line].isNullOrEmpty().not()
                val needsPrefix = containsTab ||
                    segments.isNotEmpty() ||
                    semanticSegments.isNotEmpty() ||
                    rainbowEnabled ||
                    foldEndLine >= 0 ||
                    hasInlayHints
                val prefixLayout = if (needsPrefix) {
                    lineLayoutCache.getPrefixLayout(
                        state = state,
                        line = line,
                        lineText = lookup.text,
                        textVersion = state.textBuffer.version,
                        paint = textPaint,
                    )
                } else {
                    null
                }
                val prefix = prefixLayout?.prefix
                fun prefixAdvance(column: Int): Float {
                    if (prefixLayout == null || prefix == null) return 0f
                    val safeColumn = column.coerceIn(0, prefixLayout.length)
                    return prefix[safeColumn]
                }
                val segmentStartAdvance = if (prefixLayout == null) {
                    0f
                } else {
                    prefixLayout.segmentStartAdvance(visualStartColumn)
                }
                val baseX = xPos - segmentStartAdvance

                val defaultColor = scheme.syntax.defaultText.toArgb()

                val bracketInfos = visibleBracketInfos[line].orEmpty()

                val hasBrackets = bracketInfos.isNotEmpty()
                if (segments.isEmpty() && semanticSegments.isEmpty() && !hasBrackets) {
                    textPaint.color = defaultColor
                    if (containsTab) {
                        drawTextRangeWithTabStops(
                            canvas = canvas.nativeCanvas,
                            lineText = lookup.text,
                            startColumn = visualStartColumn,
                            endColumn = visualEndColumn,
                            textStartX = baseX,
                            baselineY = baselineY,
                            paint = textPaint,
                            prefixAdvance = ::prefixAdvance,
                            tabColumns = tabColumns,
                            inlayHintColumns = prefixLayout?.inlayHintColumns ?: EMPTY_INLAY_HINT_COLUMNS,
                        )
                    } else {
                        drawClampedTextRange(
                            canvas = canvas.nativeCanvas,
                            lineText = lookup.text,
                            startColumn = visualStartColumn,
                            endColumn = visualEndColumn,
                            fallbackX = xPos,
                            baselineY = baselineY,
                            paint = textPaint,
                            prefixAdvance = ::prefixAdvance,
                            segmentStartAdvance = segmentStartAdvance,
                            inlayHintColumns = prefixLayout?.inlayHintColumns ?: EMPTY_INLAY_HINT_COLUMNS,
                        )
                    }
                } else {
                    val syntaxOverlays = reusableSyntaxOverlays
                    syntaxOverlays.clear()
                    segments.forEach { segment ->
                        syntaxOverlays.add(
                            TextRenderOverlay(
                                startColumn = segment.startColumn,
                                endColumn = segment.endColumn,
                                color = scheme.syntax.colorOf(segment.type).toArgb()
                            )
                        )
                    }
                    val semOverlays = reusableSemanticOverlays
                    semOverlays.clear()
                    semanticSegments.forEach { segment ->
                        semOverlays.add(
                            TextRenderOverlay(
                                startColumn = segment.startColumn,
                                endColumn = segment.endColumn,
                                color = scheme.syntax.colorOfSemantic(
                                    tokenType = segment.tokenType,
                                    tokenModifiers = segment.tokenModifiers
                                ).toArgb()
                            )
                        )
                    }

                    if (hasBrackets) {
                        bracketInfos.forEach { bracket ->
                            val depth = bracket.depth
                            val colorIndex = depth % rainbowColors.size
                            semOverlays.add(
                                TextRenderOverlay(
                                    startColumn = bracket.column,
                                    endColumn = bracket.column + 1,
                                    color = rainbowColorsArgb[colorIndex]
                                )
                            )
                        }
                        semOverlays.sortWith(compareBy<TextRenderOverlay> { it.startColumn }.thenByDescending { it.endColumn - it.startColumn })
                    }

                    val renderRuns =
                        TextRenderPlanner.buildRuns(
                            visibleStartColumn = visualStartColumn,
                            visibleEndColumn = visualEndColumn,
                            defaultColor = defaultColor,
                            syntaxOverlays = syntaxOverlays,
                            semanticOverlays = semOverlays
                        )

                    renderRuns.forEach { run ->
                        if (run.endColumn <= run.startColumn) return@forEach
                        textPaint.color = run.color
                        if (containsTab) {
                            drawTextRangeWithTabStops(
                                canvas = canvas.nativeCanvas,
                                lineText = lookup.text,
                                startColumn = run.startColumn,
                                endColumn = run.endColumn,
                                textStartX = baseX,
                                baselineY = baselineY,
                                paint = textPaint,
                                prefixAdvance = ::prefixAdvance,
                                tabColumns = tabColumns,
                                inlayHintColumns = prefixLayout?.inlayHintColumns ?: EMPTY_INLAY_HINT_COLUMNS,
                            )
                        } else {
                            drawClampedTextRange(
                                canvas = canvas.nativeCanvas,
                                lineText = lookup.text,
                                startColumn = run.startColumn,
                                endColumn = run.endColumn,
                                fallbackX = xPos,
                                baselineY = baselineY,
                                paint = textPaint,
                                prefixAdvance = ::prefixAdvance,
                                segmentStartAdvance = segmentStartAdvance,
                                inlayHintColumns = prefixLayout?.inlayHintColumns ?: EMPTY_INLAY_HINT_COLUMNS,
                            )
                        }
                    }
                }

                if (foldEndLine >= 0) {
                    val trimmedEndColumn = frameContext.textScanCache.getWhitespaceInfo(
                        line = line,
                        lineText = lookup.text,
                        textVersion = frameContext.textVersion,
                        tabSize = state.config.tabSize
                    ).trailingWhitespaceStart
                    val contentTextEndX = if (prefixLayout == null || prefix == null) {
                        0f
                    } else {
                        prefix[trimmedEndColumn.coerceIn(0, prefixLayout.length)]
                    }
                    val screenX = xPos + contentTextEndX - segmentStartAdvance
                    val endLineText = if (foldEndLine < state.textBuffer.lineCount) {
                        val foldEndLineText = frameContext.lineText(foldEndLine)
                        val foldEndLineWhitespace = frameContext.textScanCache.getWhitespaceInfo(
                            line = foldEndLine,
                            lineText = foldEndLineText,
                            textVersion = frameContext.textVersion,
                            tabSize = state.config.tabSize
                        )
                        foldEndLineText.substring(
                            startIndex = foldEndLineWhitespace.leadingWhitespaceEnd.coerceIn(
                                0,
                                foldEndLineText.length
                            ),
                            endIndex = foldEndLineWhitespace.trailingWhitespaceStart.coerceIn(
                                foldEndLineWhitespace.leadingWhitespaceEnd,
                                foldEndLineText.length
                            )
                        )
                    } else {
                        ""
                    }
                    val foldBracketColor = if (rainbowEnabled) {
                        val lastOpen = bracketInfos.lastOrNull { it.isOpen }
                        if (lastOpen != null) {
                            rainbowColorsArgb[lastOpen.depth % rainbowColors.size]
                        } else {
                            defaultColor
                        }
                    } else {
                        defaultColor
                    }
                    drawFoldPlaceholder(
                        nativeCanvas = canvas.nativeCanvas,
                        screenX = screenX,
                        baselineY = baselineY,
                        lineTop = yTop,
                        lineHeightPx = state.lineHeightPx,
                        textPaint = textPaint,
                        scheme = scheme,
                        endLineText = endLineText,
                        endLineColor = foldBracketColor
                    )
                }
            }
        }

        trimCache(state, visibleDocLines)
        textPaint.color = originalColor

        val drawTextTotalMs = (System.nanoTime() - drawTextStartNs) / 1_000_000L
        if (drawTextTotalMs > 16L) {
            Timber.tag("EditorPerf").w(
                "drawText: %dms (highlight=%dms), hits=%d, misses=%d, lines=%s",
                drawTextTotalMs,
                highlightPrepMs,
                cacheHits,
                cacheMisses,
                visibleDocLines
            )
        }

        return FrameCacheStats(hits = cacheHits, misses = cacheMisses)
    }

    private fun drawFoldPlaceholder(
        nativeCanvas: android.graphics.Canvas,
        screenX: Float,
        baselineY: Float,
        lineTop: Float,
        lineHeightPx: Float,
        textPaint: Paint,
        scheme: EditorColorScheme,
        endLineText: String,
        endLineColor: Int
    ) {
        val vPad = lineHeightPx * 0.15f
        val bgLeft = screenX + badgeMargin
        val bgRight = bgLeft + badgeWidth
        val bgTop = lineTop + vPad
        val bgBottom = lineTop + lineHeightPx - vPad
        val cornerRadius = lineHeightPx * 0.15f

        foldBgPaint.color = scheme.foldPlaceholderBackground.toArgb()
        nativeCanvas.drawRoundRect(
            bgLeft,
            bgTop,
            bgRight,
            bgBottom,
            cornerRadius,
            cornerRadius,
            foldBgPaint
        )

        val savedColor = textPaint.color
        val savedAlign = textPaint.textAlign

        val centerX = (bgLeft + bgRight) / 2f
        val centerY = (bgTop + bgBottom) / 2f
        textPaint.getTextBounds("\u2026", 0, 1, foldGlyphBounds)
        val textY = centerY - (foldGlyphBounds.top + foldGlyphBounds.bottom) / 2f

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = scheme.foldPlaceholderForeground.toArgb()
        nativeCanvas.drawText("\u2026", centerX, textY, textPaint)
        textPaint.textAlign = savedAlign

        if (endLineText.isNotEmpty()) {
            textPaint.color = endLineColor
            nativeCanvas.drawText(endLineText, bgRight + badgeMargin, baselineY, textPaint)
        }

        textPaint.color = savedColor
    }

    private fun drawTextRangeWithTabStops(
        canvas: android.graphics.Canvas,
        lineText: String,
        startColumn: Int,
        endColumn: Int,
        textStartX: Float,
        baselineY: Float,
        paint: Paint,
        prefixAdvance: (Int) -> Float,
        tabColumns: IntArray,
        inlayHintColumns: IntArray,
    ) {
        if (startColumn >= endColumn) return
        val safeStart = startColumn.coerceAtLeast(0)
        val safeEnd = endColumn.coerceIn(safeStart, lineText.length)
        var segmentStart = safeStart
        for (inlayColumn in inlayHintColumns) {
            if (inlayColumn <= segmentStart) continue
            if (inlayColumn >= safeEnd) break
            drawTextRangeWithTabStopsSegment(
                canvas = canvas,
                lineText = lineText,
                startColumn = segmentStart,
                endColumn = inlayColumn,
                textStartX = textStartX,
                baselineY = baselineY,
                paint = paint,
                prefixAdvance = prefixAdvance,
                tabColumns = tabColumns,
            )
            segmentStart = inlayColumn
        }
        drawTextRangeWithTabStopsSegment(
            canvas = canvas,
            lineText = lineText,
            startColumn = segmentStart,
            endColumn = safeEnd,
            textStartX = textStartX,
            baselineY = baselineY,
            paint = paint,
            prefixAdvance = prefixAdvance,
            tabColumns = tabColumns,
        )
    }

    private fun drawTextRangeWithTabStopsSegment(
        canvas: android.graphics.Canvas,
        lineText: String,
        startColumn: Int,
        endColumn: Int,
        textStartX: Float,
        baselineY: Float,
        paint: Paint,
        prefixAdvance: (Int) -> Float,
        tabColumns: IntArray,
    ) {
        if (startColumn >= endColumn) return
        val safeStart = startColumn.coerceAtLeast(0)
        val safeEnd = endColumn.coerceIn(safeStart, lineText.length)
        var index = safeStart
        var tabIndex = tabColumns.binarySearch(safeStart)
        if (tabIndex < 0) {
            tabIndex = -tabIndex - 1
        }
        while (tabIndex < tabColumns.size) {
            val tabAt = tabColumns[tabIndex]
            if (tabAt >= safeEnd) break
            if (tabAt > index) {
                val x = textStartX + prefixAdvance(index)
                canvas.drawText(lineText, index, tabAt, x, baselineY, paint)
            }
            index = tabAt + 1
            tabIndex++
        }
        if (index < safeEnd) {
            val x = textStartX + prefixAdvance(index)
            canvas.drawText(lineText, index, safeEnd, x, baselineY, paint)
        }
    }

    private fun drawClampedTextRange(
        canvas: android.graphics.Canvas,
        lineText: String,
        startColumn: Int,
        endColumn: Int,
        fallbackX: Float,
        baselineY: Float,
        paint: Paint,
        prefixAdvance: (Int) -> Float,
        segmentStartAdvance: Float,
        inlayHintColumns: IntArray,
    ) {
        val safeRange = clampTextDrawRange(
            textLength = lineText.length,
            startColumn = startColumn,
            endColumn = endColumn
        ) ?: return
        if (
            safeRange.startColumn == 0 &&
            safeRange.endColumn == lineText.length &&
            inlayHintColumns.isEmpty()
        ) {
            canvas.drawText(lineText, fallbackX, baselineY, paint)
            return
        }
        var segmentStart = safeRange.startColumn
        for (inlayColumn in inlayHintColumns) {
            if (inlayColumn <= segmentStart) continue
            if (inlayColumn >= safeRange.endColumn) break
            drawTextSegment(
                canvas = canvas,
                lineText = lineText,
                startColumn = segmentStart,
                endColumn = inlayColumn,
                fallbackX = fallbackX,
                baselineY = baselineY,
                paint = paint,
                prefixAdvance = prefixAdvance,
                segmentStartAdvance = segmentStartAdvance,
            )
            segmentStart = inlayColumn
        }
        drawTextSegment(
            canvas = canvas,
            lineText = lineText,
            startColumn = segmentStart,
            endColumn = safeRange.endColumn,
            fallbackX = fallbackX,
            baselineY = baselineY,
            paint = paint,
            prefixAdvance = prefixAdvance,
            segmentStartAdvance = segmentStartAdvance,
        )
    }

    private fun drawTextSegment(
        canvas: android.graphics.Canvas,
        lineText: String,
        startColumn: Int,
        endColumn: Int,
        fallbackX: Float,
        baselineY: Float,
        paint: Paint,
        prefixAdvance: (Int) -> Float,
        segmentStartAdvance: Float,
    ) {
        if (endColumn <= startColumn) return
        val runX = fallbackX + (prefixAdvance(startColumn) - segmentStartAdvance)
        canvas.drawText(lineText, startColumn, endColumn, runX, baselineY, paint)
    }

    fun lineText(state: EditorState, line: Int): String {
        ensureCacheVersion(state.textBuffer.version)
        return getOrCacheLineText(state, line).text
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            lineCache.clear()
            cacheVersion = -1L
            visibleHighlightCacheKey = null
            visibleSemanticCacheKey = null
        }
    }

    fun applyTextChange(change: TextChange, currentVersion: Long) {
        val startLine = change.startLine.coerceAtLeast(0)
        val lineDelta = change.lineDelta
        val oldChangedEndLine = change.endLine.coerceAtLeast(startLine)
        val shiftFromLine = (change.endLine + 1).coerceAtLeast(0)
        val newChangedEndLine = maxOf(startLine, change.endLine + lineDelta)

        synchronized(cacheLock) {
            invalidateCacheRangeInternal(startLine, oldChangedEndLine)
            shiftCacheInternal(shiftFromLine, lineDelta)
            invalidateCacheRangeInternal(startLine, newChangedEndLine)
            cacheVersion = currentVersion
            visibleHighlightCacheKey = null
            visibleSemanticCacheKey = null
            trimCacheLocked(visibleLines = 0..-1, maxCacheSize = DEFAULT_MAX_CACHE_SIZE)
        }
    }

    fun cacheSize(): Int = synchronized(cacheLock) {
        lineCache.size
    }

    internal fun resolveDrawHighlightSegmentsForVisibleWindow(
        state: EditorState,
        visibleLines: IntRange
    ): Map<Int, List<LineHighlightSegment>> {
        val highlighter = state.highlighter ?: return emptyMap()
        if (visibleLines.isEmpty()) return emptyMap()

        // 把当前视口行告诉 highlighter —— bulk prewarm 据此按视口优先顺序填充缓存，
        // 保证"用户正在看的那一块"最先上色。写操作廉价（@Volatile Int），可以每帧调。
        highlighter.setViewportHint(visibleLines.first)

        val lineCount = state.textBuffer.lineCount
        if (lineCount <= 0) return emptyMap()
        val maxLine = lineCount - 1
        val windowFirstLine = (visibleLines.first - HIGHLIGHT_CACHE_MARGIN_LINES).coerceIn(0, maxLine)
        val windowLastLine = (visibleLines.last + HIGHLIGHT_CACHE_MARGIN_LINES).coerceIn(windowFirstLine, maxLine)

        synchronized(cacheLock) {
            val cachedKey = visibleHighlightCacheKey
            if (
                cachedKey != null &&
                cachedKey.version == state.textBuffer.version &&
                cachedKey.highlightVersion == state.highlightVersion &&
                visibleLines.first >= cachedKey.windowFirstLine &&
                visibleLines.last <= cachedKey.windowLastLine
            ) {
                return visibleHighlightCache
            }
        }

        val result = visibleHighlightCache
        synchronized(cacheLock) {
            result.clear()
            for (line in windowFirstLine..windowLastLine) {
                val segments = highlighter.getLineSegments(line)
                if (segments.isEmpty()) continue
                // HighlightLineSegment 是 immutable data class，直接复用 highlighter 返回的 List 引用即可，
                // 不需要再 `.map { LineHighlightSegment(...) }` 分配一轮等价副本。
                result[line] = segments
            }
            visibleHighlightCacheKey = VisibleHighlightCacheKey(
                version = state.textBuffer.version,
                windowFirstLine = windowFirstLine,
                windowLastLine = windowLastLine,
                highlightVersion = state.highlightVersion
            )
        }
        return result
    }

    private fun resolveVisibleSemanticSegments(
        state: EditorState,
        visibleLines: IntRange
    ): Map<Int, List<LineSemanticSegment>> {
        if (visibleLines.isEmpty()) return emptyMap()
        val semanticTokensByLine = state.semanticTokensByLine
        if (semanticTokensByLine.isEmpty()) return emptyMap()

        val maxLine = (state.textBuffer.lineCount - 1).coerceAtLeast(0)
        val windowFirstLine = (visibleLines.first - HIGHLIGHT_CACHE_MARGIN_LINES).coerceIn(0, maxLine)
        val windowLastLine = (visibleLines.last + HIGHLIGHT_CACHE_MARGIN_LINES).coerceIn(windowFirstLine, maxLine)

        synchronized(cacheLock) {
            val cachedKey = visibleSemanticCacheKey
            if (
                cachedKey != null &&
                cachedKey.version == state.textBuffer.version &&
                cachedKey.semanticTokensVersion == state.semanticTokensVersion &&
                visibleLines.first >= cachedKey.windowFirstLine &&
                visibleLines.last <= cachedKey.windowLastLine
            ) {
                return visibleSemanticCache
            }
        }

        val result = visibleSemanticCache
        synchronized(cacheLock) {
            result.clear()
            for (line in windowFirstLine..windowLastLine) {
                val tokens = semanticTokensByLine[line].orEmpty()
                if (tokens.isEmpty() || line >= state.textBuffer.lineCount) continue
                val lineText = lineText(state, line)
                val lineSegments = ArrayList<LineSemanticSegment>(tokens.size)
                tokens.forEach { token ->
                    val startColumn = token.startColumn.coerceIn(0, lineText.length)
                    val endColumn = (token.startColumn + token.length).coerceIn(startColumn, lineText.length)
                    if (endColumn <= startColumn) return@forEach
                    lineSegments.add(
                        LineSemanticSegment(
                            startColumn = startColumn,
                            endColumn = endColumn,
                            tokenType = token.tokenType,
                            tokenModifiers = token.tokenModifiers
                        )
                    )
                }
                if (lineSegments.isEmpty()) continue
                if (lineSegments.size > 1) {
                    lineSegments.sortWith(
                        compareBy<LineSemanticSegment> { it.startColumn }
                            .thenByDescending { it.endColumn - it.startColumn }
                    )
                }
                result[line] = lineSegments
            }
            visibleSemanticCacheKey = VisibleSemanticCacheKey(
                version = state.textBuffer.version,
                windowFirstLine = windowFirstLine,
                windowLastLine = windowLastLine,
                semanticTokensVersion = state.semanticTokensVersion
            )
        }
        return result
    }

    private fun invalidateCacheRangeInternal(startLine: Int, endLine: Int) {
        if (startLine > endLine) return
        for (line in startLine..endLine) {
            lineCache.remove(line)
        }
    }

    private fun shiftCacheInternal(fromLine: Int, delta: Int) {
        if (delta == 0) return
        val affectedEntries = lineCache.entries
            .filter { it.key >= fromLine }
            .map { it.key to it.value }
        affectedEntries.forEach { (line, _) ->
            lineCache.remove(line)
        }
        affectedEntries.forEach { (line, cached) ->
            val targetLine = line + delta
            if (targetLine >= 0) {
                lineCache[targetLine] = cached
            }
        }
    }

    private fun ensureCacheVersion(currentVersion: Long) {
        synchronized(cacheLock) {
            if (cacheVersion == currentVersion) return
            lineCache.clear()
            cacheVersion = currentVersion
            visibleHighlightCacheKey = null
            visibleSemanticCacheKey = null
        }
    }

    private fun getOrCacheLineText(
        state: EditorState,
        line: Int
    ): LineLookupResult {
        synchronized(cacheLock) {
            val cached = lineCache[line]
            if (cached != null) {
                return LineLookupResult(text = cached.text, fromCache = true)
            }
        }

        val text = state.textBuffer.getLine(line)

        synchronized(cacheLock) {
            val cached = lineCache[line]
            if (cached != null) {
                return LineLookupResult(text = cached.text, fromCache = true)
            }
            lineCache[line] = CachedLine(text = text)
        }
        return LineLookupResult(text = text, fromCache = false)
    }

    private fun trimCache(state: EditorState, visibleLines: IntRange) {
        synchronized(cacheLock) {
            trimCacheLocked(visibleLines, resolveMaxCacheSize(state))
        }
    }

    private fun trimCacheLocked(visibleLines: IntRange, maxCacheSize: Int) {
        if (lineCache.size <= maxCacheSize) return

        if (!visibleLines.isEmpty()) {
            val iterator = lineCache.entries.iterator()
            while (iterator.hasNext() && lineCache.size > maxCacheSize) {
                val entry = iterator.next()
                if (entry.key !in visibleLines) {
                    iterator.remove()
                }
            }
        }

        while (lineCache.size > maxCacheSize) {
            val eldestKey = lineCache.entries.firstOrNull()?.key ?: break
            lineCache.remove(eldestKey)
        }
    }

    private fun resolveMaxCacheSize(state: EditorState): Int = state.config.lineRenderCacheSize.coerceIn(128, 8192)
}

internal data class TextRenderOverlay(
    val startColumn: Int,
    val endColumn: Int,
    val color: Int
)

internal data class TextRenderRun(
    val startColumn: Int,
    val endColumn: Int,
    val color: Int
)

internal data class TextDrawRange(
    val startColumn: Int,
    val endColumn: Int
)

internal fun clampTextDrawRange(
    textLength: Int,
    startColumn: Int,
    endColumn: Int
): TextDrawRange? {
    val safeLength = textLength.coerceAtLeast(0)
    val safeStart = startColumn.coerceIn(0, safeLength)
    val safeEnd = endColumn.coerceIn(safeStart, safeLength)
    return if (safeEnd <= safeStart) {
        null
    } else {
        TextDrawRange(startColumn = safeStart, endColumn = safeEnd)
    }
}

internal object TextRenderPlanner {
    fun buildRuns(
        visibleStartColumn: Int,
        visibleEndColumn: Int,
        defaultColor: Int,
        syntaxOverlays: List<TextRenderOverlay>,
        semanticOverlays: List<TextRenderOverlay>
    ): List<TextRenderRun> {
        if (visibleEndColumn <= visibleStartColumn) return emptyList()

        val boundaries = IntArray(2 + syntaxOverlays.size * 2 + semanticOverlays.size * 2)
        var boundaryCount = 0
        boundaries[boundaryCount++] = visibleStartColumn
        boundaries[boundaryCount++] = visibleEndColumn

        boundaryCount = collectBoundaries(
            overlays = syntaxOverlays,
            visibleStartColumn = visibleStartColumn,
            visibleEndColumn = visibleEndColumn,
            boundaries = boundaries,
            currentCount = boundaryCount
        )
        boundaryCount = collectBoundaries(
            overlays = semanticOverlays,
            visibleStartColumn = visibleStartColumn,
            visibleEndColumn = visibleEndColumn,
            boundaries = boundaries,
            currentCount = boundaryCount
        )

        boundaries.sort(0, boundaryCount)
        var uniqueCount = 0
        for (index in 0 until boundaryCount) {
            val value = boundaries[index]
            if (uniqueCount == 0 || boundaries[uniqueCount - 1] != value) {
                boundaries[uniqueCount++] = value
            }
        }
        if (uniqueCount < 2) return emptyList()

        val runs = ArrayList<TextRenderRun>(uniqueCount - 1)
        var syntaxPointer = 0
        var semanticPointer = 0
        for (index in 0 until uniqueCount - 1) {
            val startColumn = boundaries[index]
            val endColumn = boundaries[index + 1]
            if (endColumn <= startColumn) continue

            val syntaxColor = resolveOverlayColor(
                overlays = syntaxOverlays,
                intervalStart = startColumn,
                intervalEnd = endColumn,
                startPointer = syntaxPointer
            )
            syntaxPointer = syntaxColor.nextPointer

            val semanticColor = resolveOverlayColor(
                overlays = semanticOverlays,
                intervalStart = startColumn,
                intervalEnd = endColumn,
                startPointer = semanticPointer
            )
            semanticPointer = semanticColor.nextPointer

            val resolvedColor = semanticColor.color ?: syntaxColor.color ?: defaultColor
            val lastRun = runs.lastOrNull()
            if (lastRun != null && lastRun.color == resolvedColor && lastRun.endColumn == startColumn) {
                runs[runs.lastIndex] = lastRun.copy(endColumn = endColumn)
            } else {
                runs.add(
                    TextRenderRun(
                        startColumn = startColumn,
                        endColumn = endColumn,
                        color = resolvedColor
                    )
                )
            }
        }
        return runs
    }

    private fun collectBoundaries(
        overlays: List<TextRenderOverlay>,
        visibleStartColumn: Int,
        visibleEndColumn: Int,
        boundaries: IntArray,
        currentCount: Int
    ): Int {
        var count = currentCount
        overlays.forEach { overlay ->
            val startColumn = overlay.startColumn.coerceIn(visibleStartColumn, visibleEndColumn)
            val endColumn = overlay.endColumn.coerceIn(startColumn, visibleEndColumn)
            if (endColumn <= startColumn) return@forEach
            boundaries[count++] = startColumn
            boundaries[count++] = endColumn
        }
        return count
    }

    private data class OverlayResolution(
        val color: Int?,
        val nextPointer: Int
    )

    private fun resolveOverlayColor(
        overlays: List<TextRenderOverlay>,
        intervalStart: Int,
        intervalEnd: Int,
        startPointer: Int
    ): OverlayResolution {
        var pointer = startPointer
        while (pointer < overlays.size && overlays[pointer].endColumn <= intervalStart) {
            pointer++
        }

        var resolvedColor: Int? = null
        var index = pointer
        while (index < overlays.size) {
            val overlay = overlays[index]
            if (overlay.startColumn >= intervalEnd) break
            if (overlay.endColumn > intervalStart) {
                resolvedColor = overlay.color
            }
            index++
        }
        return OverlayResolution(color = resolvedColor, nextPointer = pointer)
    }
}

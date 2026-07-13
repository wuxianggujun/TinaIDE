package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.wuxianggujun.tinaide.core.textengine.TextChange
import timber.log.Timber

internal class EditorRenderer(
    private val lineNumberRenderer: LineNumberRenderer,
    private val gutterRenderer: GutterRenderer,
    private val lineLayoutCache: EditorLineLayoutCache,
    private val dividerMarginLeftPx: Float,
    private val dividerMarginRightPx: Float,
    private val dividerWidthPx: Float
) : EditorRenderEngine {
    private companion object {
        private const val SLOW_RENDER_THRESHOLD_MS = 16L
        private const val SLOW_RENDER_LOG_INTERVAL_MS = 1200L
    }

    private val metricsLock = Any()
    private val textRenderer = TextRenderer()
    private val textScanCache = EditorTextScanCache()
    internal val sharedTextScanCache: EditorTextScanCache
        get() = textScanCache
    private val bracketSnapshotCache = EditorBracketSnapshotCache()
    private val selectionRenderer = SelectionRenderer()
    private val cursorRenderer = CursorRenderer()
    private val diagnosticRenderer = DiagnosticRenderer()
    private val bracketPairGuideRenderer = BracketPairGuideRenderer()
    private val matchingBracketRenderer = MatchingBracketHighlightRenderer()
    private val wordOccurrenceRenderer = WordOccurrenceHighlightRenderer()
    private val whitespaceRenderer = WhitespaceRenderer()
    private var lastSlowRenderLogAtMs: Long = 0L
    private var totalRenderedFrames: Long = 0L
    private var slowRenderedFrames: Long = 0L
    private var lastRenderDurationMs: Long = 0L
    private var lastVisibleLineCount: Int = 0
    private var lastFrameCacheHits: Int = 0
    private var lastFrameCacheMisses: Int = 0
    private var totalCacheHits: Long = 0L
    private var totalCacheMisses: Long = 0L

    private val reusableFontMetrics = Paint.FontMetrics()

    // 跨帧复用：避免每帧 new EditorRenderFrameContext + new lineTextProvider 捕获闭包。
    private val reusableFrameContext = EditorRenderFrameContext(textRenderer)
    private var cachedHitZonesTextSize = 0f
    private var cachedHitZonesLineCount = 0
    private var cachedHitZonesShowLineNumbers = false
    private var cachedHitZonesTypeface: Typeface? = null
    private var cachedHitZones: EditorHitZones? = null

    override fun render(
        drawScope: DrawScope,
        state: EditorState,
        textPaint: Paint,
        lineNumberPaint: Paint
    ) {
        val startNs = System.nanoTime()
        val scheme = state.colorScheme
        val zones = hitZones(state, lineNumberPaint)
        val setupMs = (System.nanoTime() - startNs) / 1_000_000L
        val lineNumberWidth = zones.lineNumberEndX
        val gutterWidth = zones.gutterEndX - zones.lineNumberEndX
        val textStartX = zones.textStartX
        var textStats = TextRenderer.FrameCacheStats(hits = 0, misses = 0)
        val pinLineNumber = state.pinLineNumber

        drawScope.drawRect(
            color = scheme.background,
            topLeft = Offset.Zero,
            size = drawScope.size
        )

        val frameContext = frameContext(state)
        if (pinLineNumber) {
            if (lineNumberWidth > 0f) {
                drawScope.drawRect(
                    color = scheme.lineNumberBackground,
                    topLeft = Offset.Zero,
                    size = Size(lineNumberWidth, drawScope.size.height)
                )
            }
            drawScope.drawRect(
                color = scheme.gutterBackground,
                topLeft = Offset(lineNumberWidth, 0f),
                size = Size(gutterWidth, drawScope.size.height)
            )
            lineNumberRenderer.draw(drawScope, state, lineNumberPaint, lineNumberWidth)
            gutterRenderer.draw(drawScope, state, lineNumberWidth)
            drawScope.drawLine(
                color = scheme.gutterDivider,
                start = Offset(lineNumberWidth + gutterWidth, 0f),
                end = Offset(lineNumberWidth + gutterWidth, drawScope.size.height),
                strokeWidth = dividerWidthPx
            )

            drawScope.clipRect(
                left = textStartX,
                top = 0f,
                right = drawScope.size.width,
                bottom = drawScope.size.height
            ) {
                translate(left = -state.scrollOffsetXPx) {
                    selectionRenderer.drawCurrentLineHighlight(this, frameContext, textStartX)
                    wordOccurrenceRenderer.drawHighlights(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    bracketPairGuideRenderer.drawGuides(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    selectionRenderer.drawSelection(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    textStats = textRenderer.drawText(
                        drawScope = this,
                        frameContext = frameContext,
                        textPaint = textPaint,
                        textStartX = textStartX,
                        lineLayoutCache = lineLayoutCache
                    )
                    whitespaceRenderer.drawWhitespace(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    matchingBracketRenderer.drawHighlights(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    diagnosticRenderer.drawDiagnostics(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    selectionRenderer.drawSelectionHandles(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                }
            }
        } else {
            drawScope.clipRect(
                left = 0f,
                top = 0f,
                right = drawScope.size.width,
                bottom = drawScope.size.height
            ) {
                translate(left = -state.scrollOffsetXPx) {
                    if (lineNumberWidth > 0f) {
                        drawRect(
                            color = scheme.lineNumberBackground,
                            topLeft = Offset.Zero,
                            size = Size(lineNumberWidth, size.height)
                        )
                    }
                    drawRect(
                        color = scheme.gutterBackground,
                        topLeft = Offset(lineNumberWidth, 0f),
                        size = Size(gutterWidth, size.height)
                    )
                    lineNumberRenderer.draw(this, state, lineNumberPaint, lineNumberWidth)
                    gutterRenderer.draw(this, state, lineNumberWidth)
                    drawLine(
                        color = scheme.gutterDivider,
                        start = Offset(lineNumberWidth + gutterWidth, 0f),
                        end = Offset(lineNumberWidth + gutterWidth, size.height),
                        strokeWidth = dividerWidthPx
                    )

                    selectionRenderer.drawCurrentLineHighlight(this, frameContext, textStartX)
                    wordOccurrenceRenderer.drawHighlights(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    bracketPairGuideRenderer.drawGuides(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    selectionRenderer.drawSelection(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    textStats = textRenderer.drawText(
                        drawScope = this,
                        frameContext = frameContext,
                        textPaint = textPaint,
                        textStartX = textStartX,
                        lineLayoutCache = lineLayoutCache
                    )
                    whitespaceRenderer.drawWhitespace(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    matchingBracketRenderer.drawHighlights(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    diagnosticRenderer.drawDiagnostics(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                    selectionRenderer.drawSelectionHandles(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache
                    )
                }
            }
        }

        val totalMs = (System.nanoTime() - startNs) / 1_000_000L
        if (totalMs > 16L) {
            Timber.tag("EditorPerf").w(
                "render breakdown: total=%dms, setup=%dms",
                totalMs,
                setupMs
            )
        }
        recordRenderMetrics(
            durationMs = totalMs,
            visibleLineCount = state.visibleLines.count(),
            frameStats = textStats
        )
    }

    override fun renderCursorOverlay(
        drawScope: DrawScope,
        state: EditorState,
        textPaint: Paint,
        lineNumberPaint: Paint
    ) {
        if (!state.isFocused) return
        val zones = hitZones(state, lineNumberPaint)
        val textStartX = zones.textStartX
        val frameContext = frameContext(state)

        val cursorLine = state.cursorPosition.line
        val foldEndLineInfo = if (state.isFoldEndLineVirtuallyVisible(cursorLine)) {
            val ownerStart = state.foldOwnerForEndLine(cursorLine)
            if (ownerStart >= 0) {
                textRenderer.ensureBadgeMetrics(textPaint)
                val startLineText = frameContext.lineText(ownerStart)
                val endLineText = frameContext.lineText(cursorLine)
                val startLineWhitespace = frameContext.textScanCache.getWhitespaceInfo(
                    line = ownerStart,
                    lineText = startLineText,
                    textVersion = frameContext.textVersion,
                    tabSize = state.config.tabSize
                )
                val endLineWhitespace = frameContext.textScanCache.getWhitespaceInfo(
                    line = cursorLine,
                    lineText = endLineText,
                    textVersion = frameContext.textVersion,
                    tabSize = state.config.tabSize
                )
                FoldEndLineCursorInfo(
                    foldStartLine = ownerStart,
                    foldStartLineText = startLineText,
                    foldStartLineTrimmedEndCol = startLineWhitespace.trailingWhitespaceStart,
                    endLineTrimStartCol = endLineWhitespace.leadingWhitespaceEnd,
                    badgeMargin = textRenderer.badgeMargin,
                    badgeWidth = textRenderer.badgeWidth
                )
            } else {
                null
            }
        } else {
            null
        }

        val clipLeft = if (state.pinLineNumber) textStartX else 0f
        drawScope.clipRect(
            left = clipLeft,
            top = 0f,
            right = drawScope.size.width,
            bottom = drawScope.size.height
        ) {
            translate(left = -state.scrollOffsetXPx) {
                if (state.cursorBlinkVisible) {
                    cursorRenderer.drawCursor(
                        drawScope = this,
                        frameContext = frameContext,
                        textStartX = textStartX,
                        textPaint = textPaint,
                        lineLayoutCache = lineLayoutCache,
                        foldEndLineInfo = foldEndLineInfo
                    )
                }
                cursorRenderer.drawCursorHandle(
                    drawScope = this,
                    frameContext = frameContext,
                    textStartX = textStartX,
                    textPaint = textPaint,
                    lineLayoutCache = lineLayoutCache,
                    foldEndLineInfo = foldEndLineInfo
                )
            }
        }
    }

    override fun contentStartX(state: EditorState, lineNumberPaint: Paint): Float = hitZones(state, lineNumberPaint).textStartX

    override fun hitZones(state: EditorState, lineNumberPaint: Paint): EditorHitZones {
        val textSize = lineNumberPaint.textSize
        val lineCount = state.textBuffer.lineCount
        val showLineNumbers = state.config.showLineNumbers
        val cached = cachedHitZones
        if (cached != null &&
            textSize == cachedHitZonesTextSize &&
            lineCount == cachedHitZonesLineCount &&
            showLineNumbers == cachedHitZonesShowLineNumbers &&
            lineNumberPaint.typeface === cachedHitZonesTypeface
        ) {
            return cached
        }

        val lineNumberWidth = lineNumberRenderer.calculateWidth(
            lineCount = lineCount,
            enabled = showLineNumbers,
            paint = lineNumberPaint
        )
        lineNumberPaint.getFontMetrics(reusableFontMetrics)
        val lineHeightPx = (reusableFontMetrics.descent - reusableFontMetrics.ascent + reusableFontMetrics.leading).coerceAtLeast(1f)
        val gutterEndX = lineNumberWidth + gutterRenderer.widthForLineHeight(lineHeightPx)
        // 对齐 Sora 官方 CodeEditor#measureTextRegionOffset()：
        // 行号宽度 + 侧边区域宽度 + divider 左右边距 + divider 宽度。
        val textStartX = gutterEndX + dividerMarginLeftPx + dividerWidthPx + dividerMarginRightPx
        val result = EditorHitZones(
            lineNumberEndX = lineNumberWidth,
            gutterEndX = gutterEndX,
            textStartX = textStartX
        )
        cachedHitZonesTextSize = textSize
        cachedHitZonesLineCount = lineCount
        cachedHitZonesShowLineNumbers = showLineNumbers
        cachedHitZonesTypeface = lineNumberPaint.typeface
        cachedHitZones = result
        return result
    }

    override fun isFoldBadgeHit(docLine: Int, contentX: Float, state: EditorState, textPaint: Paint): Boolean {
        textRenderer.ensureBadgeMetrics(textPaint)
        val lineText = textRenderer.lineText(state, docLine)
        val trimmedEndColumn = textScanCache.getWhitespaceInfo(
            line = docLine,
            lineText = lineText,
            textVersion = state.textBuffer.version,
            tabSize = state.config.tabSize
        ).trailingWhitespaceStart
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            line = docLine,
            lineText = lineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
            tabSize = state.config.tabSize
        )
        val textEndX = prefixLayout.prefix[trimmedEndColumn.coerceIn(0, prefixLayout.length)]
        val badgeLeft = textEndX + textRenderer.badgeMargin
        return contentX >= badgeLeft && contentX <= badgeLeft + textRenderer.badgeWidth
    }

    override fun resolveFoldEndLineOffset(
        foldStartLine: Int,
        contentX: Float,
        state: EditorState,
        textPaint: Paint
    ): Int {
        textRenderer.ensureBadgeMetrics(textPaint)
        val startLineText = textRenderer.lineText(state, foldStartLine)
        val trimmedEndCol = textScanCache.getWhitespaceInfo(
            line = foldStartLine,
            lineText = startLineText,
            textVersion = state.textBuffer.version,
            tabSize = state.config.tabSize
        ).trailingWhitespaceStart
        val startPrefixLayout = lineLayoutCache.getPrefixLayout(
            line = foldStartLine,
            lineText = startLineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
            tabSize = state.config.tabSize
        )
        val safeTrimmedEndCol = trimmedEndCol.coerceIn(0, startPrefixLayout.length)
        val startLineEndX = startPrefixLayout.prefix[safeTrimmedEndCol]
        val endLineTextStartX = startLineEndX +
            textRenderer.badgeMargin + textRenderer.badgeWidth + textRenderer.badgeMargin
        if (contentX < endLineTextStartX) return -1

        val endLine = state.collapsedFoldEndLine(foldStartLine)
        if (endLine < 0 || endLine >= state.textBuffer.lineCount) return -1

        val endLineText = textRenderer.lineText(state, endLine)
        val trimStartCol = textScanCache.getWhitespaceInfo(
            line = endLine,
            lineText = endLineText,
            textVersion = state.textBuffer.version,
            tabSize = state.config.tabSize
        ).leadingWhitespaceEnd
        val endPrefixLayout = lineLayoutCache.getPrefixLayout(
            line = endLine,
            lineText = endLineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
            tabSize = state.config.tabSize
        )
        val safeTrimStartCol = trimStartCol.coerceIn(0, endPrefixLayout.length)
        val trimStartX = endPrefixLayout.prefix[safeTrimStartCol]
        val endLineColumn = lineLayoutCache.xToColumn(
            layout = endPrefixLayout,
            contentX = trimStartX + (contentX - endLineTextStartX),
        ).coerceIn(trimStartCol, endLineText.length)

        return state.textBuffer.positionToOffset(endLine, endLineColumn)
    }

    override fun performanceSnapshot(): EditorRenderPerformanceSnapshot {
        val metrics = synchronized(metricsLock) {
            PerformanceMetrics(
                totalRenderedFrames = totalRenderedFrames,
                slowRenderedFrames = slowRenderedFrames,
                lastRenderDurationMs = lastRenderDurationMs,
                lastVisibleLineCount = lastVisibleLineCount,
                lastFrameCacheHits = lastFrameCacheHits,
                lastFrameCacheMisses = lastFrameCacheMisses,
                totalCacheHits = totalCacheHits,
                totalCacheMisses = totalCacheMisses
            )
        }
        return EditorRenderPerformanceSnapshot(
            totalRenderedFrames = metrics.totalRenderedFrames,
            slowRenderedFrames = metrics.slowRenderedFrames,
            lastRenderDurationMs = metrics.lastRenderDurationMs,
            lastVisibleLineCount = metrics.lastVisibleLineCount,
            lastFrameCacheHits = metrics.lastFrameCacheHits,
            lastFrameCacheMisses = metrics.lastFrameCacheMisses,
            totalCacheHits = metrics.totalCacheHits,
            totalCacheMisses = metrics.totalCacheMisses,
            totalCacheHitRatePercent = cacheHitRatePercent(
                totalHits = metrics.totalCacheHits,
                totalMisses = metrics.totalCacheMisses
            ),
            textLineCacheSize = textRenderer.cacheSize(),
            textScanCacheSize = textScanCache.cacheSize(),
            lineLayoutCacheEntryCount = lineLayoutCache.entryCount(),
            lineLayoutCacheFloatCount = lineLayoutCache.cachedFloatCount()
        )
    }

    override fun invalidateCache() {
        textRenderer.invalidateCache()
        textScanCache.invalidateAll()
        lineLayoutCache.invalidateAll()
        bracketSnapshotCache.invalidate()
    }

    override fun applyTextChange(change: TextChange, currentVersion: Long, currentLineCount: Int) {
        textRenderer.applyTextChange(change, currentVersion)
        textScanCache.applyTextChange(change, currentVersion)
        lineLayoutCache.applyTextChange(change, currentVersion)
        bracketSnapshotCache.applyTextChange(change, currentVersion, currentLineCount)
    }

    private fun frameContext(state: EditorState): EditorRenderFrameContext {
        reusableFrameContext.prepare(
            state = state,
            textVersion = state.textBuffer.version,
            textScanCache = textScanCache,
            bracketSnapshotCache = bracketSnapshotCache
        )
        return reusableFrameContext
    }

    internal fun recordRenderMetrics(
        durationMs: Long,
        visibleLineCount: Int,
        frameStats: TextRenderer.FrameCacheStats
    ) {
        synchronized(metricsLock) {
            totalRenderedFrames += 1L
            lastRenderDurationMs = durationMs
            lastVisibleLineCount = visibleLineCount
            lastFrameCacheHits = frameStats.hits
            lastFrameCacheMisses = frameStats.misses
            totalCacheHits += frameStats.hits.toLong()
            totalCacheMisses += frameStats.misses.toLong()
            if (durationMs > SLOW_RENDER_THRESHOLD_MS) {
                slowRenderedFrames += 1L
            }
        }

        if (durationMs <= SLOW_RENDER_THRESHOLD_MS) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSlowRenderLogAtMs < SLOW_RENDER_LOG_INTERVAL_MS) return
        lastSlowRenderLogAtMs = now

        val (hitRatePercent, cacheSize) = synchronized(metricsLock) {
            cacheHitRatePercent(
                totalHits = totalCacheHits,
                totalMisses = totalCacheMisses
            ) to textRenderer.cacheSize()
        }
        Timber.tag("EditorPerf").w(
            "Slow render: %dms, visibleLines=%d, frameCacheHit=%d/%d, totalHitRate=%.1f%%, cacheSize=%d",
            durationMs,
            visibleLineCount,
            frameStats.hits,
            frameStats.hits + frameStats.misses,
            hitRatePercent,
            cacheSize
        )
    }

    private fun cacheHitRatePercent(totalHits: Long, totalMisses: Long): Double {
        val totalLookups = totalHits + totalMisses
        if (totalLookups <= 0L) return 100.0
        return totalHits.toDouble() * 100.0 / totalLookups.toDouble()
    }

    private data class PerformanceMetrics(
        val totalRenderedFrames: Long,
        val slowRenderedFrames: Long,
        val lastRenderDurationMs: Long,
        val lastVisibleLineCount: Int,
        val lastFrameCacheHits: Int,
        val lastFrameCacheMisses: Int,
        val totalCacheHits: Long,
        val totalCacheMisses: Long
    )
}

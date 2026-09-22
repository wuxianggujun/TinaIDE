package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WordOccurrenceHighlightRendererTest {

    @Test
    fun resolveHighlightRects_shouldMatchColumnToXBaselineForVisibleMatches() {
        val buffer = RopeTextBuffer().apply { insert(0, "foo bar foo") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 240f,
                contentStartXPx = 24f
            )
            cursorOffset = buffer.positionToOffset(0, 1)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = state.fontSizeSp
        }
        val lineLayoutCache = EditorLineLayoutCache()
        val frameContext = EditorRenderFrameContext(
            state = state,
            textVersion = buffer.version,
            textScanCache = EditorTextScanCache(),
            bracketSnapshotCache = EditorBracketSnapshotCache(),
            lineTextProvider = buffer::getLine
        )
        val renderer = WordOccurrenceHighlightRenderer()

        val rects = renderer.resolveHighlightRects(
            frameContext = frameContext,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        )

        assertThat(rects).hasSize(2)
        val lineText = buffer.getLine(0)
        val textVersion = buffer.version
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = textVersion,
            paint = textPaint,
            tabSize = state.config.tabSize
        )
        val prefix = prefixLayout.prefix
        val firstStart = prefix[0]
        val firstEnd = prefix[3]
        val secondStart = prefix[8]
        val secondEnd = prefix[11]

        assertThat(rects[0].left).isWithin(0.01f).of(24f + firstStart)
        assertThat(rects[0].width).isWithin(0.01f).of(firstEnd - firstStart)
        assertThat(rects[1].left).isWithin(0.01f).of(24f + secondStart)
        assertThat(rects[1].width).isWithin(0.01f).of(secondEnd - secondStart)
        assertThat(rects[1].left).isGreaterThan(rects[0].left)
    }

    @Test
    fun resolveHighlightRects_shouldReuseCursorLineTextForVisibleScan() {
        val buffer = RopeTextBuffer().apply { insert(0, "foo bar foo") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 240f,
                contentStartXPx = 24f
            )
            cursorOffset = buffer.positionToOffset(0, 1)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = state.fontSizeSp
        }
        val lineLayoutCache = EditorLineLayoutCache()
        val lineTextCalls = linkedMapOf<Int, Int>()
        val frameContext = EditorRenderFrameContext(
            state = state,
            textVersion = buffer.version,
            textScanCache = EditorTextScanCache(),
            bracketSnapshotCache = EditorBracketSnapshotCache(),
            lineTextProvider = { line ->
                lineTextCalls[line] = lineTextCalls.getOrDefault(line, 0) + 1
                buffer.getLine(line)
            }
        )
        val renderer = WordOccurrenceHighlightRenderer()

        val rects = renderer.resolveHighlightRects(
            frameContext = frameContext,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        )

        assertThat(rects).hasSize(2)
        assertThat(lineTextCalls[0]).isEqualTo(1)
    }

    @Test
    fun resolveHighlightRects_shouldAvoidOffsetToPositionWhenReadingCursorWord() {
        val delegate = RopeTextBuffer().apply { insert(0, "foo bar foo") }
        val buffer = CountingTextBuffer(delegate)
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 240f,
                contentStartXPx = 24f
            )
            cursorOffset = buffer.positionToOffset(0, 1)
        }
        state.cursorPosition
        buffer.resetCounters()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = state.fontSizeSp
        }
        val lineLayoutCache = EditorLineLayoutCache()
        val frameContext = EditorRenderFrameContext(
            state = state,
            textVersion = buffer.version,
            textScanCache = EditorTextScanCache(),
            bracketSnapshotCache = EditorBracketSnapshotCache(),
            lineTextProvider = buffer::getLine
        )
        val renderer = WordOccurrenceHighlightRenderer()

        val rects = renderer.resolveHighlightRects(
            frameContext = frameContext,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache
        )

        assertThat(rects).hasSize(2)
        assertThat(buffer.offsetToPositionCalls).isEqualTo(0)
    }

    @Test
    fun wrappedWords_splitAcrossRowsAndClipToScrolledViewport() {
        val buffer = RopeTextBuffer("abcd abcd")
        val state = EditorState(buffer, config = EditorConfig(wordWrap = true, codeFolding = false)).apply {
            updateMetrics(20f, 1f, 40f, 2f, 0f)
            cursorOffset = 1
        }
        var reads = 0
        val frame = EditorRenderFrameContext(state, buffer.version, EditorTextScanCache(), EditorBracketSnapshotCache()) {
            reads++
            buffer.getLine(it)
        }
        val renderer = WordOccurrenceHighlightRenderer()
        val cache = EditorLineLayoutCache()
        val paint = Paint().apply { textSize = 14f }
        val all = renderer.resolveHighlightRects(frame, 10f, paint, cache)
        assertThat(all.map { it.top }).containsExactly(0f, 20f, 40f, 60f, 80f).inOrder()
        assertThat(all.map { it.left }.minOrNull()).isAtLeast(10f)
        assertThat(reads).isEqualTo(1)

        state.scrollBy(40f)
        val scrolled = renderer.resolveHighlightRects(frame, 10f, paint, cache)
        assertThat(scrolled.map { it.top }).containsExactly(0f, 20f, 40f).inOrder()
        assertThat(reads).isEqualTo(2)
        val prefix = cache.getPrefixLayout(state, 0, buffer.getLine(0), buffer.version, paint)
        assertThat(scrolled[0].left).isWithin(0.01f)
            .of(10f + prefix.textStartAdvance(5) - prefix.segmentStartAdvance(4))
    }

    @Test
    fun foldedGap_limitsTraversalToVisibleRows() {
        val buffer = CountingTextBuffer(RopeTextBuffer((0 until 5_000).joinToString("\n") { "foo bar" }))
        val state = EditorState(buffer, config = EditorConfig(wordWrap = false, codeFolding = true)).apply {
            updateMetrics(20f, 1f, 120f, 100f, 0f)
            setFoldRegions(listOf(FoldRegion(100, 4_900)), buffer.version)
            toggleFoldAtLine(100)
            cursorOffset = buffer.positionToOffset(95, 1)
            scrollToLine(95)
        }
        val requested = mutableListOf<Int>()
        val frame = EditorRenderFrameContext(state, buffer.version, EditorTextScanCache(), EditorBracketSnapshotCache()) {
            requested.add(it)
            buffer.getLine(it)
        }
        buffer.resetCounters()
        val rects = WordOccurrenceHighlightRenderer().resolveHighlightRects(frame, 10f, Paint(), EditorLineLayoutCache())
        assertThat(rects).hasSize(9)
        assertThat(requested).containsExactlyElementsIn((95..100) + (4_901..4_903)).inOrder()
        assertThat(buffer.lineCountReads).isLessThan(1_000)
    }

    private class CountingTextBuffer(
        private val delegate: RopeTextBuffer
    ) : TextBuffer by delegate {
        var offsetToPositionCalls: Int = 0
            private set
        var lineCountReads: Int = 0
            private set

        override val lineCount: Int get() {
            lineCountReads++
            return delegate.lineCount
        }

        override fun offsetToPosition(offset: Int): Position {
            offsetToPositionCalls++
            return delegate.offsetToPosition(offset)
        }

        fun resetCounters() {
            offsetToPositionCalls = 0
            lineCountReads = 0
        }
    }
}

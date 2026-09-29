package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.treesitter.HighlightLineSegment
import com.wuxianggujun.tinaide.core.treesitter.HighlightSpan
import com.wuxianggujun.tinaide.core.treesitter.HighlightType
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TextRendererCacheTest {

    @Test
    fun repeatedDrawsAndWrapWidthChanges_reuseFullLineColorPlan() {
        val buffer = RopeTextBuffer("abcdefghijklmnopqrst")
        val state = EditorState(buffer, config = EditorConfig(wordWrap = true, rainbowBrackets = false))
        state.highlighter = CountingSyntaxHighlighter()
        state.updateMetrics(20f, 10f, 240f, 40f, 0f)
        val renderer = TextRenderer()
        val frame = EditorRenderFrameContext(renderer).apply {
            prepare(state, buffer.version, EditorTextScanCache(), EditorBracketSnapshotCache())
        }
        val layoutCache = EditorLineLayoutCache()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f }
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        val scope = CanvasDrawScope()
        try {
            repeat(120) {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(240f, 240f)) {
                    renderer.drawText(this, frame, paint, 0f, layoutCache)
                }
            }
            assertThat(renderer.renderPlanCacheStats().builds).isEqualTo(1)
            assertThat(renderer.renderPlanCacheStats().hits).isAtLeast(119)
            state.updateMetrics(20f, 10f, 240f, 60f, 0f)
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(240f, 240f)) {
                renderer.drawText(this, frame, paint, 0f, layoutCache)
            }
            assertThat(renderer.renderPlanCacheStats().builds).isEqualTo(1)
            renderer.invalidateCache()
            assertThat(renderer.renderPlanCacheStats().entries).isEqualTo(0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun foldedWindow_shouldSkipHiddenLinesWithoutExpandingSynchronousRequests() {
        val buffer = RopeTextBuffer((0 until 10_000).joinToString("\n") { "line$it" })
        val state = EditorState(buffer, config = EditorConfig(wordWrap = false)).apply {
            setFoldRegions(listOf(FoldRegion(100, 9_900)), buffer.version)
            toggleFoldAtLine(100)
        }
        val highlighter = CountingSyntaxHighlighter()
        state.highlighter = highlighter

        TextRenderer().resolveDrawHighlightSegmentsForVisibleWindow(state, 90..9_910)

        assertThat(highlighter.requestedLines)
            .containsExactlyElementsIn((58..100) + (9_901..9_942)).inOrder()
    }

    @Test
    fun unfolding_shouldInvalidateWindowEvenWithoutTextOrHighlightChanges() {
        val buffer = RopeTextBuffer((0 until 300).joinToString("\n") { "line$it" })
        val state = EditorState(buffer, config = EditorConfig(wordWrap = false)).apply {
            setFoldRegions(listOf(FoldRegion(100, 200)), buffer.version)
            toggleFoldAtLine(100)
        }
        val highlighter = CountingSyntaxHighlighter()
        state.highlighter = highlighter
        val renderer = TextRenderer()
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 90..110)
        assertThat(highlighter.requestedLines).doesNotContain(101)

        state.toggleFoldAtLine(100)
        highlighter.requestedLines.clear()
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 90..110)

        assertThat(highlighter.requestedLines).containsExactlyElementsIn(58..142).inOrder()
    }

    @Test
    fun asyncHighlightUpdate_shouldReplaceCachedEmptyWindow() {
        val state = EditorState(RopeTextBuffer("text"))
        val highlighter = CountingSyntaxHighlighter().apply { ready = false }
        state.highlighter = highlighter
        highlighter.setOnStateUpdated(state::notifyHighlightChanged)
        val renderer = TextRenderer()
        assertThat(renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0)).isEmpty()

        highlighter.ready = true
        highlighter.notifyReady()

        assertThat(renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0).keys).containsExactly(0)
    }

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldBoundSynchronousLookups() {
        val state = EditorState(RopeTextBuffer((0 until 10_000).joinToString("\n") { "line$it" }))
        val highlighter = CountingSyntaxHighlighter()
        state.highlighter = highlighter

        TextRenderer().resolveDrawHighlightSegmentsForVisibleWindow(state, 5_000..5_020)

        assertThat(highlighter.requestedLines).containsExactlyElementsIn(4_968..5_052).inOrder()
    }

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldRefreshWhenHighlighterChanges() {
        val state = EditorState(RopeTextBuffer("text"))
        val first = CountingSyntaxHighlighter()
        val second = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = first
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0)

        state.highlighter = second
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 0..0)

        assertThat(second.requestedLines).containsExactly(0)
    }

    @Test
    fun resolveDrawHighlightSegmentsForVisibleWindow_shouldReuseCachedWindowUntilHighlightVersionChanges() {
        val buffer = RopeTextBuffer().apply {
            insert(0, (0..80).joinToString("\n") { "line$it" })
        }
        val state = EditorState(buffer)
        val highlighter = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = highlighter

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 10..12)
        val firstPassCalls = highlighter.requestedLines.size

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 11..12)

        assertThat(highlighter.requestedLines.size).isEqualTo(firstPassCalls)

        state.notifyHighlightChanged()
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 11..12)

        assertThat(highlighter.requestedLines.size).isGreaterThan(firstPassCalls)
    }

    @Test
    fun applyTextChange_shouldInvalidateVisibleHighlightCache() {
        val buffer = RopeTextBuffer().apply {
            insert(0, (0..40).joinToString("\n") { "line$it" })
        }
        val state = EditorState(buffer)
        val highlighter = CountingSyntaxHighlighter()
        val renderer = TextRenderer()
        state.highlighter = highlighter

        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 5..8)
        val firstPassCalls = highlighter.requestedLines.size

        renderer.applyTextChange(
            change = TextChange(
                startOffset = 0,
                endOffset = 0,
                oldText = "",
                newText = "/",
                startLine = 0,
                startColumn = 0,
                endLine = 0,
                endColumn = 0
            ),
            currentVersion = state.textBuffer.version
        )
        renderer.resolveDrawHighlightSegmentsForVisibleWindow(state, 5..8)

        assertThat(highlighter.requestedLines.size).isGreaterThan(firstPassCalls)
    }

    private class CountingSyntaxHighlighter : SyntaxHighlighter {
        val requestedLines = mutableListOf<Int>()
        var ready = true
        private var callback: (() -> Unit)? = null

        override fun setOnStateUpdated(callback: (() -> Unit)?) {
            this.callback = callback
        }

        fun notifyReady() {
            callback?.invoke()
        }

        override fun highlight(text: String, visibleRange: IntRange): List<HighlightSpan> = emptyList()

        override fun getLineSegments(line: Int): List<HighlightLineSegment> {
            requestedLines += line
            if (!ready) return emptyList()
            return listOf(
                HighlightLineSegment(
                    startColumn = 0,
                    endColumn = 1,
                    type = HighlightType.KEYWORD
                )
            )
        }
    }
}

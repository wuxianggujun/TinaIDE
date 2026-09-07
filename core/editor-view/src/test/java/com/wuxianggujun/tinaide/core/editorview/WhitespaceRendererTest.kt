package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhitespaceRendererTest {
    private data class Marker(val tab: Boolean, val startX: Float, val endX: Float, val centerY: Float)

    @Test
    fun wrappedMarkers_followVisualRowsAndReuseDocumentText() {
        val buffer = RopeTextBuffer(" \t  x   ")
        val state = createState(buffer, WhitespaceRenderMode.ALL, 4f)
        var reads = 0
        val frame = createFrame(state) { reads++; buffer.getLine(it) }
        val paint = Paint().apply { textSize = 14f }
        val layoutCache = EditorLineLayoutCache()
        val markers = collectMarkers(frame, paint, layoutCache)

        assertThat(reads).isEqualTo(1)
        assertThat(markers).hasSize(7)
        assertThat(markers.map { it.centerY }).containsExactly(10f, 10f, 30f, 30f, 30f, 50f, 50f).inOrder()
        assertThat(markers.count { it.tab }).isEqualTo(1)
        val layout = layoutCache.getPrefixLayout(state, 0, buffer.getLine(0), buffer.version, paint)
        assertThat(markers[2].startX).isWithin(0.01f).of(10f)
        assertThat(markers[2].endX).isWithin(0.01f)
            .of(10f + layout.textEndAdvance(3) - layout.segmentStartAdvance(2))
    }

    @Test
    fun scrollingIntoContinuation_onlyEmitsVisibleMarkers() {
        val buffer = RopeTextBuffer(" ".repeat(40))
        val state = createState(buffer, WhitespaceRenderMode.ALL, 4f)
        state.updateMetrics(20f, 1f, 20f, 4f, 0f)
        state.scrollBy(80f)
        val markers = collectMarkers(createFrame(state), Paint(), EditorLineLayoutCache())

        assertThat(markers).hasSize(state.visibleLines.count() * 4)
        assertThat(markers.minOf { it.centerY }).isEqualTo(10f)
        assertThat(markers.maxOf { it.centerY }).isEqualTo(70f)
    }

    @Test
    fun boundaryMode_usesDocumentBoundariesInsteadOfEachWrappedRow() {
        val state = createState(RopeTextBuffer("  aa bb  "), WhitespaceRenderMode.BOUNDARY, 3f)
        val markers = collectMarkers(createFrame(state), Paint(), EditorLineLayoutCache())
        assertThat(markers.map { it.centerY }).containsExactly(10f, 10f, 50f, 50f).inOrder()
    }

    @Test
    fun disabledMode_doesNotLoadText() {
        val state = createState(RopeTextBuffer("   "), WhitespaceRenderMode.NONE, 4f)
        val frame = createFrame(state) { error("Whitespace disabled: no line should be read") }
        assertThat(collectMarkers(frame, Paint(), EditorLineLayoutCache())).isEmpty()
    }

    @Test
    fun foldedGap_doesNotVisitThousandsOfHiddenRows() {
        val buffer = CountingTextBuffer(RopeTextBuffer((0 until 5_000).joinToString("\n") { " a " }))
        val state = createState(buffer, WhitespaceRenderMode.ALL, 100f)
        state.updateMetrics(20f, 1f, 120f, 100f, 0f)
        state.setFoldRegions(listOf(FoldRegion(100, 4_900)), buffer.version)
        state.toggleFoldAtLine(100)
        state.scrollToLine(95)
        val requested = mutableListOf<Int>()
        val frame = createFrame(state) { requested.add(it); buffer.getLine(it) }
        buffer.lineCountReads = 0

        collectMarkers(frame, Paint(), EditorLineLayoutCache())

        assertThat(requested).containsExactlyElementsIn((95..100) + (4_901..4_903)).inOrder()
        assertThat(buffer.lineCountReads).isLessThan(1_000)
    }

    private fun createState(buffer: TextBuffer, mode: WhitespaceRenderMode, width: Float): EditorState =
        EditorState(buffer, config = EditorConfig(wordWrap = true, codeFolding = true, renderWhitespace = mode)).apply {
            updateMetrics(20f, 1f, 240f, width, 0f)
        }

    private fun createFrame(state: EditorState, read: (Int) -> String = state.textBuffer::getLine) =
        EditorRenderFrameContext(state, state.textBuffer.version, EditorTextScanCache(), EditorBracketSnapshotCache(), read)

    private fun collectMarkers(frame: EditorRenderFrameContext, paint: Paint, cache: EditorLineLayoutCache): List<Marker> = buildList {
        WhitespaceRenderer().forEachVisibleMarker(frame, 10f, paint, cache) { tab, start, end, y ->
            add(Marker(tab, start, end, y))
        }
    }

    private class CountingTextBuffer(private val delegate: TextBuffer) : TextBuffer by delegate {
        var lineCountReads = 0
        override val lineCount: Int get() {
            lineCountReads++
            return delegate.lineCount
        }
    }
}

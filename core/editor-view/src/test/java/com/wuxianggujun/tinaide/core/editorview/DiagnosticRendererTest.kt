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
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DiagnosticRendererTest {
    @Test
    fun absentDiagnostics_skipTextReads() {
        val state = createState()
        assertThat(drawAndCollectReads(state)).isEmpty()
    }

    @Test
    fun sparseDiagnostics_onlyReadAnnotatedLines() {
        val state = createState()
        state.diagnosticsByLine = mapOf(1 to listOf(EditorDiagnostic(1, 0, 2, "test", DiagnosticSeverity.ERROR)))
        assertThat(drawAndCollectReads(state)).containsExactly(1)
    }

    @Test
    fun hiddenDiagnostics_skipTextReads() {
        val state = createState()
        state.diagnosticsByLine = mapOf(1 to listOf(EditorDiagnostic(1, 0, 2, "test", DiagnosticSeverity.ERROR)))
        state.setFoldRegions(listOf(FoldRegion(0, 1)), state.textBuffer.version)
        state.toggleFoldAtLine(0)
        assertThat(drawAndCollectReads(state)).isEmpty()
    }

    private fun createState() = EditorState(RopeTextBuffer("one\ntwo\nthree"), config = EditorConfig(wordWrap = false)).apply {
        updateMetrics(20f, 1f, 240f, 240f, 0f)
    }

    private fun drawAndCollectReads(state: EditorState): List<Int> {
        val requested = mutableListOf<Int>()
        val frame = EditorRenderFrameContext(state, state.textBuffer.version, EditorTextScanCache(), EditorBracketSnapshotCache()) {
            requested.add(it)
            state.textBuffer.getLine(it)
        }
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(240f, 240f)) {
                DiagnosticRenderer().drawDiagnostics(this, frame, 10f, Paint(), EditorLineLayoutCache())
            }
        } finally {
            bitmap.recycle()
        }
        return requested
    }
}

package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorRendererPerformanceSnapshotTest {

    @Test
    fun performanceSnapshot_shouldExposeAccumulatedMetricsAndCacheStats() {
        val lineLayoutCache = EditorLineLayoutCache()
        val renderer = createRenderer(lineLayoutCache)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
        }

        lineLayoutCache.getPrefixLayout(
            line = 2,
            lineText = "a\tbc",
            textVersion = 1L,
            paint = paint,
            tabSize = 4
        )
        renderer.sharedTextScanCache.getVisualColumnPrefix(
            line = 2,
            lineText = "a\tbc",
            textVersion = 1L,
            tabSize = 4
        )

        renderer.recordRenderMetrics(
            durationMs = 12L,
            visibleLineCount = 8,
            frameStats = TextRenderer.FrameCacheStats(hits = 4, misses = 1)
        )
        renderer.recordRenderMetrics(
            durationMs = 24L,
            visibleLineCount = 10,
            frameStats = TextRenderer.FrameCacheStats(hits = 6, misses = 2)
        )

        val snapshot = renderer.performanceSnapshot()

        assertThat(snapshot.totalRenderedFrames).isEqualTo(2L)
        assertThat(snapshot.slowRenderedFrames).isEqualTo(1L)
        assertThat(snapshot.lastRenderDurationMs).isEqualTo(24L)
        assertThat(snapshot.lastVisibleLineCount).isEqualTo(10)
        assertThat(snapshot.lastFrameCacheHits).isEqualTo(6)
        assertThat(snapshot.lastFrameCacheMisses).isEqualTo(2)
        assertThat(snapshot.totalCacheHits).isEqualTo(10L)
        assertThat(snapshot.totalCacheMisses).isEqualTo(3L)
        assertThat(snapshot.totalCacheHitRatePercent).isWithin(0.0001).of(10.0 * 100.0 / 13.0)
        assertThat(snapshot.textLineCacheSize).isEqualTo(0)
        assertThat(snapshot.textScanCacheSize).isEqualTo(1)
        assertThat(snapshot.lineLayoutCacheEntryCount).isEqualTo(1)
        assertThat(snapshot.lineLayoutCacheFloatCount).isEqualTo(5)
    }

    @Test
    fun hitZones_shouldInvalidateWhenLineNumberTypefaceChanges() {
        val state = EditorState(RopeTextBuffer((1..120).joinToString("\n")))
        val renderer = createRenderer()
        val monospacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
        }
        val serifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        renderer.hitZones(state, monospacePaint)
        val actual = renderer.hitZones(state, serifPaint)
        val expected = createRenderer().hitZones(state, serifPaint)

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun performanceSnapshot_exposesRenderPlanReuseAndMemoryAfterDrawing() {
        val state = EditorState(RopeTextBuffer("call(1)"), config = EditorConfig(wordWrap = false))
        state.updateMetrics(20f, 10f, 240f, 240f, 0f)
        val renderer = createRenderer()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f }
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        val scope = CanvasDrawScope()
        try {
            repeat(3) {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(240f, 240f)) {
                    renderer.render(this, state, paint, paint)
                }
            }
            val snapshot = renderer.performanceSnapshot()
            assertThat(snapshot.totalRenderPlanBuilds).isEqualTo(1)
            assertThat(snapshot.totalRenderPlanCacheHits).isEqualTo(2)
            assertThat(snapshot.renderPlanCacheEntryCount).isEqualTo(1)
            assertThat(snapshot.renderPlanCacheCharCount).isEqualTo(7)
            assertThat(snapshot.renderPlanCacheElementCount).isGreaterThan(0)
            renderer.invalidateCache()
            assertThat(renderer.performanceSnapshot().renderPlanCacheEntryCount).isEqualTo(0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createRenderer(
        lineLayoutCache: EditorLineLayoutCache = EditorLineLayoutCache()
    ): EditorRenderer = EditorRenderer(
        lineNumberRenderer = LineNumberRenderer(
            horizontalPaddingPx = 8f,
            edgeStartPaddingPx = 8f
        ),
        gutterRenderer = GutterRenderer(minWidthPx = 18f),
        lineLayoutCache = lineLayoutCache,
        dividerMarginLeftPx = 4f,
        dividerMarginRightPx = 4f,
        dividerWidthPx = 1f
    )
}

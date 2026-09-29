package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorScrollbarHitTest {

    @Test
    fun hitTest_verticalThumbAndTrack_shouldUseWideRightEdge() {
        val geometry = ScrollbarGeometry(
            axis = ScrollbarAxis.Vertical,
            trackStartPx = 8f,
            trackEndPx = 392f,
            crossEndPx = 394f,
            touchCrossStartPx = 376f,
            touchCrossEndPx = 400f,
            thicknessPx = 5f,
            thumbStartPx = 120f,
            thumbLengthPx = 80f,
            thumbTouchPaddingPx = 16f,
            maxScrollOffsetPx = 800f,
        )
        val layout = EditorScrollbarLayout(vertical = geometry, horizontal = null)

        val thumbHit = layout.hitTest(Offset(390f, 150f))
        val trackHit = layout.hitTest(Offset(390f, 40f))
        val miss = layout.hitTest(Offset(360f, 150f))

        assertThat(thumbHit).isEqualTo(
            ScrollbarDragTarget(axis = ScrollbarAxis.Vertical, hitOnThumb = true)
        )
        assertThat(trackHit).isEqualTo(
            ScrollbarDragTarget(axis = ScrollbarAxis.Vertical, hitOnThumb = false)
        )
        assertThat(miss).isNull()
        assertThat(geometry.touchCrossEndPx - geometry.touchCrossStartPx).isEqualTo(24f)
    }

    @Test
    fun calculateLayout_shouldExposeTwentyFourDpVerticalTouchTarget() {
        val state = EditorState(
            RopeTextBuffer(listOf("line1", "line2", "line3", "line4", "line5").joinToString(Char(10).toString()))
        )
        state.updateMetrics(
            lineHeightPx = 40f,
            charWidthPx = 10f,
            viewportHeightPx = 80f,
            viewportWidthPx = 200f,
            contentStartXPx = 24f,
        )
        val layout = EditorScrollbarRenderer().calculateLayout(
            state = state,
            canvasWidth = 200f,
            canvasHeight = 80f,
            density = Density(1f),
        )

        val vertical = layout.vertical
        assertThat(vertical).isNotNull()
        assertThat(vertical!!.touchCrossEndPx - vertical.touchCrossStartPx)
            .isEqualTo(EditorScrollbarMetrics.TOUCH_TARGET_THICKNESS_DP)
        assertThat(layout.hitTest(Offset(190f, 20f))).isNotNull()
        assertThat(layout.hitTest(Offset(160f, 20f))).isNull()
    }
}

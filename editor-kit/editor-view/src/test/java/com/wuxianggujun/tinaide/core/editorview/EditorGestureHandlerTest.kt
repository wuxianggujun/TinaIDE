package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorGestureHandlerTest {

    @Test
    fun registerTextTap_secondTapWithinTimeoutAndSlop_shouldRecognizeDoubleTap() {
        var now = 100L
        val handler = EditorGestureHandler(
            doubleTapTimeoutMs = 300L,
            doubleTapSlopPx = 24f,
            uptimeMillisProvider = { now }
        )

        val firstTap = handler.registerTextTap(Offset(40f, 60f))
        now = 280L
        val secondTap = handler.registerTextTap(Offset(52f, 68f))
        now = 360L
        val thirdTap = handler.registerTextTap(Offset(52f, 68f))

        assertThat(firstTap).isFalse()
        assertThat(secondTap).isTrue()
        assertThat(thirdTap).isFalse()
    }

    @Test
    fun registerTextTap_secondTapOutsideTimeout_shouldStartNewSequence() {
        var now = 0L
        val handler = EditorGestureHandler(
            doubleTapTimeoutMs = 200L,
            doubleTapSlopPx = 24f,
            uptimeMillisProvider = { now }
        )

        val firstTap = handler.registerTextTap(Offset(12f, 18f))
        now = 260L
        val lateTap = handler.registerTextTap(Offset(12f, 18f))

        assertThat(firstTap).isFalse()
        assertThat(lateTap).isFalse()
    }

    @Test
    fun onScrollConsumed_shouldClearPendingDoubleTapTracking() {
        var now = 50L
        val handler = EditorGestureHandler(
            doubleTapTimeoutMs = 300L,
            doubleTapSlopPx = 24f,
            uptimeMillisProvider = { now }
        )

        val firstTap = handler.registerTextTap(Offset(20f, 20f))
        handler.onScrollConsumed(consumedPx = 8f, suppressionMs = 120L)
        now = 180L
        val nextTap = handler.registerTextTap(Offset(20f, 20f))

        assertThat(firstTap).isFalse()
        assertThat(nextTap).isFalse()
    }
}

package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransformGestureFocusResolverTest {

    @Test
    fun resolve_returnsPreviousStableCentroid_whenAtLeastTwoStablePointers() {
        val snapshot = TransformGestureFocusResolver.resolve(
            pointerCount = 2,
            samples = listOf(
                TransformPointerSample(previousPressed = true, previousPosition = Offset(10f, 20f)),
                TransformPointerSample(previousPressed = true, previousPosition = Offset(30f, 40f))
            )
        )

        assertThat(snapshot.focus).isEqualTo(Offset(20f, 30f))
        assertThat(snapshot.pointerCount).isEqualTo(2)
        assertThat(snapshot.stablePointerCount).isEqualTo(2)
        assertThat(snapshot.basis).isEqualTo(TransformGestureFocusBasis.PREVIOUS_STABLE)
    }

    @Test
    fun resolve_ignoresUnstablePointers_whenComputingCentroid() {
        val snapshot = TransformGestureFocusResolver.resolve(
            pointerCount = 3,
            samples = listOf(
                TransformPointerSample(previousPressed = true, previousPosition = Offset(10f, 10f)),
                TransformPointerSample(previousPressed = false, previousPosition = Offset(200f, 200f)),
                TransformPointerSample(previousPressed = true, previousPosition = Offset(30f, 30f))
            )
        )

        assertThat(snapshot.focus).isEqualTo(Offset(20f, 20f))
        assertThat(snapshot.stablePointerCount).isEqualTo(2)
        assertThat(snapshot.basis).isEqualTo(TransformGestureFocusBasis.PREVIOUS_STABLE)
    }

    @Test
    fun resolve_returnsNullFocus_whenStablePointersLessThanTwo() {
        val snapshot = TransformGestureFocusResolver.resolve(
            pointerCount = 2,
            samples = listOf(
                TransformPointerSample(previousPressed = true, previousPosition = Offset(10f, 10f)),
                TransformPointerSample(previousPressed = false, previousPosition = Offset(30f, 30f))
            )
        )

        assertThat(snapshot.focus).isNull()
        assertThat(snapshot.stablePointerCount).isEqualTo(1)
        assertThat(snapshot.basis).isEqualTo(TransformGestureFocusBasis.NONE)
    }

    @Test
    fun resolve_returnsNone_whenPointerCountLessThanTwo() {
        val snapshot = TransformGestureFocusResolver.resolve(
            pointerCount = 1,
            samples = listOf(
                TransformPointerSample(previousPressed = true, previousPosition = Offset(10f, 10f))
            )
        )

        assertThat(snapshot.focus).isNull()
        assertThat(snapshot.stablePointerCount).isEqualTo(0)
        assertThat(snapshot.basis).isEqualTo(TransformGestureFocusBasis.NONE)
    }
}

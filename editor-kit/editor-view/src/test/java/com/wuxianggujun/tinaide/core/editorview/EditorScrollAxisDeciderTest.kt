package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorScrollAxisDeciderTest {

    @Test
    fun resolveDominantAxis_returnsHorizontalWhenHorizontalMovementDominates() {
        val axis = EditorScrollAxisDecider.resolveDominantAxis(
            deltaX = 14f,
            deltaY = 6f,
            touchSlop = 10f
        )

        assertThat(axis).isEqualTo(ScrollDragAxis.HORIZONTAL)
    }

    @Test
    fun resolveDominantAxis_returnsVerticalWhenVerticalMovementDominates() {
        val axis = EditorScrollAxisDecider.resolveDominantAxis(
            deltaX = 5f,
            deltaY = 15f,
            touchSlop = 10f
        )

        assertThat(axis).isEqualTo(ScrollDragAxis.VERTICAL)
    }

    @Test
    fun resolveDominantAxis_returnsNullWhenGestureNotDominant() {
        val axis = EditorScrollAxisDecider.resolveDominantAxis(
            deltaX = 7f,
            deltaY = 6.8f,
            touchSlop = 10f
        )

        assertThat(axis).isNull()
    }

    @Test
    fun canTakeOverRunningFling_respectsSingleDirectionDragging() {
        val touchSlop = 10f

        val takeOverWithMixedDirection = EditorScrollAxisDecider.canTakeOverRunningFling(
            delta = 2.3f,
            singleDirectionDragging = false,
            activeScrollDragAxis = null,
            expectedAxis = ScrollDragAxis.HORIZONTAL,
            touchSlop = touchSlop
        )
        val blockedByWrongAxis = EditorScrollAxisDecider.canTakeOverRunningFling(
            delta = 2.3f,
            singleDirectionDragging = true,
            activeScrollDragAxis = ScrollDragAxis.VERTICAL,
            expectedAxis = ScrollDragAxis.HORIZONTAL,
            touchSlop = touchSlop
        )
        val allowedByMatchedAxis = EditorScrollAxisDecider.canTakeOverRunningFling(
            delta = 2.3f,
            singleDirectionDragging = true,
            activeScrollDragAxis = ScrollDragAxis.HORIZONTAL,
            expectedAxis = ScrollDragAxis.HORIZONTAL,
            touchSlop = touchSlop
        )

        assertThat(takeOverWithMixedDirection).isTrue()
        assertThat(blockedByWrongAxis).isFalse()
        assertThat(allowedByMatchedAxis).isTrue()
    }
}

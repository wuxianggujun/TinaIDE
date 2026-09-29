package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.Position
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EditorMouseHoverCoordinatorTest {

    @Test
    fun onMove_shouldRequestHoverAfterDelay() = runTest {
        val requestedTargets = mutableListOf<EditorMouseHoverTarget>()
        val target = hoverTarget(line = 1, column = 2)
        val coordinator = EditorMouseHoverCoordinator(
            coroutineScope = this,
            hoverDelayMs = 100L,
            resolveTarget = { target },
            hideCurrentHover = {},
            requestHover = requestedTargets::add,
            dismissHover = {}
        )

        coordinator.onMove(Offset(10f, 20f))
        advanceTimeBy(99L)
        runCurrent()
        assertThat(requestedTargets).isEmpty()

        advanceTimeBy(1L)
        runCurrent()
        assertThat(requestedTargets).containsExactly(target)
    }

    @Test
    fun onMove_shouldReplacePendingHoverWhenTargetChanges() = runTest {
        val requestedTargets = mutableListOf<EditorMouseHoverTarget>()
        val firstTarget = hoverTarget(line = 1, column = 2)
        val secondTarget = hoverTarget(line = 3, column = 4)
        var currentTarget = firstTarget
        val coordinator = EditorMouseHoverCoordinator(
            coroutineScope = this,
            hoverDelayMs = 100L,
            resolveTarget = { currentTarget },
            hideCurrentHover = {},
            requestHover = requestedTargets::add,
            dismissHover = {}
        )

        coordinator.onMove(Offset(10f, 20f))
        advanceTimeBy(50L)
        currentTarget = secondTarget
        coordinator.onMove(Offset(30f, 40f))
        advanceTimeBy(100L)
        runCurrent()

        assertThat(requestedTargets).containsExactly(secondTarget)
    }

    @Test
    fun cancelAndDismiss_shouldCancelPendingHover() = runTest {
        val requestedTargets = mutableListOf<EditorMouseHoverTarget>()
        var dismissCount = 0
        val coordinator = EditorMouseHoverCoordinator(
            coroutineScope = this,
            hoverDelayMs = 100L,
            resolveTarget = { hoverTarget(line = 1, column = 2) },
            hideCurrentHover = {},
            requestHover = requestedTargets::add,
            dismissHover = { dismissCount += 1 }
        )

        coordinator.onMove(Offset(10f, 20f))
        coordinator.cancelAndDismiss()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(requestedTargets).isEmpty()
        assertThat(dismissCount).isEqualTo(1)
    }

    private fun hoverTarget(line: Int, column: Int): EditorMouseHoverTarget = EditorMouseHoverTarget(
        position = Position(line, column),
        anchorInViewportPx = IntOffset(column * 10, line * 10)
    )
}

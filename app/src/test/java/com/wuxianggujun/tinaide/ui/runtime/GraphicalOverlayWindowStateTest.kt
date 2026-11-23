package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class GraphicalOverlayWindowStateTest {
    private val viewport = IntRect(0, 24, 400, 800)
    private fun window() = GraphicalOverlayWindowState(viewport, density = 1f)

    @Test
    fun startsAsOneSmallControlInsteadOfAnExpandedLogOrTwoButtons() {
        val state = window()
        assertThat(state.expanded).isFalse()
        assertThat(state.maximized).isFalse()
        assertThat(state.bounds.width).isEqualTo(48)
        assertThat(state.bounds.height).isEqualTo(48)
        assertWithin(state.bounds, viewport)
    }

    @Test
    fun controlAndWindowDraggingStayInsideSystemBarAndCutoutBounds() {
        val state = window()
        state.dragBy(Offset(10_000f, 10_000f))
        assertWithin(state.bounds, viewport)
        state.openWindow()
        assertWithin(state.bounds, viewport)
        state.dragBy(Offset(-10_000f, -10_000f))
        assertThat(state.bounds.left).isEqualTo(8)
        assertThat(state.bounds.top).isEqualTo(32)
        state.dragBy(Offset(10_000f, 10_000f))
        assertWithin(state.bounds, viewport)
    }

    @Test
    fun fractionalDraggingAccumulatesRatherThanTruncatingEachMotionEvent() {
        val updates = mutableListOf<IntRect>()
        val state = GraphicalOverlayWindowState(viewport, 1f, onBoundsChanged = updates::add)
        val origin = state.snapshot().ballPositionDp
        repeat(4) { state.dragBy(Offset(0.25f, 0.25f)) }
        assertThat(state.snapshot().ballPositionDp).isEqualTo(origin + Offset(1f, 1f))
        assertThat(updates.zipWithNext().all { (previous, next) -> previous != next }).isTrue()
    }

    @Test
    fun resizingHasMinimumSizeAndDoesNotMoveTheTopLeftCorner() {
        val state = window().apply { openWindow() }
        val origin = state.bounds.topLeft
        state.resizeBy(Offset(-10_000f, -10_000f))
        assertThat(state.bounds.width).isEqualTo(224)
        assertThat(state.bounds.height).isEqualTo(144)
        assertThat(state.bounds.topLeft).isEqualTo(origin)
        state.resizeBy(Offset(10_000f, 10_000f))
        assertThat(state.bounds.topLeft).isEqualTo(origin)
        assertWithin(state.bounds, viewport)
    }

    @Test
    fun maximizeAndRestorePreserveTheUsersFloatingGeometry() {
        val state = window().apply { openWindow(); resizeBy(Offset(-32f, -32f)); dragBy(Offset(20f, 30f)) }
        val floating = state.bounds
        state.toggleMaximized()
        assertThat(state.maximized).isTrue()
        assertThat(state.bounds).isEqualTo(IntRect(8, 32, 392, 792))
        state.dragBy(Offset(100f, 100f))
        state.resizeBy(Offset(-100f, -100f))
        assertThat(state.bounds).isEqualTo(IntRect(8, 32, 392, 792))
        state.toggleMaximized()
        assertThat(state.bounds).isEqualTo(floating)
    }

    @Test
    fun collapsingAndReopeningRetainWindowSizeAndPosition() {
        val state = window().apply { openWindow(); resizeBy(Offset(-40f, -50f)); dragBy(Offset(15f, 20f)) }
        val floating = state.bounds
        state.collapseWindow()
        assertThat(state.expanded).isFalse()
        assertThat(state.bounds.width).isEqualTo(48)
        state.openWindow()
        assertThat(state.bounds).isEqualTo(floating)
    }

    @Test
    fun maximizedCollapseKeepsTheNormalRestoreGeometry() {
        val state = window().apply { openWindow(); resizeBy(Offset(-30f, -30f)) }
        val floating = state.bounds
        state.toggleMaximized()
        state.collapseWindow()
        state.openWindow()
        assertThat(state.maximized).isTrue()
        state.toggleMaximized()
        assertThat(state.bounds).isEqualTo(floating)
    }

    @Test
    fun movingTheCollapsedControlMovesTheReopenedWindowAnchor() {
        val state = window().apply { openWindow(); collapseWindow(); dragBy(Offset(30f, 40f)) }
        val anchor = state.bounds.topLeft
        state.openWindow()
        assertThat(state.bounds.topLeft).isEqualTo(anchor)
    }

    @Test
    fun snapshotsRestoreGeometryAndWindowModeWithoutKeepingAndroidViews() {
        val state = window().apply { openWindow(); resizeBy(Offset(-32f, 20f)); dragBy(Offset(12f, 20f)); toggleMaximized() }
        val restored = GraphicalOverlayWindowState(viewport, 1f, state.snapshot())
        assertThat(restored.snapshot()).isEqualTo(state.snapshot())
        assertThat(restored.bounds).isEqualTo(state.bounds)
        state.toggleMaximized()
        restored.toggleMaximized()
        assertThat(restored.bounds).isEqualTo(state.bounds)
    }

    @Test
    fun densityChangesKeepDpSizeWhileRotationClampsToTheNewViewport() {
        val state = GraphicalOverlayWindowState(IntRect(0, 0, 800, 1600), 2f).apply { openWindow() }
        val widthDp = state.snapshot().windowBoundsDp.width
        state.updateViewport(IntRect(0, 0, 1200, 2400), 3f)
        assertThat(state.snapshot().windowBoundsDp.width).isWithin(0.01f).of(widthDp)
        val landscape = IntRect(20, 10, 720, 320)
        state.updateViewport(landscape, 3f)
        assertWithin(state.bounds, landscape)
        state.collapseWindow()
        assertWithin(state.bounds, landscape)
    }

    @Test
    fun malformedRestoresAndMotionCannotCorruptGeometry() {
        val malformed = GraphicalOverlayWindowState.Snapshot(true, true, Rect(Float.NaN, 0f, 100f, 100f), Offset.Zero)
        val state = GraphicalOverlayWindowState(viewport, Float.NaN, malformed)
        val before = state.bounds
        state.dragBy(Offset(Float.POSITIVE_INFINITY, 0f))
        state.resizeBy(Offset(Float.NaN, 0f))
        assertThat(state.bounds).isEqualTo(before)
        assertThat(state.expanded).isFalse()
    }

    @Test
    fun narrowShortAndHighDensityViewportsNeverProduceInvalidOrOffscreenRects() {
        val random = Random(20261123)
        for (width in listOf(40, 120, 280, 720)) for (height in listOf(48, 180, 800)) {
            for (density in listOf(0.75f, 1f, 2.625f, 4f)) {
                val viewport = IntRect(4, 8, width - 4, height - 8)
                val state = GraphicalOverlayWindowState(viewport, density)
                repeat(20) { step ->
                    state.openWindow()
                    state.dragBy(Offset(random.nextFloat() * 1000 - 500, random.nextFloat() * 1000 - 500))
                    state.resizeBy(Offset(random.nextFloat() * 1000 - 500, random.nextFloat() * 1000 - 500))
                    if (step % 3 == 0) state.toggleMaximized()
                    assertWithin(state.bounds, viewport)
                    state.collapseWindow()
                    assertWithin(state.bounds, viewport)
                }
            }
        }
    }

    private fun assertWithin(bounds: IntRect, viewport: IntRect) {
        assertThat(bounds.left).isAtLeast(viewport.left)
        assertThat(bounds.top).isAtLeast(viewport.top)
        assertThat(bounds.right).isAtMost(viewport.right)
        assertThat(bounds.bottom).isAtMost(viewport.bottom)
        assertThat(bounds.width).isGreaterThan(0)
        assertThat(bounds.height).isGreaterThan(0)
    }
}

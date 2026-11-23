package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry for the attached control/log popup, never for the engine's rendering surface. */
internal class GraphicalOverlayWindowState(
    viewport: IntRect,
    density: Float,
    restored: Snapshot? = null,
    private val onBoundsChanged: (IntRect) -> Unit = {},
) {
    private var viewport by mutableStateOf(validViewport(viewport))
    private var density by mutableStateOf(validDensity(density))
    private var floatingBounds by mutableStateOf(defaultWindowBounds())
    private var ballPosition by mutableStateOf(floatingBounds.topLeft)
    private var lastReportedBounds: IntRect? = null

    var expanded by mutableStateOf(false)
        private set
    var maximized by mutableStateOf(false)
        private set

    init {
        if (restored != null && restored.isValid()) {
            floatingBounds = fitWindow(restored.windowBoundsDp.scale(this.density))
            ballPosition = fitBall(restored.ballPositionDp * this.density)
            expanded = restored.expanded
            maximized = restored.maximized
        }
    }

    val bounds: IntRect
        get() {
            val rect = when {
                expanded && maximized -> usableBounds
                expanded -> floatingBounds
                else -> Rect(ballPosition, androidx.compose.ui.geometry.Size(ballSize, ballSize))
            }
            val left = rect.left.roundToInt().coerceIn(viewport.left, viewport.right - 1)
            val top = rect.top.roundToInt().coerceIn(viewport.top, viewport.bottom - 1)
            val width = rect.width.roundToInt().coerceIn(1, viewport.right - left)
            val height = rect.height.roundToInt().coerceIn(1, viewport.bottom - top)
            return IntRect(left, top, left + width, top + height)
        }

    fun openWindow() {
        if (expanded) return
        floatingBounds = fitWindow(Rect(ballPosition, floatingBounds.size))
        expanded = true
        reportBounds()
    }

    fun collapseWindow() {
        if (!expanded) return
        ballPosition = fitBall(floatingBounds.topLeft)
        expanded = false
        reportBounds()
    }

    fun dragBy(delta: Offset) {
        if (!delta.isFinite() || (expanded && maximized)) return
        if (expanded) floatingBounds = fitWindow(floatingBounds.translate(delta))
        else ballPosition = fitBall(ballPosition + delta)
        reportBounds()
    }

    fun resizeBy(delta: Offset) {
        if (!expanded || maximized || !delta.isFinite()) return
        val width = (floatingBounds.width + delta.x)
            .coerceIn(minWindowWidth, (usableBounds.right - floatingBounds.left).coerceAtLeast(minWindowWidth))
        val height = (floatingBounds.height + delta.y)
            .coerceIn(minWindowHeight, (usableBounds.bottom - floatingBounds.top).coerceAtLeast(minWindowHeight))
        floatingBounds = Rect(floatingBounds.topLeft, androidx.compose.ui.geometry.Size(width, height))
        reportBounds()
    }

    fun toggleMaximized() {
        if (!expanded) return
        maximized = !maximized
        reportBounds()
    }

    fun updateViewport(viewport: IntRect, density: Float) {
        val nextViewport = validViewport(viewport)
        val nextDensity = validDensity(density)
        if (this.viewport == nextViewport && this.density == nextDensity) return
        val scale = nextDensity / this.density
        this.viewport = nextViewport
        this.density = nextDensity
        floatingBounds = fitWindow(floatingBounds.scale(scale))
        ballPosition = fitBall(ballPosition * scale)
        reportBounds()
    }

    fun snapshot(): Snapshot = Snapshot(
        expanded = expanded,
        maximized = maximized,
        windowBoundsDp = floatingBounds.scale(1f / density),
        ballPositionDp = ballPosition / density,
    )

    private val usableBounds: Rect
        get() {
            val horizontalMargin = min(WINDOW_MARGIN_DP * density, (viewport.width - 1) / 2f)
            val verticalMargin = min(WINDOW_MARGIN_DP * density, (viewport.height - 1) / 2f)
            return Rect(
                viewport.left + horizontalMargin,
                viewport.top + verticalMargin,
                viewport.right - horizontalMargin,
                viewport.bottom - verticalMargin,
            )
        }
    private val ballSize: Float get() = min(CONTROL_SIZE_DP * density, min(usableBounds.width, usableBounds.height))
    private val minWindowWidth: Float get() = min(MIN_WINDOW_WIDTH_DP * density, usableBounds.width)
    private val minWindowHeight: Float get() = min(MIN_WINDOW_HEIGHT_DP * density, usableBounds.height)

    private fun defaultWindowBounds(): Rect {
        val area = usableBounds
        val width = min(DEFAULT_WINDOW_WIDTH_DP * density, area.width * DEFAULT_WIDTH_FRACTION)
            .coerceAtLeast(minWindowWidth)
        val height = min(DEFAULT_WINDOW_HEIGHT_DP * density, area.height * DEFAULT_HEIGHT_FRACTION)
            .coerceAtLeast(minWindowHeight)
        val origin = Offset(area.left + WINDOW_MARGIN_DP * density, area.top + area.height * DEFAULT_TOP_FRACTION)
        return fitWindow(Rect(origin, androidx.compose.ui.geometry.Size(width, height)))
    }

    private fun fitWindow(rect: Rect): Rect {
        val area = usableBounds
        val width = rect.width.coerceIn(minWindowWidth, area.width)
        val height = rect.height.coerceIn(minWindowHeight, area.height)
        val left = rect.left.coerceIn(area.left, (area.right - width).coerceAtLeast(area.left))
        val top = rect.top.coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
        return Rect(left, top, left + width, top + height)
    }

    private fun fitBall(position: Offset): Offset = Offset(
        position.x.coerceIn(usableBounds.left, (usableBounds.right - ballSize).coerceAtLeast(usableBounds.left)),
        position.y.coerceIn(usableBounds.top, (usableBounds.bottom - ballSize).coerceAtLeast(usableBounds.top)),
    )

    private fun reportBounds() {
        val current = bounds
        if (current == lastReportedBounds) return
        lastReportedBounds = current
        onBoundsChanged(current)
    }

    data class Snapshot(
        val expanded: Boolean,
        val maximized: Boolean,
        val windowBoundsDp: Rect,
        val ballPositionDp: Offset,
    ) {
        internal fun isValid(): Boolean = listOf(
            windowBoundsDp.left, windowBoundsDp.top, windowBoundsDp.right, windowBoundsDp.bottom,
            ballPositionDp.x, ballPositionDp.y,
        ).all(Float::isFinite) && windowBoundsDp.width > 0f && windowBoundsDp.height > 0f
    }

    private companion object {
        const val CONTROL_SIZE_DP = 48f
        const val WINDOW_MARGIN_DP = 8f
        const val MIN_WINDOW_WIDTH_DP = 224f
        const val MIN_WINDOW_HEIGHT_DP = 144f
        const val DEFAULT_WINDOW_WIDTH_DP = 320f
        const val DEFAULT_WINDOW_HEIGHT_DP = 248f
        const val DEFAULT_WIDTH_FRACTION = 0.85f
        const val DEFAULT_HEIGHT_FRACTION = 0.4f
        const val DEFAULT_TOP_FRACTION = 0.15f

        fun validViewport(rect: IntRect): IntRect = IntRect(
            rect.left, rect.top, rect.right.coerceAtLeast(rect.left + 1), rect.bottom.coerceAtLeast(rect.top + 1),
        )
        fun validDensity(density: Float): Float = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        fun Rect.scale(factor: Float): Rect = Rect(left * factor, top * factor, right * factor, bottom * factor)
        fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()
    }
}

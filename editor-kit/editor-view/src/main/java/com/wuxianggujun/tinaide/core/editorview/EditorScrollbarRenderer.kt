package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import kotlin.math.max

internal enum class ScrollbarAxis {
    Vertical,
    Horizontal
}

internal data class ScrollbarDragTarget(
    val axis: ScrollbarAxis,
    val hitOnThumb: Boolean
)

internal data class ScrollbarGeometry(
    val axis: ScrollbarAxis,
    val trackStartPx: Float,
    val trackEndPx: Float,
    val crossEndPx: Float,
    val touchCrossStartPx: Float,
    val touchCrossEndPx: Float,
    val thicknessPx: Float,
    val thumbStartPx: Float,
    val thumbLengthPx: Float,
    val thumbTouchPaddingPx: Float,
    val maxScrollOffsetPx: Float
) {
    val trackLengthPx: Float
        get() = (trackEndPx - trackStartPx).coerceAtLeast(0f)

    val thumbEndPx: Float
        get() = thumbStartPx + thumbLengthPx

    fun containsThumb(position: Offset): Boolean = when (axis) {
        ScrollbarAxis.Vertical -> {
            position.x in touchCrossStartPx..touchCrossEndPx &&
                position.y in (thumbStartPx - thumbTouchPaddingPx)..(thumbEndPx + thumbTouchPaddingPx)
        }

        ScrollbarAxis.Horizontal -> {
            position.x in (thumbStartPx - thumbTouchPaddingPx)..(thumbEndPx + thumbTouchPaddingPx) &&
                position.y in touchCrossStartPx..touchCrossEndPx
        }
    }

    fun containsTrack(position: Offset): Boolean = when (axis) {
        ScrollbarAxis.Vertical -> {
            position.x in touchCrossStartPx..touchCrossEndPx &&
                position.y in trackStartPx..trackEndPx
        }

        ScrollbarAxis.Horizontal -> {
            position.x in trackStartPx..trackEndPx &&
                position.y in touchCrossStartPx..touchCrossEndPx
        }
    }
}

internal data class EditorScrollbarLayout(
    val vertical: ScrollbarGeometry?,
    val horizontal: ScrollbarGeometry?
) {
    fun hitTest(position: Offset): ScrollbarDragTarget? {
        val verticalGeometry = vertical
        if (verticalGeometry != null) {
            if (verticalGeometry.containsThumb(position)) {
                return ScrollbarDragTarget(
                    axis = ScrollbarAxis.Vertical,
                    hitOnThumb = true
                )
            }
            if (verticalGeometry.containsTrack(position)) {
                return ScrollbarDragTarget(
                    axis = ScrollbarAxis.Vertical,
                    hitOnThumb = false
                )
            }
        }

        val horizontalGeometry = horizontal
        if (horizontalGeometry != null) {
            if (horizontalGeometry.containsThumb(position)) {
                return ScrollbarDragTarget(
                    axis = ScrollbarAxis.Horizontal,
                    hitOnThumb = true
                )
            }
            if (horizontalGeometry.containsTrack(position)) {
                return ScrollbarDragTarget(
                    axis = ScrollbarAxis.Horizontal,
                    hitOnThumb = false
                )
            }
        }
        return null
    }
}

internal object EditorScrollbarMetrics {
    const val TOUCH_TARGET_THICKNESS_DP = 24f
}

internal class EditorScrollbarRenderer {

    companion object {
        private const val TRACK_MARGIN_DP = 2f
        private const val BAR_THICKNESS_DP = 5f
        private const val ACTIVE_BAR_THICKNESS_DP = 8f
        private const val TOUCH_TARGET_THICKNESS_DP = EditorScrollbarMetrics.TOUCH_TARGET_THICKNESS_DP
        private const val THUMB_TOUCH_PADDING_DP = 16f
        private const val BAR_GAP_DP = 2f
        private const val MIN_THUMB_LENGTH_DP = 56f
    }

    private var dpCacheDensity = 0f
    private var dpTrackMargin = 0f
    private var dpBarThickness = 0f
    private var dpActiveBar = 0f
    private var dpTouchTarget = 0f
    private var dpThumbPadding = 0f
    private var dpBarGap = 0f
    private var dpMinThumb = 0f

    private fun ensureDpCache(density: Density) {
        val d = density.density
        if (d == dpCacheDensity) return
        dpCacheDensity = d
        dpTrackMargin = TRACK_MARGIN_DP * d
        dpBarThickness = BAR_THICKNESS_DP * d
        dpActiveBar = ACTIVE_BAR_THICKNESS_DP * d
        dpTouchTarget = TOUCH_TARGET_THICKNESS_DP * d
        dpThumbPadding = THUMB_TOUCH_PADDING_DP * d
        dpBarGap = BAR_GAP_DP * d
        dpMinThumb = MIN_THUMB_LENGTH_DP * d
    }

    fun calculateLayout(
        state: EditorState,
        canvasWidth: Float,
        canvasHeight: Float,
        density: Density
    ): EditorScrollbarLayout {
        ensureDpCache(density)
        val maxVerticalOffset = state.maxVerticalScrollOffsetPx()
        val maxHorizontalOffset = state.maxHorizontalScrollOffsetPx()
        val showVertical = maxVerticalOffset > 0f
        val showHorizontal = maxHorizontalOffset > 0f && !state.config.wordWrap
        if (!showVertical && !showHorizontal) {
            return EditorScrollbarLayout(vertical = null, horizontal = null)
        }

        val trackMarginPx = dpTrackMargin
        val barThicknessPx = dpBarThickness
        val touchTargetThicknessPx = dpTouchTarget
        val thumbTouchPaddingPx = dpThumbPadding
        val barGapPx = dpBarGap
        val minThumbLengthPx = dpMinThumb

        val verticalGeometry = if (showVertical) {
            val reservedBottom = if (showHorizontal) barThicknessPx + barGapPx else 0f
            val trackTop = trackMarginPx
            val trackBottom = canvasHeight - trackMarginPx - reservedBottom
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(0f)
            if (trackHeight <= 0f) {
                null
            } else {
                val trackX = canvasWidth - trackMarginPx - barThicknessPx
                val contentHeight = maxVerticalOffset + state.viewportHeightPx.coerceAtLeast(1f)
                val rawThumbHeight = (state.viewportHeightPx / contentHeight) * trackHeight
                val thumbHeight = rawThumbHeight.coerceIn(
                    minThumbLengthPx.coerceAtMost(trackHeight),
                    trackHeight
                )
                val progress = (state.scrollOffsetPx / maxVerticalOffset).coerceIn(0f, 1f)
                val thumbTop = trackTop + (trackHeight - thumbHeight) * progress
                val trackRight = trackX + barThicknessPx
                val touchCrossEnd = canvasWidth.coerceAtLeast(trackRight)
                ScrollbarGeometry(
                    axis = ScrollbarAxis.Vertical,
                    trackStartPx = trackTop,
                    trackEndPx = trackBottom,
                    crossEndPx = trackRight,
                    touchCrossStartPx = (touchCrossEnd - touchTargetThicknessPx).coerceAtLeast(0f),
                    touchCrossEndPx = touchCrossEnd,
                    thicknessPx = barThicknessPx,
                    thumbStartPx = thumbTop,
                    thumbLengthPx = thumbHeight,
                    thumbTouchPaddingPx = thumbTouchPaddingPx,
                    maxScrollOffsetPx = maxVerticalOffset
                )
            }
        } else {
            null
        }

        val horizontalGeometry = if (showHorizontal) {
            val reservedRight = if (showVertical) barThicknessPx + barGapPx else 0f
            val trackLeft = trackMarginPx
            val trackRight = canvasWidth - trackMarginPx - reservedRight
            val trackWidth = (trackRight - trackLeft).coerceAtLeast(0f)
            if (trackWidth <= 0f) {
                null
            } else {
                val trackY = canvasHeight - trackMarginPx - barThicknessPx
                val contentWidth = maxHorizontalOffset + state.viewportWidthPx.coerceAtLeast(1f)
                val rawThumbWidth = (state.viewportWidthPx / contentWidth) * trackWidth
                val thumbWidth = rawThumbWidth.coerceIn(
                    minThumbLengthPx.coerceAtMost(trackWidth),
                    trackWidth
                )
                val progress = (state.scrollOffsetXPx / maxHorizontalOffset).coerceIn(0f, 1f)
                val thumbLeft = trackLeft + (trackWidth - thumbWidth) * progress
                val trackBottom = trackY + barThicknessPx
                val touchCrossEnd = canvasHeight.coerceAtLeast(trackBottom)
                ScrollbarGeometry(
                    axis = ScrollbarAxis.Horizontal,
                    trackStartPx = trackLeft,
                    trackEndPx = trackRight,
                    crossEndPx = trackBottom,
                    touchCrossStartPx = (touchCrossEnd - touchTargetThicknessPx).coerceAtLeast(0f),
                    touchCrossEndPx = touchCrossEnd,
                    thicknessPx = barThicknessPx,
                    thumbStartPx = thumbLeft,
                    thumbLengthPx = thumbWidth,
                    thumbTouchPaddingPx = thumbTouchPaddingPx,
                    maxScrollOffsetPx = maxHorizontalOffset
                )
            }
        } else {
            null
        }

        return EditorScrollbarLayout(
            vertical = verticalGeometry,
            horizontal = horizontalGeometry
        )
    }

    fun draw(
        drawScope: DrawScope,
        layout: EditorScrollbarLayout,
        alpha: Float,
        colorScheme: EditorColorScheme,
        activeAxis: ScrollbarAxis? = null
    ) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        if (clampedAlpha <= 0f) return

        val vertical = layout.vertical
        if (vertical != null) {
            drawAxis(
                drawScope = drawScope,
                geometry = vertical,
                alpha = clampedAlpha,
                active = activeAxis == ScrollbarAxis.Vertical,
                colorScheme = colorScheme
            )
        }

        val horizontal = layout.horizontal
        if (horizontal != null) {
            drawAxis(
                drawScope = drawScope,
                geometry = horizontal,
                alpha = clampedAlpha,
                active = activeAxis == ScrollbarAxis.Horizontal,
                colorScheme = colorScheme
            )
        }
    }

    private fun drawAxis(
        drawScope: DrawScope,
        geometry: ScrollbarGeometry,
        alpha: Float,
        active: Boolean,
        colorScheme: EditorColorScheme
    ) {
        val drawThickness = if (active) {
            max(geometry.thicknessPx, dpActiveBar)
        } else {
            geometry.thicknessPx
        }
        val cornerRadius = CornerRadius(drawThickness, drawThickness)
        val trackAlpha = if (active) alpha * 0.72f else alpha * 0.55f
        val thumbAlpha = if (active) alpha else alpha * 0.92f
        val trackColor = colorScheme.scrollbarTrack
        val thumbColor = if (active) colorScheme.scrollbarThumbHover else colorScheme.scrollbarThumb
        when (geometry.axis) {
            ScrollbarAxis.Vertical -> {
                val drawCrossStart = geometry.crossEndPx - drawThickness
                drawScope.drawRoundRect(
                    color = trackColor.copy(alpha = trackColor.alpha * trackAlpha),
                    topLeft = Offset(drawCrossStart, geometry.trackStartPx),
                    size = Size(drawThickness, geometry.trackLengthPx),
                    cornerRadius = cornerRadius
                )
                drawScope.drawRoundRect(
                    color = thumbColor.copy(alpha = thumbColor.alpha * thumbAlpha),
                    topLeft = Offset(drawCrossStart, geometry.thumbStartPx),
                    size = Size(drawThickness, geometry.thumbLengthPx),
                    cornerRadius = cornerRadius
                )
            }

            ScrollbarAxis.Horizontal -> {
                val drawCrossStart = geometry.crossEndPx - drawThickness
                drawScope.drawRoundRect(
                    color = trackColor.copy(alpha = trackColor.alpha * trackAlpha),
                    topLeft = Offset(geometry.trackStartPx, drawCrossStart),
                    size = Size(geometry.trackLengthPx, drawThickness),
                    cornerRadius = cornerRadius
                )
                drawScope.drawRoundRect(
                    color = thumbColor.copy(alpha = thumbColor.alpha * thumbAlpha),
                    topLeft = Offset(geometry.thumbStartPx, drawCrossStart),
                    size = Size(geometry.thumbLengthPx, drawThickness),
                    cornerRadius = cornerRadius
                )
            }
        }
    }
}

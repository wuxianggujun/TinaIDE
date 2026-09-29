package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SignatureHelpPopupLayout(
    val offset: IntOffset,
    val widthPx: Float,
    val maxHeightPx: Float
)

internal object SignatureHelpPopupLayoutResolver {
    fun resolve(
        cursorXInViewportPx: Float,
        cursorLineTopInViewportPx: Float,
        lineHeightPx: Float,
        canvasOriginInWindowPx: IntOffset,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        windowWidthPx: Float,
        windowHeightPx: Float,
        imeBottomInsetPx: Float,
        preferredPopupWidthPx: Float,
        popupMaxHeightPx: Float,
        preferredContentHeightPx: Float,
        marginPx: Float,
        cursorGapPx: Float,
        narrowEditorThresholdPx: Float,
        minPopupHeightPx: Float
    ): SignatureHelpPopupLayout {
        val safeWindowWidth = windowWidthPx.coerceAtLeast(1f)
        val safeWindowHeight = windowHeightPx.coerceAtLeast(1f)
        val safeCanvasWidth = canvasWidthPx.coerceAtLeast(1f)
        val safeCanvasHeight = canvasHeightPx.coerceAtLeast(1f)
        val visibleWindowBottom = (safeWindowHeight - imeBottomInsetPx).coerceAtLeast(0f)

        val canvasLeft = canvasOriginInWindowPx.x.toFloat().coerceAtLeast(0f)
        val canvasTop = canvasOriginInWindowPx.y.toFloat().coerceAtLeast(0f)
        val canvasRight = min(canvasLeft + safeCanvasWidth, safeWindowWidth)
        val canvasBottom = min(canvasTop + safeCanvasHeight, visibleWindowBottom)

        val visibleCanvasWidth = (canvasRight - canvasLeft).coerceAtLeast(1f)
        val maxPopupWidth = (visibleCanvasWidth - marginPx * 2f).coerceAtLeast(1f)
        val useWidePanelMode = visibleCanvasWidth <= narrowEditorThresholdPx
        val popupWidth = if (useWidePanelMode) {
            (visibleCanvasWidth * 0.9f).coerceIn(1f, maxPopupWidth)
        } else {
            min(preferredPopupWidthPx, min(visibleCanvasWidth * 0.62f, maxPopupWidth))
                .coerceAtLeast(1f)
        }

        val anchorXInWindow = canvasLeft + cursorXInViewportPx
        val lineTopInWindow = canvasTop + cursorLineTopInViewportPx
        val popupTopBelow = lineTopInWindow + lineHeightPx + cursorGapPx
        val popupBottomAbove = lineTopInWindow - cursorGapPx
        val availableAbove = (popupBottomAbove - canvasTop - marginPx).coerceAtLeast(0f)
        val availableBelow = (canvasBottom - marginPx - popupTopBelow).coerceAtLeast(0f)
        val showAbove = when {
            availableAbove <= 0f -> false
            availableBelow <= 0f -> true
            availableAbove >= minPopupHeightPx -> true
            availableBelow >= minPopupHeightPx -> false
            else -> availableAbove >= availableBelow
        }
        val availableHeight = if (showAbove) availableAbove else availableBelow
        val popupHeightPx = min(availableHeight, preferredContentHeightPx)
            .coerceAtMost(popupMaxHeightPx)
            .coerceAtLeast(1f)

        val minX = canvasLeft + marginPx
        val maxX = max(minX, canvasRight - popupWidth - marginPx)
        val desiredX = if (useWidePanelMode) {
            canvasLeft + (visibleCanvasWidth - popupWidth) * 0.5f
        } else {
            anchorXInWindow - popupWidth * 0.16f
        }
        val x = desiredX.coerceIn(minX, maxX)

        val minY = canvasTop + marginPx
        val maxY = max(minY, canvasBottom - popupHeightPx - marginPx)
        val y = if (showAbove) {
            (popupBottomAbove - popupHeightPx).coerceIn(minY, maxY)
        } else {
            popupTopBelow.coerceIn(minY, maxY)
        }

        return SignatureHelpPopupLayout(
            offset = IntOffset(x.roundToInt(), y.roundToInt()),
            widthPx = popupWidth,
            maxHeightPx = popupHeightPx
        )
    }
}

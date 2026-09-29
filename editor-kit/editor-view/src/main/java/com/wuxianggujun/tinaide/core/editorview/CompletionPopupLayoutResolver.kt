package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class CompletionPopupLayout(
    val offset: IntOffset,
    val widthPx: Float,
    val maxHeightPx: Float
)

/**
 * 代码补全弹窗布局解析器：
 * 1. 使用窗口绝对坐标，避免 Popup 相对编辑器画布“飘移”；
 * 2. 在编辑器可见区域内优先下方显示，空间不足时切到上方；
 * 3. 小屏时切到更接近 Sora 的宽面板模式，大屏时跟随光标。
 */
internal object CompletionPopupLayoutResolver {
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
        itemCount: Int,
        itemHeightPx: Float,
        loadingIndicatorHeightPx: Float,
        isLoading: Boolean,
        marginPx: Float,
        cursorGapPx: Float,
        narrowEditorThresholdPx: Float,
        minPopupHeightPx: Float
    ): CompletionPopupLayout {
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
            (visibleCanvasWidth * 0.875f).coerceIn(1f, maxPopupWidth)
        } else {
            min(preferredPopupWidthPx, min(visibleCanvasWidth * 0.5f, maxPopupWidth))
                .coerceAtLeast(1f)
        }

        val anchorXInWindow = canvasLeft + cursorXInViewportPx
        val lineTopInWindow = canvasTop + cursorLineTopInViewportPx
        // 放在"当前行下一整行"位置，避免只让出半行高度导致弹窗盖住光标所在行。
        val belowY = lineTopInWindow + lineHeightPx
        val availableBelow = (canvasBottom - marginPx - belowY).coerceAtLeast(0f)
        val availableAbove = (lineTopInWindow - canvasTop - marginPx).coerceAtLeast(0f)
        val showBelow = availableAbove <= 0f ||
            availableBelow >= minPopupHeightPx ||
            availableBelow >= availableAbove * 0.7f
        val availableHeight = if (showBelow) availableBelow else availableAbove
        val desiredContentHeight = (itemCount.coerceAtLeast(1) * itemHeightPx) +
            if (isLoading) loadingIndicatorHeightPx else 0f
        val popupHeightPx = min(availableHeight, desiredContentHeight)
            .coerceAtMost(popupMaxHeightPx)
            .coerceAtLeast(1f)

        val minX = canvasLeft + marginPx
        val maxX = max(minX, canvasRight - popupWidth - marginPx)
        val desiredX = if (useWidePanelMode) {
            canvasLeft + (visibleCanvasWidth - popupWidth) * 0.5f
        } else {
            anchorXInWindow + cursorGapPx
        }
        val x = desiredX.coerceIn(minX, maxX)

        val minY = canvasTop + marginPx
        val maxY = max(minY, canvasBottom - popupHeightPx - marginPx)
        val y = if (showBelow) {
            belowY.coerceIn(minY, maxY)
        } else {
            (lineTopInWindow - popupHeightPx - marginPx).coerceIn(minY, maxY)
        }

        return CompletionPopupLayout(
            offset = IntOffset(x.roundToInt(), y.roundToInt()),
            widthPx = popupWidth,
            maxHeightPx = popupHeightPx
        )
    }
}

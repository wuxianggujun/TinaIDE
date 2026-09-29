package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.max

/**
 * 选择菜单的 Popup 定位器：
 * 1. 优先放在锚点上方
 * 2. 放不下则放下方
 * 3. 最终在“可见窗口区域”内 clamp，且永不进入 IME 覆盖区域
 */
internal class ContextMenuPopupPositionProvider(
    private val anchorInWindowPx: IntOffset,
    private val imeBottomInsetPx: Int,
    private val marginPx: Int = 8
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val margin = marginPx.coerceAtLeast(0)
        val windowWidth = windowSize.width
        val windowHeight = windowSize.height
        val visibleBottom = (windowHeight - imeBottomInsetPx).coerceAtLeast(0)

        val maxX = max(margin, windowWidth - popupContentSize.width - margin)
        val x = anchorInWindowPx.x.coerceIn(margin, maxX)

        val maxY = max(margin, visibleBottom - popupContentSize.height - margin)
        val aboveY = anchorInWindowPx.y - popupContentSize.height - margin
        val belowY = anchorInWindowPx.y + margin
        val y = if (aboveY >= margin) {
            aboveY
        } else {
            belowY.coerceIn(margin, maxY)
        }

        return IntOffset(x, y)
    }
}

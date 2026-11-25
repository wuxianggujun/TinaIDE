package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/** Native PopupWindow hosts need screen coordinates, not Compose's local window anchor. */
internal class GraphicalRuntimeMenuPositionProvider(
    private val screenAnchor: () -> IntRect,
    private val safeScreenBounds: () -> IntRect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = screenAnchor()
        val safeBounds = safeScreenBounds()
        val maximumX = (safeBounds.right - popupContentSize.width).coerceAtLeast(safeBounds.left)
        val maximumY = (safeBounds.bottom - popupContentSize.height).coerceAtLeast(safeBounds.top)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchor.left
        } else {
            anchor.right - popupContentSize.width
        }
        val below = anchor.bottom + gapPx
        val above = anchor.top - gapPx - popupContentSize.height
        val preferredY = when {
            below <= maximumY -> below
            above >= safeBounds.top -> above
            else -> below
        }
        return IntOffset(
            preferredX.coerceIn(safeBounds.left, maximumX),
            preferredY.coerceIn(safeBounds.top, maximumY),
        )
    }
}

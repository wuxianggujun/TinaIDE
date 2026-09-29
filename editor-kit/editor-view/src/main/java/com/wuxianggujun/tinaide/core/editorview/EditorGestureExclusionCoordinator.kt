package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.ui.unit.Density

internal class EditorGestureExclusionCoordinator(
    private val composeView: View,
    private val touchDiagnostics: EditorTouchDiagnostics
) {
    fun update(
        density: Density,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        maxVerticalScrollOffsetPx: Float,
        maxHorizontalScrollOffsetPx: Float
    ) {
        val rects = resolveExclusionRects(
            density = density,
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            maxVerticalScrollOffsetPx = maxVerticalScrollOffsetPx,
            maxHorizontalScrollOffsetPx = maxHorizontalScrollOffsetPx
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            composeView.systemGestureExclusionRects = rects
        }
        if (touchDiagnostics.isVerboseEnabled() && rects.isNotEmpty()) {
            val rectSummary = rects.joinToString(prefix = "[", postfix = "]") { rect ->
                "(${rect.left},${rect.top},${rect.right},${rect.bottom})"
            }
            touchDiagnostics.log("gestureExclusion rects=$rectSummary", true)
        }
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            composeView.systemGestureExclusionRects = mutableListOf()
        }
    }

    private fun resolveExclusionRects(
        density: Density,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        maxVerticalScrollOffsetPx: Float,
        maxHorizontalScrollOffsetPx: Float
    ): MutableList<Rect> {
        // 之前这里为了“保护滚动条/边缘拖拽”设置了较大的 systemGestureExclusionRects，
        // 会显著降低右侧返回手势（以及部分机型底部手势）的触发成功率。
        //
        // 当前滚动条已改为“长按后拖动”，且拖动方向主要为纵向，不需要系统层面的排他保护。
        // 因此直接关闭排他区域，让系统手势保持自然。
        return mutableListOf()
    }
}

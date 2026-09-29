package com.wuxianggujun.tinaide.core.editorview

import android.os.Build
import android.view.View
import android.widget.Magnifier
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.max

internal class SelectionMagnifierController(
    private val hostView: View
) {
    private companion object {
        // 放大镜窗口尺寸：
        // - 之前 100x70dp 太窄，用户很难看清更多上下文。
        // - 这里调整为更宽一些的尺寸（更接近 Sora 的观感）。
        private const val MAGNIFIER_WIDTH_DP = 280f

        // 高度保持与之前一致，避免放大镜位置（Y 方向）发生额外变化
        private const val MAGNIFIER_HEIGHT_DP = 70f
        private const val MAGNIFIER_CORNER_RADIUS_DP = 8f
        private const val MAGNIFIER_ELEVATION_DP = 4f
        private const val MAGNIFIER_ZOOM = 1.25f
        private const val MAGNIFIER_MAX_TEXT_SIZE_SP = 28f
    }

    private val density = hostView.resources.displayMetrics.density.coerceAtLeast(0.01f)
    private fun dpToPx(dp: Float): Int = (dp * density + 0.5f).toInt().coerceAtLeast(1)
    private fun dpToPxF(dp: Float): Float = (dp * density).coerceAtLeast(0f)

    // Magnifier.Builder 仅 API 29+ 可用；API 28 (Android 9) 必须走基础构造器，
    // 否则在加载 <init> 做类校验时会抛 NoClassDefFoundError: Magnifier$Builder。
    private val magnifier: Magnifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildCustomMagnifier()
        } else {
            @Suppress("DEPRECATION")
            Magnifier(hostView)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCustomMagnifier(): Magnifier = Magnifier.Builder(hostView)
        .setSize(dpToPx(MAGNIFIER_WIDTH_DP), dpToPx(MAGNIFIER_HEIGHT_DP))
        .setCornerRadius(dpToPxF(MAGNIFIER_CORNER_RADIUS_DP))
        .setElevation(dpToPxF(MAGNIFIER_ELEVATION_DP))
        .setInitialZoom(MAGNIFIER_ZOOM)
        .build()

    private val magnifierWidthPx = dpToPxF(MAGNIFIER_WIDTH_DP)
    private val magnifierHeightPx = dpToPxF(MAGNIFIER_HEIGHT_DP)

    private val locationInWindowTmp = IntArray(2)

    private val hostViewWidthPx: Float
        get() = hostView.width.toFloat().coerceAtLeast(1f)

    private val hostViewHeightPx: Float
        get() = hostView.height.toFloat().coerceAtLeast(1f)

    private var visible = false

    /**
     * 在拖动选择句柄时更新放大镜。
     *
     * 关键点：
     * 1. 坐标必须从 Canvas 局部坐标转换到 hostView 的局部坐标，否则会放大到“别的 UI 元素”。
     * 2. sourceY 要偏移到被选中文本所在行，而不是句柄圆点区域（对齐 Sora 的观感）。
     */
    fun update(
        pointerInCanvasPx: Offset,
        canvasOriginInWindowPx: IntOffset,
        hostViewOriginInWindowPx: IntOffset,
        lineHeightPx: Float,
        handleRadiusPx: Float,
        clampLeftInCanvasPx: Float,
        clampRightInCanvasPx: Float,
        fontSizeSp: Float
    ) {
        // 对齐 Sora：字体足够大时不显示放大镜（避免冗余与遮挡）。
        if (fontSizeSp > MAGNIFIER_MAX_TEXT_SIZE_SP) {
            dismiss()
            return
        }

        // 先把 pointer 从 Canvas 局部坐标转换到 Window 坐标
        val pointerWindowX = canvasOriginInWindowPx.x.toFloat() + pointerInCanvasPx.x
        val pointerWindowY = canvasOriginInWindowPx.y.toFloat() + pointerInCanvasPx.y

        // 再把 Window 坐标转换到 hostView 局部坐标（Magnifier.show 需要的是 View 内部坐标）
        val sourceYOffsetPx = lineHeightPx + handleRadiusPx
        val sourceWindowX = pointerWindowX.coerceIn(
            canvasOriginInWindowPx.x.toFloat() + clampLeftInCanvasPx,
            canvasOriginInWindowPx.x.toFloat() + clampRightInCanvasPx
        )
        val sourceWindowY = (pointerWindowY - sourceYOffsetPx)
            .coerceAtLeast(canvasOriginInWindowPx.y.toFloat())

        val sourceViewX = (sourceWindowX - hostViewOriginInWindowPx.x.toFloat())
            .coerceIn(0f, hostViewWidthPx)
        val sourceViewY = (sourceWindowY - hostViewOriginInWindowPx.y.toFloat())
            .coerceIn(0f, hostViewHeightPx)

        // 放大镜窗口位置：
        // - 相比之前再上浮一点，减少贴近手指/句柄的拥挤感；
        // - 同时结合 handleRadius，让单光标句柄与选区句柄都保持自然间距。
        val halfW = magnifierWidthPx * 0.5f
        val halfH = magnifierHeightPx * 0.5f
        val minCenterX = minOf(halfW, hostViewWidthPx * 0.5f)
        val maxCenterX = max(minCenterX, hostViewWidthPx - halfW)
        val minCenterY = minOf(halfH, hostViewHeightPx * 0.5f)
        val maxCenterY = max(minCenterY, hostViewHeightPx - halfH)

        val centerX = sourceViewX.coerceIn(minCenterX, maxCenterX)
        val floatingGapY = lineHeightPx * 1.15f + handleRadiusPx * 0.75f
        val centerY = (sourceViewY - (floatingGapY + halfH)).coerceIn(minCenterY, maxCenterY)

        // Magnifier.show(sx, sy, cx, cy) 仅 API 29+ 可用；API 28 退化为自动定位的 2 参版本。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            magnifier.show(sourceViewX, sourceViewY, centerX, centerY)
        } else {
            @Suppress("DEPRECATION")
            magnifier.show(sourceViewX, sourceViewY)
        }
        visible = true
    }

    fun hostViewOriginInWindowPx(): IntOffset {
        hostView.getLocationInWindow(locationInWindowTmp)
        return IntOffset(locationInWindowTmp[0], locationInWindowTmp[1])
    }

    fun dismiss() {
        if (!visible) return
        magnifier.dismiss()
        visible = false
    }

    fun release() {
        dismiss()
        // android.widget.Magnifier 没有显式释放接口；dismiss 后交给 GC 即可
    }
}

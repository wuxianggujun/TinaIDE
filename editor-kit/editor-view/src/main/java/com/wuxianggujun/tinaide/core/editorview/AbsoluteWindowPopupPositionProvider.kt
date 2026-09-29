package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * 使用调用方预先算好的窗口绝对坐标放置 Popup。
 *
 * Why: `Popup(offset = ...)` 默认的 AlignmentOffsetPositionProvider 会把 offset 加在
 * 父组件 anchorBounds 的 topLeft 上，导致 overlay 位于非 (0,0) 父布局时出现坐标被
 * 二次叠加的问题（例如 Scaffold 内容区被 TopBar 挤到 y=topBarHeight，补全弹窗会被
 * 额外下推到 IME 顶部）。
 *
 * How to apply: 当 Resolver 已经把 offset 计算成"窗口绝对坐标"时，直接用本 Provider
 * 替换默认定位，calculatePosition 忽略 anchorBounds，按绝对坐标返回。
 */
internal class AbsoluteWindowPopupPositionProvider(
    private val offset: IntOffset
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = offset
}

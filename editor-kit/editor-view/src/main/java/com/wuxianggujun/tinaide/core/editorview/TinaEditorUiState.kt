package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

internal class TinaEditorUiState(
    density: Density
) {
    var canvasHeightPx by mutableStateOf(with(density) { 360.dp.toPx() })
    var canvasWidthPx by mutableStateOf(with(density) { 640.dp.toPx() })
    var canvasOriginInWindowPx by mutableStateOf(IntOffset.Zero)
    var contentStartXPx by mutableStateOf(0f)
    var contextMenuVisible by mutableStateOf(false)
    var contextMenuOffset by mutableStateOf(IntOffset.Zero)
    var contextMenuKeyboardAction by mutableStateOf<EditorContextMenuActionId?>(null)
    var hoverOffset by mutableStateOf(IntOffset.Zero)

    /**
     * 双指缩放的手势焦点（Canvas 本地坐标）。
     *
     * 说明：
     * - 该值会在 pointer stream 中高频更新（每帧），不应作为 Compose state，
     *   否则会导致无意义重组甚至触发指针协程重启，进而引入“焦点落后一帧”的缩放漂移。
     * - 该值仅用于缩放锚定与调试日志，不参与 UI 直接渲染。
     */
    var transformGestureFocus: Offset? = null
    var transformGestureFocusPointerCount: Int = 0
    var transformGestureFocusStablePointerCount: Int = 0
    var transformGestureFocusBasis: TransformGestureFocusBasis = TransformGestureFocusBasis.NONE

    // 仅用于诊断：焦点更新时间与序列号（避免日志里“看不出是不是落后一帧”）。
    var transformGestureFocusUpdatedAtMs: Long = 0L
    var transformGestureFocusSeq: Long = 0L

    // ========== 缩放手势视觉变换 ==========
    // 在双指缩放手势期间，不实际改变字体大小，而是通过 Canvas scale 变换实现视觉缩放。
    // 手势结束后一次性应用最终字体大小并调整滚动位置。
    // 使用 mutableStateOf 以便 Canvas draw block 能观察到变化并触发重绘。
    var scaleGestureVisualScale by mutableStateOf(1f)

    // 缩放焦点（Canvas 本地坐标），在手势首帧锁定。
    // 不需要是 Compose state，因为只在 Canvas draw block 内读取（由 scaleGestureVisualScale 驱动重绘）。
    var scaleGestureVisualPivotX: Float = 0f
    var scaleGestureVisualPivotY: Float = 0f

    var composeFocusActive by mutableStateOf(false)
    var activeScrollbarDrag by mutableStateOf<ActiveScrollbarDrag?>(null)
    var activeSelectionHandle by mutableStateOf<SelectionHandleKind?>(null)
    var isCursorHandleDragging by mutableStateOf(false)

    fun setContextMenuVisible(
        visible: Boolean,
        keyboardAction: EditorContextMenuActionId? = null
    ) {
        contextMenuKeyboardAction = keyboardAction
        contextMenuVisible = visible
    }
}

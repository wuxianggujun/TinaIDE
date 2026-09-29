package com.wuxianggujun.tinaide.core.editorview

sealed interface EditorEvent {
    data class TextChanged(
        val reason: String,
        val version: Long,
        val length: Int
    ) : EditorEvent

    data class CursorMoved(
        val oldOffset: Int,
        val newOffset: Int
    ) : EditorEvent

    data class SelectionChanged(
        val range: OffsetRange?
    ) : EditorEvent

    data class ScrollChanged(
        val offsetX: Float,
        val offsetY: Float
    ) : EditorEvent

    data class FocusChanged(
        val focused: Boolean
    ) : EditorEvent
}

package com.wuxianggujun.tinaide.core.editorview

import kotlin.math.abs

internal class EditorFontScaleCoordinator(
    private val state: EditorState
) {
    fun apply(rawSizeSp: Float) {
        val targetSize = rawSizeSp.coerceIn(10f, 40f)
        if (abs(targetSize - state.fontSizeSp) < 0.1f) return
        state.fontSizeSp = targetSize
        state.runtimeOptions.onFontSizeChanged(targetSize)
    }

    fun applyWithAnchor(
        rawSizeSp: Float,
        focusX: Float,
        focusY: Float,
        contentStartXPx: Float
    ) {
        val previousSize = state.fontSizeSp
        val targetSize = rawSizeSp.coerceIn(10f, 40f)
        if (abs(targetSize - previousSize) < 0.1f) return

        val scale = (targetSize / previousSize).coerceIn(0.5f, 2f)
        // scrollOffsetX 只作用在文本区（不包含行号/ gutter），因此横向锚点必须使用“文本区内的 viewportX”。
        // 否则在固定栏存在时会出现缩放后内容向左/向右“漂移”的错位感。
        val textViewportWidthPx = state.viewportWidthPx.coerceAtLeast(1f)
        val focusXInTextViewport = (focusX - contentStartXPx)
            .coerceIn(0f, textViewportWidthPx)
        val targetScrollX = if (state.config.wordWrap) {
            0f
        } else {
            (state.scrollOffsetXPx + focusXInTextViewport) * scale - focusXInTextViewport
        }
        val targetScrollY = (state.scrollOffsetPx + focusY) * scale - focusY

        state.fontSizeSp = targetSize
        state.scrollOffsetXPx = targetScrollX.coerceAtLeast(0f)
        state.scrollOffsetPx = targetScrollY.coerceAtLeast(0f)
        state.runtimeOptions.onFontSizeChanged(targetSize)
    }
}

package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope

internal data class FoldEndLineCursorInfo(
    val foldStartLine: Int,
    val foldStartLineText: String,
    val foldStartLineTrimmedEndCol: Int,
    val endLineTrimStartCol: Int,
    val badgeMargin: Float,
    val badgeWidth: Float
)

internal class CursorRenderer {
    fun drawCursor(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        foldEndLineInfo: FoldEndLineCursorInfo? = null
    ) {
        val state = frameContext.state
        if (!state.isFocused) return
        val layout = resolveCursorHandleLayout(
            state = state,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = frameContext::lineText,
            foldEndLineInfo = foldEndLineInfo,
            textScanCache = frameContext.textScanCache
        ) ?: return
        drawScope.drawLine(
            color = state.colorScheme.cursor,
            start = Offset(layout.cursorX, layout.cursorTop),
            end = Offset(layout.cursorX, layout.cursorBottom),
            strokeWidth = 2f
        )
    }

    fun drawCursorHandle(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        foldEndLineInfo: FoldEndLineCursorInfo? = null
    ) {
        val state = frameContext.state
        val range = state.selectionRange
        if (!state.isFocused || (range != null && !range.isEmpty)) return
        val layout = resolveCursorHandleLayout(
            state = state,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = frameContext::lineText,
            foldEndLineInfo = foldEndLineInfo,
            textScanCache = frameContext.textScanCache
        ) ?: return
        val handleColor = state.colorScheme.selectionHandle
        // 让连杆明显一点，但仍轻微插入圆点内部，观感更像 Sora 的 side-drop handle。
        val stemBottom = (layout.handleCenter.y - layout.drawRadiusPx * 0.78f)
            .coerceAtLeast(layout.cursorBottom)
        drawScope.drawLine(
            color = handleColor,
            start = Offset(layout.cursorX, layout.cursorBottom),
            end = Offset(layout.cursorX, stemBottom),
            strokeWidth = (layout.drawRadiusPx * 0.52f).coerceAtLeast(2f)
        )
        drawScope.drawCircle(
            color = handleColor,
            radius = layout.drawRadiusPx,
            center = layout.handleCenter
        )
    }
}

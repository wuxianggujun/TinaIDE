package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.textengine.Position
import kotlin.math.abs

internal class EditorGestureCoordinator(
    private val state: EditorState,
    private val renderer: EditorRenderEngine,
    private val lineNumberPaint: Paint,
    private val textPaint: Paint,
    private val density: Density,
    private val lineLayoutCache: EditorLineLayoutCache,
    private val gestureHandler: EditorGestureHandler,
    private val transformableState: TransformableState,
    private val interactionController: EditorInteractionController,
    private val onContextMenuVisibleChange: (Boolean) -> Unit,
    private val onContextMenuOffsetChange: (IntOffset) -> Unit
) {
    private val lineTextLookup = EditorLineTextLookup(state)

    private fun syncPaintForHitTest() {
        val fontPx = with(density) { state.fontSizeSp.sp.toPx() }
        val typeface = state.typeface
        textPaint.typeface = typeface
        textPaint.textSize = fontPx
        lineNumberPaint.typeface = typeface
        lineNumberPaint.textSize = fontPx
    }

    fun onPointerCountChanged(pointerCount: Int) {
        if (gestureHandler.onPointerCountChanged(pointerCount, isTransformInProgress())) {
            onContextMenuVisibleChange(false)
        }
    }

    fun onTap(position: Offset, isCtrlPressed: Boolean = false) {
        if (shouldBlockBasicGestures()) return
        onContextMenuVisibleChange(false)
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }

        when {
            gutterX < zones.lineNumberEndX -> {
                gestureHandler.clearTextTapTracking()
                state.onLineNumberTap?.invoke(line)
                return
            }
            gutterX < zones.gutterEndX -> {
                gestureHandler.clearTextTapTracking()
                // gutter 仅用于折叠：可折叠行优先触发折叠，否则回退为 gutterTap（例如书签）。
                state.dispatchGutterFoldToggle(line)
                return
            }
            gutterX < zones.textStartX -> {
                gestureHandler.clearTextTapTracking()
                return
            }
        }

        if (state.isCollapsedFoldStart(line)) {
            val contentX = (viewportX - zones.textStartX + state.scrollOffsetXPx).coerceAtLeast(0f)
            if (renderer.isFoldBadgeHit(line, contentX, state, textPaint)) {
                gestureHandler.clearTextTapTracking()
                state.toggleFoldAtLine(line)
                return
            }
            val foldEndOffset = renderer.resolveFoldEndLineOffset(line, contentX, state, textPaint)
            if (foldEndOffset >= 0) {
                gestureHandler.clearTextTapTracking()
                state.moveCursorTo(foldEndOffset)
                interactionController.requestEditorFocusAndKeyboard()
                interactionController.syncSelectionToIme()
                return
            }
        }

        val lineText = lineTextLookup.lineText(line)
        val column = resolveColumnFromViewportX(
            visualLine = visualLine,
            line = line,
            lineText = lineText,
            viewportX = position.x,
            textStartX = zones.textStartX
        )
        val offset = state.textBuffer.positionToOffset(line, column)
        if (isCtrlPressed && state.onRequestGotoDefinition != null && state.hasWordAt(line, column)) {
            gestureHandler.clearTextTapTracking()
            state.moveCursorTo(offset)
            state.onRequestGotoDefinition?.invoke()
            return
        }

        val isDoubleTap = gestureHandler.registerTextTap(position)
        if (isDoubleTap) {
            val selected = state.selectWord(line, column)
            if (!selected) {
                state.moveCursorTo(offset)
            } else {
                showSelectionUi(position)
            }
        } else {
            state.moveCursorTo(offset)
        }
        interactionController.requestEditorFocusAndKeyboard()
        interactionController.syncSelectionToIme()
    }

    fun onLongPress(position: Offset) {
        if (shouldBlockBasicGestures()) return
        gestureHandler.clearTextTapTracking()
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }

        when {
            gutterX < zones.lineNumberEndX -> {
                state.onLineNumberLongPress?.invoke(line)
                return
            }
            gutterX < zones.gutterEndX -> {
                state.dispatchGutterLongPress(line)
                return
            }
            gutterX < zones.textStartX -> {
                return
            }
        }

        val lineText = lineTextLookup.lineText(line)
        val column = resolveColumnFromViewportX(
            visualLine = visualLine,
            line = line,
            lineText = lineText,
            viewportX = position.x,
            textStartX = zones.textStartX
        )
        val pressOffset = state.textBuffer.positionToOffset(line, column)

        val existingRange = state.selectionRange
        val keepExistingSelection = if (existingRange != null && !existingRange.isEmpty) {
            pressOffset in existingRange.start..existingRange.end
        } else {
            false
        }

        if (!keepExistingSelection) {
            val selected = state.selectWord(line, column)
            if (!selected) {
                // 没有选中单词（空白区域/空文件/行尾等），移动光标到长按位置
                state.moveCursorTo(pressOffset)
            }
        }

        // 无论是否选中了单词，长按都显示上下文菜单。
        // 空白区域长按的主要用途是粘贴剪贴板内容，若不显示菜单则无法粘贴。
        interactionController.requestEditorFocus()
        interactionController.syncSelectionToIme()
        showSelectionUi(position)
    }

    fun onSecondaryClick(position: Offset) {
        if (shouldBlockBasicGestures()) return
        gestureHandler.clearTextTapTracking()
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }
        if (gutterX < zones.textStartX) return

        val offset = resolveOffsetFromViewportX(
            visualLine = visualLine,
            line = line,
            viewportX = position.x,
            textStartX = zones.textStartX
        )
        val selection = state.selectionRange
        if (selection == null || selection.isEmpty || offset !in selection.start..selection.end) {
            state.moveCursorTo(offset)
        }
        onContextMenuOffsetChange(IntOffset(position.x.toInt(), position.y.toInt()))
        onContextMenuVisibleChange(true)
        interactionController.requestEditorFocus()
        interactionController.syncSelectionToIme()
    }

    fun resolveHoverTarget(position: Offset): EditorMouseHoverTarget? {
        if (shouldBlockBasicGestures()) return null
        if (state.onRequestHover == null) return null
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }
        if (gutterX < zones.textStartX) return null

        val lineText = lineTextLookup.lineText(line)
        val column = resolveColumnFromViewportX(
            visualLine = visualLine,
            line = line,
            lineText = lineText,
            viewportX = position.x,
            textStartX = zones.textStartX
        )
        if (!state.hasWordAt(line, column)) return null
        return EditorMouseHoverTarget(
            position = Position(line, column),
            anchorInViewportPx = IntOffset(position.x.toInt(), position.y.toInt())
        )
    }

    fun onCursorDragStart(position: Offset) {
        if (shouldBlockBasicGestures()) {
            gestureHandler.cancelCursorDrag()
            return
        }
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }

        if (gutterX < zones.textStartX) {
            gestureHandler.cancelCursorDrag()
            return
        }

        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val dragOffset = resolveOffsetFromViewportX(
            visualLine = visualLine,
            line = line,
            viewportX = position.x,
            textStartX = zones.textStartX
        )
        state.moveCursorTo(dragOffset)
        gestureHandler.startCursorDrag()
        onContextMenuVisibleChange(false)
        interactionController.requestEditorFocusAndKeyboard()
        interactionController.syncSelectionToIme()
    }

    fun onCursorDrag(position: Offset): Boolean {
        if (!gestureHandler.isCursorDragActive) {
            return false
        }
        val textStartX = renderer.contentStartX(state, lineNumberPaint)
        val visualLine = state.visualLineFromViewportY(position.y)
        val dragOffset = resolveOffsetFromViewportX(
            visualLine = visualLine,
            line = state.docLineForVisualLine(visualLine),
            viewportX = position.x,
            textStartX = textStartX
        )
        state.moveCursorTo(dragOffset)
        gestureHandler.markCursorDragMoved()
        interactionController.syncSelectionToIme()
        return true
    }

    fun onCursorDragEnd() {
        gestureHandler.finishCursorDrag()
        interactionController.syncSelectionToIme()
    }

    fun onCursorDragCancel() {
        gestureHandler.cancelCursorDrag()
        interactionController.syncSelectionToIme()
    }

    fun onSelectionDragStart(position: Offset) {
        if (shouldBlockBasicGestures()) {
            gestureHandler.cancelSelectionDrag()
            return
        }
        syncPaintForHitTest()
        val zones = renderer.hitZones(state, lineNumberPaint)
        val viewportX = position.x
        val gutterX = if (state.pinLineNumber) {
            viewportX
        } else {
            viewportX + state.scrollOffsetXPx
        }

        if (gutterX < zones.textStartX) {
            gestureHandler.cancelSelectionDrag()
            return
        }

        val existingRange = state.selectionRange
        if (existingRange == null || existingRange.isEmpty) {
            gestureHandler.cancelSelectionDrag()
            return
        }

        val visualLine = state.visualLineFromViewportY(position.y)
        val line = state.docLineForVisualLine(visualLine)
        val dragOffset = resolveOffsetFromViewportX(
            visualLine = visualLine,
            line = line,
            viewportX = position.x,
            textStartX = zones.textStartX
        )

        // 仅在触点落在当前选区内时才进入"拖拽调整选区"模式，避免选区存在时拦截正常滚动。
        if (dragOffset < existingRange.start || dragOffset > existingRange.end) {
            gestureHandler.cancelSelectionDrag()
            return
        }
        val anchorOffset = if (abs(dragOffset - existingRange.start) <= abs(dragOffset - existingRange.end)) {
            existingRange.end
        } else {
            existingRange.start
        }

        gestureHandler.startSelectionDrag(
            anchorOffset = IntOffset(position.x.toInt(), position.y.toInt()),
            anchorCharOffset = anchorOffset
        )
        onContextMenuVisibleChange(false)
        state.selectRange(startOffset = anchorOffset, endOffset = dragOffset)
        interactionController.requestEditorFocusAndKeyboard()
        interactionController.syncSelectionToIme()
    }

    fun onSelectionDrag(position: Offset): Boolean {
        if (!gestureHandler.isSelectionDragActive) {
            return false
        }
        val textStartX = renderer.contentStartX(state, lineNumberPaint)
        val anchorOffset = gestureHandler.selectionDragAnchorOffset ?: return false
        val visualLine = state.visualLineFromViewportY(position.y)
        val dragOffset = resolveOffsetFromViewportX(
            visualLine = visualLine,
            line = state.docLineForVisualLine(visualLine),
            viewportX = position.x,
            textStartX = textStartX
        )
        state.selectRange(startOffset = anchorOffset, endOffset = dragOffset)
        gestureHandler.markSelectionDragMoved()
        interactionController.syncSelectionToIme()
        return true
    }

    fun onSelectionDragEnd() {
        val menuAnchor = gestureHandler.finishSelectionDrag()
        if (menuAnchor != null) {
            onContextMenuOffsetChange(menuAnchor)
            onContextMenuVisibleChange(true)
        }
        interactionController.syncSelectionToIme()
    }

    fun onSelectionDragCancel() {
        gestureHandler.cancelSelectionDrag()
        interactionController.syncSelectionToIme()
    }

    private fun shouldBlockBasicGestures(): Boolean = gestureHandler.shouldBlockBasicGestures(isTransformInProgress())

    private fun isTransformInProgress(): Boolean = transformableState.isTransformInProgress

    private fun showSelectionUi(position: Offset) {
        onContextMenuOffsetChange(IntOffset(position.x.toInt(), position.y.toInt()))
        onContextMenuVisibleChange(true)
    }

    /**
     * 通过 Paint.measureText 反解点击位置对应的 charOffset，保证命中与光标渲染同一坐标体系。
     */
    private fun resolveOffsetFromViewportX(
        visualLine: Int,
        line: Int,
        viewportX: Float,
        textStartX: Float
    ): Int {
        val lineText = lineTextLookup.lineText(line)
        val column = resolveColumnFromViewportX(
            visualLine = visualLine,
            line = line,
            lineText = lineText,
            viewportX = viewportX,
            textStartX = textStartX
        )
        return state.textBuffer.positionToOffset(line, column)
    }

    /**
     * 通过 Paint.measureText 反解点击位置对应列号，保证命中与光标渲染同一坐标体系。
     */
    private fun resolveColumnFromViewportX(
        visualLine: Int,
        line: Int,
        lineText: String,
        viewportX: Float,
        textStartX: Float
    ): Int {
        // 手势回调发生在 draw 之外，确保 Paint 与当前字体大小一致，避免命中与渲染出现"像素级偏差"。
        textPaint.typeface = state.typeface
        textPaint.textSize = with(density) { state.fontSizeSp.sp.toPx() }
        val contentX = (viewportX - textStartX + state.scrollOffsetXPx).coerceAtLeast(0f)
        val visualStartColumn = state.visualLineStartColumn(visualLine).coerceIn(0, lineText.length)
        val visualEndColumn = state.visualLineEndColumn(visualLine).coerceIn(visualStartColumn, lineText.length)
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            state = state,
            line = line,
            lineText = lineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
        )
        val segmentStartXInText =
            prefixLayout.segmentStartAdvance(visualStartColumn.coerceIn(0, prefixLayout.length))
        val rawColumn = lineLayoutCache.xToColumn(
            layout = prefixLayout,
            contentX = segmentStartXInText + contentX
        )
        return rawColumn.coerceIn(visualStartColumn, visualEndColumn)
    }
}

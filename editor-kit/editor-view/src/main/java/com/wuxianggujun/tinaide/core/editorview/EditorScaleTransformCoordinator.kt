package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 统一坐标系缩放协调器。
 *
 * 核心设计：双指缩放手势期间 **不改变字体大小**，仅更新 [TinaEditorUiState.scaleGestureVisualScale]，
 * 由 [EditorCanvasLayer] 通过 `Canvas.scale()` 变换实现视觉缩放。
 * 手势结束后，一次性应用最终字体大小并调整滚动位置。
 *
 * 优势：
 * - 手势期间无 Paint textSize 变化 → 无 Layout Cache 重建 → 无前缀宽度数组重算 → 无帧预算超支
 * - 滚动偏移不变 → 无每帧 state 多属性写入 → 无 Compose snapshot 风暴
 * - Canvas scale 是 GPU 加速的矩阵变换 → 几乎零开销
 */
internal class EditorScaleTransformCoordinator(
    private val state: EditorState,
    private val ui: TinaEditorUiState,
    private val density: Density,
    private val textPaint: Paint,
    private val lineNumberPaint: Paint,
    private val renderer: EditorRenderEngine,
    private val lineLayoutCache: EditorLineLayoutCache,
    private val touchDiagnostics: EditorTouchDiagnostics,
    private val interactionController: EditorInteractionController,
    private val gestureHandler: EditorGestureHandler,
    private val fontScaleCoordinator: EditorFontScaleCoordinator,
    private val scrollbarVisibilityCoordinator: ScrollbarVisibilityCoordinator
) {
    private val lineTextLookup = EditorLineTextLookup(state)
    private var wasTransformInProgress: Boolean = false

    // ========== 手势状态：在手势首帧初始化，手势结束时清除 ==========
    private var gestureInitialFontSizeSp: Float? = null
    private var gestureInitialLineHeight: Float? = null
    private var gestureInitialCharWidth: Float? = null
    private var gestureInitialContentStartX: Float? = null
    private var gestureInitialScrollY: Float? = null
    private var gestureInitialScrollX: Float? = null
    private var gestureInitialFocus: Offset? = null
    private var gestureTargetFontSizeSp: Float? = null

    fun onScaleGesture(zoomChange: Float, panChange: Offset, rotationChange: Float) {
        val shouldScale = abs(zoomChange - 1f) > 0.002f
        if (!shouldScale) return

        val focus = ui.transformGestureFocus
            ?: Offset(ui.canvasWidthPx * 0.5f, ui.canvasHeightPx * 0.5f)

        // 首帧：记录初始状态
        if (gestureInitialFontSizeSp == null) {
            val currentFontPx = with(density) { state.fontSizeSp.sp.toPx() }
            textPaint.typeface = state.typeface
            lineNumberPaint.typeface = state.typeface
            textPaint.textSize = currentFontPx
            lineNumberPaint.textSize = currentFontPx
            val metrics = textPaint.fontMetrics

            gestureInitialFontSizeSp = state.fontSizeSp
            gestureInitialLineHeight =
                (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
            gestureInitialCharWidth = textPaint.measureText("0").coerceAtLeast(1f)
            gestureInitialContentStartX = renderer.contentStartX(state, lineNumberPaint)
            gestureInitialScrollY = state.scrollOffsetPx
            gestureInitialScrollX = state.scrollOffsetXPx
            gestureInitialFocus = focus
            gestureTargetFontSizeSp = state.fontSizeSp

            ui.scaleGestureVisualPivotX = focus.x
            ui.scaleGestureVisualPivotY = focus.y

            if (state.config.wordWrap) {
                state.freezeWordWrapLayoutIfNeeded()
            }
            state.pendingScaleAnchor = null
        }

        // 累积缩放
        val newTarget = (gestureTargetFontSizeSp!! * zoomChange).coerceIn(10f, 40f)
        if (abs(newTarget - gestureTargetFontSizeSp!!) < 0.05f &&
            abs(newTarget - gestureInitialFontSizeSp!!) < 0.1f
        ) {
            return
        }
        gestureTargetFontSizeSp = newTarget

        // 更新视觉缩放比例（Canvas 会读取此值应用 scale 变换）
        ui.scaleGestureVisualScale = newTarget / gestureInitialFontSizeSp!!

        // 副作用（取消补全、隐藏菜单、显示滚动条）
        interactionController.cancelPendingCompletionRequest()
        ui.setContextMenuVisible(false)
        scrollbarVisibilityCoordinator.trigger()
        gestureHandler.onScaleApplied()

        // 节流诊断日志
        touchDiagnostics.logThrottled(
            category = EditorTouchLogCategory.SCALE,
            throttleKey = "scale-step",
            minIntervalMs = 120L
        ) {
            val now = SystemClock.uptimeMillis()
            "scaleVisual t=$now " +
                "fontSp=${gestureInitialFontSizeSp!!.format1()}→${newTarget.format1()} " +
                "visualScale=${ui.scaleGestureVisualScale.format3()} " +
                "zoom=$zoomChange " +
                "focus=(${focus.x.format1()},${focus.y.format1()}) " +
                "pivot=(${ui.scaleGestureVisualPivotX.format1()},${ui.scaleGestureVisualPivotY.format1()}) " +
                "cfg=(wrap=${state.config.wordWrap},pinLN=${state.pinLineNumber})"
        }
    }

    fun onTransformProgressChanged(inProgress: Boolean) {
        if (wasTransformInProgress && !inProgress) {
            applyFinalScaleAndScroll()
        }
        wasTransformInProgress = inProgress
    }

    /**
     * 手势结束：一次性应用最终字体大小和滚动位置。
     */
    private fun applyFinalScaleAndScroll() {
        val initialFontSizeSp = gestureInitialFontSizeSp
        val targetFontSizeSp = gestureTargetFontSizeSp

        // 先清除视觉缩放（在应用最终值之前，避免"旧视觉缩放 + 新字体"同时存在的中间状态）
        ui.scaleGestureVisualScale = 1f

        if (initialFontSizeSp == null || targetFontSizeSp == null) {
            clearGestureState()
            return
        }

        val fontChanged = abs(targetFontSizeSp - initialFontSizeSp) >= 0.1f
        if (!fontChanged) {
            if (state.isWordWrapLayoutFrozen()) {
                state.unfreezeWordWrapLayout()
            }
            clearGestureState()
            return
        }

        val stableFocus = gestureInitialFocus!!
        val initialScrollY = gestureInitialScrollY!!
        val initialScrollX = gestureInitialScrollX!!
        val initialLineHeight = gestureInitialLineHeight!!
        val initialCharWidth = gestureInitialCharWidth!!
        val initialContentStartX = gestureInitialContentStartX!!

        // 用 Paint 测量目标字体的度量
        textPaint.typeface = state.typeface
        lineNumberPaint.typeface = state.typeface
        val targetFontPx = with(density) { targetFontSizeSp.sp.toPx() }
        textPaint.textSize = targetFontPx
        lineNumberPaint.textSize = targetFontPx
        val targetMetrics = textPaint.fontMetrics
        val targetLineHeight =
            (targetMetrics.descent - targetMetrics.ascent + targetMetrics.leading).coerceAtLeast(1f)
        val targetCharWidth = textPaint.measureText("0").coerceAtLeast(1f)
        val targetContentStartX = renderer.contentStartX(state, lineNumberPaint)
        val targetTextViewportWidthPx =
            (ui.canvasWidthPx - targetContentStartX).coerceAtLeast(1f)

        // 累积锚点法计算最终滚动偏移
        val cumulativeHeightFactor = targetLineHeight / initialLineHeight
        val cumulativeWidthFactor = targetCharWidth / initialCharWidth

        val afterScrollY =
            (initialScrollY + stableFocus.y) * cumulativeHeightFactor - stableFocus.y

        val initialFocusXInTextViewport = if (state.pinLineNumber) {
            (stableFocus.x - initialContentStartX)
                .coerceIn(0f, (ui.canvasWidthPx - initialContentStartX).coerceAtLeast(1f))
        } else {
            stableFocus.x - initialContentStartX
        }
        val targetFocusXInTextViewport = if (state.pinLineNumber) {
            (stableFocus.x - targetContentStartX)
                .coerceIn(0f, targetTextViewportWidthPx)
        } else {
            stableFocus.x - targetContentStartX
        }
        val afterScrollX =
            (initialScrollX + initialFocusXInTextViewport) * cumulativeWidthFactor - targetFocusXInTextViewport

        // 应用字体变更
        fontScaleCoordinator.apply(rawSizeSp = targetFontSizeSp)

        // 同步 metrics（避免下一帧 updateMetrics 前的"滞后一帧"）
        state.lineHeightPx = targetLineHeight
        state.charWidthPx = targetCharWidth
        state.contentStartXPx = targetContentStartX
        state.viewportWidthPx = targetTextViewportWidthPx

        // 应用滚动偏移
        val maxScrollY = state.maxVerticalScrollOffsetPx()
        state.scrollOffsetPx = afterScrollY.coerceIn(0f, maxScrollY)

        if (state.config.wordWrap) {
            state.scrollOffsetXPx = 0f
            // wordWrap 模式：设置 pendingScaleAnchor 以便 re-layout 后精确对齐
            computeAndSetPendingScaleAnchor(
                stableFocus = stableFocus,
                targetLineHeight = targetLineHeight,
                targetContentStartX = targetContentStartX,
                targetFontPx = targetFontPx
            )
            state.unfreezeWordWrapLayout()
        } else {
            val maxScrollX = state.maxHorizontalScrollOffsetPx()
            state.scrollOffsetXPx = afterScrollX.coerceAtLeast(0f).coerceAtMost(maxScrollX)
        }

        // 诊断日志
        if (touchDiagnostics.isScaleEnabled()) {
            touchDiagnostics.log(
                category = EditorTouchLogCategory.SCALE,
                message = "scaleEnd fontSp=${initialFontSizeSp.format1()}→${targetFontSizeSp.format1()} " +
                    "focus=(${stableFocus.x.toInt()},${stableFocus.y.toInt()}) " +
                    "scroll=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()}) " +
                    "lineHeight=${targetLineHeight.format1()} " +
                    "cfg=(wrap=${state.config.wordWrap},pinLN=${state.pinLineNumber})"
            )
        }

        clearGestureState()
    }

    /**
     * 为 wordWrap 模式计算 pendingScaleAnchor。
     * re-layout 后 [EditorState.updateMetrics] 会应用此锚点做最终对齐。
     */
    private fun computeAndSetPendingScaleAnchor(
        stableFocus: Offset,
        targetLineHeight: Float,
        targetContentStartX: Float,
        targetFontPx: Float
    ) {
        runCatching {
            val lineCount = state.textBuffer.lineCount
            if (lineCount <= 0) return@runCatching
            val rawVisualLine =
                ((state.scrollOffsetPx + stableFocus.y) / targetLineHeight).toInt()
            val maxVisualLine = (state.visualLineCount() - 1).coerceAtLeast(0)
            val visualLine = rawVisualLine.coerceIn(0, maxVisualLine)
            val docLine = state.docLineForVisualLine(visualLine).coerceIn(0, lineCount - 1)
            val lineText = lineTextLookup.lineText(docLine)
            val contentX =
                (stableFocus.x - targetContentStartX + state.scrollOffsetXPx).coerceAtLeast(0f)
            val visualStartColumn =
                state.visualLineStartColumn(visualLine).coerceIn(0, lineText.length)
            val visualEndColumn =
                state.visualLineEndColumn(visualLine).coerceIn(visualStartColumn, lineText.length)
            textPaint.textSize = targetFontPx
            val prefixLayout = lineLayoutCache.getPrefixLayout(
                state = state,
                line = docLine,
                lineText = lineText,
                textVersion = state.textBuffer.version,
                paint = textPaint,
                lineHeightPx = targetLineHeight,
            )
            val segmentStartXInTextPx =
                prefixLayout.segmentStartAdvance(visualStartColumn.coerceIn(0, prefixLayout.length))
            val rawColumn = lineLayoutCache.xToColumn(
                layout = prefixLayout,
                contentX = segmentStartXInTextPx + contentX
            )
            val safeColumn = rawColumn.coerceIn(visualStartColumn, visualEndColumn)
            val anchorOffset = state.textBuffer.positionToOffset(docLine, safeColumn)
            val offsetInLine =
                (state.scrollOffsetPx + stableFocus.y) - visualLine * targetLineHeight
            val ratio = (offsetInLine / targetLineHeight).coerceIn(0f, 1f)
            state.pendingScaleAnchor = EditorState.PendingScaleAnchor(
                charOffset = anchorOffset,
                focusX = stableFocus.x,
                focusY = stableFocus.y,
                focusYInVisualLineRatio = ratio
            )
        }
    }

    private fun clearGestureState() {
        gestureInitialFontSizeSp = null
        gestureInitialLineHeight = null
        gestureInitialCharWidth = null
        gestureInitialContentStartX = null
        gestureInitialScrollY = null
        gestureInitialScrollX = null
        gestureInitialFocus = null
        gestureTargetFontSizeSp = null
    }
}

private fun Float.format1(): String = String.format("%.1f", this)

private fun Float.format3(): String = String.format("%.3f", this)

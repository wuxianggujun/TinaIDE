package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal data class EditorRenderAssembly(
    val textPaint: Paint,
    val lineNumberPaint: Paint,
    val renderer: EditorRenderEngine,
    val scrollbarRenderer: EditorScrollbarRenderer,
    val selectionMagnifier: SelectionMagnifierController,
    val lineLayoutCache: EditorLineLayoutCache,
    val textScanCache: EditorTextScanCache
)

@Composable
internal fun rememberEditorRenderAssembly(
    density: Density,
    composeView: View,
    runtimeOptions: EditorRuntimeOptions
): EditorRenderAssembly {
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            color = android.graphics.Color.WHITE
        }
    }
    val lineNumberPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.MONOSPACE
            color = android.graphics.Color.GRAY
        }
    }

    // 行号区域左侧需要一点安全边距，避免数字/断点贴边后在圆角屏/边框上显得被“吃掉”。
    // 这个值不宜过大，否则会挤压文本区域；4dp 是一个比较克制的默认。
    val lineNumberEdgeStartPaddingPx = with(density) { 4.dp.toPx() }
    val lineNumberPaddingPx = with(density) { 4.dp.toPx() }
    val gutterMinWidthPx = with(density) { 18.dp.toPx() }
    val dividerMarginPx = with(density) { 2.dp.toPx() }
    val dividerWidthPx = with(density) { 1.dp.toPx() }
    val lineNumberRenderer = remember(lineNumberPaddingPx, lineNumberEdgeStartPaddingPx) {
        LineNumberRenderer(
            horizontalPaddingPx = lineNumberPaddingPx,
            edgeStartPaddingPx = lineNumberEdgeStartPaddingPx
        )
    }
    val gutterRenderer = remember(gutterMinWidthPx) { GutterRenderer(gutterMinWidthPx) }
    val lineLayoutCache = remember(runtimeOptions) {
        EditorLineLayoutCache(diagnosticsEnabled = runtimeOptions.layoutDiagnosticsEnabled)
    }
    val renderer = remember(
        lineNumberRenderer,
        gutterRenderer,
        lineLayoutCache,
        dividerMarginPx,
        dividerWidthPx
    ) {
        EditorRenderer(
            lineNumberRenderer = lineNumberRenderer,
            gutterRenderer = gutterRenderer,
            lineLayoutCache = lineLayoutCache,
            dividerMarginLeftPx = dividerMarginPx,
            dividerMarginRightPx = dividerMarginPx,
            dividerWidthPx = dividerWidthPx
        )
    }

    val scrollbarRenderer = remember { EditorScrollbarRenderer() }
    val selectionMagnifier = remember(composeView) { SelectionMagnifierController(composeView) }

    return EditorRenderAssembly(
        textPaint = textPaint,
        lineNumberPaint = lineNumberPaint,
        renderer = renderer,
        scrollbarRenderer = scrollbarRenderer,
        selectionMagnifier = selectionMagnifier,
        lineLayoutCache = lineLayoutCache,
        textScanCache = renderer.sharedTextScanCache
    )
}

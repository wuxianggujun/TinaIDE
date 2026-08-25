package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipboardManager
import android.graphics.Paint
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope

internal data class EditorSessionCoreRuntime(
    val ui: TinaEditorUiState,
    val coroutineScope: CoroutineScope,
    val textPaint: Paint,
    val lineNumberPaint: Paint,
    val renderer: EditorRenderEngine,
    val scrollbarRenderer: EditorScrollbarRenderer,
    val selectionMagnifier: SelectionMagnifierController,
    val lineLayoutCache: EditorLineLayoutCache,
    val textScanCache: EditorTextScanCache,
    val touchDiagnostics: EditorTouchDiagnostics,
    val gestureExclusionCoordinator: EditorGestureExclusionCoordinator,
    val gestureHandler: EditorGestureHandler,
    val interactionController: EditorInteractionController,
    val focusCoordinator: EditorFocusCoordinator,
    val fontScaleCoordinator: EditorFontScaleCoordinator,
    val clipboardCoordinator: EditorClipboardCoordinator,
    val scrollbarVisibilityCoordinator: ScrollbarVisibilityCoordinator,
    val gestureSuppressionMs: Long,
    val gestureReleaseSuppressionMs: Long
)

@Composable
internal fun rememberEditorSessionCoreRuntime(
    state: EditorState,
    context: android.content.Context,
    composeView: View,
    density: Density,
    coroutineScope: CoroutineScope,
    focusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?,
    inputMethodManager: InputMethodManager?,
    androidClipboardManager: ClipboardManager?,
    onWriteClipboard: suspend (android.content.ClipData) -> Unit
): EditorSessionCoreRuntime {
    val ui = remember { TinaEditorUiState(density) }

    val renderAssembly = rememberEditorRenderAssembly(
        density = density,
        composeView = composeView
    )
    val textPaint = renderAssembly.textPaint
    val lineNumberPaint = renderAssembly.lineNumberPaint
    val renderer = renderAssembly.renderer
    val scrollbarRenderer = renderAssembly.scrollbarRenderer
    val selectionMagnifier = renderAssembly.selectionMagnifier
    val lineLayoutCache = renderAssembly.lineLayoutCache
    val textScanCache = renderAssembly.textScanCache

    bindColumnXResolverEffect(
        state = state,
        lineLayoutCache = lineLayoutCache,
        density = density,
        textPaint = textPaint
    )

    val touchDiagnostics = remember { EditorTouchDiagnostics() }
    val gestureExclusionCoordinator = remember(composeView, touchDiagnostics) {
        EditorGestureExclusionCoordinator(
            composeView = composeView,
            touchDiagnostics = touchDiagnostics
        )
    }

    val gestureSuppressionMs = state.config.gestureSuppressionMs.coerceIn(60L, 500L)
    val gestureReleaseSuppressionMs = max(60L, gestureSuppressionMs * 2 / 3)
    val platformViewConfiguration = ViewConfiguration.get(context)
    val gestureDoubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(120L)
    val gestureDoubleTapSlopPx = platformViewConfiguration.scaledDoubleTapSlop.toFloat().coerceAtLeast(1f)
    val gestureHandler = remember(
        gestureSuppressionMs,
        gestureReleaseSuppressionMs,
        gestureDoubleTapTimeoutMs,
        gestureDoubleTapSlopPx
    ) {
        EditorGestureHandler(
            scaleSuppressionMs = gestureSuppressionMs,
            releaseSuppressionMs = gestureReleaseSuppressionMs,
            doubleTapTimeoutMs = gestureDoubleTapTimeoutMs,
            doubleTapSlopPx = gestureDoubleTapSlopPx
        )
    }

    val interactionController = remember(
        state,
        coroutineScope,
        focusRequester,
        keyboardController,
        inputMethodManager
    ) {
        EditorInteractionController(
            state = state,
            coroutineScope = coroutineScope,
            focusRequester = focusRequester,
            keyboardController = keyboardController,
            inputMethodManager = inputMethodManager
        )
    }
    val focusCoordinator = remember(state, interactionController, ui) {
        EditorFocusCoordinator(
            state = state,
            interactionController = interactionController,
            onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) }
        )
    }
    val fontScaleCoordinator = remember(state) {
        EditorFontScaleCoordinator(state)
    }
    val clipboardCoordinator = remember(context, androidClipboardManager, coroutineScope, onWriteClipboard) {
        EditorClipboardCoordinator(
            context = context,
            androidClipboardManager = androidClipboardManager,
            coroutineScope = coroutineScope,
            onWriteClipboard = onWriteClipboard
        )
    }
    val scrollbarVisibilityCoordinator = remember(state, coroutineScope, ui) {
        ScrollbarVisibilityCoordinator(
            state = state,
            coroutineScope = coroutineScope,
            isScrollbarDragActive = { ui.activeScrollbarDrag != null }
        )
    }

    return EditorSessionCoreRuntime(
        ui = ui,
        coroutineScope = coroutineScope,
        textPaint = textPaint,
        lineNumberPaint = lineNumberPaint,
        renderer = renderer,
        scrollbarRenderer = scrollbarRenderer,
        selectionMagnifier = selectionMagnifier,
        lineLayoutCache = lineLayoutCache,
        textScanCache = textScanCache,
        touchDiagnostics = touchDiagnostics,
        gestureExclusionCoordinator = gestureExclusionCoordinator,
        gestureHandler = gestureHandler,
        interactionController = interactionController,
        focusCoordinator = focusCoordinator,
        fontScaleCoordinator = fontScaleCoordinator,
        clipboardCoordinator = clipboardCoordinator,
        scrollbarVisibilityCoordinator = scrollbarVisibilityCoordinator,
        gestureSuppressionMs = gestureSuppressionMs,
        gestureReleaseSuppressionMs = gestureReleaseSuppressionMs
    )
}

@Composable
private fun bindColumnXResolverEffect(
    state: EditorState,
    lineLayoutCache: EditorLineLayoutCache,
    density: Density,
    textPaint: Paint
) {
    // 让 EditorState 的滚动对齐逻辑使用与渲染/命中测试一致的列宽计算，避免坐标体系分裂。
    val lineTextLookup = remember(state) { EditorLineTextLookup(state) }
    val columnXResolver = remember(state, lineTextLookup, lineLayoutCache, density, textPaint) {
        { line: Int, column: Int ->
            textPaint.typeface = state.typeface
            textPaint.textSize = with(density) { state.fontSizeSp.sp.toPx() }
            val safeLine = line.coerceIn(0, (state.textBuffer.lineCount - 1).coerceAtLeast(0))
            val lineText = lineTextLookup.lineText(safeLine)
            val safeColumn = column.coerceIn(0, lineText.length)
            val prefixLayout = lineLayoutCache.getPrefixLayout(
                state = state,
                line = safeLine,
                lineText = lineText,
                textVersion = state.textBuffer.version,
                paint = textPaint,
            )
            prefixLayout.textStartAdvance(safeColumn)
        }
    }
    DisposableEffect(state, columnXResolver) {
        state.columnXInTextPxResolver = columnXResolver
        onDispose {
            if (state.columnXInTextPxResolver === columnXResolver) {
                state.columnXInTextPxResolver = null
            }
        }
    }
}

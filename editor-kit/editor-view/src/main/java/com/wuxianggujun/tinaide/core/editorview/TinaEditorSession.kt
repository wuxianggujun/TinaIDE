package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipboardManager
import android.graphics.Paint
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.toClipEntry

internal data class TinaEditorSession(
    val state: EditorState,
    val ui: TinaEditorUiState,
    val density: androidx.compose.ui.unit.Density,
    val touchSlop: Float,
    val focusRequester: FocusRequester,
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
    val transformableState: TransformableState,
    val scrollGestureCoordinator: EditorScrollGestureCoordinator,
    val selectionHandleDragCoordinator: SelectionHandleDragCoordinator,
    val cursorHandleDragCoordinator: CursorHandleDragCoordinator,
    val scrollbarDragCoordinator: ScrollbarDragCoordinator,
    val verticalFlingBehavior: FlingBehavior,
    val horizontalFlingBehavior: FlingBehavior,
    val canvasGesturePipeline: EditorCanvasGesturePipeline,
    val selectionContextMenuCoordinator: EditorSelectionContextMenuCoordinator,
    val scrollDeltaCoordinator: EditorScrollDeltaCoordinator,
    val verticalScrollableState: ScrollableState,
    val horizontalScrollableState: ScrollableState
)

@Composable
internal fun rememberTinaEditorSession(
    state: EditorState
): TinaEditorSession {
    val context = LocalContext.current
    val composeView = LocalView.current
    val density = LocalDensity.current
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val inputMethodManager = remember(context) {
        context.getSystemService(InputMethodManager::class.java)
    }
    val androidClipboardManager = remember(context) {
        context.getSystemService(ClipboardManager::class.java)
    }

    val coreRuntime = rememberEditorSessionCoreRuntime(
        state = state,
        context = context,
        composeView = composeView,
        density = density,
        coroutineScope = coroutineScope,
        focusRequester = focusRequester,
        keyboardController = keyboardController,
        inputMethodManager = inputMethodManager,
        androidClipboardManager = androidClipboardManager,
        onWriteClipboard = { clipData ->
            clipboard.setClipEntry(clipData.toClipEntry())
        }
    )

    val gestureRuntime = rememberEditorSessionGestureRuntime(
        state = state,
        context = context,
        density = density,
        touchSlop = touchSlop,
        coreRuntime = coreRuntime
    )

    return TinaEditorSession(
        state = state,
        ui = coreRuntime.ui,
        density = density,
        touchSlop = touchSlop,
        focusRequester = focusRequester,
        textPaint = coreRuntime.textPaint,
        lineNumberPaint = coreRuntime.lineNumberPaint,
        renderer = coreRuntime.renderer,
        scrollbarRenderer = coreRuntime.scrollbarRenderer,
        selectionMagnifier = coreRuntime.selectionMagnifier,
        lineLayoutCache = coreRuntime.lineLayoutCache,
        textScanCache = coreRuntime.textScanCache,
        touchDiagnostics = coreRuntime.touchDiagnostics,
        gestureExclusionCoordinator = coreRuntime.gestureExclusionCoordinator,
        gestureHandler = coreRuntime.gestureHandler,
        interactionController = coreRuntime.interactionController,
        focusCoordinator = coreRuntime.focusCoordinator,
        fontScaleCoordinator = coreRuntime.fontScaleCoordinator,
        clipboardCoordinator = coreRuntime.clipboardCoordinator,
        scrollbarVisibilityCoordinator = coreRuntime.scrollbarVisibilityCoordinator,
        transformableState = gestureRuntime.transformableState,
        scrollGestureCoordinator = gestureRuntime.scrollGestureCoordinator,
        selectionHandleDragCoordinator = gestureRuntime.selectionHandleDragCoordinator,
        cursorHandleDragCoordinator = gestureRuntime.cursorHandleDragCoordinator,
        scrollbarDragCoordinator = gestureRuntime.scrollbarDragCoordinator,
        verticalFlingBehavior = gestureRuntime.verticalFlingBehavior,
        horizontalFlingBehavior = gestureRuntime.horizontalFlingBehavior,
        canvasGesturePipeline = gestureRuntime.canvasGesturePipeline,
        selectionContextMenuCoordinator = gestureRuntime.selectionContextMenuCoordinator,
        scrollDeltaCoordinator = gestureRuntime.scrollDeltaCoordinator,
        verticalScrollableState = gestureRuntime.verticalScrollableState,
        horizontalScrollableState = gestureRuntime.horizontalScrollableState
    )
}

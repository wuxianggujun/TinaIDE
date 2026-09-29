package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density

internal data class EditorSessionGestureRuntime(
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
internal fun rememberEditorSessionGestureRuntime(
    state: EditorState,
    context: Context,
    density: Density,
    touchSlop: Float,
    coreRuntime: EditorSessionCoreRuntime
): EditorSessionGestureRuntime {
    val ui = coreRuntime.ui
    val textPaint = coreRuntime.textPaint
    val lineNumberPaint = coreRuntime.lineNumberPaint
    val renderer = coreRuntime.renderer
    val scrollbarRenderer = coreRuntime.scrollbarRenderer
    val selectionMagnifier = coreRuntime.selectionMagnifier
    val lineLayoutCache = coreRuntime.lineLayoutCache
    val touchDiagnostics = coreRuntime.touchDiagnostics
    val gestureHandler = coreRuntime.gestureHandler
    val interactionController = coreRuntime.interactionController
    val fontScaleCoordinator = coreRuntime.fontScaleCoordinator
    val clipboardCoordinator = coreRuntime.clipboardCoordinator
    val scrollbarVisibilityCoordinator = coreRuntime.scrollbarVisibilityCoordinator
    val gestureSuppressionMs = coreRuntime.gestureSuppressionMs
    val gestureReleaseSuppressionMs = coreRuntime.gestureReleaseSuppressionMs

    val scaleTransformCoordinator = remember(
        state,
        ui,
        density,
        textPaint,
        lineNumberPaint,
        renderer,
        lineLayoutCache,
        touchDiagnostics,
        interactionController,
        gestureHandler,
        fontScaleCoordinator,
        scrollbarVisibilityCoordinator
    ) {
        EditorScaleTransformCoordinator(
            state = state,
            ui = ui,
            density = density,
            textPaint = textPaint,
            lineNumberPaint = lineNumberPaint,
            renderer = renderer,
            lineLayoutCache = lineLayoutCache,
            touchDiagnostics = touchDiagnostics,
            interactionController = interactionController,
            gestureHandler = gestureHandler,
            fontScaleCoordinator = fontScaleCoordinator,
            scrollbarVisibilityCoordinator = scrollbarVisibilityCoordinator
        )
    }
    val transformableState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        scaleTransformCoordinator.onScaleGesture(
            zoomChange = zoomChange,
            panChange = panChange,
            rotationChange = rotationChange
        )
    }
    LaunchedEffect(transformableState.isTransformInProgress) {
        scaleTransformCoordinator.onTransformProgressChanged(
            inProgress = transformableState.isTransformInProgress
        )
    }

    val scrollGestureCoordinator = remember(state, touchSlop, ui, gestureHandler, transformableState) {
        EditorScrollGestureCoordinator(
            state = state,
            touchSlop = touchSlop,
            shouldBlockScrollGestures = {
                ui.activeScrollbarDrag != null ||
                    ui.activeSelectionHandle != null ||
                    ui.isCursorHandleDragging ||
                    gestureHandler.shouldBlockScrollGestures(
                        isTransformInProgress = transformableState.isTransformInProgress
                    )
            }
        )
    }
    val selectionHandleDragCoordinator = remember(
        state,
        lineLayoutCache,
        selectionMagnifier,
        touchDiagnostics
    ) {
        SelectionHandleDragCoordinator(
            state = state,
            lineLayoutCache = lineLayoutCache,
            selectionMagnifier = selectionMagnifier,
            logEditorTouch = { message, verbose -> touchDiagnostics.log(message, verbose) }
        )
    }
    val scrollbarDragCoordinator = remember(
        state,
        scrollbarRenderer,
        scrollGestureCoordinator,
        gestureHandler,
        interactionController,
        scrollbarVisibilityCoordinator,
        touchDiagnostics,
        ui
    ) {
        ScrollbarDragCoordinator(
            state = state,
            scrollbarRenderer = scrollbarRenderer,
            scrollGestureCoordinator = scrollGestureCoordinator,
            gestureHandler = gestureHandler,
            onActiveDragChanged = { drag -> ui.activeScrollbarDrag = drag },
            onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) },
            onTriggerScrollbarVisibility = { keepVisible -> scrollbarVisibilityCoordinator.trigger(keepVisible) },
            cancelPendingCompletionRequest = { interactionController.cancelPendingCompletionRequest() },
            logEditorTouch = { message, verbose -> touchDiagnostics.log(message, verbose) }
        )
    }

    val verticalFlingBehavior = remember(context, state, scrollGestureCoordinator, scrollbarVisibilityCoordinator, touchDiagnostics) {
        EditorOverScrollerFlingBehavior(
            context = context,
            currentOffsetProvider = { state.scrollOffsetPx },
            maxOffsetProvider = { state.maxVerticalScrollOffsetPx() },
            canScrollProvider = {
                scrollGestureCoordinator.canStartAxisFling(ScrollDragAxis.VERTICAL)
            },
            onFlingStarted = {
                scrollGestureCoordinator.onFlingStarted(ScrollDragAxis.VERTICAL)
                touchDiagnostics.log(
                    category = EditorTouchLogCategory.FLING,
                    "flingStart axis=Vertical offset=${state.scrollOffsetPx.toInt()} max=${state.maxVerticalScrollOffsetPx().toInt()}"
                )
                scrollbarVisibilityCoordinator.trigger(keepVisible = true)
            },
            onFlingFinished = {
                scrollGestureCoordinator.onFlingFinished(ScrollDragAxis.VERTICAL)
                touchDiagnostics.log(
                    category = EditorTouchLogCategory.FLING,
                    message = "flingEnd axis=Vertical offset=${state.scrollOffsetPx.toInt()}"
                )
                scrollbarVisibilityCoordinator.trigger()
            }
        )
    }
    val horizontalFlingBehavior = remember(context, state, scrollGestureCoordinator, scrollbarVisibilityCoordinator, touchDiagnostics) {
        EditorOverScrollerFlingBehavior(
            context = context,
            currentOffsetProvider = { state.scrollOffsetXPx },
            maxOffsetProvider = { state.maxHorizontalScrollOffsetPx() },
            canScrollProvider = {
                scrollGestureCoordinator.canStartAxisFling(ScrollDragAxis.HORIZONTAL)
            },
            onFlingStarted = {
                scrollGestureCoordinator.onFlingStarted(ScrollDragAxis.HORIZONTAL)
                touchDiagnostics.log(
                    category = EditorTouchLogCategory.FLING,
                    "flingStart axis=Horizontal offset=${state.scrollOffsetXPx.toInt()} max=${state.maxHorizontalScrollOffsetPx().toInt()}"
                )
                scrollbarVisibilityCoordinator.trigger(keepVisible = true)
            },
            onFlingFinished = {
                scrollGestureCoordinator.onFlingFinished(ScrollDragAxis.HORIZONTAL)
                touchDiagnostics.log(
                    category = EditorTouchLogCategory.FLING,
                    message = "flingEnd axis=Horizontal offset=${state.scrollOffsetXPx.toInt()}"
                )
                scrollbarVisibilityCoordinator.trigger()
            }
        )
    }

    val gestureCoordinator = remember(
        state,
        renderer,
        lineNumberPaint,
        textPaint,
        gestureHandler,
        transformableState,
        interactionController,
        density,
        lineLayoutCache,
        ui
    ) {
        EditorGestureCoordinator(
            state = state,
            renderer = renderer,
            lineNumberPaint = lineNumberPaint,
            textPaint = textPaint,
            density = density,
            lineLayoutCache = lineLayoutCache,
            gestureHandler = gestureHandler,
            transformableState = transformableState,
            interactionController = interactionController,
            onContextMenuVisibleChange = { visible -> ui.setContextMenuVisible(visible) },
            onContextMenuOffsetChange = { offset -> ui.contextMenuOffset = offset }
        )
    }
    val cursorHandleDragCoordinator = remember(
        state,
        lineLayoutCache,
        coreRuntime.textScanCache,
        gestureCoordinator,
        gestureHandler,
        selectionMagnifier,
        touchDiagnostics
    ) {
        CursorHandleDragCoordinator(
            state = state,
            lineLayoutCache = lineLayoutCache,
            textScanCache = coreRuntime.textScanCache,
            gestureCoordinator = gestureCoordinator,
            gestureHandler = gestureHandler,
            selectionMagnifier = selectionMagnifier,
            logEditorTouch = { message, verbose -> touchDiagnostics.log(message, verbose) }
        )
    }
    val transformFocusTracker = remember(ui, touchDiagnostics) {
        EditorTransformFocusTracker(
            ui = ui,
            touchDiagnostics = touchDiagnostics
        )
    }
    val mouseHoverCoordinator = remember(gestureCoordinator, interactionController, ui) {
        EditorMouseHoverCoordinator(
            coroutineScope = coreRuntime.coroutineScope,
            resolveTarget = gestureCoordinator::resolveHoverTarget,
            hideCurrentHover = { interactionController.dismissHover() },
            requestHover = { target ->
                ui.hoverOffset = target.anchorInViewportPx
                interactionController.requestHoverAt(
                    position = target.position,
                    dismissInteractivePopups = false
                )
            },
            dismissHover = { interactionController.dismissHover() }
        )
    }
    val canvasGesturePipeline = remember(
        gestureCoordinator,
        scrollGestureCoordinator,
        mouseHoverCoordinator,
        ui,
        transformFocusTracker
    ) {
        EditorCanvasGesturePipeline(
            gestureCoordinator = gestureCoordinator,
            scrollGestureCoordinator = scrollGestureCoordinator,
            mouseHoverCoordinator = mouseHoverCoordinator,
            isScrollbarDragActive = { ui.activeScrollbarDrag != null },
            isHandleDragging = { ui.activeSelectionHandle != null || ui.isCursorHandleDragging },
            // 不要在 pointerCount<2 时清空焦点：需要保留最后一次双指焦点用于缩放结束后的锚点回推（对齐 Sora 行为）。
            onTransformGestureFocusChanged = transformFocusTracker::onFocusSnapshot
        )
    }

    val selectionContextMenuCoordinator = remember(
        state,
        interactionController,
        clipboardCoordinator,
        ui
    ) {
        EditorSelectionContextMenuCoordinator(
            state = state,
            interactionController = interactionController,
            readClipboardText = { clipboardCoordinator.readText() },
            copyTextToClipboard = { text -> clipboardCoordinator.copyText(text) },
            onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) },
            onContextMenuOffsetChanged = { offset -> ui.contextMenuOffset = offset },
            onHoverOffsetChanged = { offset -> ui.hoverOffset = offset }
        )
    }
    val scrollDeltaCoordinator = remember(
        state,
        scrollGestureCoordinator,
        gestureHandler,
        touchDiagnostics,
        interactionController,
        scrollbarVisibilityCoordinator,
        ui,
        gestureSuppressionMs,
        gestureReleaseSuppressionMs
    ) {
        EditorScrollDeltaCoordinator(
            state = state,
            scrollGestureCoordinator = scrollGestureCoordinator,
            gestureHandler = gestureHandler,
            touchDiagnostics = touchDiagnostics,
            onContextMenuVisibilityChanged = { visible -> ui.setContextMenuVisible(visible) },
            cancelPendingCompletionRequest = { interactionController.cancelPendingCompletionRequest() },
            dismissHover = { interactionController.dismissHover() },
            triggerScrollbarVisibility = { scrollbarVisibilityCoordinator.trigger() },
            verticalSuppressionMs = gestureSuppressionMs,
            horizontalSuppressionMs = gestureReleaseSuppressionMs
        )
    }

    val verticalScrollableState = rememberScrollableState { delta ->
        scrollDeltaCoordinator.consumeVertical(delta)
    }
    val horizontalScrollableState = rememberScrollableState { delta ->
        scrollDeltaCoordinator.consumeHorizontal(delta)
    }

    return EditorSessionGestureRuntime(
        transformableState = transformableState,
        scrollGestureCoordinator = scrollGestureCoordinator,
        selectionHandleDragCoordinator = selectionHandleDragCoordinator,
        cursorHandleDragCoordinator = cursorHandleDragCoordinator,
        scrollbarDragCoordinator = scrollbarDragCoordinator,
        verticalFlingBehavior = verticalFlingBehavior,
        horizontalFlingBehavior = horizontalFlingBehavior,
        canvasGesturePipeline = canvasGesturePipeline,
        selectionContextMenuCoordinator = selectionContextMenuCoordinator,
        scrollDeltaCoordinator = scrollDeltaCoordinator,
        verticalScrollableState = verticalScrollableState,
        horizontalScrollableState = horizontalScrollableState
    )
}

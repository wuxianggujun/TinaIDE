package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun EditorRuntimeEffects(
    state: EditorState,
    ui: TinaEditorUiState,
    density: Density,
    isTransformInProgress: Boolean,
    isHandleDragging: Boolean,
    gestureHandler: EditorGestureHandler,
    scrollbarVisibilityCoordinator: ScrollbarVisibilityCoordinator,
    gestureExclusionCoordinator: EditorGestureExclusionCoordinator,
    focusRequester: FocusRequester,
    interactionController: EditorInteractionController,
    selectionMagnifier: SelectionMagnifierController
) {
    LaunchedEffect(isTransformInProgress) {
        if (!isTransformInProgress && gestureHandler.isMultiTouchActive) {
            delay(100)
            if (!isTransformInProgress) {
                gestureHandler.onTransformSettled()
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(120)
        scrollbarVisibilityCoordinator.trigger()
    }

    LaunchedEffect(gestureExclusionCoordinator, density) {
        snapshotFlow {
            GestureExclusionSnapshot(
                canvasWidthPx = ui.canvasWidthPx,
                canvasHeightPx = ui.canvasHeightPx,
                maxVertical = state.maxVerticalScrollOffsetPx(),
                maxHorizontal = state.maxHorizontalScrollOffsetPx()
            )
        }.distinctUntilChanged().collect { snap ->
            gestureExclusionCoordinator.update(
                density = density,
                canvasWidthPx = snap.canvasWidthPx,
                canvasHeightPx = snap.canvasHeightPx,
                maxVerticalScrollOffsetPx = snap.maxVertical,
                maxHorizontalScrollOffsetPx = snap.maxHorizontal
            )
        }
    }
    DisposableEffect(gestureExclusionCoordinator) {
        onDispose { gestureExclusionCoordinator.clear() }
    }

    LaunchedEffect(Unit) {
        val canvasH = ui.canvasHeightPx.coerceAtLeast(1f)
        val initialFontPx = with(density) { state.fontSizeSp.sp.toPx() }.coerceAtLeast(1f)
        state.updateMetrics(
            lineHeightPx = (initialFontPx * 1.45f).coerceAtLeast(1f),
            charWidthPx = (initialFontPx * 0.62f).coerceAtLeast(1f),
            viewportHeightPx = canvasH,
            viewportWidthPx = 1f,
            contentStartXPx = 0f
        )
    }

    data class ImeSyncSnapshot(
        val textVersion: Long,
        val cursorOffset: Int,
        val selectionRange: OffsetRange?,
        val focused: Boolean
    )
    LaunchedEffect(interactionController, isTransformInProgress, isHandleDragging) {
        if (isTransformInProgress || isHandleDragging) return@LaunchedEffect
        snapshotFlow {
            ImeSyncSnapshot(
                textVersion = state.textVersion,
                cursorOffset = state.cursorOffset,
                selectionRange = state.selectionRange,
                focused = state.isFocused
            )
        }
            .distinctUntilChanged()
            .collectLatest { snap ->
                if (!snap.focused) return@collectLatest
                interactionController.syncSelectionToIme()
            }
    }

    LaunchedEffect(interactionController, state) {
        snapshotFlow {
            val cursorPosition = state.cursorPosition
            val visualLine = state.visualLineForPosition(cursorPosition.line, cursorPosition.column)
            CursorAnchorGeometrySnapshot(
                visualLine = visualLine,
                segmentStartColumn = state.visualLineStartColumn(visualLine),
                scrollOffsetX = state.scrollOffsetXPx,
                scrollOffsetY = state.scrollOffsetPx,
                viewportWidth = state.viewportWidthPx,
                viewportHeight = state.viewportHeightPx,
                contentStartX = state.contentStartXPx,
                lineHeight = state.lineHeightPx,
                charWidth = state.charWidthPx,
                fontSizeSp = state.fontSizeSp,
                typeface = state.typeface,
                tabSize = state.config.tabSize
            )
        }
            .distinctUntilChanged()
            .collectLatest {
                if (state.isFocused) {
                    interactionController.notifyCursorGeometryChanged()
                }
            }
    }

    LaunchedEffect(interactionController, state, isTransformInProgress, isHandleDragging) {
        state.events.collectLatest { event ->
            interactionController.onEditorEvent(
                event = event,
                allowAutoSignatureHelpRefresh = !isTransformInProgress && !isHandleDragging
            )
        }
    }

    LaunchedEffect(state.isFocused) {
        if (!state.isFocused) {
            interactionController.cancelPendingCompletionRequest()
            interactionController.dismissHover()
            interactionController.dismissSignatureHelp()
            state.cursorBlinkVisible = false
            return@LaunchedEffect
        }
        state.cursorBlinkVisible = true
        while (state.isFocused) {
            delay(550)
            state.cursorBlinkVisible = !state.cursorBlinkVisible
        }
    }

    val viewportHeightBucket = state.viewportHeightPx.roundToInt()
    LaunchedEffect(state.isFocused, viewportHeightBucket) {
        if (!state.isFocused) return@LaunchedEffect
        if (isTransformInProgress || isHandleDragging) return@LaunchedEffect
        state.ensureCursorVisibleVertically()
    }

    DisposableEffect(interactionController) {
        onDispose {
            scrollbarVisibilityCoordinator.onDispose()
            selectionMagnifier.release()
            interactionController.onDispose()
        }
    }
}

private data class GestureExclusionSnapshot(
    val canvasWidthPx: Float,
    val canvasHeightPx: Float,
    val maxVertical: Float,
    val maxHorizontal: Float
)

private data class CursorAnchorGeometrySnapshot(
    val visualLine: Int,
    val segmentStartColumn: Int,
    val scrollOffsetX: Float,
    val scrollOffsetY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val contentStartX: Float,
    val lineHeight: Float,
    val charWidth: Float,
    val fontSizeSp: Float,
    val typeface: android.graphics.Typeface,
    val tabSize: Int
)

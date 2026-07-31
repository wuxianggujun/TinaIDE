package com.wuxianggujun.tinaide.core.editorview

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp

@Composable
internal fun TinaEditorScaffold(
    session: TinaEditorSession,
    modifier: Modifier = Modifier
) {
    val state = session.state
    val ui = session.ui
    val interactionController = session.interactionController
    val keyEventHandler: (ComposeKeyEvent) -> Boolean = { event ->
        if (ui.contextMenuVisible && session.handleContextMenuKeyboardEvent(event)) {
            true
        } else {
            handleEditorShortcut(
                event = event,
                state = state,
                onCopy = { selected ->
                    session.clipboardCoordinator.copyText(selected)
                },
                onCut = { selected ->
                    session.clipboardCoordinator.copyText(selected)
                },
                readPasteText = { session.clipboardCoordinator.readText() },
                onAfterTextEdit = { interactionController.syncSelectionToIme() },
                onRequestCompletion = { interactionController.requestManualCompletion() },
                onRequestSignatureHelp = { interactionController.requestManualSignatureHelp() },
                onCycleSignatureHelp = { delta -> state.cycleSignatureHelp(delta) },
                onCompletionNavigate = { delta -> state.moveCompletionSelection(delta) },
                onApplySelectedCompletion = { state.applySelectedCompletion() },
                onDismissCompletion = { interactionController.dismissCompletion() },
                onDismissSignatureHelp = { interactionController.dismissSignatureHelp() },
                onIncreaseFont = { session.fontScaleCoordinator.apply(state.fontSizeSp + 1f) },
                onDecreaseFont = { session.fontScaleCoordinator.apply(state.fontSizeSp - 1f) },
                onRequestContextMenu = { session.showContextMenuAtCursor() },
                onBeforeTextEdit = { interactionController.prepareForExternalEdit() }
            )
        }
    }
    val currentKeyEventHandler = rememberUpdatedState(keyEventHandler)

    DisposableEffect(interactionController) {
        val hardwareKeyEventInterceptor: (android.view.KeyEvent) -> Boolean = { event ->
            currentKeyEventHandler.value(ComposeKeyEvent(event))
        }
        interactionController.hardwareKeyEventInterceptor = hardwareKeyEventInterceptor
        onDispose {
            if (interactionController.hardwareKeyEventInterceptor === hardwareKeyEventInterceptor) {
                interactionController.hardwareKeyEventInterceptor = null
            }
        }
    }

    EditorTextBufferRendererSyncEffect(
        state = state,
        renderer = session.renderer
    )

    EditorRuntimeEffects(
        state = state,
        ui = ui,
        density = session.density,
        isTransformInProgress = session.transformableState.isTransformInProgress,
        isHandleDragging = ui.activeSelectionHandle != null || ui.isCursorHandleDragging,
        gestureHandler = session.gestureHandler,
        scrollbarVisibilityCoordinator = session.scrollbarVisibilityCoordinator,
        gestureExclusionCoordinator = session.gestureExclusionCoordinator,
        focusRequester = session.focusRequester,
        interactionController = interactionController,
        selectionMagnifier = session.selectionMagnifier
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(session.focusRequester)
            .focusable()
            .onFocusChanged { focusState ->
                ui.composeFocusActive = focusState.isFocused
                val hostFocused = interactionController.hasActiveInputHostFocus()
                session.focusCoordinator.onComposeFocusChanged(
                    composeFocused = focusState.isFocused,
                    hostFocused = hostFocused
                )
            }
            .onPreviewKeyEvent { event ->
                keyEventHandler(event)
            }
    ) {
        EditorInputHostLayer(
            interactionController = interactionController,
            focusCoordinator = session.focusCoordinator,
            isComposeFocusActive = { ui.composeFocusActive }
        )

        EditorCanvasLayer(session)
    }

    EditorSelectionContextMenuOverlay(session)
    EditorHoverOverlay(session)
    EditorSignatureHelpOverlay(session)
    EditorCompletionOverlay(session)
}

private fun TinaEditorSession.handleContextMenuKeyboardEvent(event: ComposeKeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.Escape -> {
            ui.contextMenuKeyboardAction = null
            selectionContextMenuCoordinator.onDismiss()
            true
        }

        event.key == Key.DirectionDown || (event.key == Key.Tab && !event.isShiftPressed) -> {
            moveContextMenuKeyboardSelection(delta = 1)
            true
        }

        event.key == Key.DirectionUp || (event.key == Key.Tab && event.isShiftPressed) -> {
            moveContextMenuKeyboardSelection(delta = -1)
            true
        }

        event.key == Key.Enter || event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
            val action = ui.contextMenuKeyboardAction
                ?: selectionContextMenuCoordinator.availableKeyboardActions().firstOrNull()
            if (action != null) {
                selectionContextMenuCoordinator.performKeyboardAction(
                    action = action,
                    anchorInViewportPx = ui.contextMenuOffset
                )
            }
            ui.contextMenuKeyboardAction = null
            true
        }

        else -> false
    }
}

private fun TinaEditorSession.moveContextMenuKeyboardSelection(delta: Int) {
    val actions = selectionContextMenuCoordinator.availableKeyboardActions()
    if (actions.isEmpty()) {
        ui.contextMenuKeyboardAction = null
        return
    }
    val currentIndex = actions.indexOf(ui.contextMenuKeyboardAction)
    val nextIndex = if (currentIndex >= 0) {
        Math.floorMod(currentIndex + delta, actions.size)
    } else if (delta >= 0) {
        0
    } else {
        actions.lastIndex
    }
    ui.contextMenuKeyboardAction = actions[nextIndex]
}

private fun TinaEditorSession.showContextMenuAtCursor() {
    interactionController.dismissHover()
    interactionController.dismissCompletion()
    interactionController.dismissSignatureHelp()
    interactionController.requestEditorFocus()
    interactionController.syncSelectionToIme()
    ui.contextMenuOffset = resolveCursorContextMenuAnchor()
    ui.setContextMenuVisible(
        visible = true,
        keyboardAction = selectionContextMenuCoordinator.availableKeyboardActions().firstOrNull()
    )
}

private fun TinaEditorSession.resolveCursorContextMenuAnchor(): IntOffset {
    val lineTextLookup = EditorLineTextLookup(state)
    val cursorAnchor = CursorPopupAnchorResolver.resolve(
        state = state,
        contentStartXPx = ui.contentStartXPx,
        textPaint = textPaint,
        lineLayoutCache = lineLayoutCache,
        fontSizePx = with(density) { state.fontSizeSp.sp.toPx() },
        lineTextProvider = lineTextLookup::lineText,
        textScanCache = textScanCache
    )
    val fallbackAnchorX =
        (ui.contentStartXPx + (ui.canvasWidthPx - ui.contentStartXPx) * 0.5f).coerceAtLeast(ui.contentStartXPx)
    val fallbackAnchorY = (ui.canvasHeightPx * 0.28f).coerceAtLeast(0f)
    val anchorX = if (cursorAnchor.cursorXInViewportPx.isFinite()) {
        cursorAnchor.cursorXInViewportPx
    } else {
        fallbackAnchorX
    }
    val anchorY = if (cursorAnchor.cursorLineTopInViewportPx.isFinite()) {
        cursorAnchor.cursorLineTopInViewportPx + state.lineHeightPx * 0.62f
    } else {
        fallbackAnchorY
    }
    return IntOffset(
        x = anchorX.coerceIn(0f, (ui.canvasWidthPx - 1f).coerceAtLeast(0f)).toInt(),
        y = anchorY.coerceIn(0f, (ui.canvasHeightPx - 1f).coerceAtLeast(0f)).toInt()
    )
}

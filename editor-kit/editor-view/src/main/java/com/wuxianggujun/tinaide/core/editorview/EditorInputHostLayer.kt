package com.wuxianggujun.tinaide.core.editorview

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun EditorInputHostLayer(
    interactionController: EditorInteractionController,
    focusCoordinator: EditorFocusCoordinator,
    isComposeFocusActive: () -> Boolean
) {
    AndroidView(
        factory = { viewContext ->
            EditorInputHostView(viewContext).apply {
                fun syncHostFocus(hostFocused: Boolean) {
                    focusCoordinator.syncFocus(
                        composeFocused = isComposeFocusActive(),
                        hostFocused = hostFocused
                    )
                }

                isFocusable = true
                isFocusableInTouchMode = true
                alpha = 0f
                layoutParams = ViewGroup.LayoutParams(1, 1)
                onFocusChangeListener = View.OnFocusChangeListener { _, _ ->
                    syncHostFocus(interactionController.hasActiveInputHostFocus())
                }
                onWindowFocusChangedCallback = { hasWindowFocus ->
                    syncHostFocus(isFocused && hasWindowFocus && isAttachedToWindow)
                }
                onDetachedFromWindowCallback = {
                    syncHostFocus(false)
                }
            }.also { host ->
                interactionController.bindInputHostView(host)
            }
        },
        update = { host ->
            interactionController.bindInputHostView(host)
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    )
}

package com.wuxianggujun.tinaide.core.editorview

internal class EditorFocusCoordinator(
    private val state: EditorState,
    private val interactionController: EditorInteractionController,
    private val onContextMenuVisibilityChanged: (Boolean) -> Unit
) {
    fun syncFocus(
        composeFocused: Boolean,
        hostFocused: Boolean
    ) {
        val effectiveFocused = composeFocused || hostFocused
        val wasFocused = state.isFocused
        state.updateFocus(effectiveFocused)
        if (effectiveFocused && !wasFocused) {
            state.cursorBlinkVisible = true
            interactionController.restartInput()
            return
        }
        if (!effectiveFocused && wasFocused) {
            interactionController.dismissCompletion()
            onContextMenuVisibilityChanged(false)
            interactionController.hideKeyboardAndClearHostFocus()
        }
    }

    fun onComposeFocusChanged(
        composeFocused: Boolean,
        hostFocused: Boolean
    ) {
        syncFocus(
            composeFocused = composeFocused,
            hostFocused = hostFocused
        )
    }
}

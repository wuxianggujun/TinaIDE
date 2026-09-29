package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset

internal enum class EditorContextMenuActionId {
    Copy,
    Cut,
    Paste,
    SelectAll,
    PeekDefinition,
    GotoDefinition,
    FindReferences,
    GotoTypeDefinition,
    GotoImplementation,
    CodeActions,
    RenameSymbol,
    SwitchHeaderSource,
    Hover
}

internal class EditorSelectionContextMenuCoordinator(
    private val state: EditorState,
    private val interactionController: EditorInteractionController,
    private val readClipboardText: () -> String?,
    private val copyTextToClipboard: (String) -> Unit,
    private val onContextMenuVisibilityChanged: (Boolean) -> Unit,
    private val onContextMenuOffsetChanged: (IntOffset) -> Unit,
    private val onHoverOffsetChanged: (IntOffset) -> Unit
) {
    fun selectedText(): String? = state.selectedText()

    fun availableKeyboardActions(): List<EditorContextMenuActionId> = mutableListOf<EditorContextMenuActionId>().apply {
        if (selectedText() != null) {
            add(EditorContextMenuActionId.Copy)
            add(EditorContextMenuActionId.Cut)
        }
        add(EditorContextMenuActionId.Paste)
        add(EditorContextMenuActionId.SelectAll)
        if (state.onRequestPeekDefinition != null) add(EditorContextMenuActionId.PeekDefinition)
        if (state.onRequestGotoDefinition != null) add(EditorContextMenuActionId.GotoDefinition)
        if (state.onRequestFindReferences != null) add(EditorContextMenuActionId.FindReferences)
        if (state.onRequestGotoTypeDefinition != null) add(EditorContextMenuActionId.GotoTypeDefinition)
        if (state.onRequestGotoImplementation != null) add(EditorContextMenuActionId.GotoImplementation)
        if (state.onRequestCodeActions != null) add(EditorContextMenuActionId.CodeActions)
        if (state.onRequestRenameSymbol != null) add(EditorContextMenuActionId.RenameSymbol)
        if (state.onRequestSwitchHeaderSource != null) add(EditorContextMenuActionId.SwitchHeaderSource)
        if (state.onRequestHover != null) add(EditorContextMenuActionId.Hover)
    }

    fun performKeyboardAction(
        action: EditorContextMenuActionId,
        anchorInViewportPx: IntOffset
    ): Boolean {
        if (action !in availableKeyboardActions()) return false
        when (action) {
            EditorContextMenuActionId.Copy -> onCopy(selectedText())
            EditorContextMenuActionId.Cut -> onCut(selectedText())
            EditorContextMenuActionId.Paste -> onPaste()
            EditorContextMenuActionId.SelectAll -> onSelectAll(anchorInViewportPx)
            EditorContextMenuActionId.PeekDefinition -> onPeekDefinition()
            EditorContextMenuActionId.GotoDefinition -> onGotoDefinition()
            EditorContextMenuActionId.FindReferences -> onFindReferences()
            EditorContextMenuActionId.GotoTypeDefinition -> onGotoTypeDefinition()
            EditorContextMenuActionId.GotoImplementation -> onGotoImplementation()
            EditorContextMenuActionId.CodeActions -> onRequestCodeActions()
            EditorContextMenuActionId.RenameSymbol -> onRequestRenameSymbol()
            EditorContextMenuActionId.SwitchHeaderSource -> onSwitchHeaderSource()
            EditorContextMenuActionId.Hover -> onHover(anchorInViewportPx)
        }
        return true
    }

    fun onCopy(selectedText: String?) {
        selectedText?.let(copyTextToClipboard)
        onContextMenuVisibilityChanged(false)
    }

    fun onCut(selectedText: String?) {
        selectedText?.let {
            interactionController.prepareForExternalEdit()
            copyTextToClipboard(it)
            state.replaceSelection("")
            interactionController.syncSelectionToIme()
        }
        onContextMenuVisibilityChanged(false)
    }

    fun onPaste() {
        readClipboardText()?.let {
            interactionController.prepareForExternalEdit()
            state.replaceSelection(it)
            interactionController.syncSelectionToIme()
        }
        onContextMenuVisibilityChanged(false)
    }

    fun onSelectAll(
        anchorInViewportPx: IntOffset
    ) {
        interactionController.prepareForExternalEdit()
        state.selectAll()
        interactionController.syncSelectionToIme()
        onContextMenuOffsetChanged(anchorInViewportPx)
        onContextMenuVisibilityChanged(true)
    }

    fun onHover(anchorInViewportPx: IntOffset) {
        onHoverOffsetChanged(anchorInViewportPx)
        onContextMenuVisibilityChanged(false)
        interactionController.requestHover()
    }

    fun onGotoDefinition() {
        onContextMenuVisibilityChanged(false)
        state.onRequestGotoDefinition?.invoke()
    }

    fun onPeekDefinition() {
        onContextMenuVisibilityChanged(false)
        state.onRequestPeekDefinition?.invoke()
    }

    fun onFindReferences() {
        onContextMenuVisibilityChanged(false)
        state.onRequestFindReferences?.invoke()
    }

    fun onGotoTypeDefinition() {
        onContextMenuVisibilityChanged(false)
        state.onRequestGotoTypeDefinition?.invoke()
    }

    fun onGotoImplementation() {
        onContextMenuVisibilityChanged(false)
        state.onRequestGotoImplementation?.invoke()
    }

    fun onRequestCodeActions() {
        onContextMenuVisibilityChanged(false)
        state.onRequestCodeActions?.invoke()
    }

    fun onRequestRenameSymbol() {
        onContextMenuVisibilityChanged(false)
        state.onRequestRenameSymbol?.invoke()
    }

    fun onSwitchHeaderSource() {
        onContextMenuVisibilityChanged(false)
        state.onRequestSwitchHeaderSource?.invoke()
    }

    fun onDismiss() {
        onContextMenuVisibilityChanged(false)
    }
}

package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EditorSelectionContextMenuCoordinatorTest {

    @Test
    fun onHover_shouldUpdateHoverAnchorWithoutMutatingContextMenuAnchor() = runTest {
        var contextMenuVisible = true
        var contextMenuOffset = IntOffset(8, 12)
        var hoverOffset = IntOffset.Zero
        val state = EditorState(RopeTextBuffer())
        val interactionController = EditorInteractionController(
            state = state,
            coroutineScope = this,
            focusRequester = FocusRequester(),
            keyboardController = null,
            inputMethodManager = null
        )
        val coordinator = EditorSelectionContextMenuCoordinator(
            state = state,
            interactionController = interactionController,
            readClipboardText = { null },
            copyTextToClipboard = {},
            onContextMenuVisibilityChanged = { visible -> contextMenuVisible = visible },
            onContextMenuOffsetChanged = { offset -> contextMenuOffset = offset },
            onHoverOffsetChanged = { offset -> hoverOffset = offset }
        )

        coordinator.onHover(IntOffset(48, 96))

        assertThat(contextMenuVisible).isFalse()
        assertThat(contextMenuOffset).isEqualTo(IntOffset(8, 12))
        assertThat(hoverOffset).isEqualTo(IntOffset(48, 96))
    }

    @Test
    fun availableKeyboardActions_shouldSkipDisabledTextActions() = runTest {
        val state = createState("abc")
        state.onRequestGotoDefinition = {}
        state.onRequestHover = { "doc" }
        val coordinator = createCoordinator(state, coroutineScope = this)

        val actions = coordinator.availableKeyboardActions()

        assertThat(actions).containsExactly(
            EditorContextMenuActionId.Paste,
            EditorContextMenuActionId.SelectAll,
            EditorContextMenuActionId.GotoDefinition,
            EditorContextMenuActionId.Hover
        ).inOrder()
    }

    @Test
    fun performKeyboardAction_shouldRunSelectedActionAndDismissMenu() = runTest {
        val state = createState("abc")
        state.selectAll()
        var copiedText: String? = null
        var menuVisible = true
        val coordinator = createCoordinator(
            state = state,
            coroutineScope = this,
            copyTextToClipboard = { copiedText = it },
            onContextMenuVisibilityChanged = { menuVisible = it }
        )

        val handled = coordinator.performKeyboardAction(
            action = EditorContextMenuActionId.Copy,
            anchorInViewportPx = IntOffset.Zero
        )

        assertThat(handled).isTrue()
        assertThat(copiedText).isEqualTo("abc")
        assertThat(menuVisible).isFalse()
    }

    @Test
    fun performKeyboardAction_shouldRejectUnavailableAction() = runTest {
        val state = createState("abc")
        val coordinator = createCoordinator(state, coroutineScope = this)

        val handled = coordinator.performKeyboardAction(
            action = EditorContextMenuActionId.Copy,
            anchorInViewportPx = IntOffset.Zero
        )

        assertThat(handled).isFalse()
    }

    private fun createCoordinator(
        state: EditorState,
        coroutineScope: CoroutineScope,
        copyTextToClipboard: (String) -> Unit = {},
        onContextMenuVisibilityChanged: (Boolean) -> Unit = {}
    ): EditorSelectionContextMenuCoordinator = EditorSelectionContextMenuCoordinator(
        state = state,
        interactionController = EditorInteractionController(
            state = state,
            coroutineScope = coroutineScope,
            focusRequester = FocusRequester(),
            keyboardController = null,
            inputMethodManager = null
        ),
        readClipboardText = { null },
        copyTextToClipboard = copyTextToClipboard,
        onContextMenuVisibilityChanged = onContextMenuVisibilityChanged,
        onContextMenuOffsetChanged = {},
        onHoverOffsetChanged = {}
    )

    private fun createState(text: String): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        return EditorState(buffer)
    }
}

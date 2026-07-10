package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.wuxianggujun.tinaide.core.textengine.TextChangeListener

@Composable
internal fun EditorTextBufferRendererSyncEffect(
    state: EditorState,
    renderer: EditorRenderEngine
) {
    DisposableEffect(state.textBuffer, renderer) {
        val listener = TextChangeListener { change ->
            renderer.applyTextChange(change, state.textBuffer.version, state.textBuffer.lineCount)
        }
        state.textBuffer.addChangeListener(listener)
        onDispose {
            state.textBuffer.removeChangeListener(listener)
            renderer.invalidateCache()
        }
    }
}

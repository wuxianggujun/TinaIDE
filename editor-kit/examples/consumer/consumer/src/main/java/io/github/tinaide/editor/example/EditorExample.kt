package io.github.tinaide.editor.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.TinaEditor
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer

@Composable
fun EditorExample() {
    val buffer = remember { RopeTextBuffer("Hello, editor!\n") }
    val editorState = remember(buffer) { EditorState(textBuffer = buffer) }
    TinaEditor(state = editorState, modifier = Modifier.fillMaxSize())
}

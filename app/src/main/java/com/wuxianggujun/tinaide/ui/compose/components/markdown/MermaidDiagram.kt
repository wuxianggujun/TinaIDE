package com.wuxianggujun.tinaide.ui.compose.components.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wuxianggujun.tinaide.ui.compose.components.CodeBlock

/**
 * Displays Mermaid source without executing remote JavaScript.
 * Rendering can be restored after a reviewed Mermaid runtime is pinned and bundled in the APK.
 */
@Composable
internal fun MermaidDiagram(
    code: String,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CodeBlock(
        code = code,
        language = "mermaid",
        onCopy = onCopy,
        onInsert = onInsert,
        modifier = modifier,
    )
}

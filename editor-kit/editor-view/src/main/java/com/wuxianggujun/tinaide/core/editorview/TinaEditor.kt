package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

typealias EditorHoverContent = @Composable (
    markdown: String,
    modifier: Modifier,
    onLinkClick: (String) -> Unit,
    onCodeCopy: (String) -> Unit
) -> Unit

@Composable
internal fun PlainHoverContent(
    markdown: String,
    modifier: Modifier,
    onLinkClick: (String) -> Unit,
    onCodeCopy: (String) -> Unit
) {
    Text(markdown, modifier.padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
fun TinaEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    onPerformanceSnapshotReaderChanged: (((() -> EditorRenderPerformanceSnapshot)?) -> Unit)? = null,
    onExternalEditPreparerChanged: (((() -> Unit)?) -> Unit)? = null,
    hoverContent: EditorHoverContent = ::PlainHoverContent
) {
    val session = rememberTinaEditorSession(state)
    DisposableEffect(session, onPerformanceSnapshotReaderChanged) {
        onPerformanceSnapshotReaderChanged?.invoke {
            session.renderer.performanceSnapshot()
        }
        onDispose {
            onPerformanceSnapshotReaderChanged?.invoke(null)
        }
    }
    DisposableEffect(session, onExternalEditPreparerChanged) {
        onExternalEditPreparerChanged?.invoke {
            session.interactionController.prepareForExternalEdit()
        }
        onDispose {
            onExternalEditPreparerChanged?.invoke(null)
        }
    }
    TinaEditorScaffold(
        session = session,
        modifier = modifier,
        hoverContent = hoverContent
    )
}

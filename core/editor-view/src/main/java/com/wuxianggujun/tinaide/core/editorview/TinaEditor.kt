package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier

@Composable
fun TinaEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    onPerformanceSnapshotReaderChanged: (((() -> EditorRenderPerformanceSnapshot)?) -> Unit)? = null,
    onExternalEditPreparerChanged: (((() -> Unit)?) -> Unit)? = null
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
        modifier = modifier
    )
}

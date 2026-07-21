package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.DetachedEditorSnapshot
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import java.nio.charset.Charset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class EditorDocumentSessionCoordinator(
    private val editorManager: IEditorManager,
    private val activeTabProvider: () -> EditorTabState?,
) {
    fun getTabToolbarStateFlow(tabId: String): Flow<TabToolbarState>? =
        editorManager.getSessionState(tabId)
            ?.map { docState ->
                TabToolbarState(
                    isDirty = docState.isDirty,
                    canUndo = docState.canUndo,
                    canRedo = docState.canRedo,
                    charsetName = docState.charsetName
                )
            }
            ?.distinctUntilChanged()

    fun getTabLastEditAtFlow(tabId: String): Flow<Long?>? =
        editorManager.getSessionState(tabId)
            ?.map { it.lastEditAt }
            ?.distinctUntilChanged()

    fun getActiveEditorSessionAlertFlow(): Flow<ActiveEditorSessionAlertState>? {
        val activeTab = activeTabProvider() ?: return null
        return editorManager.getSessionState(activeTab.id)
            ?.map { docState ->
                ActiveEditorSessionAlertState(
                    tabId = activeTab.id,
                    file = activeTab.file,
                    hasExternalModification = docState.hasExternalModification,
                    lastError = docState.lastError
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                )
            }
            ?.distinctUntilChanged()
    }

    fun attachEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        getSession(tabId)?.attachEditor(binding)
    }

    fun detachEditorBinding(tabId: String, binding: DocumentSession.EditorBinding) {
        getSession(tabId)?.detachEditor(binding)
    }

    fun getDetachedEditorSnapshot(tabId: String): DetachedEditorSnapshot? =
        getSession(tabId)?.detachedEditorSnapshot()

    fun markDetachedEditorSnapshotRestored(tabId: String, snapshot: DetachedEditorSnapshot) {
        getSession(tabId)?.markDetachedEditorSnapshotRestored(snapshot)
    }

    fun getEditorViewState(tabId: String): EditorViewState? =
        getSession(tabId)?.state?.value?.let { state ->
            EditorViewState(
                cursorLine = state.cursorLine,
                cursorColumn = state.cursorColumn,
                scrollX = state.scrollX,
                scrollY = state.scrollY
            )
        }

    fun notifyEditorContentChanged(
        tabId: String,
        canUndo: Boolean,
        canRedo: Boolean,
        changeCausedByUndoManager: Boolean
    ) {
        getSession(tabId)?.notifyEditorContentChanged(
            canUndo = canUndo,
            canRedo = canRedo,
            changeCausedByUndoManager = changeCausedByUndoManager
        )
    }

    fun markEditorSnapshotClean(tabId: String, charset: Charset) {
        getSession(tabId)?.markEditorSnapshotClean(charset)
    }

    fun updateCursorPosition(tabId: String, line: Int, column: Int) {
        getSession(tabId)?.updateCursorPosition(line, column)
    }

    fun updateScrollPosition(tabId: String, scrollX: Int, scrollY: Int) {
        getSession(tabId)?.updateScrollPosition(scrollX, scrollY)
    }

    private fun getSession(tabId: String): DocumentSession? = editorManager.getSession(tabId)
}

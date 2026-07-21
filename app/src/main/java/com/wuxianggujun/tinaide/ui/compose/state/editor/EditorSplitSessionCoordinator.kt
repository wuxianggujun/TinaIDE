package com.wuxianggujun.tinaide.ui.compose.state.editor

internal class EditorSplitSessionCoordinator(
    private val storage: SplitEditorSessionStorage,
    private val projectPathProvider: () -> String?,
    private val hasTabs: () -> Boolean,
    private val createSnapshot: () -> SplitEditorStateSnapshot,
    private val restoreSnapshot: (SplitEditorStateSnapshot) -> Unit,
    private val normalizePaneState: () -> Unit,
    private val clearInMemory: () -> Unit,
) {
    private var pendingSnapshot: SplitEditorStateSnapshot? = null
    private var restoredProjectPath: String? = null
    private var lastProjectPath: String? = null

    fun persist() {
        val projectPath = projectPathProvider() ?: return
        if (!hasTabs()) {
            storage.clear(projectPath)
            return
        }
        storage.save(projectPath, createSnapshot())
    }

    fun restoreIfNeeded() {
        val projectPath = projectPathProvider() ?: return
        if (lastProjectPath != projectPath) {
            lastProjectPath = projectPath
            restoredProjectPath = null
            pendingSnapshot = null
            clearInMemory()
        }

        if (restoredProjectPath == projectPath) return
        val snapshot = pendingSnapshot ?: storage.load(projectPath)
        pendingSnapshot = snapshot
        if (!hasTabs()) return

        if (snapshot != null) {
            restoreSnapshot(snapshot)
        } else {
            normalizePaneState()
        }
        restoredProjectPath = projectPath
        pendingSnapshot = null
    }
}

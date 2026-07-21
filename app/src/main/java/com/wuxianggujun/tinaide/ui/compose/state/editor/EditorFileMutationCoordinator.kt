package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorCallback

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import java.io.File
import timber.log.Timber

internal fun normalizeOpenTabLookupPath(path: String): String {
    val normalized = path.replace('\\', '/')
    return if (File.separatorChar == '\\') normalized.lowercase() else normalized
}

internal class EditorFileMutationCoordinator(
    private val editorManager: IEditorManager,
    private val tabManager: EditorTabManager,
    private val tabs: SnapshotStateList<EditorTabState>,
    private val navigationBackStack: SnapshotStateList<NavigationHistoryEntry>,
    private val navigationForwardStack: SnapshotStateList<NavigationHistoryEntry>,
    private val splitPaneState: EditorSplitPaneState,
    private val codeRuntimeCache: EditorCodeRuntimeCache,
    private val codeEditorCallbacks: MutableMap<String, CodeEditorCallback>,
    private val lspUiState: EditorLspUiState,
    private val diagnosticsState: EditorDiagnosticsState,
    private val isCodeEditableType: (ContentType) -> Boolean,
    private val requestCloseTabAt: (Int) -> Unit,
    private val releaseTinaLspForTab: (String) -> Unit,
    private val normalizeEditorPaneState: () -> Unit,
    private val persistSplitEditorState: () -> Unit,
) {
    fun closeTabsForDeletedPath(deletedPath: File): Int {
        val targetPath = normalizeOpenTabLookupPath(deletedPath.absolutePath)
            .trimEnd('/')
        if (targetPath.isBlank()) return 0

        val targetPrefix = "$targetPath/"
        val affectedTabs = tabs
            .filter { tab ->
                val tabPath = normalizeOpenTabLookupPath(tab.file.absolutePath)
                tabPath == targetPath || tabPath.startsWith(targetPrefix)
            }
        if (affectedTabs.isEmpty()) return 0

        val cleanTabIds = affectedTabs
            .filterNot { it.isDirty }
            .mapTo(linkedSetOf()) { it.id }
        val closedTabs = tabManager.closeTabsByIds(cleanTabIds)
        affectedTabs
            .firstOrNull { it.isDirty && tabs.any { tab -> tab.id == it.id } }
            ?.let { dirtyTab ->
                val index = tabs.indexOfFirst { it.id == dirtyTab.id }
                if (index >= 0) requestCloseTabAt(index)
            }

        normalizeEditorPaneState()
        persistSplitEditorState()
        return closedTabs.size
    }

    fun syncTabsForMovedPath(oldPath: File, newPath: File): Int {
        runCatching {
            editorManager.retargetOpenTabsForMovedPath(oldPath, newPath)
        }.onFailure { error ->
            Timber.tag("EditorContainerState").w(
                error,
                "Failed to retarget editor manager tabs: %s -> %s",
                oldPath.absolutePath,
                newPath.absolutePath
            )
        }

        val retargetedTabs = tabManager.retargetTabsForMovedPath(oldPath, newPath)
        if (retargetedTabs.isEmpty()) return 0

        remapRetargetedTabIds(retargetedTabs)
        retargetNavigationHistory(oldPath, newPath)
        retargetedTabs.forEach { tab ->
            diagnosticsState.removeForFile(tab.oldFile)
            if (isCodeEditableType(tab.contentType)) {
                codeRuntimeCache.retargetFile(tab.newId, tab.newFile)
                releaseTinaLspForTab(tab.newId)
            }
        }

        normalizeEditorPaneState()
        persistSplitEditorState()
        return retargetedTabs.size
    }

    private fun remapRetargetedTabIds(retargetedTabs: List<EditorTabManager.RetargetedTab>) {
        val idMap = retargetedTabs
            .filter { it.oldId != it.newId }
            .associate { it.oldId to it.newId }
        if (idMap.isEmpty()) return

        idMap.forEach { (oldId, newId) ->
            codeEditorCallbacks.remove(oldId)?.let { callback -> codeEditorCallbacks[newId] = callback }
        }
        splitPaneState.remapTabIds(idMap)
        codeRuntimeCache.remapTabIds(idMap)
        lspUiState.remapTabIds(idMap)
    }

    private fun retargetNavigationHistory(oldPath: File, newPath: File) {
        retargetNavigationStack(navigationBackStack, oldPath, newPath)
        retargetNavigationStack(navigationForwardStack, oldPath, newPath)
    }

    private fun retargetNavigationStack(
        stack: SnapshotStateList<NavigationHistoryEntry>,
        oldPath: File,
        newPath: File
    ) {
        stack.indices.forEach { index ->
            val entry = stack[index]
            val retargetedFile = resolveMovedPathForOpenPath(entry.filePath, oldPath, newPath)
                ?: return@forEach
            stack[index] = entry.copy(filePath = retargetedFile.absolutePath)
        }
    }

    private fun resolveMovedPathForOpenPath(openPath: String, oldPath: File, newPath: File): File? {
        val oldNormalized = normalizeOpenTabLookupPath(oldPath.absolutePath).trimEnd('/')
        if (oldNormalized.isBlank()) return null

        val openNormalized = openPath.replace('\\', '/')
        val compareOpen = normalizeOpenTabLookupPath(openPath)
        val oldPrefix = "$oldNormalized/"
        return when {
            compareOpen == oldNormalized -> newPath
            compareOpen.startsWith(oldPrefix) -> {
                val suffix = openNormalized.substring(oldNormalized.length + 1)
                File(newPath, suffix.replace('/', File.separatorChar))
            }
            else -> null
        }
    }
}

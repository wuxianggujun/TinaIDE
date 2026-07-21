package com.wuxianggujun.tinaide.ui.compose.state.editor

import java.io.File

/**
 * Editor container public result/snapshot model types.
 */

data class NavigationHistoryEntry(
    val filePath: String,
    val line: Int,
    val column: Int
)

data class ActiveEditableEditorSnapshot(
    val file: File,
    val text: String
)

data class ActivePluginEditorContext(
    val tabId: String,
    val file: File,
    val languageId: String
)

data class PluginLspDependencyAlert(
    val sequence: Long,
    val pluginId: String,
    val pluginName: String,
    val message: String
)

data class ActiveSaveTarget(
    val tabId: String,
    val file: File
)

data class ActiveBookmarkCursorContext(
    val file: File,
    val line: Int
)

data class ActiveBookmarkTarget(
    val file: File,
    val line: Int
)

enum class ActiveEditorCommandResult {
    SUCCESS,
    NO_OPEN_FILE,
    UNSUPPORTED_EDITOR
}

enum class EditorPaneId {
    PRIMARY,
    SECONDARY
}

enum class SplitEditorLayout {
    HORIZONTAL,
    VERTICAL
}

data class SplitEditorStateSnapshot(
    val isEnabled: Boolean = false,
    val focusedPane: EditorPaneId = EditorPaneId.PRIMARY,
    val layout: SplitEditorLayout = SplitEditorLayout.HORIZONTAL,
    val primaryRatio: Float = DEFAULT_SPLIT_EDITOR_PRIMARY_RATIO,
    val tabPaneAssignments: Map<String, EditorPaneId> = emptyMap(),
    val mirroredFilePathsByPane: Map<EditorPaneId, Set<String>> = emptyMap(),
    val activeFilePathByPane: Map<EditorPaneId, String> = emptyMap()
) {
    fun normalized(): SplitEditorStateSnapshot {
        val sanitizedAssignments = linkedMapOf<String, EditorPaneId>()
        tabPaneAssignments.forEach { (path, pane) ->
            if (path.isNotBlank()) sanitizedAssignments[path] = pane
        }

        val sanitizedMirrors = linkedMapOf<EditorPaneId, Set<String>>()
        mirroredFilePathsByPane.forEach { (pane, paths) ->
            val sanitizedPaths = paths.filterTo(linkedSetOf()) { it.isNotBlank() }
            if (sanitizedPaths.isNotEmpty()) sanitizedMirrors[pane] = sanitizedPaths
        }

        val sanitizedActivePaths = linkedMapOf<EditorPaneId, String>()
        activeFilePathByPane.forEach { (pane, path) ->
            if (path.isNotBlank()) sanitizedActivePaths[pane] = path
        }

        return copy(
            primaryRatio = coerceSplitEditorPrimaryRatio(primaryRatio),
            tabPaneAssignments = sanitizedAssignments,
            mirroredFilePathsByPane = sanitizedMirrors,
            activeFilePathByPane = sanitizedActivePaths
        )
    }
}

sealed interface ActiveEditableEditorSnapshotResult {
    object NoOpenFile : ActiveEditableEditorSnapshotResult
    object UnsupportedEditor : ActiveEditableEditorSnapshotResult
    data class Success(val snapshot: ActiveEditableEditorSnapshot) : ActiveEditableEditorSnapshotResult
}

sealed interface ReplaceAllInActiveEditorResult {
    object NoOpenFile : ReplaceAllInActiveEditorResult
    object UnsupportedEditor : ReplaceAllInActiveEditorResult
    object NoMatches : ReplaceAllInActiveEditorResult
    data class Success(val count: Int) : ReplaceAllInActiveEditorResult
}

sealed interface ActiveBookmarkCursorContextResult {
    object NoOpenFile : ActiveBookmarkCursorContextResult
    object UnsupportedEditor : ActiveBookmarkCursorContextResult
    data class Success(val context: ActiveBookmarkCursorContext) : ActiveBookmarkCursorContextResult
}

sealed interface ActiveBookmarkTargetResult {
    object NoOpenFile : ActiveBookmarkTargetResult
    object UnsupportedEditor : ActiveBookmarkTargetResult
    object NoBookmarkableLine : ActiveBookmarkTargetResult
    data class Success(val target: ActiveBookmarkTarget) : ActiveBookmarkTargetResult
}

sealed interface ActiveDocumentSymbolsTargetResult {
    object NoOpenFile : ActiveDocumentSymbolsTargetResult
    object Unavailable : ActiveDocumentSymbolsTargetResult
    data class Available(val tabId: String) : ActiveDocumentSymbolsTargetResult
}

sealed interface ActiveWorkspaceSymbolsTargetResult {
    object NoOpenFile : ActiveWorkspaceSymbolsTargetResult
    object Unavailable : ActiveWorkspaceSymbolsTargetResult
    data class Available(val tabId: String) : ActiveWorkspaceSymbolsTargetResult
}

sealed interface ActiveSaveTargetResult {
    object NoOpenFile : ActiveSaveTargetResult
    data class Available(val target: ActiveSaveTarget) : ActiveSaveTargetResult
}

data class TabToolbarState(
    val isDirty: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val charsetName: String
)

data class ActiveEditorSessionAlertState(
    val tabId: String,
    val file: File,
    val hasExternalModification: Boolean,
    val lastError: String?
)


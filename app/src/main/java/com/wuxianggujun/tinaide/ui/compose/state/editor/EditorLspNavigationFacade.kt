package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.LspEditorManager
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import java.io.File

/**
 * 活动标签上的 LSP 导航/重构请求门面。
 * 从 [EditorContainerState] 抽出，降低容器内导航分支复杂度。
 */
internal class EditorLspNavigationFacade(
    private val lspEditorManager: LspEditorManager,
    private val lspUiState: EditorLspUiState,
    private val activeTabProvider: () -> EditorTabState?,
    private val cursorProvider: () -> CursorSnapshot?,
    private val selectionProvider: () -> SelectionSnapshot?,
    private val activeTabTextProvider: () -> String?,
) {
    var onLspNavigationRequested: ((tabId: String, navigationType: String) -> Unit)? = null
    var onLspCodeActionsRequested: ((tabId: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, onlyKinds: List<String>) -> Unit)? = null
    var onLspRenameRequested: ((tabId: String, line: Int, column: Int, currentName: String) -> Unit)? = null

    fun supportsBasicLspNavigation(file: File): Boolean =
        lspEditorManager.supportsBasicNavigation(file)

    fun supportsAdvancedLspNavigation(file: File): Boolean =
        lspEditorManager.supportsAdvancedNavigation(file)

    fun supportsLspRefactorActions(file: File): Boolean =
        lspEditorManager.supportsRefactorActions(file)

    fun supportsHeaderSourceSwitch(file: File): Boolean =
        lspEditorManager.supportsHeaderSourceSwitch(file)

    fun supportsActiveCallHierarchyIncoming(): Boolean {
        val tab = activeTabProvider() ?: return false
        val status = lspUiState.getStatus(tab.id)
        if (!isInteractiveLspStatus(status)) return false
        return lspEditorManager.supportsCallHierarchyIncoming(tab.id, tab.file)
    }

    fun requestActiveLspNavigation(navigationType: String): Boolean {
        val tab = activeTabProvider() ?: return false
        if (!supportsLspNavigationType(tab.id, tab.file, navigationType)) return false
        val callback = onLspNavigationRequested ?: return false
        callback(tab.id, navigationType)
        return true
    }

    fun requestActiveLspCodeActions(): Boolean {
        val tab = activeTabProvider() ?: return false
        if (!supportsLspRefactorActions(tab.file)) return false
        val callback = onLspCodeActionsRequested ?: return false
        val cursor = cursorProvider() ?: return false
        val selection = selectionProvider()
        val startLine = selection?.startLine ?: cursor.line
        val startColumn = selection?.startColumn ?: cursor.column
        val endLine = selection?.endLine ?: cursor.line
        val endColumn = selection?.endColumn ?: cursor.column
        callback(tab.id, startLine, startColumn, endLine, endColumn, emptyList())
        return true
    }

    fun requestActiveLspRename(): Boolean {
        val tab = activeTabProvider() ?: return false
        if (!supportsLspRefactorActions(tab.file)) return false
        val callback = onLspRenameRequested ?: return false
        val cursor = cursorProvider() ?: return false
        val currentName = resolveIdentifierAroundCursor(
            lineText = activeTabTextProvider()
                ?.lineSequence()
                ?.drop(cursor.line)
                ?.firstOrNull()
                .orEmpty(),
            cursor = cursor,
        )
        callback(tab.id, cursor.line, cursor.column, currentName)
        return true
    }

    fun getActiveLspTabIdOrNull(): String? {
        val tab = activeTabProvider() ?: return null
        val status = lspUiState.getStatus(tab.id)
        return tab.id.takeIf { isInteractiveLspStatus(status) }
    }

    private fun supportsLspNavigationType(tabId: String, file: File, navigationType: String): Boolean =
        when (navigationType) {
            "definition",
            "peekDefinition",
            "references",
            -> supportsBasicLspNavigation(file)
            "typeDefinition",
            "implementation",
            -> supportsAdvancedLspNavigation(file)
            "callHierarchyIncoming" -> lspEditorManager.supportsCallHierarchyIncoming(tabId, file)
            "switchHeaderSource" -> supportsHeaderSourceSwitch(file)
            else -> false
        }

    companion object {
        fun isInteractiveLspStatus(status: EditorStatus): Boolean =
            status == EditorStatus.Ready || status == EditorStatus.Busy

        fun resolveIdentifierAroundCursor(lineText: String, cursor: CursorSnapshot): String {
            if (lineText.isEmpty()) return ""
            var anchor = cursor.column.coerceIn(0, lineText.length)
            if (anchor >= lineText.length || !lineText[anchor].isEditorIdentifierChar()) {
                val leftIndex = (anchor - 1).coerceAtLeast(0)
                if (leftIndex >= lineText.length || !lineText[leftIndex].isEditorIdentifierChar()) {
                    return ""
                }
                anchor = leftIndex
            }
            var start = anchor
            while (start > 0 && lineText[start - 1].isEditorIdentifierChar()) {
                start--
            }
            var end = anchor + 1
            while (end < lineText.length && lineText[end].isEditorIdentifierChar()) {
                end++
            }
            return lineText.substring(start, end)
        }

        private fun Char.isEditorIdentifierChar(): Boolean =
            isLetterOrDigit() || this == '_' || this == '~'
    }
}

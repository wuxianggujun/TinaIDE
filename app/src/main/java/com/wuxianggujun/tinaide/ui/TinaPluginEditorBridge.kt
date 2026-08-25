package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.plugin.script.api.CursorPosition
import com.wuxianggujun.tinaide.plugin.script.api.EditorSelection
import com.wuxianggujun.tinaide.plugin.script.api.PluginActiveEditor
import com.wuxianggujun.tinaide.plugin.script.api.PluginEditorBridge
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.TextEditOperation
import com.wuxianggujun.tinaide.ui.compose.state.editor.SelectionSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot
import java.io.File

/**
 * Tina 编辑器桥接实现（不依赖第三方 CodeEditor）
 */
class TinaPluginEditorBridge(
    private val stateProvider: () -> EditorContainerState?
) : PluginEditorBridge {

    override fun getActiveEditor(): PluginActiveEditor? {
        val state = stateProvider() ?: return null
        val context = state.snapshotActivePluginEditorContextOrNull(cHeaderLanguageId = "c") ?: return null
        return PluginActiveEditor(
            tabId = context.tabId,
            filePath = context.file.absolutePath,
            fileName = context.file.name,
            languageId = context.languageId,
            isDirty = state.isActiveTabDirty(),
            cursor = state.getCursorPositionInActiveTab()?.toPluginCursorPosition()
        )
    }

    override fun getActiveFile(): File? = stateProvider()?.snapshotActivePluginEditorContextOrNull()?.file

    override fun getActiveTabId(): String? = stateProvider()?.snapshotActivePluginEditorContextOrNull()?.tabId

    override fun getText(): String? = stateProvider()?.readActiveTabText()

    override fun setText(text: String): Boolean {
        val state = stateProvider() ?: return false
        return state.replaceActiveTabText(text)
    }

    override fun getSelection(): EditorSelection? {
        val state = stateProvider() ?: return null
        state.getSelectionSnapshotInActiveTab()?.let { selection ->
            return selection.toPluginSelection()
        }

        return state.getCursorPositionInActiveTab()?.toCollapsedPluginSelection()
    }

    override fun setSelection(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean {
        val state = stateProvider() ?: return false
        return state.setSelectionInActiveTab(startLine, startColumn, endLine, endColumn)
    }

    override fun insertText(text: String, line: Int?, column: Int?): Boolean {
        val state = stateProvider() ?: return false
        return if (line != null && column != null) {
            state.applyTextEditsInActiveTab(
                edits = listOf(
                    TextEditOperation(
                        startLine = line,
                        startColumn = column,
                        endLine = line,
                        endColumn = column,
                        newText = text
                    )
                )
            )
        } else {
            state.insertTextAtCursor(text)
        }
    }

    override fun replaceSelection(text: String): Boolean {
        val state = stateProvider() ?: return false
        return state.replaceSelectionInActiveTab(text)
    }

    override fun getLanguage(): String? = stateProvider()?.snapshotActivePluginEditorContextOrNull(cHeaderLanguageId = "c")?.languageId

    override fun getCursorPosition(): CursorPosition? = stateProvider()
        ?.getCursorPositionInActiveTab()
        ?.toPluginCursorPosition()

    override fun setCursorPosition(line: Int, column: Int): Boolean {
        val state = stateProvider() ?: return false
        return state.goToPositionInActiveEditableEditor(line, column)
    }
}

private fun SelectionSnapshot.toPluginSelection(): EditorSelection = EditorSelection(
    startLine = startLine,
    startColumn = startColumn,
    endLine = endLine,
    endColumn = endColumn,
    text = text
)

private fun CursorSnapshot.toCollapsedPluginSelection(): EditorSelection = EditorSelection(
    startLine = line,
    startColumn = column,
    endLine = line,
    endColumn = column,
    text = ""
)

private fun CursorSnapshot.toPluginCursorPosition(): CursorPosition = CursorPosition(line = line, column = column)

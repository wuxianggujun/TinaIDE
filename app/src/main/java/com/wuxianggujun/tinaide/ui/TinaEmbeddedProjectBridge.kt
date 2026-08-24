package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.script.api.PluginWorkspaceFileAccess
import com.wuxianggujun.tinaide.ui.compose.state.editor.ConditionalEditorTextReplaceResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.embedded.EmbeddedProjectBridge
import me.rerere.rikkahub.data.ai.embedded.EmbeddedProjectContext
import me.rerere.rikkahub.data.ai.embedded.EmbeddedProjectFile
import me.rerere.rikkahub.data.ai.embedded.EmbeddedProjectMutation

/**
 * Exposes only the current TinaIDE project to the embedded RikkaHub instance.
 * Paths never leave the project-relative boundary enforced by PluginWorkspaceFileAccess.
 */
class TinaEmbeddedProjectBridge(
    private val projectContext: IProjectContext,
    private val editorState: EditorContainerState,
) : EmbeddedProjectBridge {
    private val fileAccess = PluginWorkspaceFileAccess {
        projectContext.getCurrentProject()?.rootPath
    }

    override suspend fun snapshot(): EmbeddedProjectContext = withContext(Dispatchers.Main.immediate) {
        val project = projectContext.getCurrentProject()
        val activeEditor = editorState.snapshotActivePluginEditorContextOrNull()
        val selection = editorState.getSelectionSnapshotInActiveTab()?.text
        EmbeddedProjectContext(
            projectName = project?.name.orEmpty(),
            activeFile = activeEditor?.file?.let(::toProjectRelativePath),
            selection = selection?.takeIf { it.isNotEmpty() },
            activeFileDirty = editorState.isActiveTabDirty(),
        )
    }

    override suspend fun findFiles(pattern: String?, maxResults: Int): List<String> = withContext(Dispatchers.IO) {
        fileAccess.findFiles(pattern, maxResults)
    }

    override suspend fun readFile(path: String): EmbeddedProjectFile {
        val normalizedPath = normalizePath(path)
        val editorContent = withContext(Dispatchers.Main.immediate) {
            val file = fileAccess.resolveSafePath(normalizedPath) ?: return@withContext null
            val tabId = editorState.findOpenTabIdByFileOrNull(file) ?: return@withContext null
            val content = editorState.readTextFromTab(tabId)
                ?: error("Open editor buffer is unavailable: $normalizedPath")
            EmbeddedProjectFile(
                path = normalizedPath,
                content = content,
                fromEditorBuffer = true,
                dirty = editorState.isOpenTabDirty(file) == true,
            )
        }
        if (editorContent != null) return editorContent

        return withContext(Dispatchers.IO) {
            val safeFile = fileAccess.resolveSafePath(normalizedPath)
                ?: error("Path is outside the current TinaIDE project")
            require(safeFile.isFile) { "File not found: $normalizedPath" }
            val content = fileAccess.openFileForRead(normalizedPath)?.use { input ->
                String(input.readBytes(), StandardCharsets.UTF_8)
            } ?: error("Unable to read file: $normalizedPath")
            EmbeddedProjectFile(
                path = normalizedPath,
                content = content,
                fromEditorBuffer = false,
                dirty = false,
            )
        }
    }

    override suspend fun writeFile(path: String, content: String): EmbeddedProjectMutation {
        val normalizedPath = normalizePath(path)
        val openEditorMutation = withContext(Dispatchers.Main.immediate) {
            val safeFile = fileAccess.resolveSafePath(normalizedPath)
                ?: error("Path is outside the current TinaIDE project")
            val tabId = editorState.findOpenTabIdByFileOrNull(safeFile)
                ?: return@withContext null

            check(editorState.isOpenTabDirty(safeFile) != true) {
                "Open editor has unsaved changes: $normalizedPath. Read it again and use host_project_edit_file for a focused change."
            }
            val currentText = editorState.readTextFromTab(tabId)
                ?: error("Open editor buffer is unavailable: $normalizedPath")
            when (editorState.replaceTextInTabIfUnchanged(tabId, currentText, content)) {
                ConditionalEditorTextReplaceResult.REPLACED -> EmbeddedProjectMutation(
                    path = normalizedPath,
                    content = content,
                    persisted = false,
                    dirty = true,
                )

                ConditionalEditorTextReplaceResult.UNCHANGED -> EmbeddedProjectMutation(
                    path = normalizedPath,
                    content = content,
                    persisted = false,
                    dirty = editorState.isOpenTabDirty(safeFile) == true,
                )

                ConditionalEditorTextReplaceResult.CONTENT_CHANGED ->
                    error("Editor content changed while writing: $normalizedPath. Read it again before writing.")

                ConditionalEditorTextReplaceResult.TARGET_UNAVAILABLE ->
                    error("Unable to update open editor: $normalizedPath")
            }
        }
        if (openEditorMutation != null) {
            return openEditorMutation
        }

        withContext(Dispatchers.IO) {
            check(fileAccess.writeUtf8File(normalizedPath, content)) {
                "Unable to write file: $normalizedPath"
            }
        }
        return EmbeddedProjectMutation(
            path = normalizedPath,
            content = content,
            persisted = true,
            dirty = false,
        )
    }

    override suspend fun editFile(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): EmbeddedProjectMutation {
        require(oldText.isNotEmpty()) { "old_text must not be empty" }
        val current = readFile(path)
        val occurrences = countOccurrences(current.content, oldText)
        require(occurrences > 0) { "old_text was not found: ${normalizePath(path)}" }
        require(replaceAll || occurrences == 1) {
            "old_text matches $occurrences locations; add context or set replace_all=true"
        }
        val updated = if (replaceAll) {
            current.content.replace(oldText, newText)
        } else {
            current.content.replaceFirst(oldText, newText)
        }
        val replacements = if (replaceAll) occurrences else 1
        updateOpenEditorIfPresent(
            normalizedPath = current.path,
            expectedText = current.content,
            newText = updated,
            replacements = replacements,
            requireOpenEditor = current.fromEditorBuffer,
        )?.let { return it }

        withContext(Dispatchers.IO) {
            val latestContent = fileAccess.openFileForRead(current.path)?.use { input ->
                String(input.readBytes(), StandardCharsets.UTF_8)
            } ?: error("Unable to read file before editing: ${current.path}")
            check(latestContent == current.content) {
                "File changed after it was read; read it again before editing: ${current.path}"
            }
            check(fileAccess.writeUtf8File(current.path, updated)) {
                "Unable to write file: ${current.path}"
            }
        }
        return EmbeddedProjectMutation(
            path = current.path,
            replacements = replacements,
            content = updated,
            persisted = true,
            dirty = false,
        )
    }

    private suspend fun updateOpenEditorIfPresent(
        normalizedPath: String,
        expectedText: String,
        newText: String,
        replacements: Int,
        requireOpenEditor: Boolean,
    ): EmbeddedProjectMutation? = withContext(Dispatchers.Main.immediate) {
        val file = fileAccess.resolveSafePath(normalizedPath)
            ?: error("Path is outside the current TinaIDE project")
        val tabId = editorState.findOpenTabIdByFileOrNull(file)
        if (tabId == null) {
            check(!requireOpenEditor) {
                "Open editor changed after it was read; read the file again: $normalizedPath"
            }
            return@withContext null
        }

        when (editorState.replaceTextInTabIfUnchanged(tabId, expectedText, newText)) {
            ConditionalEditorTextReplaceResult.REPLACED -> EmbeddedProjectMutation(
                path = normalizedPath,
                replacements = replacements,
                content = newText,
                persisted = false,
                dirty = true,
            )

            ConditionalEditorTextReplaceResult.UNCHANGED -> EmbeddedProjectMutation(
                path = normalizedPath,
                replacements = replacements,
                content = newText,
                persisted = false,
                dirty = editorState.isOpenTabDirty(file) == true,
            )

            ConditionalEditorTextReplaceResult.CONTENT_CHANGED ->
                error("Editor content changed after it was read; read the file again: $normalizedPath")

            ConditionalEditorTextReplaceResult.TARGET_UNAVAILABLE ->
                error("Open editor buffer is unavailable: $normalizedPath")
        }
    }

    private fun normalizePath(path: String): String = path.trim().replace('\\', '/')

    private fun toProjectRelativePath(file: File): String? {
        val root = projectContext.getCurrentProject()?.rootPath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            ?: return null
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (target == root) return "."
        val prefix = root.path + File.separator
        return target.path.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.replace('\\', '/')
    }

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var index = content.indexOf(needle)
        while (index >= 0) {
            count++
            index = content.indexOf(needle, index + needle.length)
        }
        return count
    }
}

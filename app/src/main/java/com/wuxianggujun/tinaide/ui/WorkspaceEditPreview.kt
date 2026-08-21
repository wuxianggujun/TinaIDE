package com.wuxianggujun.tinaide.ui

import java.io.File
import java.net.URI
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

internal data class WorkspaceEditFilePreview(
    val relativePath: String,
    val editCount: Int,
)

internal data class WorkspaceEditPreview(
    val files: List<WorkspaceEditFilePreview>,
) {
    val totalEdits: Int = files.sumOf(WorkspaceEditFilePreview::editCount)
}

internal data class ParsedWorkspaceFileEdits(
    val file: File,
    val edits: List<TextEdit>,
    val expectedVersion: Int?,
)

internal data class ParsedWorkspaceEdit(
    val projectRoot: File,
    val files: List<ParsedWorkspaceFileEdits>,
) {
    val preview: WorkspaceEditPreview = WorkspaceEditPreview(
        files = files
            .filter { it.edits.isNotEmpty() }
            .map { fileEdits ->
                WorkspaceEditFilePreview(
                    relativePath = fileEdits.file.relativeTo(projectRoot).invariantSeparatorsPath,
                    editCount = fileEdits.edits.size,
                )
            },
    )
}

internal object WorkspaceEditParser {
    fun parse(
        edit: WorkspaceEdit,
        projectRoot: File,
        maxFiles: Int,
        maxReplacementBytes: Long,
    ): ParsedWorkspaceEdit? {
        val canonicalProjectRoot = projectRoot
            .takeIf(File::isDirectory)
            ?.runCatching { canonicalFile }
            ?.getOrNull()
            ?: return null
        val editsByFile = linkedMapOf<File, WorkspaceFileEditBatch>()
        var replacementBytes = 0L

        fun addEdits(uri: String, edits: List<TextEdit>, expectedVersion: Int?): Boolean {
            val file = workspaceUriToFile(uri, canonicalProjectRoot) ?: return false
            if (file !in editsByFile && editsByFile.size >= maxFiles) return false
            edits.forEach { textEdit ->
                val editBytes = textEdit.newText.orEmpty().toByteArray(Charsets.UTF_8).size.toLong()
                if (editBytes > maxReplacementBytes - replacementBytes) return false
                replacementBytes += editBytes
            }
            val batch = editsByFile.getOrPut(file) { WorkspaceFileEditBatch() }
            if (expectedVersion != null) {
                val previousVersion = batch.expectedVersion
                if (previousVersion != null && previousVersion != expectedVersion) return false
                batch.expectedVersion = expectedVersion
            }
            batch.edits.addAll(edits)
            return true
        }

        edit.changes.orEmpty().forEach { (uri, edits) ->
            if (!addEdits(uri, edits, expectedVersion = null)) return null
        }

        edit.documentChanges.orEmpty().forEach { change ->
            if (!change.isLeft) return null
            val documentEdit = change.left
            val extractedEdits = mutableListOf<TextEdit>()
            @Suppress("UNCHECKED_CAST")
            val rawEdits = documentEdit.edits as List<*>
            rawEdits.forEach { item ->
                val textEdit = when (item) {
                    is TextEdit -> item
                    is org.eclipse.lsp4j.jsonrpc.messages.Either<*, *> -> when {
                        item.isLeft -> item.left as? TextEdit
                        item.isRight -> item.right as? TextEdit
                        else -> null
                    }

                    else -> null
                } ?: return null
                extractedEdits += textEdit
            }
            if (
                !addEdits(
                    uri = documentEdit.textDocument.uri,
                    edits = extractedEdits,
                    expectedVersion = documentEdit.textDocument.version,
                )
            ) {
                return null
            }
        }

        if (editsByFile.isEmpty()) return null
        return ParsedWorkspaceEdit(
            projectRoot = canonicalProjectRoot,
            files = editsByFile.map { (file, batch) ->
                ParsedWorkspaceFileEdits(
                    file = file,
                    edits = batch.edits.toList(),
                    expectedVersion = batch.expectedVersion,
                )
            },
        )
    }

    private fun workspaceUriToFile(uri: String, projectRoot: File): File? = runCatching {
        val parsed = URI(uri)
        val file = when {
            parsed.scheme == null -> File(uri)
            parsed.scheme.equals("file", ignoreCase = true) -> File(parsed)
            else -> null
        }
        val canonicalFile = file?.canonicalFile ?: return@runCatching null
        canonicalFile.takeIf { candidate ->
            candidate.toPath().startsWith(projectRoot.toPath()) && candidate.isFile
        }
    }.getOrNull()

    private data class WorkspaceFileEditBatch(
        val edits: MutableList<TextEdit> = mutableListOf(),
        var expectedVersion: Int? = null,
    )
}

package com.wuxianggujun.tinaide.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.After
import org.junit.Before
import org.junit.Test

class WorkspaceEditPreviewTest {
    private lateinit var projectRoot: File

    @Before
    fun setUp() {
        projectRoot = Files.createTempDirectory("workspace-edit-preview").toFile()
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
    }

    @Test
    fun `parse builds project relative multi-file summary`() {
        val source = projectFile("src/main.cpp")
        val header = projectFile("include/main.h")
        val edit = WorkspaceEdit(
            linkedMapOf(
                source.toURI().toString() to listOf(textEdit("first"), textEdit("second")),
                header.toURI().toString() to listOf(textEdit("header")),
            ),
        )

        val parsed = parse(edit)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.preview.files).containsExactly(
            WorkspaceEditFilePreview("src/main.cpp", 2),
            WorkspaceEditFilePreview("include/main.h", 1),
        ).inOrder()
        assertThat(parsed.preview.totalEdits).isEqualTo(3)
    }

    @Test
    fun `parse rejects file outside project`() {
        val outsideFile = Files.createTempFile("workspace-edit-outside", ".cpp").toFile()
        try {
            val edit = WorkspaceEdit(mapOf(outsideFile.toURI().toString() to listOf(textEdit("fixed"))))

            assertThat(parse(edit)).isNull()
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun `parse rejects resource operations`() {
        val edit = WorkspaceEdit().apply {
            documentChanges = listOf(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                    org.eclipse.lsp4j.CreateFile(projectRoot.resolve("created.cpp").toURI().toString()),
                ),
            )
        }

        assertThat(parse(edit)).isNull()
    }

    @Test
    fun `parse keeps expected document version`() {
        val source = projectFile("versioned.cpp")
        val documentEdit = TextDocumentEdit(
            VersionedTextDocumentIdentifier(source.toURI().toString(), 7),
            listOf(textEdit("updated")),
        )
        val edit = WorkspaceEdit(listOf(Either.forLeft(documentEdit)))

        val parsed = parse(edit)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.files.single().expectedVersion).isEqualTo(7)
        assertThat(parsed.preview.totalEdits).isEqualTo(1)
    }

    @Test
    fun `parse enforces file and replacement byte limits`() {
        val first = projectFile("first.cpp")
        val second = projectFile("second.cpp")
        val twoFiles = WorkspaceEdit(
            linkedMapOf(
                first.toURI().toString() to listOf(textEdit("a")),
                second.toURI().toString() to listOf(textEdit("b")),
            ),
        )
        val oversizedReplacement = WorkspaceEdit(
            mapOf(first.toURI().toString() to listOf(textEdit("four"))),
        )

        assertThat(parse(twoFiles, maxFiles = 1)).isNull()
        assertThat(parse(oversizedReplacement, maxReplacementBytes = 3)).isNull()
    }

    private fun parse(
        edit: WorkspaceEdit,
        maxFiles: Int = 8,
        maxReplacementBytes: Long = 1024,
    ): ParsedWorkspaceEdit? = WorkspaceEditParser.parse(
        edit = edit,
        projectRoot = projectRoot,
        maxFiles = maxFiles,
        maxReplacementBytes = maxReplacementBytes,
    )

    private fun projectFile(relativePath: String): File = projectRoot.resolve(relativePath).apply {
        parentFile?.mkdirs()
        writeText("int main() {}\n", Charsets.UTF_8)
    }

    private fun textEdit(replacement: String) = TextEdit(
        Range(Position(0, 0), Position(0, 0)),
        replacement,
    )
}

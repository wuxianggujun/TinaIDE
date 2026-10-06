package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateMetadataReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class UserProjectTemplateManagerTest {

    @Test
    fun listTemplates_shouldOnlyIncludeZipFilesSortedByName() {
        val dir = tempDir("user-template-list")
        try {
            zipFile(dir.resolve("beta.zip"))
            dir.resolve("notes.txt").writeText("ignored", Charsets.UTF_8)
            zipFile(dir.resolve("Alpha.ZIP"))

            val items = UserProjectTemplateManager.listTemplates(dir)

            assertThat(items.map { it.name }).containsExactly("Alpha.ZIP", "beta.zip").inOrder()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun listTemplates_shouldReadTemplateMetadata() {
        val dir = tempDir("user-template-list-metadata")
        try {
            zipFile(
                dir.resolve("demo.zip"),
                ProjectTemplateMetadataReader.METADATA_FILE_NAME to """
                    {
                      "name": "Native Game",
                      "description": "Custom SDL starter",
                      "author": "Tina",
                      "buildSystem": "cmake",
                      "primaryLanguage": "cpp",
                      "ndkTemplate": true
                    }
                """.trimIndent(),
                "CMakeLists.txt" to "project({{PROJECT_NAME}})"
            )

            val item = UserProjectTemplateManager.listTemplates(dir).single()

            assertThat(item.metadata?.name).isEqualTo("Native Game")
            assertThat(item.metadata?.description).isEqualTo("Custom SDL starter")
            assertThat(item.metadata?.author).isEqualTo("Tina")
            assertThat(item.metadata?.buildSystem).isEqualTo(ProjectBuildSystem.CMAKE)
            assertThat(item.metadata?.primaryLanguage).isEqualTo(ProjectLanguage.CPP)
            assertThat(item.metadata?.isNdkTemplate).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sanitizeTemplateFileName_shouldKeepSafeZipName() {
        assertThat(
            UserProjectTemplateManager.sanitizeTemplateFileName(" CMake Demo.ZIP ")
        ).isEqualTo("CMake Demo.zip")
        assertThat(
            UserProjectTemplateManager.sanitizeTemplateFileName("../bad:name.zip")
        ).isEqualTo("bad-name.zip")
        assertThat(
            UserProjectTemplateManager.sanitizeTemplateFileName("   .zip")
        ).isEqualTo("template.zip")
    }

    @Test
    fun importTemplate_shouldCreateUniqueZipFile() {
        val dir = tempDir("user-template-import")
        try {
            zipFile(dir.resolve("demo.zip"), "old.txt" to "old")

            val item = UserProjectTemplateManager.importTemplate(
                templatesDir = dir,
                sourceName = "demo.zip",
                input = zipBytes("main.cpp" to "int main() { return 0; }").inputStream(),
            )

            assertThat(item.name).isEqualTo("demo-1.zip")
            assertThat(dir.resolve("demo.zip").exists()).isTrue()
            assertThat(dir.resolve("demo-1.zip").exists()).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importTemplate_shouldRejectNonZipNameAndInvalidZipContent() {
        val dir = tempDir("user-template-invalid")
        try {
            val notZipName = runCatching {
                UserProjectTemplateManager.importTemplate(
                    templatesDir = dir,
                    sourceName = "demo.txt",
                    input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                )
            }
            assertThat((notZipName.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.NOT_ZIP)

            val invalidZip = runCatching {
                UserProjectTemplateManager.importTemplate(
                    templatesDir = dir,
                    sourceName = "demo.zip",
                    input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                )
            }
            assertThat((invalidZip.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.INVALID_ZIP)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteTemplate_shouldDeleteOnlyZipInsideTemplateDirectory() {
        val dir = tempDir("user-template-delete")
        try {
            zipFile(dir.resolve("demo.zip"))

            assertThat(
                UserProjectTemplateManager.deleteTemplate(dir, "demo.zip")
            ).isTrue()
            assertThat(dir.resolve("demo.zip").exists()).isFalse()

            val unsafe = runCatching {
                UserProjectTemplateManager.deleteTemplate(dir, "../outside.zip")
            }
            assertThat((unsafe.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.UNSAFE_PATH)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renameTemplate_shouldRenameZipInsideTemplateDirectory() {
        val dir = tempDir("user-template-rename")
        try {
            zipFile(dir.resolve("demo.zip"))

            val item = UserProjectTemplateManager.renameTemplate(
                templatesDir = dir,
                currentName = "demo.zip",
                desiredName = "Renamed Demo",
            )

            assertThat(item.name).isEqualTo("Renamed Demo.zip")
            assertThat(dir.resolve("demo.zip").exists()).isFalse()
            assertThat(dir.resolve("Renamed Demo.zip").exists()).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renameTemplate_shouldRejectBlankUnsafeAndExistingTarget() {
        val dir = tempDir("user-template-rename-invalid")
        try {
            zipFile(dir.resolve("demo.zip"))
            zipFile(dir.resolve("existing.zip"))

            val blankName = runCatching {
                UserProjectTemplateManager.renameTemplate(
                    templatesDir = dir,
                    currentName = "demo.zip",
                    desiredName = " ",
                )
            }
            assertThat((blankName.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.INVALID_NAME)

            val unsafeSource = runCatching {
                UserProjectTemplateManager.renameTemplate(
                    templatesDir = dir,
                    currentName = "../outside.zip",
                    desiredName = "safe.zip",
                )
            }
            assertThat((unsafeSource.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.UNSAFE_PATH)

            val existingTarget = runCatching {
                UserProjectTemplateManager.renameTemplate(
                    templatesDir = dir,
                    currentName = "demo.zip",
                    desiredName = "existing.zip",
                )
            }
            assertThat((existingTarget.exceptionOrNull() as? UserProjectTemplateException)?.failure)
                .isEqualTo(UserProjectTemplateFailure.RENAME_FAILED)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exportTemplate_shouldCopyExistingZipContent() {
        val dir = tempDir("user-template-export")
        try {
            val source = dir.resolve("demo.zip")
            zipFile(source, "main.cpp" to "int main() { return 0; }")

            val output = ByteArrayOutputStream()
            val exported = UserProjectTemplateManager.exportTemplate(
                templatesDir = dir,
                templateName = "demo.zip",
                output = output,
            )

            assertThat(exported).isTrue()
            assertThat(output.toByteArray()).isEqualTo(source.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateTemplateMetadata_shouldWriteMetadataAndPreserveTemplateContent() {
        val dir = tempDir("user-template-metadata-update")
        try {
            val source = dir.resolve("demo.zip")
            zipFile(source, "main.cpp" to "int main() { return 0; }")

            val item = UserProjectTemplateManager.updateTemplateMetadata(
                templatesDir = dir,
                templateName = "demo.zip",
                metadata = UserProjectTemplateMetadataUpdate(
                    name = "Native Demo",
                    description = "Edited template",
                    author = "Tina",
                    buildSystem = ProjectBuildSystem.CMAKE,
                    primaryLanguage = ProjectLanguage.CPP,
                    isNdkTemplate = true,
                    variables = mapOf("AUTHOR" to "TinaIDE"),
                ),
            )

            assertThat(item.name).isEqualTo("demo.zip")
            assertThat(item.metadata?.name).isEqualTo("Native Demo")
            assertThat(item.metadata?.description).isEqualTo("Edited template")
            assertThat(item.metadata?.author).isEqualTo("Tina")
            assertThat(item.metadata?.buildSystem).isEqualTo(ProjectBuildSystem.CMAKE)
            assertThat(item.metadata?.primaryLanguage).isEqualTo(ProjectLanguage.CPP)
            assertThat(item.metadata?.isNdkTemplate).isTrue()
            assertThat(item.metadata?.variables).containsExactly("AUTHOR", "TinaIDE")

            val entries = java.util.zip.ZipFile(source).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            assertThat(entries).contains("main.cpp")
            assertThat(entries).contains(ProjectTemplateMetadataReader.METADATA_FILE_NAME)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun templateMetadataPreview_shouldMatchWrittenMetadata() {
        val preview = UserProjectTemplateManager.buildTemplateMetadataPreview(
            UserProjectTemplateMetadataUpdate(
                name = "Native Demo",
                buildSystem = ProjectBuildSystem.CMAKE,
                variables = mapOf("AUTHOR" to "TinaIDE")
            )
        )

        assertThat(preview).contains("\"name\": \"Native Demo\"")
        assertThat(preview).contains("\"buildSystem\": \"cmake\"")
        assertThat(preview).contains("\"variables\"")
        assertThat(preview).contains("\"AUTHOR\": \"TinaIDE\"")
    }

    @Test
    fun variableDefaultsInput_shouldValidateParseAndFormat() {
        val raw = " AUTHOR = TinaIDE \nSDK_PATH=/opt/sdk\n"

        assertThat(UserProjectTemplateManager.validateVariableDefaultsInput(raw)).isNull()
        assertThat(UserProjectTemplateManager.parseVariableDefaults(raw))
            .containsExactly("AUTHOR", "TinaIDE", "SDK_PATH", "/opt/sdk")
        assertThat(
            UserProjectTemplateManager.formatVariableDefaults(
                mapOf("AUTHOR" to "TinaIDE", "EMPTY" to "")
            )
        ).isEqualTo("AUTHOR=TinaIDE")

        assertThat(UserProjectTemplateManager.validateVariableDefaultsInput("AUTHOR"))
            .isEqualTo(UserProjectTemplateVariableInputError.MISSING_SEPARATOR)
        assertThat(UserProjectTemplateManager.validateVariableDefaultsInput("1AUTHOR=TinaIDE"))
            .isEqualTo(UserProjectTemplateVariableInputError.INVALID_NAME)
        assertThat(UserProjectTemplateManager.validateVariableDefaultsInput("AUTHOR="))
            .isEqualTo(UserProjectTemplateVariableInputError.EMPTY_VALUE)
    }

    @Test
    fun updateTemplateMetadata_shouldWriteRunAndSdlTargetNames() {
        val dir = tempDir("user-template-target-update")
        try {
            val source = dir.resolve("demo.zip")
            zipFile(source, "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.10)")

            val item = UserProjectTemplateManager.updateTemplateMetadata(
                templatesDir = dir,
                templateName = "demo.zip",
                metadata = UserProjectTemplateMetadataUpdate(
                    defaultRunTargetName = "{{PROJECT_NAME}}_test",
                    defaultSdlTargetName = "{{PROJECT_NAME}}",
                ),
            )

            assertThat(item.metadata?.defaultRunTargetName).isEqualTo("{{PROJECT_NAME}}_test")
            assertThat(item.metadata?.defaultSdlTargetName).isEqualTo("{{PROJECT_NAME}}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateTemplateMetadata_shouldRemoveMetadataWhenFieldsAreBlank() {
        val dir = tempDir("user-template-metadata-clear")
        try {
            val source = dir.resolve("demo.zip")
            zipFile(
                source,
                ProjectTemplateMetadataReader.METADATA_FILE_NAME to """
                    {
                      "name": "Old Name",
                      "buildSystem": "cmake"
                    }
                """.trimIndent(),
                "main.cpp" to "int main() { return 0; }"
            )

            val item = UserProjectTemplateManager.updateTemplateMetadata(
                templatesDir = dir,
                templateName = "demo.zip",
                metadata = UserProjectTemplateMetadataUpdate(
                    name = " ",
                    description = "",
                    author = null,
                    buildSystem = null,
                    primaryLanguage = null,
                    isNdkTemplate = false,
                    defaultRunTargetName = "  ",
                    defaultSdlTargetName = "",
                ),
            )

            assertThat(item.metadata).isNull()
            val entries = java.util.zip.ZipFile(source).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            assertThat(entries).contains("main.cpp")
            assertThat(entries).doesNotContain(ProjectTemplateMetadataReader.METADATA_FILE_NAME)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun formatTemplateSize_shouldUseHumanReadableUnits() {
        assertThat(UserProjectTemplateManager.formatTemplateSize(0)).isEqualTo("0 B")
        assertThat(UserProjectTemplateManager.formatTemplateSize(512)).isEqualTo("512 B")
        assertThat(UserProjectTemplateManager.formatTemplateSize(1536)).isEqualTo("1.5 KB")
        assertThat(UserProjectTemplateManager.formatTemplateSize(2L * 1024L * 1024L)).isEqualTo("2.0 MB")
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun zipFile(file: File, vararg entries: Pair<String, String>) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                val resolvedEntries = entries.takeIf { it.isNotEmpty() }
                    ?: arrayOf("README.md" to "template")
                resolvedEntries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}

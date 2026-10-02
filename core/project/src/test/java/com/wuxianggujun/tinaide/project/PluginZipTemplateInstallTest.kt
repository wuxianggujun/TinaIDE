package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PluginZipTemplateInstallTest {

    @Test
    fun `plugin zip template install replaces placeholders and writes plugin metadata`() = runTest {
        val tempDir = Files.createTempDirectory("plugin-template-install").toFile()
        val zipFile = Files.createTempFile("plugin-template", ".zip").toFile()

        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.writeEntry(
                    "manifest.json",
                    """
                    {
                      "id": "com.example.{{PROJECT_NAME}}",
                      "name": "{{PROJECT_NAME}}",
                      "version": "0.1.0",
                      "type": "config"
                    }
                    """.trimIndent()
                )
                zip.writeEntry(
                    "README.md",
                    "# {{PROJECT_NAME}}\n"
                )
            }

            val installed = ProjectTemplateInstaller.install(
                destDir = tempDir,
                projectName = "hello-plugin",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "plugin:tinaide.plugin.starters:config-basic",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.PLUGIN,
                    primaryLanguage = ProjectLanguage.MIXED
                )
            )

            val metadata = ProjectMetadataStore.read(tempDir)

            assertThat(installed).isTrue()
            assertThat(tempDir.resolve("manifest.json").readText(Charsets.UTF_8))
                .contains("com.example.hello-plugin")
            assertThat(tempDir.resolve("README.md").readText(Charsets.UTF_8))
                .contains("# hello-plugin")
            assertThat(metadata?.buildSystem).isEqualTo(ProjectBuildSystem.PLUGIN)
            assertThat(metadata?.primaryLanguage).isEqualTo(ProjectLanguage.MIXED.name)
        } finally {
            tempDir.deleteRecursively()
            zipFile.delete()
        }
    }

    @Test
    fun `zip template install replaces author placeholder and skips template metadata`() = runTest {
        val tempDir = Files.createTempDirectory("template-author-install").toFile()
        val zipFile = Files.createTempFile("template-author", ".zip").toFile()

        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.writeEntry(
                    ProjectTemplateMetadataReader.METADATA_FILE_NAME,
                    """{"name":"Author Demo","author":"Template Author"}"""
                )
                zip.writeEntry("README.md", "# {{PROJECT_NAME}}\nAuthor: {{AUTHOR}}\n")
                zip.writeEntry("src/{{AUTHOR}}/{{PROJECT_NAME}}.cpp", "int main() { return 0; }\n")
            }

            val installed = ProjectTemplateInstaller.install(
                destDir = tempDir,
                projectName = "hello-author",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "user:author-demo",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    primaryLanguage = ProjectLanguage.CPP
                ),
                authorName = "Ada"
            )

            assertThat(installed).isTrue()
            assertThat(tempDir.resolve("README.md").readText(Charsets.UTF_8))
                .contains("Author: Ada")
            assertThat(tempDir.resolve("src/Ada/hello-author.cpp").exists()).isTrue()
            assertThat(tempDir.resolve(ProjectTemplateMetadataReader.METADATA_FILE_NAME).exists()).isFalse()
        } finally {
            tempDir.deleteRecursively()
            zipFile.delete()
        }
    }

    @Test
    fun `zip template install uses author variable default when author input is blank`() = runTest {
        val tempDir = Files.createTempDirectory("template-author-default-install").toFile()
        val zipFile = Files.createTempFile("template-author-default", ".zip").toFile()

        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.writeEntry("README.md", "# {{PROJECT_NAME}}\nAuthor: {{AUTHOR}}\n")
            }

            val installed = ProjectTemplateInstaller.install(
                destDir = tempDir,
                projectName = "hello-author-default",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "user:author-default-demo",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    primaryLanguage = ProjectLanguage.CPP,
                    variables = mapOf("AUTHOR" to "TinaIDE")
                ),
                authorName = " "
            )

            assertThat(installed).isTrue()
            assertThat(tempDir.resolve("README.md").readText(Charsets.UTF_8))
                .contains("Author: TinaIDE")
        } finally {
            tempDir.deleteRecursively()
            zipFile.delete()
        }
    }

    @Test
    fun `zip template install writes resolved default run targets to metadata`() = runTest {
        val tempDir = Files.createTempDirectory("template-default-target-install").toFile()
        val zipFile = Files.createTempFile("template-default-target", ".zip").toFile()

        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.writeEntry("CMakeLists.txt", "project({{PROJECT_NAME}})\n")
            }

            val installed = ProjectTemplateInstaller.install(
                destDir = tempDir,
                projectName = "hello-target",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "user:default-target-demo",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    primaryLanguage = ProjectLanguage.CPP,
                    defaultRunTargetName = "{{PROJECT_NAME}}_test",
                    defaultSdlTargetName = "{{PROJECT_NAME}}"
                )
            )

            val metadata = ProjectMetadataStore.read(tempDir)

            assertThat(installed).isTrue()
            assertThat(metadata?.defaultRunTargetName).isEqualTo("hello-target_test")
            assertThat(metadata?.defaultSdlTargetName).isEqualTo("hello-target")
        } finally {
            tempDir.deleteRecursively()
            zipFile.delete()
        }
    }

    @Test
    fun `zip template install rejects entries escaping project directory`() = runTest {
        val tempRoot = Files.createTempDirectory("plugin-template-escape").toFile()
        val projectDir = tempRoot.resolve("project")
        val absoluteEscapedFile = tempRoot.resolve("absolute-escaped.txt")
        val driveStyleEscapedFile = tempRoot.resolve("drive-escaped.txt")
        val cases = listOf(
            EscapeCase("../escaped.txt", tempRoot.resolve("escaped.txt")),
            EscapeCase("..\\escaped-backslash.txt", tempRoot.resolve("escaped-backslash.txt")),
            EscapeCase(absoluteEscapedFile.absolutePath, absoluteEscapedFile),
            EscapeCase(
                driveStyleEscapedFile.absolutePath.replace(File.separatorChar, '\\'),
                driveStyleEscapedFile
            ),
            EscapeCase(
                entryName = "{{PROJECT_NAME}}/file.txt",
                escapedFile = tempRoot.resolve("placeholder-escaped/file.txt"),
                projectName = "../placeholder-escaped"
            )
        )

        try {
            cases.forEachIndexed { index, case ->
                val zipFile = Files.createTempFile("plugin-template-escape-$index", ".zip").toFile()
                try {
                    ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                        zip.writeEntry(case.entryName, "escaped")
                    }

                    val installed = ProjectTemplateInstaller.install(
                        destDir = projectDir,
                        projectName = case.projectName,
                        templateSpec = ProjectTemplateSpec.Zip(
                            id = "plugin:unsafe:$index",
                            zipFile = zipFile,
                            buildSystem = ProjectBuildSystem.PLUGIN,
                            primaryLanguage = ProjectLanguage.MIXED
                        )
                    )

                    assertThat(installed).isFalse()
                    assertThat(case.escapedFile.exists()).isFalse()
                    assertThat(ProjectMetadataStore.read(projectDir)).isNull()
                } finally {
                    zipFile.delete()
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `zip template install does not leave partial files after later unsafe entry`() = runTest {
        val tempRoot = Files.createTempDirectory("plugin-template-partial").toFile()
        val projectDir = tempRoot.resolve("project").apply { mkdirs() }
        val existingFile = projectDir.resolve("existing.txt").apply {
            writeText("keep", Charsets.UTF_8)
        }
        val zipFile = Files.createTempFile("plugin-template-partial", ".zip").toFile()

        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.writeEntry("README.md", "# {{PROJECT_NAME}}\n")
                zip.writeEntry("../escaped-after-readme.txt", "escaped")
            }

            val installed = ProjectTemplateInstaller.install(
                destDir = projectDir,
                projectName = "partial-plugin",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "plugin:unsafe:partial",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.PLUGIN,
                    primaryLanguage = ProjectLanguage.MIXED
                )
            )

            assertThat(installed).isFalse()
            assertThat(projectDir.resolve("README.md").exists()).isFalse()
            assertThat(existingFile.readText(Charsets.UTF_8)).isEqualTo("keep")
            assertThat(tempRoot.resolve("escaped-after-readme.txt").exists()).isFalse()
            assertThat(ProjectMetadataStore.read(projectDir)).isNull()
        } finally {
            tempRoot.deleteRecursively()
            zipFile.delete()
        }
    }

    private data class EscapeCase(
        val entryName: String,
        val escapedFile: File,
        val projectName: String = "unsafe-plugin"
    )

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

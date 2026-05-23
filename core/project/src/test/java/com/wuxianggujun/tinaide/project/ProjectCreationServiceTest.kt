package com.wuxianggujun.tinaide.project

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class ProjectCreationServiceTest {

    @Test
    fun `createProject rejects names that can escape project root`() {
        val projectRoot = Files.createTempDirectory("project-create-root").toFile()
        val templateZip = createTemplateZip()
        val unsafeNames = listOf(
            "../escaped",
            "..\\escaped",
            "nested/project",
            "nested\\project",
            File(projectRoot.parentFile, "absolute").absolutePath,
            ".",
            ".."
        )

        try {
            unsafeNames.forEach { name ->
                val result = ProjectCreationService.createProject(
                    context = testContext(),
                    projectRoot = projectRoot,
                    projectName = name,
                    templateSpec = zipTemplateSpec(templateZip)
                )

                assertThat(result).isInstanceOf(ProjectCreationResult.Failure::class.java)
                assertThat((result as ProjectCreationResult.Failure).reason)
                    .isEqualTo(ProjectCreationFailure.INVALID_NAME)
            }

            assertThat(projectRoot.listFiles()?.toList()).isEmpty()
        } finally {
            projectRoot.deleteRecursively()
            templateZip.delete()
        }
    }

    @Test
    fun `createProject accepts safe names and installs zip template`() {
        val projectRoot = Files.createTempDirectory("project-create-success").toFile()
        val templateZip = createTemplateZip()

        try {
            val result = ProjectCreationService.createProject(
                context = testContext(),
                projectRoot = projectRoot,
                projectName = "Safe_Project-1",
                templateSpec = zipTemplateSpec(templateZip)
            )

            assertThat(result).isInstanceOf(ProjectCreationResult.Success::class.java)
            val projectDir = (result as ProjectCreationResult.Success).projectDir
            assertThat(projectDir.parentFile.canonicalFile).isEqualTo(projectRoot.canonicalFile)
            assertThat(projectDir.name).isEqualTo("Safe_Project-1")
            assertThat(projectDir.resolve("README.md").readText(Charsets.UTF_8))
                .contains("# Safe_Project-1")
        } finally {
            projectRoot.deleteRecursively()
            templateZip.delete()
        }
    }

    @Test
    fun `createProject cleans failed project only inside project root`() {
        val projectRoot = Files.createTempDirectory("project-create-cleanup").toFile()
        val siblingFile = projectRoot.resolve("sibling-marker.txt")
        val brokenZip = Files.createTempFile("broken-template", ".zip").toFile()

        try {
            siblingFile.writeText("keep", Charsets.UTF_8)

            ZipOutputStream(brokenZip.outputStream().buffered()).use { zip ->
                zip.writeEntry("../sibling-marker.txt", "escaped")
            }

            val result = ProjectCreationService.createProject(
                context = testContext(),
                projectRoot = projectRoot,
                projectName = "SafeProject",
                templateSpec = zipTemplateSpec(brokenZip)
            )

            assertThat(result).isInstanceOf(ProjectCreationResult.Failure::class.java)
            assertThat((result as ProjectCreationResult.Failure).reason)
                .isEqualTo(ProjectCreationFailure.TEMPLATE_INSTALL_FAILED)
            assertThat(projectRoot.resolve("SafeProject").exists()).isFalse()
            assertThat(siblingFile.readText(Charsets.UTF_8)).isEqualTo("keep")
        } finally {
            projectRoot.deleteRecursively()
            brokenZip.delete()
        }
    }

    private fun zipTemplateSpec(zipFile: File): ProjectTemplateSpec.Zip =
        ProjectTemplateSpec.Zip(
            id = "test:zip",
            zipFile = zipFile,
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP
        )

    private fun createTemplateZip(): File {
        val zipFile = Files.createTempFile("project-template", ".zip").toFile()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.writeEntry("README.md", "# {{PROJECT_NAME}}\n")
        }
        return zipFile
    }

    private fun testContext(): Context = mockk(relaxed = true)

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

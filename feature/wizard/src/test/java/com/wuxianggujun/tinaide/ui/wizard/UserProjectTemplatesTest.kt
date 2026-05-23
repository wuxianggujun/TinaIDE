package com.wuxianggujun.tinaide.ui.wizard

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserProjectTemplatesTest {

    @Test
    fun listOptionsFromDirectory_shouldScanZipTemplatesAndInferCMakeCpp() {
        val templatesDir = Files.createTempDirectory("user-project-templates").toFile()
        val templateZip = templatesDir.resolve("cmake_cpp_demo.zip")
        templatesDir.resolve("notes.txt").writeText("ignored", Charsets.UTF_8)

        try {
            ZipOutputStream(templateZip.outputStream().buffered()).use { zip ->
                zip.writeEntry("CMakeLists.txt", "add_executable({{PROJECT_NAME}} src/main.cpp)")
                zip.writeEntry("src/main.cpp", "int main() { return 0; }")
            }

            val options = UserProjectTemplates.listOptionsFromDirectory(
                context = RuntimeEnvironment.getApplication().applicationContext,
                templatesDir = templatesDir,
            )

            assertThat(options).hasSize(1)
            val option = options.single()
            val spec = option.spec as ProjectTemplateSpec.Zip
            assertThat(option.id).startsWith(UserProjectTemplates.TEMPLATE_ID_PREFIX)
            assertThat(option.displayName).isEqualTo("Cmake Cpp Demo")
            assertThat(option.description).contains("cmake_cpp_demo.zip")
            assertThat(spec.zipFile).isEqualTo(templateZip)
            assertThat(spec.buildSystem).isEqualTo(ProjectBuildSystem.CMAKE)
            assertThat(spec.primaryLanguage).isEqualTo(ProjectLanguage.CPP)
            assertThat(spec.isNdkTemplate).isFalse()
        } finally {
            templatesDir.deleteRecursively()
        }
    }

    @Test
    fun listOptionsFromDirectory_shouldIgnoreNonZipFiles() {
        val templatesDir = Files.createTempDirectory("user-project-templates-ignore").toFile()

        try {
            templatesDir.resolve("plain-template.txt").writeText("not a zip", Charsets.UTF_8)

            val options = UserProjectTemplates.listOptionsFromDirectory(
                context = RuntimeEnvironment.getApplication().applicationContext,
                templatesDir = templatesDir,
            )

            assertThat(options).isEmpty()
        } finally {
            templatesDir.deleteRecursively()
        }
    }

    @Test
    fun listOptionsFromDirectory_shouldLimitManifestInspectionBytes() {
        val templatesDir = Files.createTempDirectory("user-project-templates-large-manifest").toFile()
        val templateZip = templatesDir.resolve("large_manifest.zip")

        try {
            ZipOutputStream(templateZip.outputStream().buffered()).use { zip ->
                val largePrefix = " ".repeat(70 * 1024)
                zip.writeEntry(
                    "manifest.json",
                    largePrefix + """{"id":"demo","name":"Demo","version":"1.0.0","type":"plugin"}"""
                )
                zip.writeEntry("src/main.cpp", "int main() { return 0; }")
            }

            val options = UserProjectTemplates.listOptionsFromDirectory(
                context = RuntimeEnvironment.getApplication().applicationContext,
                templatesDir = templatesDir,
            )

            val spec = options.single().spec as ProjectTemplateSpec.Zip
            assertThat(spec.buildSystem).isEqualTo(ProjectBuildSystem.SINGLE_FILE)
        } finally {
            templatesDir.deleteRecursively()
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

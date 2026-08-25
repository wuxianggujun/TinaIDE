package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ExternalZipTemplateInstallTest {

    @Test
    fun `zip template install replaces placeholders`() {
        val repoRoot = locateRepoRoot()
        val zipFile = repoRoot.resolve("test-plugins/tinaide.template.sdl3/templates/sdl3_cmake.zip").toFile()
        val tempDir = Files.createTempDirectory("sdl3-template-install").toFile()

        try {
            val installed = ProjectTemplateInstaller.install(
                destDir = tempDir,
                projectName = "HelloSDL3",
                templateSpec = ProjectTemplateSpec.Zip(
                    id = "plugin:test:sdl3",
                    zipFile = zipFile,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    primaryLanguage = ProjectLanguage.CPP
                ),
                cppStandard = CppStandard.CPP_20
            )

            assertThat(installed).isTrue()
            assertThat(tempDir.resolve("CMakeLists.txt").readText()).contains("project(HelloSDL3")
            assertThat(tempDir.resolve("CMakeLists.txt").readText()).contains("add_library(HelloSDL3 SHARED")
            assertThat(tempDir.resolve("CMakeLists.txt").readText()).contains("OUTPUT_NAME \"main\"")
            assertThat(tempDir.resolve("CMakeLists.txt").readText()).contains("set(CMAKE_CXX_STANDARD 20)")
            assertThat(tempDir.resolve("src/main.cpp").readText()).contains("#include <SDL3/SDL_main.h>")
            assertThat(tempDir.resolve("src/main.cpp").readText()).contains("\"HelloSDL3\"")
            assertThat(ProjectMetadataStore.read(tempDir)?.apkExportType).isEqualTo(ProjectApkExportType.SDL3)
            assertThat(ProjectMetadataStore.read(tempDir)?.sdlVersion).isEqualTo(ProjectSdlVersion.SDL3)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun locateRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("未找到仓库根目录 settings.gradle.kts")
        }
    }
}

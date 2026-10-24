package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Test

class Sdl3TemplateRegressionTest {

    @Test
    fun `sdl3 plugin template exposes callback entry sample`() {
        val zipPath = locateRepoRoot().resolve("test-plugins/tinaide.template.sdl3/templates/sdl3_cmake.zip")
        ZipFile(zipPath.toFile()).use { zip ->
            val cmake = zip.readText("CMakeLists.txt")
            val main = zip.readText("src/main.cpp")

            assertThat(cmake).contains("find_package(SDL3 CONFIG REQUIRED)")
            assertThat(cmake).contains("add_library({{PROJECT_NAME}} SHARED")
            assertThat(cmake).contains("OUTPUT_NAME \"main\"")
            assertThat(cmake).contains("target_link_libraries({{PROJECT_NAME}} PRIVATE SDL3::SDL3)")
            assertThat(main).contains("#define SDL_MAIN_USE_CALLBACKS 1")
            assertThat(main).contains("#include <SDL3/SDL_main.h>")
            assertThat(main).contains("SDL_AppInit")
            assertThat(main).contains("SDL_AppIterate")
            assertThat(main).contains("SDL_AppQuit")
        }
    }

    @Test
    fun `sdl3 plugin zip stays in sync with tools project template source`() {
        // 源头是 tools/project-templates/sdl3_cmake；改了源码必须重新打包插件 zip，
        // 否则插件安装出的项目与内置模板分叉。行尾按 LF 归一后逐字节比对。
        val repoRoot = locateRepoRoot()
        val zipPath = repoRoot.resolve("test-plugins/tinaide.template.sdl3/templates/sdl3_cmake.zip")
        val sourceDir = repoRoot.resolve("tools/project-templates/sdl3_cmake")

        ZipFile(zipPath.toFile()).use { zip ->
            val entryNames = zip.entries().toList().map { it.name }
            assertThat(entryNames).containsExactly(".gitignore", "CMakeLists.txt", "src/main.cpp")
            entryNames.forEach { name ->
                val zipText = zip.readText(name).replace("\r\n", "\n")
                val sourceFile = sourceDir.resolve(name).toFile()
                val sourceText = sourceFile.readText(StandardCharsets.UTF_8).replace("\r\n", "\n")
                assertThat(zipText).isEqualTo(sourceText)
            }
        }
    }

    private fun ZipFile.readText(entryName: String): String {
        val entry = requireNotNull(getEntry(entryName)) {
            "sdl3_cmake.zip 缺少 $entryName"
        }
        return getInputStream(entry).use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
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

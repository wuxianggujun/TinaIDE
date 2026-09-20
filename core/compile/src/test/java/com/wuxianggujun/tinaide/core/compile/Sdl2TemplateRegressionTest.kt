package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Test

class Sdl2TemplateRegressionTest {

    @Test
    fun `sdl2 plugin template exposes classic main entry sample`() {
        val zipPath = locateRepoRoot().resolve("test-plugins/tinaide.template.sdl2/templates/sdl2_cmake.zip")
        ZipFile(zipPath.toFile()).use { zip ->
            val cmake = zip.readText("CMakeLists.txt")
            val main = zip.readText("src/main.cpp")

            assertThat(cmake).contains("find_package(SDL2 CONFIG REQUIRED)")
            assertThat(cmake).contains("add_library({{PROJECT_NAME}} SHARED")
            assertThat(cmake).contains("OUTPUT_NAME \"main\"")
            assertThat(cmake).contains("target_link_libraries({{PROJECT_NAME}} PRIVATE SDL2::SDL2)")
            assertThat(main).contains("#include <SDL2/SDL.h>")
            assertThat(main).contains("int main(")
            assertThat(main).contains("SDL_Init(SDL_INIT_VIDEO)")
            assertThat(main).contains("SDL_CreateWindow")
            assertThat(main).contains("SDL_PollEvent")
            assertThat(main).contains("SDL_QUIT")
        }
    }

    @Test
    fun `sdl2 plugin template contains no sdl3 markers`() {
        // 版本识别纯靠源码文本 marker：SDL2 模板一旦泄漏任何 SDL3 marker，
        // detectSupport 会因 hasSdl2Marker == hasSdl3Marker 判为 ambiguous，运行链就走错进程。
        val zipPath = locateRepoRoot().resolve("test-plugins/tinaide.template.sdl2/templates/sdl2_cmake.zip")
        ZipFile(zipPath.toFile()).use { zip ->
            val cmake = zip.readText("CMakeLists.txt")
            val main = zip.readText("src/main.cpp")
            val sdl3Markers = listOf(
                "SDL3",
                "SDL_MAIN_USE_CALLBACKS",
                "SDL_AppInit",
                "SDL_AppIterate",
                "SDL_AppEvent",
                "SDL_AppQuit",
            )
            sdl3Markers.forEach { marker ->
                assertThat(cmake).doesNotContain(marker)
                assertThat(main).doesNotContain(marker)
            }
        }
    }

    private fun ZipFile.readText(entryName: String): String {
        val entry = requireNotNull(getEntry(entryName)) {
            "sdl2_cmake.zip 缺少 $entryName"
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

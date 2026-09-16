package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Test

class RaylibTemplateRegressionTest {

    @Test
    fun `raylib plugin template exposes shared library entry sample`() {
        val zipPath = locateRepoRoot().resolve("test-plugins/tinaide.template.raylib/templates/raylib_cmake.zip")
        ZipFile(zipPath.toFile()).use { zip ->
            val cmake = zip.readText("CMakeLists.txt")
            val main = zip.readText("src/main.c")

            assertThat(cmake).contains("find_package(raylib CONFIG REQUIRED)")
            // 必须是 SHARED：raylib 自己导出 ANativeActivity_onCreate，由宿主 dlopen 后回调 main。
            assertThat(cmake).contains("add_library({{PROJECT_NAME}} SHARED")
            assertThat(cmake).contains("target_link_libraries({{PROJECT_NAME}} PRIVATE raylib::raylib)")
            // 导出 APK 的模板把 android.app.lib_name 写死为 main，模板要同时满足运行与导出。
            assertThat(cmake).contains("OUTPUT_NAME \"main\"")
            assertThat(main).contains("#include <raylib.h>")
            assertThat(main).contains("int main(void)")
            assertThat(main).contains("InitWindow")
            assertThat(main).contains("BeginDrawing")
            // 混入 SDL 依赖会让探测判成 SDL 项目并拒绝 NativeActivity 运行链路。
            // 这里按探测器实际使用的标记匹配，不是笼统搜 "SDL" 三个字母。
            listOf("SDL2", "SDL3", "#include <SDL", "-lSDL").forEach { marker ->
                assertThat(main).doesNotContain(marker)
                assertThat(cmake).doesNotContain(marker)
            }
        }
    }

    private fun ZipFile.readText(entryName: String): String {
        val entry = requireNotNull(getEntry(entryName)) {
            "raylib_cmake.zip 缺少 $entryName"
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

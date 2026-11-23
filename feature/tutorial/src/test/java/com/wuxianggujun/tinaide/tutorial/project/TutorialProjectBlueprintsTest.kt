package com.wuxianggujun.tinaide.tutorial.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class TutorialProjectBlueprintsTest {
    @Test
    fun vcpkgStarterDeclaresAndUsesFmtBeforeItsFirstBuild() {
        val files = blueprint("vcpkg_guide").files
        assertThat(files.keys).contains("vcpkg.json")
        val dependencies = Json.parseToJsonElement(files.getValue("vcpkg.json"))
            .jsonObject.getValue("dependencies").jsonArray.map { it.jsonPrimitive.content }
        assertThat(dependencies).containsExactly("fmt")
        assertThat(files.getValue("main.cpp")).contains("#include <fmt/core.h>")
        assertThat(files.getValue("main.cpp")).contains("fmt::print")
        assertThat(files.getValue("CMakeLists.txt")).contains("find_package(fmt CONFIG REQUIRED)")
        assertThat(files.getValue("CMakeLists.txt")).contains("fmt::fmt")
    }

    @Test
    fun allNineChaptersHaveCompleteMatchingLocalizedStarterFiles() {
        assertThat(TutorialProjectBlueprints.stepIds).hasSize(9)
        for (stepId in TutorialProjectBlueprints.stepIds) {
            val chinese = blueprint(stepId, "zh")
            val english = blueprint(stepId, "en")
            assertThat(english).isEqualTo(chinese)
            val expectedFiles = when (stepId) {
                "ide_workflow", "cpp_basics", "graphics_sdl3" -> setOf("CMakeLists.txt", "main.cpp")
                "vcpkg_guide" -> setOf("CMakeLists.txt", "main.cpp", "pricing.h", "pricing.cpp", "vcpkg.json")
                else -> setOf("CMakeLists.txt", "main.cpp", "pricing.h", "pricing.cpp")
            }
            assertThat(chinese.files.keys).containsExactlyElementsIn(expectedFiles)
            assertThat(chinese.files.getValue("CMakeLists.txt")).contains("project(native_lab LANGUAGES CXX)")
            assertThat(chinese.files.getValue("CMakeLists.txt")).contains("cxx_std_17")
            assertThat(chinese.files.getValue("main.cpp"))
                .contains(if (chinese.isSdl3) "SDL_AppInit" else "int main()")
            for (contents in chinese.files.values) {
                assertThat(contents).doesNotContain("<!-- tutorial-project-file:")
                assertThat(contents.startsWith('\uFEFF')).isFalse()
                assertThat(contents.endsWith('\n')).isTrue()
            }
        }
    }

    @Test
    fun prerequisiteSourcesAreReusedWithoutUsingExerciseAnswers() {
        val functions = blueprint("cpp_functions")
        val raii = blueprint("cpp_raii")
        for (stepId in listOf("cpp_collections", "cpp_raii", "cmake_practice", "vcpkg_guide", "ai_practice")) {
            val files = blueprint(stepId).files
            assertThat(files.getValue("pricing.h")).isEqualTo(functions.files.getValue("pricing.h"))
            assertThat(files.getValue("pricing.cpp")).isEqualTo(functions.files.getValue("pricing.cpp"))
            assertThat(files.getValue("pricing.cpp")).doesNotContain("discountedTotal")
        }
        assertThat(blueprint("cmake_practice").files.getValue("main.cpp")).isEqualTo(raii.files.getValue("main.cpp"))
        assertThat(blueprint("cpp_basics").files.getValue("main.cpp")).contains("const int count = 3;")
    }

    @Test
    fun dependencyAndGraphicsModesStayIsolatedAndAiIsOffline() {
        val vcpkg = blueprint("vcpkg_guide")
        val graphics = blueprint("graphics_sdl3")
        val ai = blueprint("ai_practice")
        assertThat(vcpkg.useVcpkg).isTrue()
        assertThat(vcpkg.files.getValue("CMakeLists.txt")).contains("fmt::fmt")
        assertThat(graphics.isSdl3).isTrue()
        assertThat(graphics.useVcpkg).isFalse()
        assertThat(graphics.files.getValue("CMakeLists.txt")).contains("OUTPUT_NAME \"main\"")
        assertThat(ai.useVcpkg).isFalse()
        assertThat(ai.isSdl3).isFalse()
        assertThat(ai.files.values.joinToString("\n")).doesNotContain("fmt")
        assertThat(ai.files.values.joinToString("\n")).doesNotContain("http")
        assertThat(TutorialProjectBlueprints.supports("final_review")).isFalse()
    }

    @Test
    fun namedMarkersIgnoreUnrelatedCodeBlocksAndAcceptCrLf() {
        val marked = "<!-- tutorial-project-file: main.cpp -->\r\n\r\n```cpp\r\nint main() {}\r\n```\r\n"
        val markdown = "```cpp\nint unrelated;\n```\n$marked\n```cpp\nint exerciseAnswer;\n```"
        assertThat(TutorialProjectBlueprints.extractProjectFiles(markdown))
            .containsExactly("main.cpp", "int main() {}\n")
    }

    @Test
    fun missingDuplicateEmptyAndUnsafeFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TutorialProjectBlueprints.load("unknown") { "" } }
        assertThrows(IllegalArgumentException::class.java) { TutorialProjectBlueprints.load("ide_workflow") { "```cpp\nint main() {}\n```" } }
        val marked = "<!-- tutorial-project-file: main.cpp -->\n```cpp\nint main() {}\n```\n"
        assertThrows(IllegalArgumentException::class.java) { TutorialProjectBlueprints.extractProjectFiles(marked + marked) }
        assertThrows(IllegalArgumentException::class.java) {
            TutorialProjectBlueprints.extractProjectFiles("<!-- tutorial-project-file: main.cpp -->\n```cpp\n \n```\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorialProjectBlueprints.load("ide_workflow") { "<!-- tutorial-project-file: ../main.cpp -->\n```cpp\nint main() {}\n```" }
        }
    }

    private fun blueprint(stepId: String, language: String = "zh") = TutorialProjectBlueprints.load(stepId) {
        readBundledLesson(it, language)
    }
}

internal fun readBundledLesson(lesson: String, language: String): String {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "feature/help/src/main/assets/tutorial-course").isDirectory }
    val prefix = if (language == "en") "feature/help/src/main/assets/tutorial-course/en" else "feature/help/src/main/assets/tutorial-course"
    return File(root, "$prefix/$lesson.md").readText(Charsets.UTF_8)
}

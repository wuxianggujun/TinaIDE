package com.wuxianggujun.tinaide.tutorial

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TutorialCourseContentTest {

    @Test
    fun loadCourseContent_shouldRejectMissingOrBlankContent() = runTest {
        var loadCount = 0

        val missingUrlState = loadTutorialCourseContent(null) {
            loadCount += 1
            Result.success("unused")
        }
        val blankContentState = loadTutorialCourseContent("blank.md") {
            loadCount += 1
            Result.success("   \n")
        }

        assertThat(missingUrlState).isEqualTo(TutorialCourseContentState.Error)
        assertThat(blankContentState).isEqualTo(TutorialCourseContentState.Error)
        assertThat(loadCount).isEqualTo(1)
    }

    @Test
    fun loadCourseContent_shouldExposeLoadedMarkdown() = runTest {
        val state = loadTutorialCourseContent("cpp-basics.md") {
            Result.success("# C++ course")
        }

        assertThat(state).isEqualTo(
            TutorialCourseContentState.Content("# C++ course")
        )
    }

    @Test
    fun loadCourseContent_shouldConvertResultFailureOrThrownLoaderToError() = runTest {
        val resultFailure = loadTutorialCourseContent("failure.md") {
            Result.failure(IllegalStateException("asset failure"))
        }
        val thrownFailure = loadTutorialCourseContent("thrown.md") {
            throw IllegalStateException("unexpected loader failure")
        }

        assertThat(resultFailure).isEqualTo(TutorialCourseContentState.Error)
        assertThat(thrownFailure).isEqualTo(TutorialCourseContentState.Error)
    }

    @Test
    fun loadCourseContent_shouldPropagateCancellation() = runTest {
        val cancellation = CancellationException("course loading cancelled")
        var propagatedCancellation: CancellationException? = null

        try {
            loadTutorialCourseContent("cancelled.md") {
                throw cancellation
            }
        } catch (caught: CancellationException) {
            propagatedCancellation = caught
        }

        assertThat(propagatedCancellation).isSameInstanceAs(cancellation)
    }

    @Test
    fun loadCourseContent_shouldSeparateProgressiveHintsFromTheLesson() = runTest {
        val state = loadTutorialCourseContent("lesson.md") {
            Result.success("# Lesson\r\n\r\nTask\r\n<!-- tutorial-hint -->\r\nWhere to edit\r\n" +
                "<!-- tutorial-hint -->\r\n\r\n<!-- tutorial-hint -->\r\nReference code\r\n")
        } as TutorialCourseContentState.Content

        assertThat(state.markdown).isEqualTo("# Lesson\n\nTask")
        assertThat(state.hints).containsExactly("Where to edit", "Reference code").inOrder()
    }

    @Test
    fun loadCourseContent_shouldKeepHintMarkersInsideFencedCode() = runTest {
        for (fence in listOf("````", "~~~")) {
            val lesson = "# Example\n${fence}text\n<!-- tutorial-hint -->\n```\n中文\n$fence"
            val state = loadTutorialCourseContent("lesson.md") {
                Result.success("$lesson\n<!-- tutorial-hint -->\nActual hint")
            } as TutorialCourseContentState.Content

            assertThat(state.markdown).isEqualTo(lesson)
            assertThat(state.hints).containsExactly("Actual hint")
        }
    }

    @Test
    fun loadCourseContent_shouldNotAcceptHintsWithoutALesson() = runTest {
        val state = loadTutorialCourseContent("lesson.md") {
            Result.success("<!-- tutorial-hint -->\nOnly a hint")
        }
        assertThat(state).isEqualTo(TutorialCourseContentState.Error)
    }

    @Test
    fun projectMarkersAreHiddenFromReaderButKeptInsideExampleCode() = runTest {
        val marker = "<!-- tutorial-project-file: main.cpp -->"
        val state = loadTutorialCourseContent("lesson.md") {
            Result.success("# Lesson\n$marker\n```cpp\nint main() {}\n```\n<!-- tutorial-hint -->\n```text\n$marker\n```")
        } as TutorialCourseContentState.Content
        assertThat(state.markdown).isEqualTo("# Lesson\n```cpp\nint main() {}\n```")
        assertThat(state.hints.single()).contains(marker)
    }

    @Test
    fun bundledManifestMarkerIsHiddenWhileItsJsonCodeIsReadable() = runTest {
        val state = loadTutorialCourseContent("vcpkg-practice.md") {
            Result.success("# Lesson\n<!-- tutorial-project-file: vcpkg.json -->\n```json\n{\"dependencies\":[\"fmt\"]}\n```")
        } as TutorialCourseContentState.Content
        assertThat(state.markdown).doesNotContain("<!-- tutorial-project-file:")
        assertThat(state.markdown).contains("{\"dependencies\":[\"fmt\"]}")
    }

    @Test
    fun bundledLessons_shouldHaveThreeLocalizedHintsWithMatchingReferenceCode() = runTest {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "feature/help/src/main/assets/tutorial-course").isDirectory }
        val directory = File(root, "feature/help/src/main/assets/tutorial-course")
        val lessons = directory.listFiles().orEmpty().filter { it.extension == "md" }
        val codeBlocks = Regex("(?ms)^```(?:cpp|cmake|json)\\r?\\n(.*?)^```[ \\t]*\\r?$")
        assertThat(lessons).hasSize(9)

        for (lesson in lessons) {
            val translated = File(directory, "en/${lesson.name}")
            val variants = listOf(lesson, translated).map { it.readText(Charsets.UTF_8) }
            for (markdown in variants) {
                val content = loadTutorialCourseContent(lesson.name) { Result.success(markdown) }
                    as TutorialCourseContentState.Content
                assertThat(content.hints).hasSize(3)
                assertThat(content.markdown).doesNotContain("<!-- tutorial-hint -->")
                assertThat(content.markdown).doesNotContain("<!-- tutorial-project-file:")
                assertThat(codeBlocks.findAll(content.hints.last()).toList()).isNotEmpty()
            }
            assertThat(codeBlocks.findAll(variants[0]).map { it.groupValues[1].trim() }.toList())
                .containsExactlyElementsIn(codeBlocks.findAll(variants[1]).map { it.groupValues[1].trim() }.toList())
                .inOrder()
        }
    }
}

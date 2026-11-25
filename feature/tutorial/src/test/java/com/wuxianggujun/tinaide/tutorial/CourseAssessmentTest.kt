package com.wuxianggujun.tinaide.tutorial

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseAssessmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun project(): File = temporary.newFolder().also {
        File(it, "CMakeLists.txt").writeText("add_executable(native_lab main.cpp)", Charsets.UTF_8)
        File(it, "main.cpp").writeText("int main() {}", Charsets.UTF_8)
    }

    @Test fun verifiesEveryInputAfterOneBuild() = runTest {
        var builds = 0
        val inputs = mutableListOf<String>()
        val checks = CourseAssessment.checks("cpp_basics")
        val result = CourseAssessment.assess(project(), "cpp_basics", { builds++ }) { input ->
            inputs += input
            val expected = checks.single { it.input == input }
            CourseCheckOutput(expected.exitCode, expected.expectedOutput + "\r\n", false)
        }
        assertThat(result.passed).isTrue()
        assertThat(builds).isEqualTo(1)
        assertThat(inputs).containsExactlyElementsIn(checks.map { it.input }).inOrder()
    }

    @Test fun rejectsStaleSourcesEvenWhenOutputMatches() = runTest {
        val project = project()
        val result = CourseAssessment.assess(project, "cpp_raii", {
            File(project, "main.cpp").appendText("// changed", Charsets.UTF_8)
        }) { CourseCheckOutput(0, "total=30", false) }
        assertThat(result.sourceChanged).isTrue()
        assertThat(result.passed).isFalse()
    }

    @Test fun ignoresGeneratedArtifactsButNotHeaders() = runTest {
        val project = project()
        val result = CourseAssessment.assess(project, "cmake_practice", {
            File(project, "build").mkdir()
            File(project, "build/cache.json").writeText("{}")
            File(project, "total.txt").writeText("30")
        }) { CourseCheckOutput(0, "total=30\n", false) }
        assertThat(result.passed).isTrue()
        val headerChanged = CourseAssessment.assess(project, "cmake_practice", {
            File(project, "pricing.h").writeText("int totalPrice(int,int);")
        }) { CourseCheckOutput(0, "total=30", false) }
        assertThat(headerChanged.passed).isFalse()
    }

    @Test fun rejectsTimeoutWrongExitAndSubstringMatches() {
        val expected = CourseCheck("", "total=30")
        listOf(CourseCheckOutput(0, "total=300", false), CourseCheckOutput(1, "total=30", false),
            CourseCheckOutput(0, "total=30", true)).forEach {
            assertThat(CourseAssessmentResult(listOf(expected to it), false).passed).isFalse()
        }
    }

    @Test fun cancellationDoesNotBecomeFailedOrPassedResult() = runTest {
        val failure = runCatching { CourseAssessment.assess(project(), "cpp_raii", { throw CancellationException() }) { error("Must not run") } }
        assertThat(failure.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(CourseAssessment.checks("graphics_sdl3")).isEmpty()
        assertThat(CourseAssessment.checks("final_review")).isEmpty()
    }
}

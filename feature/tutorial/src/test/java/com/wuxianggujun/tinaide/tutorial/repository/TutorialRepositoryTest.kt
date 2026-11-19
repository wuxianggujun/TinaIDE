package com.wuxianggujun.tinaide.tutorial.repository

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseAction
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class TutorialRepositoryTest {

    @Test
    fun resolveTutorialByLinkTarget_shouldNotShadowHelpArticles() {
        val application = RuntimeEnvironment.getApplication() as Application
        val repository = TutorialRepository(application, TutorialProgressStore(application))

        assertThat(repository.resolveTutorialByLinkTarget("build-project.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("help/vcpkg-guide.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("./getting-started.md#overview")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("create-project.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugins-settings.md"))
            .isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-manifest-compatibility.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-script-api.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-panels-events.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-lsp-troubleshooting.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-testing-recovery.md")).isNull()
        assertThat(repository.resolveTutorialByLinkTarget("https://example.com/build-project.md"))
            .isNull()
    }

    @Test
    fun builtInCatalog_shouldContainOneContinuousCourseAndNoHelpArticleCopies() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val repository = TutorialRepository(application, TutorialProgressStore(application))
        val tutorials = repository.getAllTutorialsWithProgress().first().map { it.tutorial }
        val course = tutorials.single { it.type == TutorialType.GUIDED }

        assertThat(tutorials.map { it.id }).containsNoDuplicates()
        assertThat(tutorials.map { it.id }).containsExactly("onboarding", "cpp_practice_course").inOrder()
        assertThat(tutorials.map { it.type }).containsExactly(TutorialType.INTERACTIVE, TutorialType.GUIDED)
        assertThat(course.courseSteps.map { it.id }).containsExactly(
            "ide_workflow",
            "cpp_basics",
            "cpp_functions",
            "cpp_collections",
            "cpp_raii",
            "cmake_practice",
            "vcpkg_guide",
            "graphics_sdl3",
            "ai_practice",
            "final_review",
        ).inOrder()
        assertThat(course.courseSteps.dropLast(1).all {
            it.contentLinkTarget != null || it.helpLinkTarget != null
        }).isTrue()
        assertThat(course.courseSteps[6].contentLinkTarget).isEqualTo("vcpkg-practice.md")
        assertThat(course.courseSteps[6].helpLinkTarget).isEqualTo("vcpkg-guide.md")
        assertThat(course.courseSteps[6].action).isEqualTo(TutorialCourseAction.OPEN_MARKET)
        assertThat(course.courseSteps[0].contentLinkTarget).isEqualTo("ide-workflow.md")
        assertThat(course.courseSteps.last().quiz?.correctAnswerIndex).isEqualTo(0)
    }

    @Test
    fun courseLinks_shouldResolveToTheMatchingChapterIncludingAnchors() {
        val application = RuntimeEnvironment.getApplication() as Application
        val repository = TutorialRepository(application, TutorialProgressStore(application))

        assertThat(repository.resolveCourseStepIndexByLinkTarget("help/cpp-basics.md#input")).isEqualTo(1)
        assertThat(repository.resolveCourseStepIndexByLinkTarget("vcpkg-guide.md")).isNull()
        assertThat(repository.resolveCourseStepIndexByLinkTarget("vcpkg-practice.md")).isEqualTo(6)
        assertThat(repository.resolveCourseStepIndexByLinkTarget("./graphics-sdl3.md")).isEqualTo(7)
        assertThat(repository.resolveCourseStepIndexByLinkTarget("https://example.com/cpp-basics.md")).isNull()
    }
}

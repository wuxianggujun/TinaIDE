package com.wuxianggujun.tinaide.tutorial.repository

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.data.HighlightShape
import com.wuxianggujun.tinaide.tutorial.data.StepAction
import com.wuxianggujun.tinaide.tutorial.data.TooltipPosition
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseQuiz
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseAction
import com.wuxianggujun.tinaide.tutorial.data.TutorialStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 教程仓库
 *
 * 管理教程数据的加载和访问
 */
class TutorialRepository(
    private val context: Context,
    private val progressStore: TutorialProgressStore
) {

    /**
     * 获取所有教程（带进度）
     */
    fun getAllTutorialsWithProgress(): Flow<List<TutorialWithProgress>> = progressStore.allProgressFlow.map { progressMap ->
        getBuiltInTutorials().map { tutorial ->
            TutorialWithProgress(
                tutorial = tutorial,
                progress = progressMap[tutorial.id]
            )
        }
    }

    /**
     * 获取特定教程
     */
    suspend fun getTutorial(tutorialId: String): Tutorial? = getBuiltInTutorials().find { it.id == tutorialId }

    /**
     * 根据 Markdown 链接目标解析教程。
     *
     * 优先用于教程内的站内文档跳转：
     * - `build-project.md`
     * - `./getting-started.md`
     * - `help/git-basics.md#section`
     */
    fun resolveCourseStepIndexByLinkTarget(linkTarget: String): Int? {
        val normalizedFileName = normalizeTutorialLinkTarget(linkTarget) ?: return null
        val course = getBuiltInTutorials().firstOrNull { it.id == CPP_PRACTICE_COURSE_ID } ?: return null
        return course.courseSteps.indexOfFirst { step ->
            step.contentLinkTarget
                ?.substringAfterLast('/')
                ?.equals(normalizedFileName, ignoreCase = true) == true
        }.takeIf { it >= 0 }
    }

    fun resolveTutorialByLinkTarget(linkTarget: String): Tutorial? =
        resolveCourseStepIndexByLinkTarget(linkTarget)?.let { index ->
            getBuiltInTutorials().firstOrNull { tutorial ->
                tutorial.id == CPP_PRACTICE_COURSE_ID && index in tutorial.courseSteps.indices
            }
        }

    /**
     * 获取特定教程（带进度）
     */
    suspend fun getTutorialWithProgress(tutorialId: String): TutorialWithProgress? {
        val tutorial = getTutorial(tutorialId) ?: return null
        val progress = progressStore.getProgress(tutorialId)
        return TutorialWithProgress(tutorial, progress)
    }

    /**
     * 获取新手引导教程
     */
    fun getOnboardingTutorial(): Tutorial = getBuiltInTutorials().first { it.id == ONBOARDING_TUTORIAL_ID }

    /**
     * 获取所有内置教程
     */
    private fun getBuiltInTutorials(): List<Tutorial> = listOf(
        createOnboardingTutorial(),
        createCppPracticeCourse(),
    )

    /**
     * 新手引导教程 - 首次启动时的交互式引导
     */
    private fun createOnboardingTutorial(): Tutorial = Tutorial(
        id = ONBOARDING_TUTORIAL_ID,
        titleRes = Strings.tutorial_onboarding_title,
        descriptionRes = Strings.tutorial_onboarding_desc,
        category = TutorialCategory.GETTING_STARTED,
        type = TutorialType.INTERACTIVE,
        estimatedMinutes = 3,
        order = 0,
        steps = listOf(
            TutorialStep(
                id = "onboarding_step_1",
                targetId = SpotlightTargets.BOTTOM_NAV_PROJECT,
                titleRes = Strings.tutorial_step_project_tab_title,
                descriptionRes = Strings.tutorial_step_project_tab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
            TutorialStep(
                id = "onboarding_step_2",
                targetId = SpotlightTargets.FAB_PROJECT_ACTIONS,
                titleRes = Strings.tutorial_step_project_fab_title,
                descriptionRes = Strings.tutorial_step_project_fab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.CIRCLE
            ),
            TutorialStep(
                id = "onboarding_step_3",
                targetId = SpotlightTargets.FAB_MENU_NEW_PROJECT,
                titleRes = Strings.tutorial_step_new_project_title,
                descriptionRes = Strings.tutorial_step_new_project_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
            TutorialStep(
                id = "onboarding_step_4",
                targetId = SpotlightTargets.BOTTOM_NAV_MARKET,
                titleRes = Strings.tutorial_step_market_tab_title,
                descriptionRes = Strings.tutorial_step_market_tab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
            TutorialStep(
                id = "onboarding_step_5",
                targetId = SpotlightTargets.BOTTOM_NAV_TUTORIAL,
                titleRes = Strings.tutorial_step_tutorial_tab_title,
                descriptionRes = Strings.tutorial_step_tutorial_tab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
            TutorialStep(
                id = "onboarding_step_6",
                targetId = SpotlightTargets.BOTTOM_NAV_PROFILE,
                titleRes = Strings.tutorial_step_profile_tab_title,
                descriptionRes = Strings.tutorial_step_profile_tab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            )
        )
    )

    /** 按项目生命周期组织 C++ 实践章节；正文和提示共用一份独占课程资产。 */
    private fun createCppPracticeCourse(): Tutorial = Tutorial(
        id = CPP_PRACTICE_COURSE_ID,
        titleRes = Strings.tutorial_complete_cpp_course_title,
        descriptionRes = Strings.tutorial_complete_cpp_course_desc,
        category = TutorialCategory.GETTING_STARTED,
        type = TutorialType.GUIDED,
        estimatedMinutes = 210,
        order = 1,
        courseSteps = listOf(
            TutorialCourseStep(
                id = "ide_workflow",
                titleRes = Strings.course_ide_title,
                descriptionRes = Strings.course_ide_desc,
                contentLinkTarget = "ide-workflow.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "cpp_basics",
                titleRes = Strings.course_cpp_basics_title,
                descriptionRes = Strings.course_cpp_basics_desc,
                contentLinkTarget = "cpp-basics.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "cpp_functions",
                titleRes = Strings.course_cpp_functions_title,
                descriptionRes = Strings.course_cpp_functions_desc,
                contentLinkTarget = "cpp-functions.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "cpp_collections",
                titleRes = Strings.course_cpp_collections_title,
                descriptionRes = Strings.course_cpp_collections_desc,
                contentLinkTarget = "cpp-collections.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "cpp_raii",
                titleRes = Strings.course_cpp_raii_title,
                descriptionRes = Strings.course_cpp_raii_desc,
                contentLinkTarget = "cpp-raii.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "cmake_practice",
                titleRes = Strings.course_cmake_title,
                descriptionRes = Strings.course_cmake_desc,
                contentLinkTarget = "cmake-practice.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "vcpkg_guide",
                titleRes = Strings.course_vcpkg_title,
                descriptionRes = Strings.tutorial_course_vcpkg_desc,
                contentLinkTarget = "vcpkg-practice.md",
                helpLinkTarget = "vcpkg-guide.md",
                action = TutorialCourseAction.OPEN_MARKET,
            ),
            TutorialCourseStep(
                id = "graphics_sdl3",
                titleRes = Strings.course_graphics_title,
                descriptionRes = Strings.course_graphics_desc,
                contentLinkTarget = "graphics-sdl3.md",
                action = TutorialCourseAction.OPEN_MARKET_PACKAGES,
            ),
            TutorialCourseStep(
                id = "ai_practice",
                titleRes = Strings.course_ai_title,
                descriptionRes = Strings.course_ai_desc,
                contentLinkTarget = "ai-practice.md",
                action = TutorialCourseAction.OPEN_PROJECTS,
            ),
            TutorialCourseStep(
                id = "final_review",
                titleRes = Strings.tutorial_course_step_check_title,
                descriptionRes = Strings.tutorial_course_step_check_desc,
                quiz = TutorialCourseQuiz(
                    questionRes = Strings.tutorial_course_quiz_question,
                    answerRes = listOf(
                        Strings.tutorial_course_quiz_answer_correct,
                        Strings.tutorial_course_quiz_answer_wrong_compiler,
                        Strings.tutorial_course_quiz_answer_wrong_dependencies,
                    ),
                    correctAnswerIndex = 0,
                    hintRes = listOf(
                        Strings.tutorial_course_quiz_hint_1,
                        Strings.tutorial_course_quiz_hint_2,
                        Strings.tutorial_course_quiz_hint_3,
                    ),
                ),
            ),
        ),
    )

    companion object {
        const val ONBOARDING_TUTORIAL_ID = "onboarding"
        const val CPP_PRACTICE_COURSE_ID = "cpp_practice_course"
    }

    private fun normalizeTutorialLinkTarget(linkTarget: String): String? {
        val trimmedTarget = linkTarget.trim()
        if (trimmedTarget.isBlank() || trimmedTarget.startsWith("#")) {
            return null
        }

        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmedTarget)) {
            return null
        }

        val path = trimmedTarget
            .substringBefore('#')
            .substringBefore('?')
            .removePrefix("./")
            .removePrefix("/")
            .removePrefix("help/")
            .substringAfterLast('/')

        return path.takeIf { it.endsWith(".md", ignoreCase = true) }
    }
}

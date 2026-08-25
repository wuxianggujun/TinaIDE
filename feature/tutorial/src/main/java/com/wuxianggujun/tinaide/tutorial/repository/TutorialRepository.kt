package com.wuxianggujun.tinaide.tutorial.repository

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.data.HighlightShape
import com.wuxianggujun.tinaide.tutorial.data.StepAction
import com.wuxianggujun.tinaide.tutorial.data.TooltipPosition
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
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
    fun resolveTutorialByLinkTarget(linkTarget: String): Tutorial? {
        val normalizedFileName = normalizeTutorialLinkTarget(linkTarget) ?: return null
        val tutorials = getBuiltInTutorials()

        return when {
            normalizedFileName.equals("create-project.md", ignoreCase = true) -> {
                tutorials.find { it.id == "create_project" }
            }

            else -> tutorials.find { tutorial ->
                tutorial.contentUrl
                    ?.substringAfterLast('/')
                    ?.equals(normalizedFileName, ignoreCase = true) == true
            }
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
        // 新手引导（交互式）
        createOnboardingTutorial(),
        // 创建第一个项目（交互式）
        createCreateProjectTutorial(),
        // 快速上手
        createGettingStartedTutorial(),
        // 编辑器基础
        createEditorBasicsTutorial(),
        // 构建项目
        createBuildProjectTutorial(),
        // Git 基础
        createGitBasicsTutorial(),
        // 插件开发快速开始
        createPluginQuickStartTutorial(),
        // 插件 manifest 与宿主兼容
        createPluginManifestCompatibilityTutorial(),
        // Script API 与权限
        createPluginScriptApiTutorial(),
        // 面板与事件
        createPluginPanelsEventsTutorial(),
        // LSP 插件排错
        createPluginLspTroubleshootingTutorial(),
        // 测试、自愈与发布前检查
        createPluginTestingRecoveryTutorial()
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

    /**
     * 创建第一个项目 - 交互式引导
     *
     * 目标：引导用户从「项目」页的 FAB 菜单进入新建项目向导。
     */
    private fun createCreateProjectTutorial(): Tutorial = Tutorial(
        id = "create_project",
        titleRes = Strings.help_doc_create_project_title,
        descriptionRes = Strings.help_doc_create_project_summary,
        category = TutorialCategory.GETTING_STARTED,
        type = TutorialType.INTERACTIVE,
        estimatedMinutes = 2,
        order = 1,
        steps = listOf(
            TutorialStep(
                id = "create_project_step_1",
                targetId = SpotlightTargets.BOTTOM_NAV_PROJECT,
                titleRes = Strings.tutorial_step_project_tab_title,
                descriptionRes = Strings.tutorial_step_project_tab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
            TutorialStep(
                id = "create_project_step_2",
                targetId = SpotlightTargets.FAB_PROJECT_ACTIONS,
                titleRes = Strings.tutorial_step_project_fab_title,
                descriptionRes = Strings.tutorial_step_project_fab_desc,
                position = TooltipPosition.TOP,
                action = StepAction.NONE,
                highlightShape = HighlightShape.CIRCLE
            ),
            TutorialStep(
                id = "create_project_step_3",
                targetId = SpotlightTargets.FAB_MENU_NEW_PROJECT,
                titleRes = Strings.tutorial_step_new_project_title,
                descriptionRes = Strings.tutorial_step_new_project_desc,
                position = TooltipPosition.TOP,
                action = StepAction.CLICK,
                highlightShape = HighlightShape.ROUNDED_RECT
            ),
        )
    )

    /**
     * 快速上手教程
     */
    private fun createGettingStartedTutorial(): Tutorial = Tutorial(
        id = "getting_started",
        titleRes = Strings.tutorial_getting_started_title,
        descriptionRes = Strings.tutorial_getting_started_desc,
        category = TutorialCategory.GETTING_STARTED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 10,
        order = 2,
        contentUrl = "help/getting-started.md"
    )

    /**
     * 编辑器基础教程
     */
    private fun createEditorBasicsTutorial(): Tutorial = Tutorial(
        id = "editor_basics",
        titleRes = Strings.tutorial_editor_basics_title,
        descriptionRes = Strings.tutorial_editor_basics_desc,
        category = TutorialCategory.EDITOR,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 15,
        order = 3,
        contentUrl = "help/editor-basics.md"
    )

    /**
     * 构建项目教程
     */
    private fun createBuildProjectTutorial(): Tutorial = Tutorial(
        id = "build_project",
        titleRes = Strings.tutorial_build_project_title,
        descriptionRes = Strings.tutorial_build_project_desc,
        category = TutorialCategory.BUILD,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 20,
        order = 4,
        contentUrl = "help/build-project.md"
    )

    /**
     * 插件开发快速开始
     */
    private fun createPluginQuickStartTutorial(): Tutorial = Tutorial(
        id = "plugin_quick_start",
        titleRes = Strings.help_doc_plugin_quick_start_title,
        descriptionRes = Strings.help_doc_plugin_quick_start_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 12,
        order = 0,
        contentUrl = "help/plugin-quick-start.md"
    )

    private fun createPluginManifestCompatibilityTutorial(): Tutorial = Tutorial(
        id = "plugin_manifest_compatibility",
        titleRes = Strings.help_doc_plugin_manifest_compatibility_title,
        descriptionRes = Strings.help_doc_plugin_manifest_compatibility_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 10,
        order = 1,
        contentUrl = "help/plugin-manifest-compatibility.md"
    )

    private fun createPluginScriptApiTutorial(): Tutorial = Tutorial(
        id = "plugin_script_api",
        titleRes = Strings.help_doc_plugin_script_api_title,
        descriptionRes = Strings.help_doc_plugin_script_api_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 15,
        order = 2,
        contentUrl = "help/plugin-script-api.md"
    )

    private fun createPluginPanelsEventsTutorial(): Tutorial = Tutorial(
        id = "plugin_panels_events",
        titleRes = Strings.help_doc_plugin_panels_events_title,
        descriptionRes = Strings.help_doc_plugin_panels_events_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 12,
        order = 3,
        contentUrl = "help/plugin-panels-events.md"
    )

    private fun createPluginLspTroubleshootingTutorial(): Tutorial = Tutorial(
        id = "plugin_lsp_troubleshooting",
        titleRes = Strings.help_doc_plugin_lsp_troubleshooting_title,
        descriptionRes = Strings.help_doc_plugin_lsp_troubleshooting_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 15,
        order = 4,
        contentUrl = "help/plugin-lsp-troubleshooting.md"
    )

    private fun createPluginTestingRecoveryTutorial(): Tutorial = Tutorial(
        id = "plugin_testing_recovery",
        titleRes = Strings.help_doc_plugin_testing_recovery_title,
        descriptionRes = Strings.help_doc_plugin_testing_recovery_summary,
        category = TutorialCategory.ADVANCED,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 12,
        order = 5,
        contentUrl = "help/plugin-testing-recovery.md"
    )

    /**
     * Git 基础教程
     */
    private fun createGitBasicsTutorial(): Tutorial = Tutorial(
        id = "git_basics",
        titleRes = Strings.tutorial_git_basics_title,
        descriptionRes = Strings.tutorial_git_basics_desc,
        category = TutorialCategory.GIT,
        type = TutorialType.ARTICLE,
        estimatedMinutes = 18,
        order = 5,
        contentUrl = "help/git-basics.md"
    )

    companion object {
        const val ONBOARDING_TUTORIAL_ID = "onboarding"
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

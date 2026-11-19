package com.wuxianggujun.tinaide.tutorial.data

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 教程数据模型
 */
data class Tutorial(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val category: TutorialCategory,
    val type: TutorialType,
    val estimatedMinutes: Int,
    val order: Int,
    val steps: List<TutorialStep> = emptyList(),
    /** 连续实操课程章节；与 Spotlight 的 UI 引导步骤分开建模。 */
    val courseSteps: List<TutorialCourseStep> = emptyList(),
)

/**
 * 教程类型
 */
enum class TutorialType {
    /** 交互式引导 - 遮罩高亮步骤式教程 */
    INTERACTIVE,

    /** 引导用户在 TinaIDE 中完成连续实操的课程 */
    GUIDED,
}

/**
 * 教程分类
 */
enum class TutorialCategory(
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val order: Int
) {
    GETTING_STARTED(
        Strings.tutorial_cat_getting_started,
        Strings.tutorial_cat_getting_started_desc,
        0
    ),
    EDITOR(
        Strings.tutorial_cat_editor,
        Strings.tutorial_cat_editor_desc,
        1
    ),
    BUILD(
        Strings.tutorial_cat_build,
        Strings.tutorial_cat_build_desc,
        2
    ),
    GIT(
        Strings.tutorial_cat_git,
        Strings.tutorial_cat_git_desc,
        3
    ),
    ADVANCED(
        Strings.tutorial_cat_advanced,
        Strings.tutorial_cat_advanced_desc,
        4
    );

    companion object {
        fun fromString(value: String): TutorialCategory? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 交互式教程步骤
 */
data class TutorialStep(
    val id: String,
    /** 目标组件的语义 ID，如 "run_button", "project_tab" */
    val targetId: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val position: TooltipPosition = TooltipPosition.AUTO,
    val action: StepAction = StepAction.NONE,
    val highlightShape: HighlightShape = HighlightShape.ROUNDED_RECT,
    /** 高亮区域的内边距 */
    val highlightPadding: Int = 8,
)

/**
 * 连续实操课程中的一个阶段。
 *
 * 正文只描述当前要完成的任务；详细界面说明通过 helpLinkTarget 跳转到帮助中心，
 * 避免在教程和帮助里各维护一份长文。
 */
data class TutorialCourseStep(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    /** 本课程独占章节内容；不在帮助目录重复登记。 */
    val contentLinkTarget: String? = null,
    /** 需要时跳转到一篇帮助参考文档，不复制其正文。 */
    val helpLinkTarget: String? = null,
    val action: TutorialCourseAction? = null,
    val quiz: TutorialCourseQuiz? = null,
)

/** 课程可以直接带用户进入实际完成任务的 TinaIDE 页面。 */
enum class TutorialCourseAction {
    OPEN_PROJECTS,
    OPEN_MARKET,
    OPEN_MARKET_PACKAGES,
}

/** 一道单选验收题，作为课程完成前的知识检查。 */
data class TutorialCourseQuiz(
    @param:StringRes @get:StringRes val questionRes: Int,
    @param:StringRes @get:StringRes val answerRes: List<Int>,
    val correctAnswerIndex: Int,
)

/**
 * 步骤需要用户执行的操作
 */
enum class StepAction {
    /** 点击目标 */
    CLICK,

    /** 长按 */
    LONG_PRESS,

    /** 滑动 */
    SWIPE,

    /** 输入文本 */
    INPUT,

    /** 仅查看，无需操作 */
    NONE
}

/**
 * 提示框位置
 */
enum class TooltipPosition {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,

    /** 自动计算最佳位置 */
    AUTO
}

/**
 * 高亮形状
 */
enum class HighlightShape {
    RECTANGLE,
    CIRCLE,
    ROUNDED_RECT
}

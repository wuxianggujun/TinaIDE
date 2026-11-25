package com.wuxianggujun.tinaide.tutorial.data

import kotlinx.serialization.Serializable

/**
 * 用户教程进度
 */
@Serializable
data class TutorialProgress(
    val tutorialId: String,
    val status: ProgressStatus,
    /** 当前交互步骤或课程章节索引 */
    val currentStepIndex: Int = 0,
    /** 完成时间戳 */
    val completedAt: Long? = null,
    /** 最后访问时间戳 */
    val lastAccessedAt: Long = System.currentTimeMillis(),
    /** 已确认完成的课程章节 ID；旧进度缺少该字段时默认为空。 */
    val completedStepIds: Set<String> = emptySet(),
)

/**
 * 进度状态
 */
@Serializable
enum class ProgressStatus {
    /** 未开始 */
    NOT_STARTED,

    /** 进行中 */
    IN_PROGRESS,

    /** 已完成 */
    COMPLETED
}

/**
 * 教程与进度的组合数据
 */
data class TutorialWithProgress(
    val tutorial: Tutorial,
    val progress: TutorialProgress?
) {
    val status: ProgressStatus
        get() = progress?.status ?: ProgressStatus.NOT_STARTED

    val progressPercent: Int
        get() {
            if (status == ProgressStatus.COMPLETED) return 100
            val stepCount = tutorial.steps.size.takeIf { it > 0 } ?: tutorial.courseSteps.size
            if (stepCount == 0) return 0
            val completedStepCount = if (tutorial.type == TutorialType.GUIDED) {
                val completedIds = progress?.completedStepIds.orEmpty()
                tutorial.courseSteps
                    .asSequence()
                    .takeWhile { it.id in completedIds }
                    .count()
            } else {
                (progress?.currentStepIndex ?: 0).coerceIn(0, stepCount - 1) + 1
            }
            return when (status) {
                ProgressStatus.NOT_STARTED -> 0
                ProgressStatus.IN_PROGRESS -> (completedStepCount * 100) / stepCount
                ProgressStatus.COMPLETED -> 100
            }
        }

    val isCompleted: Boolean
        get() = status == ProgressStatus.COMPLETED

    val isInProgress: Boolean
        get() = status == ProgressStatus.IN_PROGRESS
}

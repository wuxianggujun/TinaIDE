package com.wuxianggujun.tinaide.tutorial

import kotlinx.coroutines.CancellationException

sealed interface TutorialCourseContentState {
    data object Loading : TutorialCourseContentState
    data class Content(val markdown: String) : TutorialCourseContentState
    data object Error : TutorialCourseContentState
}

/** The host provides asset access; course loading and cancellation belong to the tutorial feature. */
suspend fun loadTutorialCourseContent(
    linkTarget: String?,
    loadContent: suspend (String) -> Result<String>,
): TutorialCourseContentState {
    if (linkTarget.isNullOrBlank()) return TutorialCourseContentState.Error
    return try {
        val content = loadContent(linkTarget).getOrThrow()
        if (content.isBlank()) TutorialCourseContentState.Error else TutorialCourseContentState.Content(content)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        TutorialCourseContentState.Error
    }
}

package com.wuxianggujun.tinaide.tutorial

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import org.junit.Test

class TutorialCatalogUiStateTest {

    @Test
    fun defaultState_shouldRepresentLoadingInsteadOfEmptyCatalog() {
        val state = TutorialCatalogUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.tutorials).isEmpty()
        assertThat(state.tutorialsByCategory).isEmpty()
    }

    @Test
    fun loadedState_shouldSortCategoriesAndTutorials() {
        val state = buildTutorialCatalogUiState(
            listOf(
                tutorial(id = "advanced-2", category = TutorialCategory.ADVANCED, order = 2),
                tutorial(id = "start-1", category = TutorialCategory.GETTING_STARTED, order = 1),
                tutorial(id = "advanced-1", category = TutorialCategory.ADVANCED, order = 1),
            )
        )

        assertThat(state.isLoading).isFalse()
        assertThat(state.tutorials).hasSize(3)
        assertThat(state.tutorialsByCategory.keys).containsExactly(
            TutorialCategory.GETTING_STARTED,
            TutorialCategory.ADVANCED,
        ).inOrder()
        val advancedTutorialIds = state.tutorialsByCategory
            .getValue(TutorialCategory.ADVANCED)
            .map { it.tutorial.id }
        assertThat(advancedTutorialIds)
            .containsExactly("advanced-1", "advanced-2")
            .inOrder()
    }

    private fun tutorial(
        id: String,
        category: TutorialCategory,
        order: Int,
    ): TutorialWithProgress = TutorialWithProgress(
        tutorial = Tutorial(
            id = id,
            titleRes = Strings.tutorial_title,
            descriptionRes = Strings.tutorial_empty,
            category = category,
            type = TutorialType.ARTICLE,
            estimatedMinutes = 1,
            order = order,
            contentUrl = "help/$id.md",
        ),
        progress = null,
    )
}

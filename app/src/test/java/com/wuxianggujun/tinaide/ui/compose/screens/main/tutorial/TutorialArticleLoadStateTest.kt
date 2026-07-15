package com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TutorialArticleLoadStateTest {

    @Test
    fun loadTutorialArticle_shouldRejectMissingOrBlankContent() = runTest {
        var loadCount = 0

        val missingUrlState = loadTutorialArticle(null) {
            loadCount += 1
            Result.success("unused")
        }
        val blankContentState = loadTutorialArticle("help/blank.md") {
            loadCount += 1
            Result.success("   \n")
        }

        assertThat(missingUrlState).isEqualTo(TutorialArticleLoadState.Error)
        assertThat(blankContentState).isEqualTo(TutorialArticleLoadState.Error)
        assertThat(loadCount).isEqualTo(1)
    }

    @Test
    fun loadTutorialArticle_shouldExposeLoadedMarkdown() = runTest {
        val state = loadTutorialArticle("help/plugin-quick-start.md") {
            Result.success("# Plugin course")
        }

        assertThat(state).isEqualTo(
            TutorialArticleLoadState.Content("# Plugin course")
        )
    }

    @Test
    fun loadTutorialArticle_shouldConvertResultFailureOrThrownLoaderToError() = runTest {
        val resultFailure = loadTutorialArticle("help/failure.md") {
            Result.failure(IllegalStateException("asset failure"))
        }
        val thrownFailure = loadTutorialArticle("help/thrown.md") {
            throw IllegalStateException("unexpected loader failure")
        }

        assertThat(resultFailure).isEqualTo(TutorialArticleLoadState.Error)
        assertThat(thrownFailure).isEqualTo(TutorialArticleLoadState.Error)
    }

    @Test
    fun loadTutorialArticle_shouldPropagateCancellation() = runTest {
        val cancellation = CancellationException("article loading cancelled")
        var propagatedCancellation: CancellationException? = null

        try {
            loadTutorialArticle("help/cancelled.md") {
                throw cancellation
            }
        } catch (caught: CancellationException) {
            propagatedCancellation = caught
        }

        assertThat(propagatedCancellation).isSameInstanceAs(cancellation)
    }
}

package com.wuxianggujun.tinaide.tutorial

import com.google.common.truth.Truth.assertThat
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
}

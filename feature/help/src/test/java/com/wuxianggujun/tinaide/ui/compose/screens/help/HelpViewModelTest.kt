package com.wuxianggujun.tinaide.ui.compose.screens.help

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.help.HelpCategory
import com.wuxianggujun.tinaide.core.help.HelpDocument
import com.wuxianggujun.tinaide.core.help.HelpRepository
import com.wuxianggujun.tinaide.core.help.HelpSearchResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class HelpViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedReload_shouldClearStaleContent_andRetryShouldRecover() = runTest {
        val document = testDocument()
        val repository = mockRepository(document)
        var loadCount = 0
        coEvery { repository.loadDocumentContent(document) } answers {
            loadCount += 1
            when (loadCount) {
                1 -> Result.success("# First content")
                2 -> Result.failure(IllegalStateException("temporary failure"))
                else -> Result.success("# Recovered content")
            }
        }

        val viewModel = HelpViewModel(
            RuntimeEnvironment.getApplication(),
            repository,
        )
        advanceUntilIdle()

        viewModel.selectDocument(document)
        advanceUntilIdle()
        assertThat(viewModel.documentContent.value).isEqualTo("# First content")

        viewModel.selectDocument(document)
        advanceUntilIdle()
        assertThat(viewModel.documentContent.value).isNull()
        assertThat(viewModel.uiState.value.error).isEqualTo("temporary failure")
        assertThat(viewModel.uiState.value.isLoadingContent).isFalse()

        viewModel.retrySelectedDocument()
        advanceUntilIdle()
        assertThat(viewModel.documentContent.value).isEqualTo("# Recovered content")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun staleSearchResult_shouldNotOverwriteNewerQuery() = runTest {
        val document = testDocument()
        val repository = mockRepository(document)
        val oldSearch = CompletableDeferred<List<HelpSearchResult>>()
        val oldResult = HelpSearchResult(document, matchedContent = "old")
        val newResult = HelpSearchResult(document, matchedContent = "new")
        coEvery { repository.search("old") } coAnswers { oldSearch.await() }
        coEvery { repository.search("new") } returns listOf(newResult)

        val viewModel = HelpViewModel(RuntimeEnvironment.getApplication(), repository)
        advanceUntilIdle()

        viewModel.search("old")
        viewModel.search("new")
        advanceUntilIdle()
        assertThat(viewModel.searchResults.value).containsExactly(newResult)

        oldSearch.complete(listOf(oldResult))
        advanceUntilIdle()
        assertThat(viewModel.searchResults.value).containsExactly(newResult)
    }

    @Test
    fun clearSearch_shouldIgnorePendingResult() = runTest {
        val document = testDocument()
        val repository = mockRepository(document)
        val pendingSearch = CompletableDeferred<List<HelpSearchResult>>()
        coEvery { repository.search("plugin") } coAnswers { pendingSearch.await() }

        val viewModel = HelpViewModel(RuntimeEnvironment.getApplication(), repository)
        advanceUntilIdle()

        viewModel.search("plugin")
        viewModel.clearSearch()
        pendingSearch.complete(listOf(HelpSearchResult(document)))
        advanceUntilIdle()

        assertThat(viewModel.searchResults.value).isEmpty()
        assertThat(viewModel.uiState.value.searchQuery).isEmpty()
        assertThat(viewModel.uiState.value.isSearching).isFalse()
    }

    private fun mockRepository(document: HelpDocument): HelpRepository = mockk {
        every { getAllDocuments() } returns listOf(document)
        every { getAllCategories() } returns listOf(document.category)
        every { getDocumentsByCategory(document.category) } returns listOf(document)
        coEvery { preloadAllContent() } returns Unit
    }

    private fun testDocument() = HelpDocument(
        id = "help-test",
        title = "Help test",
        category = HelpCategory.GETTING_STARTED,
        fileName = "help-test.md",
    )
}

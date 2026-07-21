package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
    application = Application::class
)
class LspNavigationDelegateTest {

    private lateinit var context: Application
    private lateinit var editorContainerState: EditorContainerState

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        editorContainerState = mockk(relaxed = true)
    }

    @Test
    fun `handleNavigationRequest should ignore calls without active cursor`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns null
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var started = 0

        delegate.onNavigationStarted = { started++ }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "definition",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(started).isEqualTo(0)
        coVerify(exactly = 0) { editorContainerState.gotoDefinition(any(), any(), any()) }
    }

    @Test
    fun `handleNavigationRequest should navigate immediately for single definition result`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 10, column = 5)
        val targetFile = File(context.cacheDir, "DefinitionTarget.kt")
        val target = locationItem(targetFile, line = 4, column = 2)
        coEvery { editorContainerState.gotoDefinition("tab-1", 10, 5) } returns listOf(target)
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var started = 0
        var dismissed = 0
        var emittedResults: List<LocationItem>? = null

        delegate.onNavigationStarted = { started++ }
        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onNavigationResults = { _, results -> emittedResults = results }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "definition",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(started).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
        assertThat(emittedResults).isNull()
        verify(exactly = 1) {
            editorContainerState.openFileAndGoToPosition(
                match { it.absolutePath == targetFile.absolutePath },
                4,
                2
            )
        }
    }

    @Test
    fun `handleNavigationRequest should show embedded peek panel without navigating`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 10, column = 5)
        val targetFile = File(context.cacheDir, "PeekTarget.kt")
        val target = locationItem(targetFile, line = 4, column = 2)
        coEvery { editorContainerState.gotoDefinition("tab-1", 10, 5) } returns listOf(target)
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var started = 0
        var dismissed = 0
        var emittedResults: List<LocationItem>? = null

        delegate.onNavigationStarted = { started++ }
        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onNavigationResults = { _, results -> emittedResults = results }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "peekDefinition",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(started).isEqualTo(0)
        assertThat(dismissed).isEqualTo(1)
        assertThat(emittedResults).isNull()
        verify(exactly = 1) { editorContainerState.showPeekDefinitionLoading("tab-1", any()) }
        verify(exactly = 1) { editorContainerState.showPeekDefinitionResults("tab-1", any(), listOf(target)) }
        verify(exactly = 0) { editorContainerState.openFileAndGoToPosition(any(), any(), any()) }
    }

    @Test
    fun `handleNavigationRequest should surface call hierarchy incoming calls without auto navigation`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 6, column = 3)
        val caller = locationItem(File(context.cacheDir, "Caller.kt"), line = 14, column = 8)
        coEvery { editorContainerState.callHierarchyIncomingCalls("tab-1", 6, 3) } returns listOf(caller)
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var title: String? = null
        var emittedResults: List<LocationItem>? = null

        delegate.onNavigationResults = { emittedTitle, results ->
            title = emittedTitle
            emittedResults = results
        }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "callHierarchyIncoming",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(title).isEqualTo(
            context.getString(
                com.wuxianggujun.tinaide.core.i18n.R.string.lsp_location_dialog_title_call_hierarchy_incoming,
                1
            )
        )
        assertThat(emittedResults).isNotNull()
        assertThat(checkNotNull(emittedResults)).containsExactly(caller)
        verify(exactly = 0) { editorContainerState.openFileAndGoToPosition(any(), any(), any()) }
    }

    @Test
    fun `handleNavigationRequest should show call hierarchy empty toast without dialog`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 6, column = 3)
        coEvery { editorContainerState.callHierarchyIncomingCalls("tab-1", 6, 3) } returns emptyList()
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var dismissed = 0
        var infoMessage: String? = null
        var emittedResults: List<LocationItem>? = null

        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onToastInfo = { infoMessage = it }
        delegate.onNavigationResults = { _, results -> emittedResults = results }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "callHierarchyIncoming",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(infoMessage).isEqualTo(Strings.lsp_call_hierarchy_no_incoming_calls.strOr(context))
        assertThat(emittedResults).isNull()
    }

    @Test
    fun `handleNavigationRequest should surface call hierarchy failures as localized error toast`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 6, column = 3)
        coEvery { editorContainerState.callHierarchyIncomingCalls("tab-1", 6, 3) } throws IllegalStateException("boom")
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var dismissed = 0
        var errorMessage: String? = null

        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onToastError = { errorMessage = it }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "callHierarchyIncoming",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(errorMessage).isEqualTo(Strings.lsp_error_call_hierarchy_failed.strOr(context))
    }

    @Test
    fun `handleNavigationRequest should surface multi reference results with dialog title`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 3, column = 1)
        val first = locationItem(File(context.cacheDir, "RefOne.kt"), line = 8, column = 0)
        val second = locationItem(File(context.cacheDir, "RefTwo.kt"), line = 12, column = 4)
        coEvery { editorContainerState.findReferences("tab-1", 3, 1) } returns listOf(first, second)
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var title: String? = null
        var emittedResults: List<LocationItem>? = null

        delegate.onNavigationResults = { emittedTitle, results ->
            title = emittedTitle
            emittedResults = results
        }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "references",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(title).isEqualTo(
            context.getString(
                com.wuxianggujun.tinaide.core.i18n.R.string.lsp_location_dialog_title_references,
                2
            )
        )
        assertThat(emittedResults).isNotNull()
        assertThat(checkNotNull(emittedResults)).containsExactly(first, second).inOrder()
        verify(exactly = 0) { editorContainerState.openFileAndGoToPosition(any(), any(), any()) }
    }

    @Test
    fun `handleNavigationRequest should reject directory header switch targets`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 1, column = 1)
        val directory = File(context.cacheDir, "header-switch-dir").apply { mkdirs() }
        coEvery { editorContainerState.switchSourceHeader("tab-1") } returns directory.absolutePath
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var dismissed = 0
        var infoMessage: String? = null

        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onToastInfo = { infoMessage = it }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "switchHeaderSource",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(infoMessage).isEqualTo(Strings.lsp_no_results.strOr(context))
        verify(exactly = 0) { editorContainerState.openFile(any()) }
    }

    @Test
    fun `handleNavigationRequest should surface navigation failures as localized error toast`() = runTest {
        every { editorContainerState.getCursorPositionInActiveTab() } returns
            CursorSnapshot(line = 7, column = 9)
        coEvery { editorContainerState.gotoImplementation("tab-1", 7, 9) } throws IllegalStateException("boom")
        val delegate = LspNavigationDelegate(context = context, scope = this)
        var dismissed = 0
        var errorMessage: String? = null

        delegate.onNavigationDismissed = { dismissed++ }
        delegate.onToastError = { errorMessage = it }

        delegate.handleNavigationRequest(
            tabId = "tab-1",
            navigationType = "implementation",
            editorContainerState = editorContainerState
        )
        advanceUntilIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(errorMessage).isEqualTo(Strings.lsp_error_navigation_failed.strOr(context))
    }

    private fun locationItem(file: File, line: Int, column: Int): LocationItem = LocationItem(
        uri = file.toURI().toString(),
        filePath = file.absolutePath,
        fileName = file.name,
        line = line,
        column = column,
        endLine = line,
        endColumn = column + 1
    )
}

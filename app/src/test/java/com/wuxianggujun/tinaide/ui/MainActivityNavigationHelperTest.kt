package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import io.mockk.coVerify
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
class MainActivityNavigationHelperTest {

    private lateinit var context: Application
    private lateinit var editorContainerState: EditorContainerState
    private lateinit var bottomPanelController: BottomPanelController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        editorContainerState = mockk(relaxed = true)
        bottomPanelController = mockk(relaxed = true)
    }

    @Test
    fun `navigateToSearchResult should report missing files without opening editor`() {
        val missingFile = File(context.cacheDir, "missing-search-target.kt")
        var missingReported = false

        MainActivityNavigationHelper.navigateToSearchResult(
            filePath = missingFile.absolutePath,
            lineNumber = 15,
            editorContainerState = editorContainerState,
            onFileNotExist = { missingReported = true }
        )

        assertThat(missingReported).isTrue()
        verify(exactly = 0) { editorContainerState.openFileAndGoToPosition(any(), any(), any()) }
    }

    @Test
    fun `navigateToSearchResult should reject directory targets`() {
        val directory = File(context.cacheDir, "search-target-dir").apply { mkdirs() }
        var missingReported = false

        MainActivityNavigationHelper.navigateToSearchResult(
            filePath = directory.absolutePath,
            lineNumber = 3,
            editorContainerState = editorContainerState,
            onFileNotExist = { missingReported = true }
        )

        assertThat(missingReported).isTrue()
        verify(exactly = 0) { editorContainerState.openFileAndGoToPosition(any(), any(), any()) }
    }

    @Test
    fun `navigateToSearchResult should translate lines to zero based positions`() {
        val file = File(context.cacheDir, "SearchResult.kt").apply {
            writeText("fun searchResult() = Unit")
        }

        MainActivityNavigationHelper.navigateToSearchResult(
            filePath = file.absolutePath,
            lineNumber = 9,
            editorContainerState = editorContainerState,
            onFileNotExist = {}
        )

        verify(exactly = 1) {
            editorContainerState.openFileAndGoToPosition(
                match { it.absolutePath == file.absolutePath },
                8,
                0
            )
        }
    }

    @Test
    fun `navigateToLocation should reuse unified file navigation entry`() {
        val file = File(context.cacheDir, "LocationTarget.kt")
        val location = LocationItem(
            uri = file.toURI().toString(),
            filePath = file.absolutePath,
            fileName = file.name,
            line = 5,
            column = 7,
            endLine = 5,
            endColumn = 8
        )

        MainActivityNavigationHelper.navigateToLocation(
            location = location,
            editorContainerState = editorContainerState
        )

        verify(exactly = 1) {
            editorContainerState.openFileAndGoToPosition(
                match { it.absolutePath == file.absolutePath },
                5,
                7
            )
        }
    }

    @Test
    fun `navigateToDiagnostic should collapse bottom panel after opening diagnostic target`() = runTest {
        val file = File(context.cacheDir, "DiagnosticTarget.kt").apply {
            writeText("fun diagnosticTarget() = Unit")
        }
        val diagnostic = Diagnostic(
            fileUri = file.absolutePath,
            fileName = file.name,
            line = 6,
            column = 2,
            message = "boom",
            severity = Diagnostic.Severity.ERROR
        )

        MainActivityNavigationHelper.navigateToDiagnostic(
            diagnostic = diagnostic,
            editorContainerState = editorContainerState,
            bottomPanelController = bottomPanelController,
            scope = this
        )
        advanceUntilIdle()

        verify(exactly = 1) {
            editorContainerState.openFileAndGoToPosition(
                match { it.name == file.name },
                6,
                2
            )
        }
        coVerify(exactly = 1) { bottomPanelController.collapseImmediate() }
    }

    @Test
    fun `resolveDiagnosticFile should decode file URI for exact code action target`() {
        val diagnostic = Diagnostic(
            fileUri = "file:///data/user/0/com.example/cache/Diagnostic%20Target.cpp",
            fileName = "Diagnostic Target.cpp",
            line = 2,
            column = 4,
            endLine = 2,
            endColumn = 10,
            message = "fix available",
            severity = Diagnostic.Severity.WARNING,
        )

        val resolved = MainActivityNavigationHelper.resolveDiagnosticFile(diagnostic)

        assertThat(resolved.path.replace('\\', '/'))
            .endsWith("/data/user/0/com.example/cache/Diagnostic Target.cpp")
    }
}

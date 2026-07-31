package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.OutputMode
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
@OptIn(ExperimentalCoroutinesApi::class)
class CompileActionsHelperTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Application
    private lateinit var helper: CompileActionsHelper
    private lateinit var projectDir: File
    private lateinit var project: Project

    private lateinit var commandRunner: CompileActionsHelper.CommandRunner
    private lateinit var uiBridge: CompileActionsHelper.UiBridge
    private lateinit var projectContext: IProjectContext
    private lateinit var editorManager: IEditorManager
    private lateinit var processController: CompileActionsHelper.ProcessController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        commandRunner = mockk(relaxed = true)
        uiBridge = mockk(relaxed = true)
        projectContext = mockk()
        editorManager = mockk()
        processController = mockk(relaxed = true)

        projectDir = Files.createTempDirectory("compile-actions-helper").toFile()
        project = Project(
            id = "project-id",
            name = "imgui",
            rootPath = projectDir.absolutePath,
            workspaceRootPath = File(projectDir, "workspace").absolutePath,
            files = emptyList(),
            buildDirPath = File(projectDir, "build").absolutePath
        )

        every { projectContext.getCurrentProject() } returns project
        every { editorManager.getOpenTabs() } returns emptyList()
        every { editorManager.getActiveTabId() } returns null
        coEvery { editorManager.saveAll(any()) } returns emptyList()

        helper = CompileActionsHelper(
            context = context,
            commandRunner = commandRunner,
            uiBridge = uiBridge,
            projectContext = projectContext,
            editorManager = editorManager,
            processController = processController
        )
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `openCMakeArtifactsDirectory emits info toast when artifacts dir missing`() = runTest {
        val events = captureUiEvents {
            helper.openCMakeArtifactsDirectory()
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_cmake_artifacts_dir_missing.strOr(context),
                CompileActionsHelper.ToastType.INFO
            )
        )
    }

    @Test
    fun `openCMakeArtifactsDirectory reveals artifacts dir when it exists`() = runTest {
        val artifactsDir = ProjectDirStructure.getArtifactsDir(project.rootPath).apply {
            mkdirs()
        }

        val events = captureUiEvents {
            helper.openCMakeArtifactsDirectory()
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.RevealInProjectTree(artifactsDir, selectTarget = false),
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_cmake_artifacts_dir_revealed.strOr(context),
                CompileActionsHelper.ToastType.SUCCESS
            )
        ).inOrder()
    }

    @Test
    fun `runProject aborts when save fails`() = runTest {
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Failure("disk full")
        )

        val events = captureUiEvents {
            helper.runProject()
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_save_failed_cancelled.strOr(
                    context,
                    Strings.action_run.strOr(context),
                    "disk full"
                ),
                CompileActionsHelper.ToastType.ERROR
            )
        )
        coVerify(exactly = 1) { editorManager.saveAll(SaveReason.MANUAL) }
        verify(exactly = 0) { processController.reset() }
        verify(exactly = 0) { commandRunner.compile(any()) }
        verify(exactly = 0) { uiBridge.setCompiling(true) }
    }

    @Test
    fun `buildProject prepares ui and delegates compile after save succeeds`() = runTest {
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Success(timestamp = 1L, reason = SaveReason.MANUAL)
        )
        val recordingRunner = RecordingCommandRunner()
        helper = CompileActionsHelper(
            context = context,
            commandRunner = recordingRunner,
            uiBridge = uiBridge,
            projectContext = projectContext,
            editorManager = editorManager,
            processController = processController
        )

        val events = captureUiEvents {
            helper.buildProject()
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_auto_saved.strOr(context, 1),
                CompileActionsHelper.ToastType.SUCCESS
            ),
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compiling.strOr(context),
                CompileActionsHelper.ToastType.INFO
            )
        ).inOrder()
        verify(exactly = 1) { processController.reset() }
        verify(exactly = 1) { uiBridge.clearBuildLogs() }
        verify(atLeast = 1) { uiBridge.showBuildLog() }
        verify(exactly = 1) { uiBridge.setCompiling(true) }
        coVerify(exactly = 1) { uiBridge.expandBottomPanel() }
        assertThat(recordingRunner.compileCalls).hasSize(1)
        assertThat(recordingRunner.compileCalls.single().mode).isEqualTo(CompileProjectUseCase.ExecutionMode.BUILD)
        assertThat(recordingRunner.compileCalls.single().action).isEqualTo(CompileProjectUseCase.Action.BUILD)
        assertThat(recordingRunner.compileCalls.single().resolveRequest(OutputMode.TERMINAL))
            .isEqualTo(CompileRequest.buildOnly())
    }

    @Test
    fun `rebuildAndRunProject prepares ui and delegates force rebuild run after save succeeds`() = runTest {
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Success(timestamp = 1L, reason = SaveReason.MANUAL)
        )
        val recordingRunner = RecordingCommandRunner()
        helper = CompileActionsHelper(
            context = context,
            commandRunner = recordingRunner,
            uiBridge = uiBridge,
            projectContext = projectContext,
            editorManager = editorManager,
            processController = processController
        )

        val events = captureUiEvents {
            helper.rebuildAndRunProject()
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_auto_saved.strOr(context, 1),
                CompileActionsHelper.ToastType.SUCCESS
            ),
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compiling.strOr(context),
                CompileActionsHelper.ToastType.INFO
            )
        ).inOrder()
        verify(exactly = 1) { processController.reset() }
        verify(exactly = 1) { uiBridge.clearBuildLogs() }
        verify(atLeast = 1) { uiBridge.showBuildLog() }
        verify(exactly = 1) { uiBridge.setCompiling(true) }
        coVerify(exactly = 1) { uiBridge.expandBottomPanel() }
        assertThat(recordingRunner.compileCalls).hasSize(1)
        assertThat(recordingRunner.compileCalls.single().mode).isEqualTo(CompileProjectUseCase.ExecutionMode.RUN)
        assertThat(recordingRunner.compileCalls.single().action).isEqualTo(CompileProjectUseCase.Action.REBUILD_RUN)
        assertThat(recordingRunner.compileCalls.single().resolveRequest(OutputMode.TERMINAL))
            .isEqualTo(CompileRequest.forceRun(OutputMode.TERMINAL))
    }

    @Test
    fun `handleProcessStateChanged keeps current output tab when run process starts`() = runTest {
        helper.runProject()
        clearMocks(uiBridge, answers = false, recordedCalls = true)

        helper.handleProcessStateChanged(CompileActionsHelper.ExecutionProcessState.RUNNING)
        advanceUntilIdle()

        verify(exactly = 1) { uiBridge.setCompiling(true) }
        verify(exactly = 0) { uiBridge.showBuildLog() }
        coVerify(exactly = 0) { uiBridge.expandBottomPanel() }
    }

    @Test
    fun `handleProcessStateChanged keeps current output tab in build mode`() = runTest {
        helper.buildProject()
        clearMocks(uiBridge, answers = false, recordedCalls = true)

        helper.handleProcessStateChanged(CompileActionsHelper.ExecutionProcessState.RUNNING)
        advanceUntilIdle()

        verify(exactly = 1) { uiBridge.setCompiling(true) }
        verify(exactly = 0) { uiBridge.showBuildLog() }
        coVerify(exactly = 0) { uiBridge.expandBottomPanel() }
    }

    @Test
    fun `handleCompileSuccess reports exported build artifact kind`() = runTest {
        val report = CompileProjectUseCase.Report(
            action = CompileProjectUseCase.Action.BUILD,
            summary = "build ok",
            artifact = CompileProjectUseCase.BuildArtifact(
                path = "/tmp/libimgui.so",
                exportedPath = "/tmp/.tinaide/artifacts/libimgui.so",
                kind = CompileProjectUseCase.BuildArtifactKind.SHARED_LIBRARY
            )
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compile_done_artifact_kind_exported.strOr(
                    context,
                    Strings.compile_artifact_kind_shared_library.strOr(context)
                ),
                CompileActionsHelper.ToastType.SUCCESS
            )
        )
        verify(exactly = 1) { uiBridge.setCompiling(false) }
        coVerify(exactly = 1) { uiBridge.expandBottomPanel() }
    }

    @Test
    fun `handleCompileSuccess opens terminal for runnable launch`() = runTest {
        val report = CompileProjectUseCase.Report(
            action = CompileProjectUseCase.Action.TERMINAL,
            summary = "run ok",
            launch = CompileProjectUseCase.LaunchSpec.Terminal(
                command = "./imgui-demo",
                runnablePath = "/tmp/imgui-demo",
                workingDirectory = "/tmp/build"
            )
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compile_done_opening_terminal.strOr(context),
                CompileActionsHelper.ToastType.SUCCESS
            ),
            CompileActionsHelper.UiEvent.OpenTerminal(
                command = "./imgui-demo",
                workDir = "/tmp/build"
            )
        ).inOrder()
        verify(exactly = 0) { uiBridge.showBuildLog() }
    }

    @Test
    fun `handleCompileSuccess reports exported non runnable artifact`() = runTest {
        val report = CompileProjectUseCase.Report(
            action = CompileProjectUseCase.Action.RUN,
            summary = "run skipped",
            artifact = CompileProjectUseCase.BuildArtifact(
                path = "/tmp/libimgui.so",
                exportedPath = "/tmp/.tinaide/artifacts/libimgui.so",
                kind = CompileProjectUseCase.BuildArtifactKind.SHARED_LIBRARY
            ),
            launch = CompileProjectUseCase.LaunchSpec.None
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compile_done_non_runnable_artifact_exported.strOr(
                    context,
                    Strings.compile_artifact_kind_shared_library.strOr(context)
                ),
                CompileActionsHelper.ToastType.INFO
            )
        )
        verify(exactly = 0) { uiBridge.showBuildLog() }
    }

    @Test
    fun `handleCompileSuccess reports plugin hot install success`() = runTest {
        val report = CompileProjectUseCase.Report(
            action = CompileProjectUseCase.Action.RUN,
            summary = "plugin installed",
            artifact = CompileProjectUseCase.BuildArtifact(
                path = "/tmp/demo.plugin-1.0.0.tinaplug",
                exportedPath = "/tmp/demo.plugin-1.0.0.tinaplug",
                kind = CompileProjectUseCase.BuildArtifactKind.PLUGIN_PACKAGE,
            ),
            launch = CompileProjectUseCase.LaunchSpec.PluginInstalled(
                pluginId = "demo.plugin",
                pluginName = "Demo Plugin",
                pluginVersion = "1.0.0",
                packagePath = "/tmp/demo.plugin-1.0.0.tinaplug",
            ),
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_plugin_project_installed.strOr(context, "Demo Plugin"),
                CompileActionsHelper.ToastType.SUCCESS,
            )
        )
        verify(exactly = 1) { uiBridge.showBuildLog() }
    }

    @Test
    fun `handleCompileSuccess starts debug session for debug launch`() = runTest {
        val report = CompileProjectUseCase.Report(
            action = CompileProjectUseCase.Action.DEBUG,
            summary = "debug ok",
            launch = CompileProjectUseCase.LaunchSpec.Debug(
                programPath = "/tmp/imgui-demo",
                workingDirectory = "/tmp"
            )
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                Strings.toast_compile_done_starting_debug.strOr(context),
                CompileActionsHelper.ToastType.SUCCESS
            )
        )
        verify(exactly = 1) {
            uiBridge.startDebugSession("/tmp/imgui-demo", "/tmp", emptyList(), emptyMap())
        }
        verify(atLeast = 1) { uiBridge.showBuildLog() }
    }

    @Test
    fun `handleCompileSuccess falls back to maintenance success message`() = runTest {
        val action = CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE
        val report = CompileProjectUseCase.Report(
            action = action,
            summary = ""
        )

        val events = captureUiEvents {
            helper.handleCompileSuccess(CompileEvent.Success(report))
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                action.resolveUiText(context).successMessage,
                CompileActionsHelper.ToastType.SUCCESS
            )
        )
        verify(atLeast = 1) { uiBridge.showBuildLog() }
    }

    @Test
    fun `handleCompileError falls back to maintenance failure message`() = runTest {
        val action = CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY

        val events = captureUiEvents {
            helper.handleCompileError(
                CompileEvent.Error(
                    action = action,
                    message = "",
                    throwable = null
                )
            )
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                action.resolveUiText(context).failureMessage,
                CompileActionsHelper.ToastType.ERROR
            )
        )
        verify(exactly = 1) { uiBridge.setCompiling(false) }
        verify(atLeast = 1) { uiBridge.showBuildLog() }
        coVerify(exactly = 1) { uiBridge.expandBottomPanel() }
    }

    @Test
    fun `handleCompileError keeps build log selected for build failures`() = runTest {
        val events = captureUiEvents {
            helper.handleCompileError(
                CompileEvent.Error(
                    action = CompileProjectUseCase.Action.BUILD,
                    message = "compile failed",
                    throwable = null
                )
            )
        }

        assertThat(events).containsExactly(
            CompileActionsHelper.UiEvent.ShowToast(
                "compile failed",
                CompileActionsHelper.ToastType.ERROR
            )
        )
        verify(exactly = 1) { uiBridge.showBuildLog() }
        coVerify(exactly = 1) { uiBridge.expandBottomPanel() }
    }

    private suspend fun TestScope.captureUiEvents(
        action: suspend () -> Unit
    ): List<CompileActionsHelper.UiEvent> {
        val events = mutableListOf<CompileActionsHelper.UiEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            helper.uiEvents.collect { events += it }
        }

        runCurrent()
        action()
        advanceUntilIdle()
        collector.cancel()

        return events.toList()
    }
}

private class RecordingCommandRunner : CompileActionsHelper.CommandRunner {
    val compileCalls = mutableListOf<CompileProjectUseCase.Operation>()

    override fun compile(operation: CompileProjectUseCase.Operation) {
        compileCalls += operation
    }

    override fun reconfigureCMake() = Unit

    override fun clearCMakeBuildDirectory() = Unit

    override fun clearAndReconfigureCMake() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

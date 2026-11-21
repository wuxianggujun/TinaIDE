package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.di.compileModule
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.editor.EditorManager
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.output.OutputManager
import com.wuxianggujun.tinaide.tutorial.CourseAssessmentResult
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.dsl.module

/** Exercise the production course entry point in bounded phases, each with a real successful baseline. */
@RunWith(AndroidJUnit4::class)
class CourseAssessmentDeviceTest {
    @Test
    fun savesRebuildsChecksAndRecordsOnlySuccessfulRuns() = runAssessment(
        InstrumentationRegistry.getArguments().getString("assessmentPhase", "save"),
    )

    @Test
    fun rejectsCompileErrorsAndWrongOutputThenRetries() = runAssessment("reject")

    @Test
    fun cancellationStopsTheProcessAndAllowsRetry() = runAssessment("cancel")

    @Test
    fun deadlineStopsTheProcessAndAllowsRetry() = runAssessment("timeout")

    private fun runAssessment(phase: String) = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val language = arguments.getString("courseLanguage", "zh")
        require(language in setOf("zh", "en"))
        require(phase in setOf("all", "save", "reject", "cancel", "timeout"))
        val parent = File(context.filesDir, "course-acceptance").apply { check(isDirectory || mkdirs()) }
        val root = Files.createTempDirectory(parent.toPath(), "assessment-$language-").toFile()
        val courseId = "device-assessment-${root.name}"
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("language", language).put("assessmentPhase", phase).put("passed", false)
        val tracker = RegressionStageTracker(context, "course-assessment-$language-$phase", report)
        val output = OutputManager(context)
        val project = Project(courseId, "native_lab", root.path, root.path, emptyList(), File(root, "build").path)
        val projects = object : IProjectContext {
            override val currentProjectFlow = MutableStateFlow<Project?>(project)
            override fun getCurrentProject(): Project? = currentProjectFlow.value
        }
        val progress = TutorialProgressStore(context)
        var dependencies: KoinApplication? = null
        var editor: EditorManager? = null
        var compiler: CompilerViewModel? = null
        try {
            withTimeout(AssessmentDeadlineMs) {
                tracker.checkpoint("create-dependencies")
                val container = KoinApplication.init().androidContext(context).modules(
                    module { single<LinuxEnvironmentProvider> { UnavailableLinuxEnvironmentProvider } },
                    compileModule,
                )
                dependencies = container
                val manager = EditorManager(context, ConfigManager(context), { projects })
                editor = manager
                val lessons = CourseLessonAssets(context, language)
                lessons.writeProject(root, "cpp-basics")
                val source = File(root, "main.cpp")
                val expectedSource = lessons.basicsWithInputAndDiscount()
                val config = RunConfiguration(targetName = "native_lab", workDir = root.path)
                check(RunConfigurationManager.save(root.path, RunConfigurationManager(configurations = listOf(config), selectedId = config.id)))
                val useCase = CompileProjectUseCase(
                    appContext = context,
                    projectContext = projects,
                    outputManager = output,
                    editorManagerProvider = { manager },
                    orchestratorProvider = { container.koin.get() },
                    strategyRegistry = container.koin.get(),
                    buildContextFactory = container.koin.get(),
                    terminalCommandBuilder = container.koin.get(),
                    eventBus = container.koin.get(),
                )
                val viewModel = withContext(Dispatchers.Main) {
                    CompilerViewModel(useCase, projects, container.koin.get<ProcessManager>())
                }
                compiler = viewModel
                tracker.checkpoint("open-editor")
                val session = withContext(Dispatchers.Main) {
                    val tab = manager.openFile(source)
                    requireNotNull(manager.getSession(tab.id))
                }
                val binding = TextBinding(source.readText(Charsets.UTF_8))
                withContext(Dispatchers.Main) {
                    session.attachEditor(binding)
                    session.markEditorSnapshotClean(Charsets.UTF_8)
                    binding.setText(expectedSource)
                    session.notifyEditorContentChanged(canUndo = true, canRedo = false)
                }
                assertTrue("The lesson starts with unsaved edits", session.state.value.isDirty)
                assertFalse(source.readText(Charsets.UTF_8) == expectedSource)

                suspend fun assess(stage: String, stepId: String = "cpp_basics"): CourseAssessmentResult = coroutineScope {
                    tracker.checkpoint(stage)
                    val monitor = launch(Dispatchers.Main) {
                        viewModel.progress.filterNotNull().collect {
                            tracker.checkpoint("$stage:${it.fileName}")
                        }
                    }
                    try {
                        withContext(Dispatchers.Main) { viewModel.assessCourse(stepId, manager) }
                    } finally {
                        withContext(NonCancellable) { monitor.cancelAndJoin() }
                    }
                }

                suspend fun replaceSource(text: String) = withContext(Dispatchers.Main) {
                    binding.setText(text)
                    session.notifyEditorContentChanged(canUndo = true, canRedo = false)
                }

                val result = assess("save-build-check")
                assertTrue(result.toString() + "\n" + output.getOutput(IOutputManager.OutputChannel.BUILD), result.passed)
                assertEquals(5, result.checks.size)
                assertEquals(expectedSource, source.readText(Charsets.UTF_8))
                assertFalse(session.state.value.isDirty)
                assertFalse(viewModel.actionGate.isBusy.value)
                assertTrue(progress.recordVerifiedStep(courseId, "cpp_basics", 0, emptySet(), null))
                assertEquals(setOf("cpp_basics"), TutorialProgressStore(context).getProgress(courseId)?.completedStepIds)
                report.put("savedAndVerified", true).put("checks", result.checks.size).put("progressReloaded", true)
                val completed = progress.getProgress(courseId)

                if (phase in setOf("all", "reject")) {
                    replaceSource("int main() { this is invalid; }")
                    val invalid = runCatching { assess("reject-compile-error") }.exceptionOrNull()
                    assertTrue("Build errors must not reuse an old executable: $invalid", invalid is IllegalStateException)
                    assertFalse(viewModel.actionGate.isBusy.value)
                    assertEquals(completed, progress.getProgress(courseId))
                    report.put("compileFailureRejected", true)

                    replaceSource(expectedSource.replace("total -= 10;", "total -= 20;"))
                    assertFalse("Wrong discount must fail output verification", assess("reject-wrong-output").passed)
                    assertEquals(completed, progress.getProgress(courseId))
                    report.put("wrongOutputRejected", true)
                }

                if (phase in setOf("all", "cancel", "timeout")) {
                    val marker = File(root, "running.marker")
                    replaceSource("#include <fstream>\n#include <unistd.h>\nint main() { std::ofstream(\"running.marker\") << getpid(); for (;;) pause(); }\n")
                    if (phase in setOf("all", "cancel")) {
                        tracker.checkpoint("cancel-wait-process")
                        val running = async(Dispatchers.Main) { viewModel.assessCourse("cpp_basics", manager) }
                        try {
                            withTimeout(ProcessStartDeadlineMs) { while (!marker.isFile || marker.length() == 0L) delay(100) }
                            val childPid = marker.readText(Charsets.UTF_8).trim().toInt()
                            tracker.checkpoint("cancel-process")
                            running.cancelAndJoin()
                            assertTrue(running.isCancelled)
                            assertFalse("Cancellation must release the build gate", viewModel.actionGate.isBusy.value)
                            withTimeout(ProcessExitDeadlineMs) { while (File("/proc/$childPid").exists()) delay(50) }
                            assertEquals(completed, progress.getProgress(courseId))
                            report.put("cancelledProcessStopped", true)
                        } finally {
                            withContext(NonCancellable) { running.cancelAndJoin() }
                        }
                    }
                    if (phase in setOf("all", "timeout")) {
                        val timeout = assess("timeout-process", "cpp_raii")
                        assertFalse(timeout.passed)
                        assertTrue("The console run must hit its five-second deadline", timeout.checks.single().second.timedOut)
                        assertFalse(viewModel.actionGate.isBusy.value)
                        val childPid = marker.readText(Charsets.UTF_8).trim().toInt()
                        withTimeout(ProcessExitDeadlineMs) { while (File("/proc/$childPid").exists()) delay(50) }
                        assertEquals(completed, progress.getProgress(courseId))
                        report.put("timeoutProcessStopped", true)
                    }
                }

                if (phase != "save") {
                    replaceSource(expectedSource)
                    assertTrue("Retry after failure or cancellation must succeed", assess("retry-success").passed)
                    assertFalse(viewModel.actionGate.isBusy.value)
                    assertEquals(completed, progress.getProgress(courseId))
                    report.put("retryPassed", true)
                }
                report.put("passed", true)
            }
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName).put("failedStage", report.optString("stage"))
            throw error
        } finally {
            tracker.appendLog(output.getOutput(IOutputManager.OutputChannel.BUILD))
            tracker.checkpoint("cleanup")
            try {
                // Cancellation must not skip resource release or erase timeout diagnostics.
                withContext(NonCancellable + Dispatchers.Main) {
                    compiler?.cancelCompile()
                    editor?.onDestroy()
                }
                progress.resetProgress(courseId)
                dependencies?.close()
                check(root.canonicalFile.parentFile == parent.canonicalFile)
                check(root.deleteRecursively()) { "Unable to remove isolated assessment project" }
            } finally {
                tracker.finish()
            }
        }
    }

    private class TextBinding(private var text: String) : DocumentSession.EditorBinding {
        private var version = 0L
        override fun readText(): String = text
        override fun setText(text: CharSequence) {
            this.text = text.toString()
            version++
        }
        override fun textLength(): Int = text.length
        override fun currentDocumentVersion(): Long = version
        override fun canUndo(): Boolean = version > 0
        override fun canRedo(): Boolean = false
        override fun undo() = Unit
        override fun redo() = Unit
    }

    private companion object {
        const val AssessmentDeadlineMs = 50_000L
        const val ProcessStartDeadlineMs = 20_000L
        const val ProcessExitDeadlineMs = 5_000L
    }
}

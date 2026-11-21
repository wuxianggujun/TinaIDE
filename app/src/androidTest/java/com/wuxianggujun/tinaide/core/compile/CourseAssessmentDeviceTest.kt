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
import com.wuxianggujun.tinaide.output.OutputManager
import com.wuxianggujun.tinaide.tutorial.CourseAssessmentResult
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Exercise the same save/build/run entry point as the course dialog, using isolated project state. */
@RunWith(AndroidJUnit4::class)
class CourseAssessmentDeviceTest {
    @Test
    fun savesRebuildsChecksAndRecordsOnlySuccessfulRuns() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val language = arguments.getString("courseLanguage", "zh")
        val parent = File(context.filesDir, "course-acceptance").apply { check(isDirectory || mkdirs()) }
        val root = Files.createTempDirectory(parent.toPath(), "assessment-$language-").toFile()
        val courseId = "device-assessment-${root.name}"
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("language", language).put("passed", false)
        val output = OutputManager(context)
        val project = Project(courseId, "native_lab", root.path, root.path, emptyList(), File(root, "build").path)
        val projects = object : IProjectContext {
            override val currentProjectFlow = MutableStateFlow<Project?>(project)
            override fun getCurrentProject(): Project? = currentProjectFlow.value
        }
        val dependencies = KoinApplication.init().androidContext(context).modules(
            module { single<LinuxEnvironmentProvider> { UnavailableLinuxEnvironmentProvider } },
            compileModule,
        )
        val editor = EditorManager(context, ConfigManager(context), { projects })
        val progress = TutorialProgressStore(context)
        var compiler: CompilerViewModel? = null
        try {
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
                editorManagerProvider = { editor },
                orchestratorProvider = { dependencies.koin.get() },
                strategyRegistry = dependencies.koin.get(),
                buildContextFactory = dependencies.koin.get(),
                terminalCommandBuilder = dependencies.koin.get(),
                eventBus = dependencies.koin.get(),
            )
            val viewModel = withContext(Dispatchers.Main) {
                CompilerViewModel(useCase, projects, dependencies.koin.get<ProcessManager>())
            }
            compiler = viewModel
            val session = withContext(Dispatchers.Main) {
                val tab = editor.openFile(source)
                requireNotNull(editor.getSession(tab.id))
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

            suspend fun assess(stepId: String = "cpp_basics"): CourseAssessmentResult = withTimeout(50_000) {
                withContext(Dispatchers.Main) { viewModel.assessCourse(stepId, editor) }
            }

            val result = assess()
            assertTrue(result.toString() + "\n" + output.getOutput(com.wuxianggujun.tinaide.output.IOutputManager.OutputChannel.BUILD), result.passed)
            assertEquals(5, result.checks.size)
            assertEquals(expectedSource, source.readText(Charsets.UTF_8))
            assertFalse(session.state.value.isDirty)
            assertFalse(viewModel.actionGate.isBusy.value)
            assertTrue(progress.recordVerifiedStep(courseId, "cpp_basics", 0, emptySet(), null))
            assertEquals(setOf("cpp_basics"), TutorialProgressStore(context).getProgress(courseId)?.completedStepIds)
            report.put("savedAndVerified", true).put("checks", result.checks.size).put("progressReloaded", true)

            suspend fun replaceSource(text: String) = withContext(Dispatchers.Main) {
                binding.setText(text)
                session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            }

            val completed = progress.getProgress(courseId)
            replaceSource("int main() { this is invalid; }")
            val invalid = runCatching { assess() }.exceptionOrNull()
            assertTrue("Build errors must not reuse an old executable: $invalid", invalid is IllegalStateException)
            assertFalse(viewModel.actionGate.isBusy.value)
            assertEquals(completed, progress.getProgress(courseId))
            report.put("compileFailureRejected", true)

            replaceSource(expectedSource.replace("total -= 10;", "total -= 20;"))
            assertFalse("Wrong discount must fail output verification", assess().passed)
            assertEquals(completed, progress.getProgress(courseId))
            report.put("wrongOutputRejected", true)

            val marker = File(root, "running.marker")
            replaceSource("#include <fstream>\n#include <unistd.h>\nint main() { std::ofstream(\"running.marker\") << getpid(); for (;;) pause(); }\n")
            val running = async(Dispatchers.Main) { viewModel.assessCourse("cpp_basics", editor) }
            try {
                withTimeout(50_000) { while (!marker.isFile || marker.length() == 0L) delay(100) }
                val childPid = marker.readText(Charsets.UTF_8).trim().toInt()
                running.cancelAndJoin()
                assertTrue(running.isCancelled)
                assertFalse("Cancellation must release the build gate", viewModel.actionGate.isBusy.value)
                withTimeout(5_000) { while (File("/proc/$childPid").exists()) delay(50) }
                assertEquals(completed, progress.getProgress(courseId))
                report.put("cancelledProcessStopped", true)
            } finally {
                running.cancelAndJoin()
            }

            val timeout = assess("cpp_raii")
            assertFalse(timeout.passed)
            assertTrue("The console run must hit its five-second deadline", timeout.checks.single().second.timedOut)
            assertFalse(viewModel.actionGate.isBusy.value)
            val timedOutPid = marker.readText(Charsets.UTF_8).trim().toInt()
            withTimeout(5_000) { while (File("/proc/$timedOutPid").exists()) delay(50) }
            assertEquals(completed, progress.getProgress(courseId))
            report.put("timeoutProcessStopped", true)

            replaceSource(expectedSource)
            assertTrue("Retry after failure and cancellation must succeed", assess().passed)
            report.put("retryPassed", true).put("passed", true)
        } finally {
            withContext(Dispatchers.Main) {
                compiler?.cancelCompile()
                editor.onDestroy()
            }
            progress.resetProgress(courseId)
            dependencies.close()
            File(regressionEvidenceDirectory(context), "course-assessment-$language.json").writeText(report.toString(2), Charsets.UTF_8)
            File(regressionEvidenceDirectory(context), "course-assessment-$language.log")
                .writeText(output.getOutput(com.wuxianggujun.tinaide.output.IOutputManager.OutputChannel.BUILD), Charsets.UTF_8)
            emitRegressionReport(report)
            check(root.canonicalFile.parentFile == parent.canonicalFile)
            root.deleteRecursively()
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
}

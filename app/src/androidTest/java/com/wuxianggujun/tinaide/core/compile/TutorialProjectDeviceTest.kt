package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectBlueprints
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectRepository
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorialProjectDeviceTest {
    @Test fun bundledChaptersCreateReopenAndRunOffline() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val language = args.getString("courseLanguage", "zh")
        val fixture = Files.createTempDirectory(context.cacheDir.toPath(), "course-project-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(fixture, "files")
            override fun getCacheDir() = File(fixture, "cache")
        }
        val report = JSONObject().put("runId", args.getString("regressionRunId", "manual"))
            .put("language", language).put("passed", false)
        val tracker = RegressionStageTracker(context, "course-project-$language", report)
        try {
            withTimeout(50_000) {
                val repository = TutorialProjectRepository(isolated)
                val root = ProjectPaths.getPrivateProjectsRoot(isolated).apply { mkdirs() }
                // A dangling symlink is still an occupied name; never create its target.
                val danglingTarget = File(fixture, "must-stay-missing")
                Files.createSymbolicLink(File(root, "tina_course_01_ide").toPath(), danglingTarget.toPath())
                val projects = TutorialProjectBlueprints.stepIds.associateWith { stepId ->
                    tracker.checkpoint("create-$stepId")
                    val project = repository.prepare(stepId, language)
                    val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
                    val blueprint = TutorialProjectBlueprints.load(stepId) { lesson ->
                        context.assets.open("$prefix/$lesson.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    blueprint.files.forEach { (name, text) -> assertEquals(text, File(project, name).readText(Charsets.UTF_8)) }
                    ProjectRunConfigBootstrapper.ensureInitialized(project)
                    val config = RunConfigurationManager.load(project.absolutePath).selectedConfig
                    assertEquals("native_lab", config.targetName)
                    assertEquals(if (blueprint.isSdl3) OutputMode.SDL else OutputMode.TERMINAL, config.outputMode)
                    assertEquals(ProjectSdlVersion.SDL3.takeIf { blueprint.isSdl3 }, ProjectMetadataStore.read(project)?.sdlVersion)
                    assertEquals(blueprint.useVcpkg, File(project, "vcpkg.json").exists())
                    assertTrue(!File(project, "vcpkg_installed").exists())
                    project
                }
                assertEquals("tina_course_01_ide_2", projects.getValue("ide_workflow").name)
                assertTrue(!danglingTarget.exists())
                report.put("created", projects.size).put("symlinkConflictPreserved", true)

                val project = projects.getValue("cpp_functions")
                val source = File(project, "main.cpp")
                source.appendText("\n// learner edit: \u4e2d\u6587 \uD83D\uDE00\n", Charsets.UTF_8)
                val bytes = source.readBytes()
                val modified = source.lastModified()
                val config = RunConfigurationManager.load(project.absolutePath)
                val custom = config.copy(configurations = config.configurations.map { it.copy(args = "learner-argument") })
                assertTrue(RunConfigurationManager.save(project.absolutePath, custom))
                assertEquals(project, repository.prepare("cpp_functions", if (language == "en") "zh" else "en"))
                ProjectRunConfigBootstrapper.ensureInitialized(project)
                assertArrayEquals(bytes, source.readBytes())
                assertEquals(modified, source.lastModified())
                assertEquals("learner-argument", RunConfigurationManager.load(project.absolutePath).selectedConfig.args)
                report.put("preservedEditsAndConfig", true)

                tracker.checkpoint("configure-and-build")
                val executor = NativeCMakeBuildExecutor(context)
                val options = NativeCMakeBuildExecutor.Options(parallelJobs = 1, onProgress = tracker::appendLog)
                val build = File(project, "build")
                val configured = executor.configure(project, build, options)
                assertTrue(configured.toString(), configured is ConfigureResult.Success)
                val built = executor.build(project, build, options = options)
                assertTrue(built.toString(), built is BuildResult.Success)
                tracker.checkpoint("run")
                val result = AndroidElfExecutor.exec(context, File(build, "native_lab"), emptyList(), workDir = project, timeoutMs = 10_000)
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("total=30"))
                report.put("output", result.output).put("passed", true)
                tracker.checkpoint("complete")
            }
        } finally {
            check(fixture.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            // Remove the deliberately dangling link before recursive cleanup.
            Files.deleteIfExists(File(ProjectPaths.getPrivateProjectsRoot(isolated), "tina_course_01_ide").toPath())
            check(fixture.deleteRecursively())
            tracker.finish()
        }
    }
}

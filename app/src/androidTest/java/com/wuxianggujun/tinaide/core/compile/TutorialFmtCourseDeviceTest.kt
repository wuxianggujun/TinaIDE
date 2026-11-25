package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.core.packages.VcpkgManagedPaths
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectRepository
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A fresh production chapter must work without tests repairing its manifest or replacing its source. */
@RunWith(AndroidJUnit4::class)
class TutorialFmtCourseDeviceTest {
    @Test fun generatedProjectInstallsBuildsRunsAndReusesFmt() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val language = arguments.getString("courseLanguage", "zh")
        val fixture = Files.createTempDirectory(context.cacheDir.toPath(), "tutorial-fmt-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(fixture, "files")
            override fun getCacheDir() = File(fixture, "cache")
        }
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("language", language).put("passed", false)
        val tracker = RegressionStageTracker(context, "tutorial-fmt-$language", report)
        var workspace: File? = null
        try {
            withTimeout(50_000) {
                tracker.checkpoint("create-production-chapter")
                val project = TutorialProjectRepository(isolated).prepare("vcpkg_guide", language)
                val manifest = File(project, "vcpkg.json")
                val dependencies = JSONObject(manifest.readText(Charsets.UTF_8)).getJSONArray("dependencies")
                assertEquals(1, dependencies.length())
                assertEquals("fmt", dependencies.getString(0))
                val source = File(project, "main.cpp")
                assertTrue(source.readText(Charsets.UTF_8).contains("fmt::print"))
                val sourceBytes = source.readBytes()
                val manifestBytes = manifest.readBytes()
                ProjectRunConfigBootstrapper.ensureInitialized(project)
                val configuration = RunConfigurationManager.load(project.path).selectedConfig
                assertEquals("native_lab", configuration.targetName)
                assertEquals(OutputMode.TERMINAL, configuration.outputMode)
                val metadata = requireNotNull(ProjectMetadataStore.read(project))
                val settings = VcpkgProjectSettings.from(metadata.normalizedNativeCMakeArgs())
                assertTrue(settings.enabled)
                val ownedWorkspace = VcpkgManagedPaths.workspace(context, project)
                workspace = ownedWorkspace
                val build = File(ownedWorkspace, "build/tutorial-fmt")
                val executor = NativeCMakeBuildExecutor(context)
                val options = NativeCMakeBuildExecutor.Options(
                    parallelJobs = 1,
                    extraCMakeArgs = metadata.normalizedNativeCMakeArgs(),
                    onProgress = tracker::appendLog,
                )
                tracker.checkpoint("install-and-configure")
                val configured = executor.configure(project, build, options)
                assertTrue(configured.toString(), configured is ConfigureResult.Success)
                val installed = VcpkgManagedPaths.installed(context, project, settings, android.os.Build.SUPPORTED_ABIS.first())
                val library = File(installed, "${settings.targetTriplet(android.os.Build.SUPPORTED_ABIS.first())}/lib/libfmt.a")
                assertTrue("The generated manifest must install fmt", library.isFile)
                val libraryHash = sha256(library)
                val modified = library.lastModified()
                tracker.checkpoint("build-and-run")
                assertTrue(executor.build(project, build, options = options) is BuildResult.Success)
                val first = AndroidElfExecutor.exec(context, File(build, "native_lab"), emptyList(), workDir = project, timeoutMs = 5_000)
                assertEquals(first.output, 0, first.exitCode)
                assertTrue(first.output, first.output.lineSequence().any { it.trim() == "total=30" })
                tracker.checkpoint("configure-and-run-again")
                assertTrue(executor.configure(project, build, options) is ConfigureResult.Success)
                assertTrue(executor.build(project, build, options = options) is BuildResult.Success)
                val repeated = AndroidElfExecutor.exec(context, File(build, "native_lab"), emptyList(), workDir = project, timeoutMs = 5_000)
                assertEquals(repeated.output, 0, repeated.exitCode)
                assertTrue(repeated.output, repeated.output.lineSequence().any { it.trim() == "total=30" })
                assertEquals(modified, library.lastModified())
                assertEquals(libraryHash, sha256(library))
                assertArrayEquals(sourceBytes, source.readBytes())
                assertArrayEquals(manifestBytes, manifest.readBytes())
                report.put("declaredFmt", true).put("usesFmt", true).put("installedFmt", true)
                    .put("reusedFmt", true).put("preservedSources", true).put("output", first.output).put("passed", true)
                tracker.checkpoint("complete")
            }
        } finally {
            workspace?.takeIf { it.exists() }?.let {
                check(it.canonicalFile.parentFile == ProjectPaths.getWorkspaceRoot(context).canonicalFile)
                check(!Files.isSymbolicLink(it.toPath()))
                check(it.deleteRecursively())
            }
            check(fixture.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(fixture.deleteRecursively())
            tracker.finish()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

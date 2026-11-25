package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs native vcpkg/CMake/compiler processes against a prepared CMake source fixture. */
@RunWith(AndroidJUnit4::class)
class VcpkgAcceptanceDeviceTest {
    @Test fun realFmtProject_configuresBuildsOrRuns() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("vcpkgPhase", "configure")
        val fixture = File(context.filesDir, "device-acceptance")
        val project = File(fixture, "project")
        assertTrue("Prepare CMake project first", File(project, "CMakeLists.txt").isFile)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val metadata = ProjectMetadataStore.ensure(project)
        assertTrue(ProjectMetadataStore.write(project, metadata.copy(nativeCMakeArgs = settings.applyTo(emptyList()))))
        val output = StringBuilder()
        val options = NativeCMakeBuildExecutor.Options(
            parallelJobs = 1,
            buildType = NativeCMakeBuildExecutor.CMakeBuildType.RELEASE,
            extraCMakeArgs = settings.applyTo(emptyList()),
            onProgress = { if (output.length < 128 * 1024) output.appendLine(it) },
        )
        val executor = NativeCMakeBuildExecutor(context)
        val build = File(project, "build/acceptance")
        val evidence = regressionEvidenceDirectory(context)
        val report = JSONObject().put("phase", phase)
        try {
            withTimeout(50_000) {
                when (phase) {
                    "configure", "repeat" -> {
                        val result = executor.configure(project, build, options)
                        report.put("result", result.toString())
                        assertTrue(result.toString() + "\n" + output, result is ConfigureResult.Success)
                        assertFalse("Acceptance must not depend on a manual app-private shell alias",
                            File(context.filesDir, "usr/bin/sh").exists())
                        assertFalse("No project-local shell alias is necessary",
                            File(build, "tina-vcpkg/runtime/bin/sh").exists())
                        report.put("systemShellWithoutAlias", true)
                        val catalog = VcpkgRepository(context).read(project, android.os.Build.SUPPORTED_ABIS.first())
                        assertTrue("fmt is absent from status", "fmt" in catalog.installed)
                        report.put("installedFmt", catalog.installed["fmt"])
                    }
                    "build" -> {
                        val result = executor.build(project, build, options = options)
                        report.put("result", result.toString())
                        assertTrue(result.toString() + "\n" + output, result is BuildResult.Success)
                        assertTrue(File(build, "tina_vcpkg_example").isFile)
                    }
                    "run" -> {
                        val result = AndroidElfExecutor.exec(context, File(build, "tina_vcpkg_example"),
                            emptyList(), workDir = project, timeoutMs = 10_000)
                        report.put("exitCode", result.exitCode).put("output", result.output)
                        assertEquals(result.output, 0, result.exitCode)
                        assertTrue(result.output, result.output.contains("TinaIDE + vcpkg: 42"))
                    }
                    else -> error("Unknown acceptance phase: $phase")
                }
            }
            report.put("passed", true)
        } finally {
            File(evidence, "vcpkg-$phase.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "vcpkg-$phase.log").writeText(output.toString(), Charsets.UTF_8)
        }
    }
}

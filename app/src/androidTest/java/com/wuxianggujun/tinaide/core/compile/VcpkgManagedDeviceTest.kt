package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.core.packages.VcpkgRuntimeManager
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production preparation without a user-supplied vcpkg path or a hand-built CLI fixture. */
@RunWith(AndroidJUnit4::class)
class VcpkgManagedDeviceTest {
    @Test fun managedCatalogAndRuntime() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("vcpkgManagedPhase", "catalog")
        val project = File(if (phase == "external") context.getExternalFilesDir(null)!! else context.filesDir,
            "managed-vcpkg-acceptance").apply { mkdirs() }
        val report = JSONObject().put("phase", phase).put("passed", false)
            .put("runId", InstrumentationRegistry.getArguments().getString("regressionRunId", "manual"))
        val output = StringBuilder()
        try {
            withTimeout(50_000) {
                when (phase) {
                    "catalog" -> {
                        val catalog = VcpkgCatalogStore(context).readLocal()
                        assertTrue(catalog.ports.size > 2000)
                        assertTrue(catalog.ports.any { it.name == "fmt" })
                        report.put("ports", catalog.ports.size).put("revision", catalog.revision)
                    }
                    "update" -> {
                        val catalog = VcpkgCatalogStore(context).update()
                        assertTrue(catalog.ports.size > 2000)
                        assertTrue(File(VcpkgCatalogStore(context).ensureSources(), "scripts/buildsystems/vcpkg.cmake").isFile)
                        report.put("ports", catalog.ports.size).put("revision", catalog.revision)
                    }
                    "runtime" -> {
                        val runtime = VcpkgRuntimeManager(context).prepare(android.os.Build.SUPPORTED_ABIS.first())
                        assertTrue(File(runtime.root, "vcpkg").isFile)
                        assertTrue(runtime.root.canonicalPath.startsWith(File(context.filesDir, "vcpkg").canonicalPath + "/"))
                        report.put("root", runtime.root.name)
                    }
                    "configure", "repeat", "build", "run", "external" -> {
                        File(project, "CMakeLists.txt").writeText("""
                            cmake_minimum_required(VERSION 3.22)
                            project(managed_vcpkg LANGUAGES CXX)
                            find_package(fmt CONFIG REQUIRED)
                            add_executable(managed_vcpkg main.cpp)
                            target_link_libraries(managed_vcpkg PRIVATE fmt::fmt)
                        """.trimIndent(), Charsets.UTF_8)
                        File(project, "main.cpp").writeText("#include <fmt/format.h>\nint main() { fmt::print(\"managed vcpkg: {}\\n\", 42); }\n", Charsets.UTF_8)
                        val repository = VcpkgRepository(context)
                        repository.enableManagedManifest(project)
                        repository.setDependency(project, "fmt", true)
                        val executor = NativeCMakeBuildExecutor(context)
                        val build = File(com.wuxianggujun.tinaide.core.packages.VcpkgManagedPaths.workspace(context, project), "build/acceptance")
                        val options = NativeCMakeBuildExecutor.Options(parallelJobs = 1,
                            extraCMakeArgs = com.wuxianggujun.tinaide.project.VcpkgProjectSettings(
                                com.wuxianggujun.tinaide.project.VcpkgProjectSettings.MANIFEST).applyTo(emptyList()),
                            onProgress = { if (output.length < 128 * 1024) output.appendLine(it) })
                        when (phase) {
                            "configure", "repeat", "external" -> {
                                val result = executor.configure(project, build, options)
                                assertTrue(result.toString() + "\n" + output, result is ConfigureResult.Success)
                                val catalog = repository.read(project, android.os.Build.SUPPORTED_ABIS.first())
                                assertTrue("fmt" in catalog.installed)
                                assertTrue(catalog.settings.root.isBlank())
                                assertTrue(catalog.settings.installedDir.isBlank())
                                val installed = com.wuxianggujun.tinaide.core.packages.VcpkgManagedPaths.installed(context, project, catalog.settings)
                                assertTrue(installed.canonicalPath.startsWith(File(context.filesDir, "workspace").canonicalPath + "/"))
                                assertFalse(File(project, "vcpkg_installed").exists())
                                report.put("fmt", catalog.installed["fmt"])
                            }
                            "build" -> assertTrue(executor.build(project, build, options = options) is BuildResult.Success)
                            "run" -> {
                                val result = AndroidElfExecutor.exec(context, File(build, "managed_vcpkg"), emptyList(), workDir = project, timeoutMs = 10_000)
                                assertEquals(result.output, 0, result.exitCode)
                                assertTrue(result.output.contains("managed vcpkg: 42"))
                                report.put("output", result.output)
                            }
                        }
                    }
                    else -> error("Unknown phase")
                }
            }
            report.put("passed", true)
        } finally {
            val evidence = regressionEvidenceDirectory(context)
            File(evidence, "vcpkg-managed-$phase.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "vcpkg-managed-$phase.log").writeText(output.toString(), Charsets.UTF_8)
        }
    }
}

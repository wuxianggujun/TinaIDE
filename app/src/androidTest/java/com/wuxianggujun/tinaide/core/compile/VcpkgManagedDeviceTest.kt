package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.core.packages.VcpkgRuntimeManager
import com.wuxianggujun.tinaide.core.packages.VcpkgSharedLibrary
import com.wuxianggujun.tinaide.core.packages.VcpkgManagedPaths
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
        val started = android.os.SystemClock.elapsedRealtime()
        val progress: (Long, Long) -> Unit = { downloaded, total ->
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            report.put("downloadedBytes", downloaded).put("expectedBytes", total)
                .put("lastProgressMs", elapsed)
            output.appendLine("${report.optString("stage")}: $downloaded/$total bytes after $elapsed ms")
        }
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
                        report.put("stage", "catalog-update")
                        val catalog = VcpkgCatalogStore(context).update(progress)
                        assertTrue(catalog.ports.size > 2000)
                        report.put("ports", catalog.ports.size).put("revision", catalog.revision)
                            .put("stage", "catalog-ready")
                    }
                    "sources" -> {
                        report.put("stage", "source-preparation")
                        val store = VcpkgCatalogStore(context)
                        val catalog = store.readLocal()
                        val sources = store.ensureSources(progress)
                        assertTrue(File(sources, "scripts/buildsystems/vcpkg.cmake").isFile)
                        assertEquals(catalog.revision, File(sources, ".tina-source-ready").readText(Charsets.UTF_8))
                        report.put("revision", catalog.revision).put("stage", "sources-ready")
                    }
                    "offline-update" -> {
                        val store = VcpkgCatalogStore(context)
                        val before = store.readLocal()
                        val failure = runCatching { store.update() }.exceptionOrNull()
                        assertNotNull("Updating without a network must fail", failure)
                        assertFalse("The test must finish before its deadline", failure is TimeoutCancellationException)
                        assertEquals(before, store.readLocal())
                        report.put("cachePreserved", true).put("failureType", failure!!.javaClass.simpleName)
                    }
                    "cancel-runtime" -> {
                        val failure = runCatching {
                            withTimeout(100) { VcpkgRuntimeManager(context).prepare(android.os.Build.SUPPORTED_ABIS.first()) }
                        }.exceptionOrNull()
                        assertTrue("Preparation must propagate cancellation", failure is TimeoutCancellationException)
                        report.put("cancelled", true)
                    }
                    "runtime", "runtime-offline" -> {
                        val runtime = VcpkgRuntimeManager(context).prepare(android.os.Build.SUPPORTED_ABIS.first())
                        assertTrue(File(runtime.root, "vcpkg").isFile)
                        assertTrue(runtime.root.canonicalPath.startsWith(File(context.filesDir, "vcpkg").canonicalPath + "/"))
                        report.put("root", runtime.root.name)
                    }
                    "cancel-shared" -> {
                        val abi = android.os.Build.SUPPORTED_ABIS.first()
                        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
                        val shared = File(VcpkgSharedLibrary.root(context), VcpkgSharedLibrary.triplet(abi))
                        val before = libraryHashes(shared)
                        assertTrue("Run the shared-library case first", before.isNotEmpty())
                        val library = shared.walkTopDown().first { it.isFile && it.extension == "a" }
                        val original = library.readBytes()
                        val probe = File(context.filesDir, "cancel-shared-vcpkg-acceptance").apply { mkdirs() }
                        ProjectMetadataStore.ensure(probe)
                        val source = File(VcpkgManagedPaths.installed(context, probe, settings), settings.targetTriplet(abi))
                        File(source, library.relativeTo(shared).path).apply {
                            parentFile!!.mkdirs()
                            writeBytes(original + "cancelled-export-change".toByteArray(Charsets.UTF_8))
                        }
                        try {
                            val failure = runCatching {
                                VcpkgSharedLibrary.export(context, probe, settings, abi) {
                                    throw CancellationException("Cancel after copying the first changed library")
                                }
                            }.exceptionOrNull()
                            assertTrue("Shared export must propagate cancellation", failure is CancellationException)
                            assertEquals("Cancellation must preserve every published file", before, libraryHashes(shared))
                            assertTrue(VcpkgSharedLibrary.root(context).listFiles().orEmpty().none {
                                it.name.startsWith(".shared-staging-") || it.name.startsWith(".shared-backup-")
                            })
                            report.put("cancelled", true).put("publishedFilesPreserved", before.size)
                        } finally {
                            // Preserve the installed fmt fixture even when testing an old/broken APK.
                            if (!library.readBytes().contentEquals(original)) library.writeBytes(original)
                        }
                    }
                    "configure", "repeat", "build", "run", "external", "shared" -> {
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
                            "shared" -> {
                                val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
                                val shared = VcpkgSharedLibrary.export(context, project, settings, android.os.Build.SUPPORTED_ABIS.first())
                                val consumer = File(context.filesDir, "shared-vcpkg-acceptance").apply { mkdirs() }
                                File(project, "CMakeLists.txt").copyTo(File(consumer, "CMakeLists.txt"), overwrite = true)
                                File(project, "main.cpp").copyTo(File(consumer, "main.cpp"), overwrite = true)
                                repository.enableSharedLibrary(consumer)
                                val prebuilt = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
                                val consumerBuild = File(context.filesDir, "shared-vcpkg-build")
                                val consumerOptions = options.copy(extraCMakeArgs = prebuilt.applyTo(emptyList()))
                                val configured = executor.configure(consumer, consumerBuild, consumerOptions)
                                assertTrue(configured.toString() + "\n" + output, configured is ConfigureResult.Success)
                                assertTrue(executor.build(consumer, consumerBuild, options = consumerOptions) is BuildResult.Success)
                                val result = AndroidElfExecutor.exec(context, File(consumerBuild, "managed_vcpkg"), emptyList(),
                                    workDir = consumer, timeoutMs = 10_000)
                                assertEquals(result.output, 0, result.exitCode)
                                assertTrue(result.output, result.output.contains("managed vcpkg: 42"))
                                report.put("output", result.output).put("sharedTriplet", VcpkgSharedLibrary.triplet(android.os.Build.SUPPORTED_ABIS.first()))
                            }
                            "configure", "repeat", "external" -> {
                                val result = executor.configure(project, build, options)
                                assertTrue(result.toString() + "\n" + output, result is ConfigureResult.Success)
                                val catalog = repository.read(project, android.os.Build.SUPPORTED_ABIS.first())
                                assertTrue("fmt" in catalog.installed)
                                assertEquals(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST), catalog.settings)
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
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
            val evidence = regressionEvidenceDirectory(context)
            File(evidence, "vcpkg-managed-$phase.json").writeText(report.toString(2), Charsets.UTF_8)
            File(evidence, "vcpkg-managed-$phase.log").writeText(output.toString(), Charsets.UTF_8)
            emitRegressionReport(report)
        }
    }

    private fun libraryHashes(directory: File): Map<String, String> = directory.walkTopDown()
        .filter(File::isFile).associate { file ->
            file.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
}

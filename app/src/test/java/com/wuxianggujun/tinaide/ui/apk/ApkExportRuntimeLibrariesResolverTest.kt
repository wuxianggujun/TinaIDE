package com.wuxianggujun.tinaide.ui.apk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class ApkExportRuntimeLibrariesResolverTest {

    @Test
    fun `resolveDependencyClosure collects transitive dependencies and skips system libs`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-libs-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }
        val foo = File(tempDir, "libfoo.so").apply { writeText("foo") }
        val bar = File(tempDir, "libbar.so").apply { writeText("bar") }

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolveDependencyClosure(
                rootLibraries = listOf(main),
                runtimeIndex = mapOf(
                    "libfoo.so" to foo,
                    "libbar.so" to bar
                ),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libfoo.so", "libandroid.so")
                        "libfoo.so" -> setOf("libbar.so")
                        else -> emptySet()
                    }
                },
                systemLibraries = setOf("libandroid.so")
            )

            assertThat(result.libraries.map { it.name }).containsExactly("libfoo.so", "libbar.so").inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildRuntimeLibraryIndex does not alias a different soname`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-index-test").toFile()
        val versionedSdl = File(tempDir, "libSDL3.so.0").apply { writeText("sdl") }

        try {
            val index = ApkExportRuntimeLibrariesResolver.buildRuntimeLibraryIndex(
                listOf(versionedSdl)
            )

            assertThat(index["libSDL3.so.0"]?.name).isEqualTo("libSDL3.so.0")
            assertThat(index["libSDL3.so"]).isNull()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveDependencyClosure skips GLES compatibility alias as system library`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-system-lib-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolveDependencyClosure(
                rootLibraries = listOf(main),
                runtimeIndex = emptyMap(),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libGLES_CM.so")
                        else -> emptySet()
                    }
                }
            )

            assertThat(result.libraries).isEmpty()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePackagedLibraries keeps only root lib and reachable dependencies`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-packaged-libs-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }
        val linked = File(tempDir, "libfoo.so").apply { writeText("foo") }
        File(tempDir, "libSDL3.so").writeText("sdl")

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolvePackagedLibraries(
                buildLibraries = listOf(main, linked, File(tempDir, "libSDL3.so")),
                runtimeCandidates = tempDir.listFiles()?.toList().orEmpty(),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libfoo.so")
                        else -> emptySet()
                    }
                }
            )

            assertThat(result.libraries.map { it.name })
                .containsExactly("libmain.so", "libfoo.so")
                .inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildRuntimeLibraryIndex preserves explicit candidate priority`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-priority-test").toFile()
        try {
            val preferred = File(tempDir, "z-preferred/libSDL3.so").apply {
                parentFile?.mkdirs()
                writeText("preferred")
            }
            val other = File(tempDir, "a-other/libSDL3.so").apply {
                parentFile?.mkdirs()
                writeText("other")
            }

            val index = ApkExportRuntimeLibrariesResolver.buildRuntimeLibraryIndex(
                listOf(preferred, other)
            )

            assertThat(index["libSDL3.so"]?.canonicalPath).isEqualTo(preferred.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildRuntimeLibraryIndex prefers the mapped installed package`() {
        val tempDir = Files.createTempDirectory("apk-export-package-priority-test").toFile()
        try {
            val installRoot = File(tempDir, "installed-packages")
            val other = File(installRoot, "aaa-other/lib/arm64-v8a/libSDL3_image.so").apply {
                parentFile?.mkdirs()
                writeText("other")
            }
            val preferred = File(installRoot, "sdl3-image/lib/arm64-v8a/libSDL3_image.so").apply {
                parentFile?.mkdirs()
                writeText("preferred")
            }

            val index = ApkExportRuntimeLibrariesResolver.buildRuntimeLibraryIndex(
                listOf(other, preferred)
            )

            assertThat(index["libSDL3_image.so"]?.canonicalPath).isEqualTo(preferred.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePackagedLibraries includes SDL3 extension libraries and shared SDL runtime`() {
        val tempDir = Files.createTempDirectory("apk-export-sdl3-extension-libs-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }
        val sdlImage = File(tempDir, "libSDL3_image.so").apply { writeText("image") }
        val sdlTtf = File(tempDir, "libSDL3_ttf.so").apply { writeText("ttf") }
        val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolvePackagedLibraries(
                buildLibraries = listOf(main),
                runtimeCandidates = listOf(main, sdlImage, sdlTtf, sdl),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libSDL3_image.so", "libSDL3_ttf.so")
                        "libSDL3_image.so" -> setOf("libSDL3.so")
                        "libSDL3_ttf.so" -> setOf("libSDL3.so")
                        else -> emptySet()
                    }
                }
            )

            assertThat(result.libraries.map { it.name })
                .containsExactly(
                    "libmain.so",
                    "libSDL3_image.so",
                    "libSDL3_ttf.so",
                    "libSDL3.so"
                )
                .inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve uses project runtime dirs for SDL library when artifact is outside project root`() {
        val tempDir = Files.createTempDirectory("apk-export-sdl-runtime-dir-test").toFile()
        try {
            val projectRoot = File(tempDir, "project").apply { mkdirs() }
            val runtimeDir = File(projectRoot, "runtime-libs").apply { mkdirs() }
            val buildDir = File(tempDir, "outside-build").apply { mkdirs() }
            File(buildDir, "libmain.so").writeText("NEEDED libSDL3.so")
            File(runtimeDir, "libSDL3.so").writeText("sdl")
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = "APK SDL Runtime",
                buildSystem = ProjectBuildSystem.SINGLE_FILE,
            )
            ProjectMetadataStore.updateNativeDependencyPaths(
                projectRoot = projectRoot,
                includeDirs = emptyList(),
                libraryDirs = emptyList(),
                runtimeDirs = listOf("runtime-libs"),
            )

            val result = ApkExportRuntimeLibrariesResolver.resolve(
                context = RuntimeEnvironment.getApplication().applicationContext,
                projectRoot = projectRoot,
                buildDir = buildDir,
            )

            assertThat(result.packagedLibraries.map { it.name })
                .containsExactly("libmain.so", "libSDL3.so")
                .inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

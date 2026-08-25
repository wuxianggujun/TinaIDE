package com.wuxianggujun.tinaide.ui.sdl

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
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
class SdlRuntimeResolverTest {

    @Test
    fun `resolve finds SDL library from extra runtime dirs`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-extra-dir-test").toFile()
        try {
            val artifactDir = File(tempDir, "outside-build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime-libs").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libSDL3.so")
            }
            val sdl = File(runtimeDir, "libSDL3.so").apply { writeSdlRuntime(3) }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve detects direct SDL2 dependency`() {
        val tempDir = Files.createTempDirectory("sdl2-runtime-direct-test").toFile()
        try {
            val artifactDir = File(tempDir, "build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libSDL2.so")
            }
            val sdl = File(runtimeDir, "libSDL2.so").apply { writeSdlRuntime(2) }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(2)
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve rejects SDL2 built for the unrelocated Android bridge`() {
        val tempDir = Files.createTempDirectory("sdl2-runtime-legacy-bridge-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply {
                writeText("NEEDED libSDL2.so")
            }
            File(tempDir, "libSDL2.so").writeText("org/libsdl/app/SDLActivity")

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Error::class.java)
            assertThat((result as SdlRuntimeResolver.ResolveResult.Error).message)
                .contains("org/libsdl2/app/SDLActivity")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve finds SDL2 runtime beside main library`() {
        val tempDir = Files.createTempDirectory("sdl2-runtime-colocated-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply {
                writeText("NEEDED libSDL2.so")
            }
            val sdl = File(tempDir, "libSDL2.so").apply { writeSdlRuntime(2) }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(2)
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve detects versioned SDL2 ABI soname`() {
        val tempDir = Files.createTempDirectory("sdl2-runtime-versioned-test").toFile()
        try {
            val artifactDir = File(tempDir, "build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libSDL2-2.0.so.0")
            }
            val sdl = File(runtimeDir, "libSDL2-2.0.so.0").apply { writeSdlRuntime(2) }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(2)
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve detects SDL2 through transitive dependency`() {
        val tempDir = Files.createTempDirectory("sdl2-runtime-transitive-detect-test").toFile()
        try {
            val artifactDir = File(tempDir, "build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libengine.so")
            }
            val engine = File(runtimeDir, "libengine.so").apply {
                writeText("NEEDED libSDL2.so")
            }
            File(runtimeDir, "libSDL2.so").writeSdlRuntime(2)

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(2)
            assertThat(spec.preloadLibraryPaths.map { File(it).canonicalPath })
                .contains(engine.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve rejects transitive SDL2 and SDL3 conflict`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-transitive-conflict-test").toFile()
        try {
            val artifactDir = File(tempDir, "build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libengine2.so libengine3.so")
            }
            File(runtimeDir, "libengine2.so").writeText("NEEDED libSDL2.so")
            File(runtimeDir, "libengine3.so").writeText("NEEDED libSDL3.so")

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Error::class.java)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve rejects configured SDL2 when artifact depends on SDL3`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-config-conflict-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply {
                writeText("NEEDED libSDL3.so")
            }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                allowUndetectedSdl = true,
                preferredSdlMajor = 2,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Error::class.java)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve uses configured SDL2 before available SDL3 when dependency is undetected`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-config-fallback-test").toFile()
        try {
            val artifactDir = File(tempDir, "build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply { writeText("static SDL") }
            val sdl2 = File(runtimeDir, "libSDL2.so").apply { writeSdlRuntime(2) }
            File(runtimeDir, "libSDL3.so").writeSdlRuntime(3)

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
                allowUndetectedSdl = true,
                preferredSdlMajor = 2,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(2)
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl2.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve allows statically linked SDL main library without dynamic SDL dependency`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-static-link-test").toFile()
        try {
            val artifactDir = File(tempDir, "outside-build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime-libs").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("statically linked SDL entry point")
            }
            val sdl = File(runtimeDir, "libSDL3.so").apply { writeSdlRuntime(3) }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
                allowUndetectedSdl = true,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(spec.requiredSdlMajor).isEqualTo(3)
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolve prefers official SDL package and its runtime directory`() {
        val context = appContext()
        val installRoot = File(context.filesDir, "installed-packages")
        val currentAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val officialPackageId = "sdl3"
        val decoyPackageId = "aaa-sdl3-runtime"
        val stateStore = LocalInstallStateStore(context)
        val tempDir = Files.createTempDirectory("sdl-runtime-package-priority-test").toFile()
        val officialRoot = File(installRoot, officialPackageId)
        val decoyRoot = File(installRoot, decoyPackageId)
        try {
            val officialRuntimeDir = File(officialRoot, "lib/$currentAbi").apply { mkdirs() }
            val officialSdl = File(officialRuntimeDir, "libSDL3.so").apply { writeSdlRuntime(3) }
            val officialShared = File(officialRuntimeDir, "libshared.so").apply { writeText("official") }
            val decoyRuntimeDir = File(decoyRoot, "lib/$currentAbi").apply { mkdirs() }
            File(decoyRuntimeDir, "libSDL3.so").writeText("decoy")
            File(decoyRuntimeDir, "libshared.so").writeText("decoy")
            stateStore.setInstalled(
                packageId = officialPackageId,
                platform = Platform.ANDROID,
                version = "3.2.0",
            )
            stateStore.setInstalled(
                packageId = decoyPackageId,
                platform = Platform.ANDROID,
                version = "99.0.0",
            )
            val main = File(tempDir, "libmain.so").apply {
                writeText("NEEDED libSDL3.so libshared.so")
            }

            val result = SdlRuntimeResolver.resolve(
                context = context,
                mainLibraryPath = main.absolutePath,
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(officialSdl.canonicalPath)
            assertThat(spec.preloadLibraryPaths.map { File(it).canonicalPath })
                .contains(officialShared.canonicalPath)
        } finally {
            stateStore.setUninstalled(officialPackageId, Platform.ANDROID)
            stateStore.setUninstalled(decoyPackageId, Platform.ANDROID)
            officialRoot.deleteRecursively()
            decoyRoot.deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries ignores unresolved libraries`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-resolver-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }

            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = emptyMap(),
                neededLibraries = setOf("libSDL3.so", "libSDL3_image.so", "libandroid.so"),
                mainLibrary = main,
                sdlLibrary = sdl
            )

            assertThat(result).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries skips OS provided NDK system libraries`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-ndk-system-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }

            // libmediandk.so 等由 OS 提供，绝不能进 preload(会被复制进私有目录后 dlopen 失败)。
            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = emptyMap(),
                neededLibraries = setOf(
                    "libmediandk.so",
                    "libnativewindow.so",
                    "libvulkan.so",
                    "libaaudio.so",
                    "libcamera2ndk.so",
                    "libc++_shared.so",
                ),
                mainLibrary = main,
                sdlLibrary = sdl
            )

            assertThat(result).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries orders transitive dependencies before dependents`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-transitive-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }
            val image = File(tempDir, "libSDL3_image.so").apply { writeText("image") }
            val codec = File(tempDir, "libcodec.so").apply { writeText("codec") }

            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = mapOf(
                    image.name to image,
                    codec.name to codec,
                ),
                neededLibraries = setOf(sdl.name, image.name),
                mainLibrary = main,
                sdlLibrary = sdl,
                dependencyReader = { library ->
                    if (library.name == image.name) setOf(codec.name) else emptySet()
                },
            )

            assertThat(result)
                .containsExactly(codec.absolutePath, image.absolutePath)
                .inOrder()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveSdlDependencyLibraries includes packaged cxx runtime before SDL`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-own-dependency-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }
            val cxx = File(tempDir, "libc++_shared.so").apply { writeText("cxx") }

            val result = SdlRuntimeResolver.resolveSdlDependencyLibraries(
                runtimeIndex = mapOf(cxx.name to cxx),
                mainLibrary = main,
                sdlLibrary = sdl,
                dependencyReader = { library ->
                    if (library == sdl) setOf(cxx.name, "libandroid.so") else emptySet()
                },
            )

            assertThat(result).containsExactly(cxx.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries does not load a second SDL library for matching soname`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-versioned-soname-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val selectedSdl = File(tempDir, "libSDL3.so.3").apply { writeText("selected") }
            val duplicateSdl = File(tempDir, "libSDL3.so").apply { writeText("duplicate") }

            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = mapOf(duplicateSdl.name to duplicateSdl),
                neededLibraries = setOf(duplicateSdl.name),
                mainLibrary = main,
                sdlLibrary = selectedSdl,
                dependencyReader = { emptySet() },
            )

            assertThat(result).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun appContext(): Context = RuntimeEnvironment.getApplication().applicationContext

    private fun File.writeSdlRuntime(major: Int) {
        val bridgeMarker = when (major) {
            2 -> "org/libsdl2/app/SDLActivity"
            3 -> "org/libsdl/app/SDLActivity"
            else -> error("Unsupported SDL major: $major")
        }
        writeText("SDL$major runtime $bridgeMarker", Charsets.US_ASCII)
    }
}

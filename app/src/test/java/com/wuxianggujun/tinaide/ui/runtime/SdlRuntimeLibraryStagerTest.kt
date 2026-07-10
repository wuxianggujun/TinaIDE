package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class SdlRuntimeLibraryStagerTest {

    @Test
    fun `stage copies only selected public preload libraries into private dir`() {
        val tempRoot = Files.createTempDirectory("sdl-runtime-stage-test").toFile()
        try {
            val publicDir = File(tempRoot, "public-build").apply { mkdirs() }
            val stageRoot = File(tempRoot, "private-stage")
            val mainLibrary = File(publicDir, "libmain.so").apply { writeText("main") }
            val sdlLibrary = File(publicDir, "libSDL3.so").apply { writeText("sdl") }
            val siblingLibrary = File(publicDir, "libhelper.so").apply { writeText("helper") }
            val unrelatedLibrary = File(publicDir, "libunrelated.so").apply { writeText("unrelated") }
            val privateRuntime = File(tempRoot, "app-data/runtime/libc++_shared.so").apply {
                parentFile?.mkdirs()
                writeText("sdl")
            }

            val result = SdlRuntimeLibraryStager.stage(
                sdlLibrary = sdlLibrary,
                mainLibrary = mainLibrary,
                preSdlLibraryPaths = listOf(privateRuntime.absolutePath),
                preloadLibraryPaths = listOf(privateRuntime.absolutePath, siblingLibrary.absolutePath),
                stageRootDir = stageRoot,
                privatePathPrefixes = listOf(File(tempRoot, "app-data").absolutePath)
            )

            assertThat(result).isInstanceOf(SdlRuntimeLibraryStager.StageResult.Success::class.java)
            val success = result as SdlRuntimeLibraryStager.StageResult.Success
            val stagedMain = File(success.runtime.mainLibraryPath)
            val stagedHelper = File(stagedMain.parentFile, siblingLibrary.name)

            assertThat(stagedMain.isFile).isTrue()
            assertThat(stagedMain.readText()).isEqualTo("main")
            assertThat(stagedMain.absolutePath).doesNotContain(publicDir.absolutePath)
            assertThat(File(success.runtime.sdlLibraryPath).readText()).isEqualTo("sdl")

            assertThat(stagedHelper.isFile).isTrue()
            assertThat(stagedHelper.readText()).isEqualTo("helper")
            assertThat(File(stagedMain.parentFile, unrelatedLibrary.name).exists()).isFalse()

            assertThat(success.runtime.preSdlLibraryPaths).containsExactly(privateRuntime.absolutePath)
            assertThat(success.runtime.preloadLibraryPaths).doesNotContain(privateRuntime.absolutePath)
            assertThat(success.runtime.preloadLibraryPaths).contains(stagedHelper.absolutePath)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `stage reports actual copy failure instead of prechecking main library`() {
        val tempRoot = Files.createTempDirectory("sdl-runtime-stage-missing-test").toFile()
        try {
            val missingMainLibrary = File(tempRoot, "missing/libmain.so")
            val sdlLibrary = File(tempRoot, "libSDL3.so").apply { writeText("sdl") }

            val result = SdlRuntimeLibraryStager.stage(
                sdlLibrary = sdlLibrary,
                mainLibrary = missingMainLibrary,
                preSdlLibraryPaths = emptyList(),
                preloadLibraryPaths = emptyList(),
                stageRootDir = File(tempRoot, "private-stage"),
                privatePathPrefixes = emptyList()
            )

            assertThat(result).isInstanceOf(SdlRuntimeLibraryStager.StageResult.Error::class.java)
            val error = result as SdlRuntimeLibraryStager.StageResult.Error
            assertThat(error.throwable).isNotNull()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `stage rejects same filename from private and public runtime sources`() {
        val tempRoot = Files.createTempDirectory("sdl-runtime-stage-conflict-test").toFile()
        try {
            val publicDir = File(tempRoot, "public-build").apply { mkdirs() }
            val privateDir = File(tempRoot, "app-data/runtime").apply { mkdirs() }
            val mainLibrary = File(publicDir, "libmain.so").apply { writeText("main") }
            val sdlLibrary = File(publicDir, "libSDL3.so").apply { writeText("sdl") }
            val privateDependency = File(privateDir, "libduplicate.so").apply { writeText("private") }
            val publicDependency = File(publicDir, "libduplicate.so").apply { writeText("public") }

            val result = SdlRuntimeLibraryStager.stage(
                sdlLibrary = sdlLibrary,
                mainLibrary = mainLibrary,
                preSdlLibraryPaths = listOf(privateDependency.absolutePath),
                preloadLibraryPaths = listOf(publicDependency.absolutePath),
                stageRootDir = File(tempRoot, "private-stage"),
                privatePathPrefixes = listOf(File(tempRoot, "app-data").absolutePath)
            )

            assertThat(result).isInstanceOf(SdlRuntimeLibraryStager.StageResult.Error::class.java)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}

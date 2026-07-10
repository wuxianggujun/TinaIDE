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
            val siblingLibrary = File(publicDir, "libhelper.so").apply { writeText("helper") }
            val unrelatedLibrary = File(publicDir, "libunrelated.so").apply { writeText("unrelated") }
            val privateRuntime = File(tempRoot, "app-data/runtime/libSDL3.so").apply {
                parentFile?.mkdirs()
                writeText("sdl")
            }

            val result = SdlRuntimeLibraryStager.stage(
                mainLibrary = mainLibrary,
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

            assertThat(stagedHelper.isFile).isTrue()
            assertThat(stagedHelper.readText()).isEqualTo("helper")
            assertThat(File(stagedMain.parentFile, unrelatedLibrary.name).exists()).isFalse()

            assertThat(success.runtime.preloadLibraryPaths).contains(privateRuntime.absolutePath)
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

            val result = SdlRuntimeLibraryStager.stage(
                mainLibrary = missingMainLibrary,
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
}

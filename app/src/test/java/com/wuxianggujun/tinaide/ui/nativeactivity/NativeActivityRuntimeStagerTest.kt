package com.wuxianggujun.tinaide.ui.nativeactivity

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeActivityRuntimeStagerTest {

    @Test
    fun `stage preserves dependency order and clears stale runtime files`() {
        val tempRoot = Files.createTempDirectory("native-activity-stage-test").toFile()
        try {
            val buildDir = File(tempRoot, "build").apply { mkdirs() }
            val stageRoot = File(tempRoot, "stage")
            val firstDependency = File(buildDir, "libfirst.so").apply { writeText("first") }
            val secondDependency = File(buildDir, "libsecond.so").apply { writeText("second") }
            val mainLibrary = File(buildDir, "libmain.so").apply { writeText("main-v1") }
            val spec = NativeActivityRuntimeResolver.RuntimeSpec(
                mainLibrary = mainLibrary,
                dependencyLibraries = listOf(firstDependency, secondDependency),
            )

            val firstResult = NativeActivityRuntimeStager.stage(stageRoot, spec)
                as NativeActivityRuntimeStager.StageResult.Success
            val firstRuntimeDir = File(firstResult.runtime.mainLibraryPath).parentFile
            File(firstRuntimeDir, "libstale.so").writeText("stale")
            mainLibrary.writeText("main-v2")

            val secondResult = NativeActivityRuntimeStager.stage(stageRoot, spec)
                as NativeActivityRuntimeStager.StageResult.Success

            assertThat(File(secondResult.runtime.mainLibraryPath).readText()).isEqualTo("main-v2")
            assertThat(File(firstRuntimeDir, "libstale.so").exists()).isFalse()
            assertThat(secondResult.runtime.dependencyLibraryPaths.map { File(it).name })
                .containsExactly("libfirst.so", "libsecond.so")
                .inOrder()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `stage rejects different libraries with the same filename`() {
        val tempRoot = Files.createTempDirectory("native-activity-stage-conflict-test").toFile()
        try {
            val firstDir = File(tempRoot, "first").apply { mkdirs() }
            val secondDir = File(tempRoot, "second").apply { mkdirs() }
            val mainLibrary = File(tempRoot, "libmain.so").apply { writeText("main") }
            val firstDependency = File(firstDir, "libduplicate.so").apply { writeText("first") }
            val secondDependency = File(secondDir, "libduplicate.so").apply { writeText("second") }

            val result = NativeActivityRuntimeStager.stage(
                stageRoot = File(tempRoot, "stage"),
                spec = NativeActivityRuntimeResolver.RuntimeSpec(
                    mainLibrary = mainLibrary,
                    dependencyLibraries = listOf(firstDependency, secondDependency),
                ),
            )

            assertThat(result).isInstanceOf(NativeActivityRuntimeStager.StageResult.Error::class.java)
            val error = result as NativeActivityRuntimeStager.StageResult.Error
            assertThat(error.message).contains("libduplicate.so")
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}

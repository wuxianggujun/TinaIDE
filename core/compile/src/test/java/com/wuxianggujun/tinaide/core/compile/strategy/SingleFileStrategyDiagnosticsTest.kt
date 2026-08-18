package com.wuxianggujun.tinaide.core.compile.strategy

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.AndroidCppRuntimeLinkage
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class SingleFileStrategyDiagnosticsTest {

    @Test
    fun `buildArtifactMissingDiagnostic includes launcher and filesystem summary`() {
        val root = createTempDirectory(prefix = "single-file-diag-").toFile()
        try {
            val projectRoot = File(root, "project").apply { mkdirs() }
            val buildDir = File(root, "workspace/build").apply { mkdirs() }
            File(buildDir, "leftover.o").writeText("obj")
            val output = File(buildDir, "main")

            val diagnostic = SingleFileStrategy.buildArtifactMissingDiagnostic(
                buildDir = buildDir,
                outputFile = output,
                workingDir = projectRoot,
                command = listOf(
                    "/data/user/0/com.example/files/toolchains/builtin/bin/clang++",
                    File(projectRoot, "main.cpp").absolutePath,
                    "-o",
                    output.absolutePath,
                ),
                rawOutput = "clang first line\nclang second line",
                preferLinker64 = true,
            )

            assertThat(diagnostic).startsWith("Single-file artifact missing diag: preferLinker64=true")
            assertThat(diagnostic).contains("launchMode=linker64")
            assertThat(diagnostic).contains("buildDir={path=${buildDir.absolutePath}")
            assertThat(diagnostic).contains("output={path=${output.absolutePath}")
            assertThat(diagnostic).contains("outputParentChildren=leftover.o")
            assertThat(diagnostic).contains("stdoutFirstLine=clang first line")
            assertThat(diagnostic).contains("fullCommand=/system/bin/linker64")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `single file C++ standard override remains the last std flag`() {
        val flags = SingleFileStrategy.mergeCompileFlagsWithStandard(
            isCpp = true,
            standard = "c++20",
            extraCompileFlags = listOf("-Wall", "-std=c++17"),
        )

        assertThat(flags.filter { it.startsWith("-std=") }.last()).isEqualTo("-std=c++20")
    }

    @Test
    fun `single file C flags may override the default C standard`() {
        val flags = SingleFileStrategy.mergeCompileFlagsWithStandard(
            isCpp = false,
            standard = "c11",
            extraCompileFlags = listOf("-std=gnu11"),
        )

        assertThat(flags.filter { it.startsWith("-std=") }.last()).isEqualTo("-std=gnu11")
    }

    @Test
    fun `single file C++ executable uses portable static runtime`() {
        val flags = AndroidCppRuntimeLinkage.flagsForOutput(
            isCpp = true,
            outputIsSharedLibrary = false,
        )

        assertThat(flags).containsExactly("-static-libstdc++")
    }
}

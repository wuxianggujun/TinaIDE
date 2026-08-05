package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class BuildProcessRunnerTest {

    @Test
    fun `run returns timedOut and terminates process`() = runBlocking {
        val command = timeoutCommand()
        assumeTrue("No supported shell for timeout test", command.isNotEmpty())

        val result = BuildProcessRunner.run(
            processBuilder = ProcessBuilder(command).redirectErrorStream(true),
            commandLabel = "timeout-test",
            timeoutMs = 250L,
            forceKillGraceMs = 100L,
            readerJoinTimeoutMs = 500L,
        )

        assertThat(result.timedOut).isTrue()
        assertThat(result.exitCode).isEqualTo(-1)
    }

    @Test
    fun `run writes stdin and captures output`() = runBlocking {
        val command = stdinEchoCommand()
        assumeTrue("No supported shell for stdin test", command.isNotEmpty())

        val result = BuildProcessRunner.run(
            processBuilder = ProcessBuilder(command).redirectErrorStream(true),
            commandLabel = "stdin-test",
            timeoutMs = 2_000L,
            stdin = "formatter input",
        )

        assertThat(result.timedOut).isFalse()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).contains("formatter input")
    }

    @Test
    fun `run times out when child does not consume stdin`() = runBlocking {
        val command = timeoutCommand()
        assumeTrue("No supported shell for stdin timeout test", command.isNotEmpty())

        val result = BuildProcessRunner.run(
            processBuilder = ProcessBuilder(command).redirectErrorStream(true),
            commandLabel = "stdin-timeout-test",
            timeoutMs = 250L,
            stdin = "x".repeat(1_000_000),
            forceKillGraceMs = 100L,
            readerJoinTimeoutMs = 500L,
        )

        assertThat(result.timedOut).isTrue()
        assertThat(result.exitCode).isEqualTo(-1)
    }

    @Test
    fun `command temp dir is removed safely`() {
        val cacheDir = Files.createTempDirectory("build-cache").toFile()
        val tempDir = BuildResourceCleaner.createCommandTempDir(cacheDir, "unit-test")
        File(tempDir, "marker.txt").writeText("ok")

        assertThat(tempDir.isDirectory).isTrue()
        assertThat(BuildResourceCleaner.cleanupCommandTempDir(tempDir)).isTrue()
        assertThat(tempDir.exists()).isFalse()
    }

    @Test
    fun `stale command temp dirs are pruned`() {
        val cacheDir = Files.createTempDirectory("build-cache").toFile()
        val root = File(cacheDir, "tina-build-tmp").apply { mkdirs() }
        val oldDir = File(root, "old").apply {
            mkdirs()
            setLastModified(1_000L)
        }
        val freshDir = File(root, "fresh").apply {
            mkdirs()
            setLastModified(19_000L)
        }

        val summary = BuildResourceCleaner.pruneStaleCommandTempDirs(
            cacheDir = cacheDir,
            nowMs = 20_000L,
            maxAgeMs = 5_000L,
            maxRetainedDirs = 10,
        )

        assertThat(summary.deletedCount).isEqualTo(1)
        assertThat(summary.failedCount).isEqualTo(0)
        assertThat(oldDir.exists()).isFalse()
        assertThat(freshDir.exists()).isTrue()
    }

    private fun timeoutCommand(): List<String> {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return if (osName.contains("windows")) {
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "Write-Output start; Start-Sleep -Seconds 5; Write-Output end",
            )
        } else {
            listOf("sh", "-c", "printf 'start\\n'; sleep 5; printf 'end\\n'")
        }
    }

    private fun stdinEchoCommand(): List<String> {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return if (osName.contains("windows")) {
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "[Console]::Out.Write([Console]::In.ReadToEnd())",
            )
        } else {
            listOf("sh", "-c", "cat")
        }
    }
}

package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import kotlinx.coroutines.runBlocking
import org.junit.Test

class GuestSystemPackageManagerTest {

    @Test
    fun isSafePackageArgument_acceptsNamesArchitecturesAndVersions() {
        assertThat(GuestSystemPackageManager.isSafePackageArgument("python3")).isTrue()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("libc6:arm64")).isTrue()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("python3=3.12.1-r0")).isTrue()
    }

    @Test
    fun isSafePackageArgument_rejectsOptionsPathsAndWhitespace() {
        assertThat(GuestSystemPackageManager.isSafePackageArgument("--root=/tmp/rootfs")).isFalse()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("../package")).isFalse()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("package other")).isFalse()
    }

    @Test
    fun packageExists_acceptsApkVirtualPackageProvider() = runBlocking {
        val environment = FakeLinuxEnvironment(
            results = mapOf(
                "clang-extra-tools" to executionResult(
                    exitCode = 0,
                    stdout = "clang21-extra-tools-21.1.2-r2\n",
                ),
                "empty-result" to executionResult(exitCode = 0, stdout = "\n"),
                "missing-package" to executionResult(exitCode = 1),
            )
        )

        assertThat(
            GuestSystemPackageManager.packageExists(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
                packageName = "clang-extra-tools",
            )
        ).isTrue()
        assertThat(
            GuestSystemPackageManager.packageExists(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
                packageName = "empty-result",
            )
        ).isFalse()
        assertThat(
            GuestSystemPackageManager.packageExists(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
                packageName = "missing-package",
            )
        ).isFalse()
        assertThat(environment.commands).containsExactly(
            listOf("/sbin/apk", "search", "-x", "clang-extra-tools"),
            listOf("/sbin/apk", "search", "-x", "empty-result"),
            listOf("/sbin/apk", "search", "-x", "missing-package"),
        ).inOrder()
    }

    private class FakeLinuxEnvironment(
        private val results: Map<String, LinuxExecutionResult>,
    ) : LinuxEnvironment {
        val commands = mutableListOf<List<String>>()

        override fun isAvailable(): Boolean = true

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            commands += command
            return results[command.last()] ?: executionResult(exitCode = 1)
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess = error("Interactive process is not needed in this test")

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    companion object {
        private fun executionResult(
            exitCode: Int,
            stdout: String = "",
        ): LinuxExecutionResult = LinuxExecutionResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = "",
            durationMs = 1L,
        )
    }
}

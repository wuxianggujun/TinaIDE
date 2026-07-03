package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LinuxDistroRootfsBootstrapperTest {

    @Test
    fun bootstrap_shouldInstallMissingAptBootstrapPackages() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                availableCommands = mutableSetOf("apt-get", "apt-cache", "tar"),
                existingPackages = setOf("bash", "curl", "xz-utils", "file", "ca-certificates", "proot"),
                packageCommands = mapOf(
                    "bash" to listOf("bash"),
                    "curl" to listOf("curl"),
                    "xz-utils" to listOf("xz"),
                    "file" to listOf("file"),
                    "ca-certificates" to listOf("update-ca-certificates"),
                    "proot" to listOf("proot"),
                ),
            )

            val progressEvents = mutableListOf<LinuxDistroRootfsBootstrapProgress>()
            val result = LinuxDistroRootfsBootstrapper().bootstrap(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APT,
            ) { progress ->
                progressEvents += progress
            }

            assertThat(progressEvents.map { it.phase }).contains(LinuxDistroRootfsBootstrapPhase.UPDATING_INDEX)
            assertThat(progressEvents.map { it.phase }).contains(LinuxDistroRootfsBootstrapPhase.INSTALLING_PACKAGES)
            assertThat(progressEvents.last().phase).isEqualTo(LinuxDistroRootfsBootstrapPhase.COMPLETED)
            assertThat(progressEvents.last().progress).isEqualTo(1f)
            assertThat(result.installedPackages).containsExactly(
                "bash",
                "curl",
                "xz-utils",
                "file",
                "ca-certificates",
                "proot",
            ).inOrder()
            val installCommand = environment.commands.single { command ->
                command.argv.take(3) == listOf("apt-get", "install", "-y")
            }
            assertThat(installCommand.env).containsEntry("DEBIAN_FRONTEND", "noninteractive")
        }
    }

    @Test
    fun bootstrap_shouldSkipUnavailableOptionalPackages() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                availableCommands = mutableSetOf("/sbin/apk", "bash", "curl", "tar", "xz", "file", "update-ca-certificates"),
                existingPackages = emptySet(),
                packageCommands = emptyMap(),
            )

            val result = LinuxDistroRootfsBootstrapper().bootstrap(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
            )

            assertThat(result.installedPackages).isEmpty()
            assertThat(result.skippedOptionalGroups).containsExactly("proot")
        }
    }

    @Test
    fun bootstrap_shouldFailWhenPackageManagerCommandsAreMissing() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                availableCommands = mutableSetOf("bash", "curl", "tar", "xz", "file", "update-ca-certificates"),
                existingPackages = setOf("bash"),
                packageCommands = emptyMap(),
            )

            val failure = runCatching {
                LinuxDistroRootfsBootstrapper().bootstrap(
                    linuxEnvironment = environment,
                    packageManager = RootfsPackageManager.APT,
                )
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("apt-get")
            assertThat(failure).hasMessageThat().contains("apt-cache")
        }
    }

    @Test
    fun packageExists_shouldUseApkExactSearchResult() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                availableCommands = mutableSetOf<String>(),
                existingPackages = setOf("bash"),
                packageCommands = emptyMap(),
            )

            val exists = GuestSystemPackageManager.packageExists(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
                packageName = "bash",
            )

            assertThat(exists).isTrue()
        }
    }

    private data class RecordedCommand(
        val argv: List<String>,
        val env: Map<String, String>,
    )

    private class FakeLinuxEnvironment(
        private val availableCommands: MutableSet<String>,
        private val existingPackages: Set<String>,
        private val packageCommands: Map<String, List<String>>,
    ) : LinuxEnvironment {
        val commands = mutableListOf<RecordedCommand>()

        override fun isAvailable(): Boolean = true

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            commands += RecordedCommand(command, env)
            return when {
                command.isExecutablePathProbe() -> commandAvailability(command.extractExecutablePathProbeName())
                command.isCommandProbe() -> commandAvailability(command.extractCommandProbeName())
                command.isAptPackageProbe() -> packageAvailability(command.extractAptPackageName())
                command.take(3) == listOf("/sbin/apk", "search", "-x") -> apkSearch(command.last())
                command.take(2) == listOf("apt-get", "update") -> success()
                command.take(3) == listOf("/sbin/apk", "update") -> success()
                command.take(3) == listOf("apt-get", "install", "-y") -> install(command.drop(3))
                command.take(3) == listOf("/sbin/apk", "add", "--no-cache") -> install(command.drop(3))
                else -> success()
            }
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess = error("Interactive process is not needed in this test")

        override fun toGuestPath(hostPath: String): String = hostPath

        private fun install(packages: List<String>): LinuxExecutionResult {
            packages.forEach { pkg -> availableCommands += packageCommands[pkg].orEmpty() }
            return success()
        }

        private fun commandAvailability(command: String): LinuxExecutionResult = if (command in availableCommands) success() else failure()

        private fun packageAvailability(packageName: String): LinuxExecutionResult = if (packageName in existingPackages) success() else failure()

        private fun apkSearch(packageName: String): LinuxExecutionResult = if (packageName in existingPackages) {
            success(stdout = "$packageName-1.0-r0")
        } else {
            success(stdout = "")
        }

        private fun success(stdout: String = ""): LinuxExecutionResult = LinuxExecutionResult(
            exitCode = 0,
            stdout = stdout,
            stderr = "",
            durationMs = 1L,
        )

        private fun failure(): LinuxExecutionResult = LinuxExecutionResult(
            exitCode = 1,
            stdout = "",
            stderr = "not found",
            durationMs = 1L,
        )
    }
}

private fun List<String>.isExecutablePathProbe(): Boolean = size >= 3 && this[0] == "/bin/sh" && this[1] == "-lc" && this[2].startsWith("[ -x '")

private fun List<String>.extractExecutablePathProbeName(): String = Regex("\\[ -x '([^']+)' ]").find(this[2])?.groupValues?.get(1).orEmpty()

private fun List<String>.isCommandProbe(): Boolean = size >= 3 && this[0] == "/bin/sh" && this[1] == "-lc" && this[2].contains("command -v")

private fun List<String>.extractCommandProbeName(): String = Regex("command -v '([^']+)'").find(this[2])?.groupValues?.get(1).orEmpty()

private fun List<String>.isAptPackageProbe(): Boolean = size >= 3 && this[0] == "/bin/sh" && this[1] == "-lc" && this[2].startsWith("apt-cache show ")

private fun List<String>.extractAptPackageName(): String = Regex("apt-cache show '([^']+)'").find(this[2])?.groupValues?.get(1).orEmpty()

package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LinuxDistroRootfsHealthCheckerTest {

    @Test
    fun check_shouldPassRequiredAptSelfChecksAndKeepOptionalProotNonBlocking() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                available = true,
                availableCommands = setOf(
                    "apt-get",
                    "apt-cache",
                    "bash",
                    "curl",
                    "tar",
                    "xz",
                    "file",
                    "update-ca-certificates",
                ),
            )

            val report = LinuxDistroRootfsHealthChecker().check(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APT,
            )

            assertThat(report.isUsable).isTrue()
            assertThat(report.allChecksPassed).isFalse()
            assertThat(report.architecture).isEqualTo("aarch64")
            assertThat(report.osRelease["PRETTY_NAME"]).isEqualTo("Ubuntu 24.04.4 LTS")
            assertThat(report.check(LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_VERSION).output)
                .contains("apt")
            assertThat(report.check(LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS).missingItems)
                .containsExactly("proot")
        }
    }

    @Test
    fun check_shouldFailWhenRequiredBootstrapCommandIsMissing() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                available = true,
                availableCommands = setOf(
                    "apt-get",
                    "apt-cache",
                    "bash",
                    "tar",
                    "xz",
                    "file",
                    "update-ca-certificates",
                ),
            )

            val report = LinuxDistroRootfsHealthChecker().check(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APT,
            )

            assertThat(report.isUsable).isFalse()
            assertThat(report.requiredFailures.map { check -> check.probe })
                .contains(LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS)
            assertThat(report.check(LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS).missingItems)
                .containsExactly("curl")
        }
    }

    @Test
    fun check_shouldFailWhenBootstrapSpecIsMissingForSupportedPackageManager() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                available = true,
                availableCommands = setOf("pacman"),
            )

            val report = LinuxDistroRootfsHealthChecker().check(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.PACMAN,
            )

            assertThat(report.isUsable).isFalse()
            assertThat(report.check(LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS).missingItems)
                .containsExactly("PACMAN")
        }
    }

    @Test
    fun check_shouldStopWhenRootfsIsUnavailable() {
        runBlocking {
            val environment = FakeLinuxEnvironment(
                available = false,
                availableCommands = emptySet(),
            )

            val report = LinuxDistroRootfsHealthChecker().check(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APT,
            )

            assertThat(report.isUsable).isFalse()
            assertThat(report.checks).hasSize(1)
            assertThat(report.check(LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE).passed).isFalse()
            assertThat(environment.commands).isEmpty()
        }
    }

    @Test
    fun toHealthSummary_shouldKeepUsableReportWhenOnlyOptionalItemsAreMissing() {
        val report = LinuxDistroRootfsHealthReport(
            packageManager = RootfsPackageManager.APT,
            checks = listOf(
                LinuxDistroRootfsHealthCheck(
                    probe = LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE,
                    passed = true,
                    required = true,
                ),
                LinuxDistroRootfsHealthCheck(
                    probe = LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS,
                    passed = false,
                    required = false,
                    checkedItems = listOf("proot"),
                    missingItems = listOf("proot"),
                ),
            ),
            architecture = "aarch64",
            osRelease = mapOf("PRETTY_NAME" to "Ubuntu 24.04.4 LTS"),
        )

        val summary = report.toHealthSummary { probe -> "probe:${probe.name}" }

        assertThat(summary.level).isEqualTo(LinuxDistroRootfsHealthLevel.ATTENTION)
        assertThat(summary.requiredMissingItems).isEmpty()
        assertThat(summary.optionalMissingItems).containsExactly("proot")
        assertThat(summary.identity).isEqualTo("Ubuntu 24.04.4 LTS · aarch64")
    }

    @Test
    fun toHealthSummary_shouldUseProbeLabelWhenRequiredCheckHasNoItems() {
        val report = LinuxDistroRootfsHealthReport(
            packageManager = RootfsPackageManager.APT,
            checks = listOf(
                LinuxDistroRootfsHealthCheck(
                    probe = LinuxDistroRootfsHealthProbe.OS_RELEASE,
                    passed = false,
                    required = true,
                ),
            ),
        )

        val summary = report.toHealthSummary { probe -> "label:${probe.name}" }

        assertThat(summary.level).isEqualTo(LinuxDistroRootfsHealthLevel.UNAVAILABLE)
        assertThat(summary.requiredMissingItems).containsExactly("label:OS_RELEASE")
        assertThat(summary.optionalMissingItems).isEmpty()
        assertThat(summary.identity).isEmpty()
    }
    private fun LinuxDistroRootfsHealthReport.check(
        probe: LinuxDistroRootfsHealthProbe,
    ): LinuxDistroRootfsHealthCheck = checks.single { check -> check.probe == probe }

    private class FakeLinuxEnvironment(
        private val available: Boolean,
        private val availableCommands: Set<String>,
    ) : LinuxEnvironment {
        val commands = mutableListOf<List<String>>()

        override fun isAvailable(): Boolean = available

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            commands += command
            return when {
                command.isExecutablePathProbe() -> commandAvailability(command.extractExecutablePathProbeName())
                command.isCommandProbe() -> commandAvailability(command.extractCommandProbeName())
                command == listOf("apt-get", "--version") -> commandVersion("apt-get", "apt 2.8.3")
                command == listOf("pacman", "--version") -> commandVersion("pacman", "Pacman v6.1.0")
                command == listOf("uname", "-m") -> success(stdout = "aarch64\n")
                command == listOf("/bin/sh", "-lc", "cat /etc/os-release") -> success(
                    stdout = """
                        NAME="Ubuntu"
                        PRETTY_NAME="Ubuntu 24.04.4 LTS"
                    """.trimIndent()
                )
                else -> failure()
            }
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess = error("Interactive process is not needed in this test")

        override fun toGuestPath(hostPath: String): String = hostPath

        private fun commandAvailability(command: String): LinuxExecutionResult = if (command in availableCommands) success() else failure()

        private fun commandVersion(command: String, version: String): LinuxExecutionResult = if (command in availableCommands) success(stdout = version) else failure()

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

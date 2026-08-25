package com.wuxianggujun.tinaide.plugin.lsp

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthCheck
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthProbe
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthReport
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.PluginManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
class LspToolchainInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `manifest should decode lsp servers and system package manager overrides`() {
        val manifest = JsonSerializer.decode<PluginManifest>(
            """
            {
              "id": "tinaide.lsp.python",
              "name": "Python Language Support",
              "version": "1.0.0",
              "type": "lsp",
              "contributions": {
                "languageServers": [
                  {
                    "id": "pylsp",
                    "name": "Python Language Server",
                    "languages": ["python"],
                    "fileExtensions": ["py"],
                    "runtime": { "type": "python", "minVersion": "3.8" },
                    "server": { "type": "stdio", "command": "pylsp" }
                  }
                ],
                "toolchains": [
                  {
                    "id": "python3",
                    "name": "Python 3",
                    "type": "system",
                    "packagesByManager": {
                      "apk": ["python3", "py3-pip"],
                      "apt": ["python3", "python3-pip"]
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val contributions = requireNotNull(manifest.contributions)
        assertThat(contributions.languageServers).hasSize(1)
        assertThat(contributions.languageServers?.single()?.server?.command).isEqualTo("pylsp")
        assertThat(contributions.toolchains).hasSize(1)
        assertThat(contributions.toolchains?.single()?.type).isEqualTo("system")
        assertThat(contributions.toolchains?.single()?.packagesByManager?.get("apt"))
            .containsExactly("python3", "python3-pip")
            .inOrder()
    }

    @Test
    fun `system install should use current apt package override`() = runBlocking {
        val environment = RecordingLinuxEnvironment()
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.APT },
        )

        val result = installer.install(
            config = LspToolchainConfig(
                id = "python3",
                name = "Python 3",
                type = "system",
                packages = listOf("fallback-python"),
                packagesByManager = mapOf(
                    "apk" to listOf("python3", "py3-pip"),
                    "apt" to listOf("python3", "python3-pip"),
                ),
                verifyCommand = "python3 --version",
            ),
            progress = {},
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(environment.commands.map { it.command })
            .containsExactly(
                listOf("apt-get", "update"),
                listOf("apt-get", "install", "-y", "python3", "python3-pip"),
                listOf("/bin/sh", "-c", "python3 --version"),
            )
            .inOrder()
    }

    @Test
    fun `system install should fall back to generic packages when manager override is absent`() = runBlocking {
        val environment = RecordingLinuxEnvironment()
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.DNF },
        )

        val result = installer.install(
            config = LspToolchainConfig(
                id = "nodejs",
                name = "Node.js",
                type = "system",
                packages = listOf("nodejs", "npm"),
                packagesByManager = mapOf("apt" to listOf("nodejs", "npm")),
                verifyCommand = "node --version",
            ),
            progress = {},
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(environment.commands.map { it.command })
            .containsExactly(
                listOf("dnf", "makecache"),
                listOf("dnf", "install", "-y", "nodejs", "npm"),
                listOf("/bin/sh", "-c", "node --version"),
            )
            .inOrder()
    }

    @Test
    fun `system install should fail when active package manager is unknown`() = runBlocking {
        val environment = RecordingLinuxEnvironment()
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.UNKNOWN },
        )

        val result = installer.install(
            config = LspToolchainConfig(
                id = "python3",
                name = "Python 3",
                type = "system",
                packages = listOf("python3"),
            ),
            progress = {},
        )

        assertThat(result.isFailure).isTrue()
        assertThat(environment.commands).isEmpty()
    }

    @Test
    fun `install should stop when linux distro health has required failures`() = runBlocking {
        val environment = RecordingLinuxEnvironment()
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.APT },
            linuxHealthReportProvider = {
                LinuxDistroRootfsHealthReport(
                    packageManager = RootfsPackageManager.APT,
                    checks = listOf(
                        LinuxDistroRootfsHealthCheck(
                            probe = LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE,
                            passed = false,
                            required = true,
                        )
                    ),
                )
            },
        )

        val result = installer.install(
            config = LspToolchainConfig(
                id = "python3",
                name = "Python 3",
                type = "system",
                packages = listOf("python3"),
            ),
            progress = {},
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(environment.commands).isEmpty()
    }

    @Test
    fun `legacy package manager toolchain types should be rejected`() = runBlocking {
        val environment = RecordingLinuxEnvironment()
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.APT },
        )

        val result = installer.install(
            config = LspToolchainConfig(
                id = "python3",
                name = "Python 3",
                type = "apt",
                packages = listOf("python3"),
            ),
            progress = {},
        )

        assertThat(result.isFailure).isTrue()
        assertThat(environment.commands).isEmpty()
    }

    @Test
    fun `install should preserve coroutine cancellation`() = runBlocking {
        val environment = RecordingLinuxEnvironment(CancellationException("cancelled"))
        val installer = LspToolchainInstaller(
            context = context,
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            packageManagerResolver = { RootfsPackageManager.APT },
        )

        val error = runCatching {
            installer.install(
                config = LspToolchainConfig(
                    id = "python3",
                    name = "Python 3",
                    type = "system",
                    packages = listOf("python3"),
                    verifyCommand = "python3 --version",
                ),
                progress = {},
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `download transaction should publish staged toolchain`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("opt/toolchain")
        val transaction = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "publish",
            unmanagedTargetMessage = "occupied",
        )
        val stagingDir = transaction.createStagingDirectory()
        stagingDir.resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("new")
        }

        transaction.publish()

        assertThat(targetDir.resolve("bin/server").readText()).isEqualTo("new")
        assertThat(targetDir.resolve(LspDownloadInstallTransaction.OWNER_MARKER_NAME).readText())
            .isEqualTo("toolchain")
        assertThat(transaction.commit()).isTrue()
        assertThat(targetDir.resolve(LspDownloadInstallTransaction.PENDING_MARKER_NAME).exists()).isFalse()
    }

    @Test
    fun `download transaction should restore managed target on rollback`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("opt/toolchain").apply { mkdirs() }
        targetDir.resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("old")
        }
        targetDir.resolve(LspDownloadInstallTransaction.OWNER_MARKER_NAME).writeText("toolchain")
        val transaction = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "rollback",
            unmanagedTargetMessage = "occupied",
        )
        transaction.createStagingDirectory().resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("new")
        }

        transaction.publish()
        transaction.rollback()

        assertThat(targetDir.resolve("bin/server").readText()).isEqualTo("old")
    }

    @Test
    fun `download transaction should reject unmanaged nonempty target`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("usr/local/bin").apply { mkdirs() }
        targetDir.resolve("unrelated").writeText("keep")
        val transaction = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "reject",
            unmanagedTargetMessage = "occupied",
        )

        val error = try {
            runCatching { transaction.createStagingDirectory() }.exceptionOrNull()
        } finally {
            transaction.cleanup()
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("occupied")
        assertThat(targetDir.resolve("unrelated").readText()).isEqualTo("keep")
    }

    @Test
    fun `download transaction should recover old target after interrupted upgrade`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("opt/interrupted-upgrade").apply { mkdirs() }
        targetDir.resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("old")
        }
        targetDir.resolve(LspDownloadInstallTransaction.OWNER_MARKER_NAME).writeText("toolchain")
        val interrupted = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "interrupted",
            unmanagedTargetMessage = "occupied",
        )
        interrupted.createStagingDirectory().resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("unverified")
        }
        interrupted.publish()
        interrupted.cleanup()

        val recovery = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "recovery",
            unmanagedTargetMessage = "occupied",
        )
        try {
            recovery.createStagingDirectory()

            assertThat(targetDir.resolve("bin/server").readText()).isEqualTo("old")
            assertThat(targetDir.resolve(LspDownloadInstallTransaction.PENDING_MARKER_NAME).exists()).isFalse()
        } finally {
            recovery.cleanup()
        }
    }

    @Test
    fun `download transaction should restore backup from matching operation`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("opt/matching-upgrade").apply { mkdirs() }
        targetDir.resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("old")
        }
        targetDir.resolve(LspDownloadInstallTransaction.OWNER_MARKER_NAME).writeText("toolchain")
        val interrupted = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "interrupted",
            unmanagedTargetMessage = "occupied",
        )
        interrupted.createStagingDirectory().resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("unverified")
        }
        interrupted.publish()

        val unrelatedBackup = targetDir.parentFile.resolve(".${targetDir.name}.unrelated.backup").apply {
            resolve("bin/server").apply {
                parentFile.mkdirs()
                writeText("unrelated")
            }
            resolve(LspDownloadInstallTransaction.OWNER_MARKER_NAME).writeText("toolchain")
            setLastModified(System.currentTimeMillis() + 60_000L)
        }
        interrupted.cleanup()

        val recovery = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "recovery",
            unmanagedTargetMessage = "occupied",
        )
        try {
            recovery.createStagingDirectory()

            assertThat(targetDir.resolve("bin/server").readText()).isEqualTo("old")
            assertThat(unrelatedBackup.exists()).isFalse()
        } finally {
            recovery.cleanup()
        }
    }

    @Test
    fun `download transaction should remove interrupted first install`() = runBlocking {
        val targetDir = temporaryFolder.root.resolve("opt/interrupted-first-install")
        val interrupted = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "interrupted-first",
            unmanagedTargetMessage = "occupied",
        )
        interrupted.createStagingDirectory().resolve("bin/server").apply {
            parentFile.mkdirs()
            writeText("unverified")
        }
        interrupted.publish()
        interrupted.cleanup()

        val recovery = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = "toolchain",
            operationId = "recovery-first",
            unmanagedTargetMessage = "occupied",
        )
        try {
            recovery.createStagingDirectory()

            assertThat(targetDir.exists()).isFalse()
        } finally {
            recovery.cleanup()
        }
    }

    private class StaticLinuxEnvironmentProvider(
        private val environment: LinuxEnvironment,
    ) : LinuxEnvironmentProvider {
        override fun get(): LinuxEnvironment = environment
    }

    private data class RecordedCommand(
        val command: List<String>,
        val workDir: String,
        val env: Map<String, String>,
        val timeout: Long?,
        val stdin: String?,
    )

    private class RecordingLinuxEnvironment(
        private val executionFailure: Throwable? = null,
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
            executionFailure?.let { throw it }
            commands += RecordedCommand(command, workDir, env, timeout, stdin)
            return LinuxExecutionResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = 1,
            )
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess {
            error("interactive process is not used by this test")
        }

        override fun toGuestPath(hostPath: String): String = hostPath
    }
}

package com.wuxianggujun.tinaide.plugin.lsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

class PluginLspConnectionProviderTest {

    @After
    fun tearDown() {
        PluginLspSessionRegistry.closeAll()
    }

    @Test
    fun `start should reject socket and websocket before resolving linux environment`() {
        listOf(" Socket ", " WebSocket ").forEach { transport ->
            val provider = PluginLspConnectionProvider(
                config = lspServerConfig(type = transport),
                workingDir = "/workspace",
                projectRoot = "/workspace",
                linuxEnvironmentProvider = object : LinuxEnvironmentProvider {
                    override fun get(): LinuxEnvironment {
                        error("Linux environment should not be resolved for unsupported transport")
                    }
                },
            )

            val result = runCatching { provider.start() }

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains(transport.trim().lowercase())
            assertThat(result.exceptionOrNull()?.message).contains("only stdio transport is currently supported")
        }
    }

    @Test
    fun `start should fail before interactive process when command is missing`() {
        PluginLspSessionRegistry.activate("pylsp")
        val environment = RecordingLinuxEnvironment(
            probeResult = LinuxExecutionResult(
                exitCode = 127,
                stdout = "",
                stderr = "not found",
                durationMs = 1,
            )
        )
        val stderrLines = mutableListOf<String>()
        val provider = PluginLspConnectionProvider(
            config = LspServerConfig(
                id = "pylsp",
                name = "Python Language Server",
                languages = listOf("python"),
                fileExtensions = listOf("py"),
                server = LspServerConnectionConfig(
                    type = "stdio",
                    command = "pylsp",
                ),
            ),
            workingDir = "/workspace",
            projectRoot = "/workspace",
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            onStderrLine = { stderrLines += it },
        )

        val result = runCatching { provider.start() }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(environment.executedCommands.map { it.command })
            .containsExactly(listOf("/bin/sh", "-lc", "command -v pylsp >/dev/null 2>&1"))
        assertThat(environment.interactiveStarted).isFalse()
        assertThat(stderrLines).containsExactly("LSP server command not found: pylsp")
    }

    @Test
    fun `unexpected process exit should report failure after successful start`() {
        PluginLspSessionRegistry.activate("plugin.python")
        val process = StubInteractiveProcess(running = false)
        val environment = RecordingLinuxEnvironment(
            probeResult = successfulProbe(),
            interactiveProcess = process,
        )
        val exitReported = CountDownLatch(1)
        val failures = mutableListOf<String>()
        val provider = PluginLspConnectionProvider(
            config = lspServerConfig(type = "stdio"),
            ownerPluginId = "plugin.python",
            workingDir = "/workspace",
            projectRoot = "/workspace",
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(environment),
            onUnexpectedExit = { message ->
                failures += message
                exitReported.countDown()
            },
        )

        provider.start()

        assertThat(exitReported.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(failures).containsExactly("LSP server 'pylsp' exited unexpectedly")
        provider.close()
    }

    @Test
    fun `session registry should close only providers owned by selected plugin`() {
        val firstProcess = BlockingInteractiveProcess()
        val secondProcess = BlockingInteractiveProcess()
        var firstOwnerStopCount = 0
        var secondOwnerStopCount = 0
        val firstProvider = providerForOwner(
            ownerPluginId = "plugin.first",
            process = firstProcess,
            onOwnerStopped = { firstOwnerStopCount += 1 },
        )
        val secondProvider = providerForOwner(
            ownerPluginId = "plugin.second",
            process = secondProcess,
            onOwnerStopped = { secondOwnerStopCount += 1 },
        )

        firstProvider.start()
        secondProvider.start()
        PluginLspSessionRegistry.closeAll("plugin.first")

        assertThat(firstProcess.destroyed).isTrue()
        assertThat(secondProcess.destroyed).isFalse()
        assertThat(firstOwnerStopCount).isEqualTo(1)
        assertThat(secondOwnerStopCount).isEqualTo(0)

        PluginLspSessionRegistry.closeAll("plugin.second")
        assertThat(secondProcess.destroyed).isTrue()
        assertThat(secondOwnerStopCount).isEqualTo(1)
    }

    @Test
    fun `normal provider close should not report owner stop`() {
        val process = BlockingInteractiveProcess()
        var ownerStopCount = 0
        val provider = providerForOwner(
            ownerPluginId = "plugin.first",
            process = process,
            onOwnerStopped = { ownerStopCount += 1 },
        )

        provider.start()
        provider.close()

        assertThat(process.destroyed).isTrue()
        assertThat(ownerStopCount).isEqualTo(0)
    }

    @Test
    fun `session registry should reject a startup lease invalidated by owner stop`() {
        val ownerPluginId = "plugin.racing"
        PluginLspSessionRegistry.activate(ownerPluginId)
        val lease = checkNotNull(PluginLspSessionRegistry.acquire(ownerPluginId))
        val provider = providerForOwner(
            ownerPluginId = ownerPluginId,
            process = BlockingInteractiveProcess(),
        )

        PluginLspSessionRegistry.closeAll(ownerPluginId)

        assertThat(PluginLspSessionRegistry.register(lease, provider)).isFalse()
    }

    private fun lspServerConfig(type: String): LspServerConfig = LspServerConfig(
        id = "pylsp",
        name = "Python Language Server",
        languages = listOf("python"),
        fileExtensions = listOf("py"),
        server = LspServerConnectionConfig(
            type = type,
            command = "pylsp",
        ),
    )

    private fun providerForOwner(
        ownerPluginId: String,
        process: LinuxInteractiveProcess,
        onOwnerStopped: () -> Unit = {},
    ): PluginLspConnectionProvider {
        PluginLspSessionRegistry.activate(ownerPluginId)
        return PluginLspConnectionProvider(
            config = lspServerConfig(type = "stdio"),
            ownerPluginId = ownerPluginId,
            workingDir = "/workspace",
            projectRoot = "/workspace",
            linuxEnvironmentProvider = StaticLinuxEnvironmentProvider(
                RecordingLinuxEnvironment(
                    probeResult = successfulProbe(),
                    interactiveProcess = process,
                ),
            ),
            onOwnerStopped = onOwnerStopped,
        )
    }

    private fun successfulProbe() = LinuxExecutionResult(
        exitCode = 0,
        stdout = "/usr/bin/pylsp\n",
        stderr = "",
        durationMs = 1,
    )

    private class StaticLinuxEnvironmentProvider(
        private val environment: LinuxEnvironment,
    ) : LinuxEnvironmentProvider {
        override fun get(): LinuxEnvironment = environment
    }

    private data class ExecutedCommand(
        val command: List<String>,
        val workDir: String,
        val env: Map<String, String>,
        val timeout: Long?,
    )

    private class RecordingLinuxEnvironment(
        private val probeResult: LinuxExecutionResult,
        private val interactiveProcess: LinuxInteractiveProcess = StubInteractiveProcess(),
    ) : LinuxEnvironment {
        val executedCommands = mutableListOf<ExecutedCommand>()
        var interactiveStarted = false

        override fun isAvailable(): Boolean = true

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            executedCommands += ExecutedCommand(
                command = command,
                workDir = workDir,
                env = env,
                timeout = timeout,
            )
            return probeResult
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess {
            interactiveStarted = true
            return interactiveProcess
        }

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    private class StubInteractiveProcess(
        private var running: Boolean = false,
    ) : LinuxInteractiveProcess {
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))

        override fun isRunning(): Boolean = running
        override fun waitFor(timeout: Long): Int = 0
        override fun destroy() {
            running = false
        }
    }

    private class BlockingInteractiveProcess : LinuxInteractiveProcess {
        private val stderrWriter = PipedOutputStream()
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val stderr: InputStream = PipedInputStream(stderrWriter)

        @Volatile
        var destroyed: Boolean = false
            private set

        override fun isRunning(): Boolean = !destroyed

        override fun waitFor(timeout: Long): Int = if (destroyed) 0 else -1

        override fun destroy() {
            destroyed = true
            stderrWriter.close()
            stderr.close()
        }
    }
}

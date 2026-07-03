package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PRoot 运行时入口。
 *
 * 当前职责被明确收缩为：
 * 1. 通过自研 Linux 发行版管理器安装 rootfs。
 * 2. 提供 LinuxEnvironment 抽象实现。
 * 3. 提供少量与 guest 命令探测相关的辅助能力。
 *
 * 不再承担旧 rootfs 下载、工具链兼容包装、调试器自动部署等职责。
 */
class PRootEnvironment(
    context: Context,
) : LinuxEnvironment {

    val context: Context = context.applicationContext

    private val configManager: IConfigManager by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val rootfsProfileStore: RootfsProfileStore by lazy {
        RootfsProfileStore(this.context, configManager)
    }

    private val rootfsHealthChecker = LinuxDistroRootfsHealthChecker()

    private val pathResolver: ToolchainPathResolver by lazy {
        ToolchainPathResolver(this.context)
    }

    @Volatile
    private var cachedPRootManager: PRootManager? = null

    @Volatile
    private var cachedPRootRootfsPath: String? = null

    fun getPRootManager(): PRootManager {
        val currentRootfsPath = rootfsProfileStore.getActiveRootfsPath()
        val cached = cachedPRootManager
        if (cached != null && cachedPRootRootfsPath == currentRootfsPath) {
            return cached
        }

        return synchronized(this) {
            val synchronizedPath = rootfsProfileStore.getActiveRootfsPath()
            val synchronizedCached = cachedPRootManager
            if (synchronizedCached != null && cachedPRootRootfsPath == synchronizedPath) {
                synchronizedCached
            } else {
                PRootManager(this.context, synchronizedPath).also { manager ->
                    cachedPRootManager = manager
                    cachedPRootRootfsPath = synchronizedPath
                }
            }
        }
    }

    fun getActiveGuestPackageManager(): RootfsPackageManager = rootfsProfileStore.getActiveProfile().packageManager

    fun isInstalled(): Boolean {
        val activeProfile = rootfsProfileStore.getActiveProfileOrNull() ?: return false
        return rootfsProfileStore.isInstalled(activeProfile)
    }

    fun needsUpdate(): Boolean = false

    suspend fun initialize(progress: (Float) -> Unit = {}): Result<Unit> = SelfHostedLinuxDistroRuntime.createForExplicitInstall(context, configManager)
        .installDistro { installProgress ->
            progress(installProgress.progress)
        }.map { }

    suspend fun clean(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rootfsProfileStore.listProfiles()
                .filter { profile -> profile.sourceType == RootfsSourceType.LINUX_DISTRO }
                .forEach { profile -> rootfsProfileStore.deleteProfile(profile.id) }
            SelfHostedLinuxDistroRuntime.defaultRuntimeDir(context).deleteRecursively()
            synchronized(this@PRootEnvironment) {
                cachedPRootManager = null
                cachedPRootRootfsPath = null
            }
        }
    }

    override fun isAvailable(): Boolean = runCatching {
        !PRootBootstrap.isInstalling() && isInstalled() && !needsUpdate()
    }.getOrDefault(false)

    override suspend fun execute(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        timeout: Long?,
        stdin: String?,
    ): LinuxExecutionResult {
        val result = getPRootManager().execute(
            command = command,
            workDir = workDir,
            env = env,
            timeout = timeout,
            stdin = stdin,
        )

        return LinuxExecutionResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
            timedOut = result.timedOut,
        )
    }

    suspend fun executeGuest(
        command: List<String>,
        workDir: String = "/",
        env: Map<String, String> = emptyMap(),
        timeout: Long? = 60_000,
        stdin: String? = null,
    ): PRootResult = getPRootManager().execute(
        command = command,
        workDir = workDir,
        env = env,
        timeout = timeout,
        stdin = stdin,
    )

    override fun startInteractive(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
    ): LinuxInteractiveProcess {
        val process = getPRootManager().startInteractive(
            command = command,
            workDir = workDir,
            extraEnv = env,
        )
        return PRootInteractiveAdapter(process)
    }

    override fun toGuestPath(hostPath: String): String = getPRootManager().toGuestPath(hostPath)

    suspend fun executeShell(
        command: String,
        timeout: Long = 30_000,
        workDir: String = "/workspace",
    ): RunResult = getPRootManager().execute(
        command = listOf("/bin/sh", "-c", command),
        workDir = workDir,
        timeout = timeout,
    )

    suspend fun executeShellWithEnv(
        command: String,
        env: Map<String, String>,
        timeout: Long = 30_000,
        workDir: String = "/workspace",
    ): RunResult = getPRootManager().execute(
        command = listOf("/bin/sh", "-c", command),
        workDir = workDir,
        env = env,
        timeout = timeout,
    )

    suspend fun isCompilerAvailable(
        compilerType: CompilerType = CompilerType.CLANG,
        customCCompiler: String? = null,
        customCppCompiler: String? = null,
    ): Boolean {
        return when (compilerType) {
            CompilerType.CUSTOM -> {
                val cCompiler = customCCompiler?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                val cppCompiler = customCppCompiler?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                isCommandAvailable(cCompiler) && isCommandAvailable(cppCompiler)
            }

            else -> isCommandAvailable(pathResolver.getCCompiler(compilerType, customCCompiler))
        }
    }

    suspend fun getCompilerVersion(
        compilerType: CompilerType = CompilerType.CLANG,
        customCCompiler: String? = null,
        customCppCompiler: String? = null,
    ): String? {
        val command = when (compilerType) {
            CompilerType.CUSTOM -> customCCompiler?.trim()?.takeIf { it.isNotEmpty() }
            else -> pathResolver.getCCompiler(compilerType, customCCompiler)
        } ?: return null
        return readVersion(command)
    }

    suspend fun isDebuggerAvailable(): Boolean = isCommandAvailable(pathResolver.getLldb())

    suspend fun probeClangVersion(): PRootResult = probeVersion(pathResolver.getCCompiler(CompilerType.CLANG))

    suspend fun probeVersion(
        command: String,
        versionArg: String = "--version",
        timeout: Long = 10_000,
    ): PRootResult = getPRootManager().execute(
        command = listOf(command, versionArg),
        workDir = "/",
        timeout = timeout,
    )

    suspend fun isCommandAvailable(command: String): Boolean {
        val probeCommand = buildCommandAvailabilityProbe(command) ?: return false

        return getPRootManager().execute(
            command = probeCommand,
            workDir = "/",
            timeout = 10_000,
        ).exitCode == 0
    }

    suspend fun queryInstalledPackageVersions(packages: List<String>): Map<String, String?> = GuestSystemPackageManager.queryInstalledVersions(
        linuxEnvironment = this,
        packageManager = getActiveGuestPackageManager(),
        packages = packages,
    )

    suspend fun checkLinuxDistroHealth(): LinuxDistroRootfsHealthReport {
        val packageManager = rootfsProfileStore.getActiveProfileOrNull()?.packageManager
            ?: RootfsPackageManager.UNKNOWN
        return rootfsHealthChecker.check(
            linuxEnvironment = this,
            packageManager = packageManager,
        )
    }

    private suspend fun readVersion(command: String): String? {
        val result = probeVersion(command)
        if (!result.isSuccess) return null
        return result.combinedOutput
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

internal fun buildCommandAvailabilityProbe(command: String): List<String>? {
    val normalized = command.trim()
    if (normalized.isEmpty()) return null

    return if (normalized.contains('/')) {
        listOf("/bin/sh", "-lc", "[ -x ${"'" + normalized.replace("'", "'\\''") + "'"} ]")
    } else {
        listOf("/bin/sh", "-lc", "command -v ${"'" + normalized.replace("'", "'\\''") + "'"} >/dev/null 2>&1")
    }
}

internal class PRootInteractiveAdapter(
    private val delegate: InteractiveProcess,
) : LinuxInteractiveProcess {
    override val stdin = delegate.stdin
    override val stdout = delegate.stdout
    override val stderr = delegate.stderr

    override fun isRunning(): Boolean = delegate.isRunning()

    override fun waitFor(timeout: Long): Int = delegate.waitFor(timeout)

    override fun destroy() = delegate.destroy()
}

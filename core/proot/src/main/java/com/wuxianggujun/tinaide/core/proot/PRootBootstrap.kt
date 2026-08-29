package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * PRoot 环境初始化引导。
 *
 * Linux rootfs 安装和同步统一走自研 `:core:linux-distro` 运行时。
 */
object PRootBootstrap {

    private const val TAG = "PRootBootstrap"

    @Volatile
    private var installLogManagerOverride: InstallLogManager? = null

    @Volatile
    private var configManagerOverride: IConfigManager? = null

    /**
     * HOST 进程在 Koin 启动后必须先调用，注入日志与配置依赖。
     * 未绑定则安装/查询路径会失败并打日志，不再回退 GlobalContext。
     */
    fun bindDependencies(
        installLogManager: InstallLogManager,
        configManager: IConfigManager,
    ) {
        installLogManagerOverride = installLogManager
        configManagerOverride = configManager
    }

    private fun installLogManager(): InstallLogManager? = installLogManagerOverride

    private fun configManager(): IConfigManager? = configManagerOverride

    private fun requireConfigManager(caller: String): IConfigManager? {
        val manager = configManager()
        if (manager == null) {
            Timber.tag(TAG).w("PRootBootstrap.%s called before bindDependencies", caller)
        }
        return manager
    }

    private fun defaultDistroId(): String = SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID

    private fun syncConfiguredRuntimeProfiles(context: Context) {
        val manager = requireConfigManager("syncConfiguredRuntimeProfiles") ?: return
        SelfHostedLinuxDistroRuntime.createForStartup(context, manager).syncInstalledProfiles()
    }

    fun getActiveProfile(context: Context): RootfsProfile {
        val appContext = context.applicationContext
        val manager = requireConfigManager("getActiveProfile")
            ?: error("PRootBootstrap.bindDependencies must be called before getActiveProfile")
        syncConfiguredRuntimeProfiles(appContext)
        return RootfsProfileStore(appContext, manager).getActiveProfile()
    }

    fun getActiveRootfsPath(context: Context): String = getActiveProfile(context).rootfsPath

    private fun getActiveProfileOrNull(context: Context): RootfsProfile? {
        val appContext = context.applicationContext
        val manager = requireConfigManager("getActiveProfileOrNull") ?: return null
        syncConfiguredRuntimeProfiles(appContext)
        return RootfsProfileStore(appContext, manager).getActiveProfileOrNull()
    }

    private fun hasProfileShell(profile: RootfsProfile): Boolean {
        val rootfsDir = File(profile.rootfsPath)
        val shellPath = profile.shellPath.ifBlank { RootfsProfile.DEFAULT_SHELL_PATH }
        return rootfsDir.isDirectory && RootfsFileChecks.exists(rootfsDir, shellPath)
    }

    private fun missingShellMessage(context: Context, profile: RootfsProfile): String {
        val shellPath = profile.shellPath.ifBlank { RootfsProfile.DEFAULT_SHELL_PATH }
        return Strings.proot_profile_missing_shell.strOr(
            context,
            profile.displayName,
            shellPath,
        )
    }

    private fun logInfo(message: String) {
        runCatching { Timber.tag(TAG).i(message) }
        installLogManager()?.info(message)
    }

    private fun logSuccess(message: String) {
        runCatching { Timber.tag(TAG).i(message) }
        installLogManager()?.success(message)
    }

    private fun logWarning(message: String) {
        runCatching { Timber.tag(TAG).w(message) }
        installLogManager()?.warning(message)
    }

    private fun logError(message: String, e: Throwable? = null) {
        runCatching {
            if (e != null) Timber.tag(TAG).e(e, message) else Timber.tag(TAG).e(message)
        }
        installLogManager()?.error(message)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installing = AtomicBoolean(false)
    private val jobLock = Any()
    private var currentJob: Job? = null

    enum class InstallStage {
        PREPARING_RUNTIME,
        INSTALLING_DISTRO,
        REGISTERING_PROFILE,
        COMPLETED,
    }

    enum class PackageStatus {
        PENDING,
        DOWNLOADING,
        INSTALLING,
        COMPLETED,
        FAILED,
    }

    data class PackageInfo(
        val name: String,
        val displayName: String,
        val status: PackageStatus = PackageStatus.PENDING,
    ) {
        fun matchesPackageName(pkgName: String?): Boolean {
            if (pkgName.isNullOrBlank()) return false
            return pkgName == name
        }
    }

    sealed interface BootstrapState {
        data object Idle : BootstrapState

        data class Installing(
            val progress: Float,
            val message: String,
            val stage: InstallStage = InstallStage.PREPARING_RUNTIME,
            val packages: List<PackageInfo> = emptyList(),
            val currentPackage: String? = null,
        ) : BootstrapState

        data object Installed : BootstrapState

        data class Failed(
            val message: String,
            val isNetworkRelated: Boolean = false,
        ) : BootstrapState

        data object NeedsToolchainRepair : BootstrapState
    }

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    fun isInstalling(): Boolean = installing.get()

    fun cancel(applicationContext: Context, reason: String = "install cancelled") {
        val job = synchronized(jobLock) { currentJob }
        if (job == null || !installing.get()) {
            return
        }

        logWarning(reason)
        job.cancel(CancellationException(reason))
        if (_state.value is BootstrapState.Installing) {
            _state.value = BootstrapState.Idle
        }
    }

    fun restart(
        applicationContext: Context,
        reason: String? = null,
    ) {
        val context = applicationContext.applicationContext
        scope.launch {
            val job = synchronized(jobLock) { currentJob }
            if (job == null || !installing.get()) {
                start(context)
                return@launch
            }

            logWarning(Strings.proot_restart_request.strOr(context, reason?.let { Strings.proot_restart_reason.strOr(context, it) } ?: ""))
            val snapshot = _state.value
            if (snapshot is BootstrapState.Installing) {
                _state.value = snapshot.copy(message = Strings.proot_restarting.strOr(context))
            }

            job.cancel(CancellationException("PRootBootstrap restart requested"))
            runCatching { job.join() }

            start(context)
        }
    }

    fun startToolchainRepair(applicationContext: Context) {
        val context = applicationContext.applicationContext
        val activeProfile = getActiveProfileOrNull(context)
        if (activeProfile != null && hasProfileShell(activeProfile)) {
            _state.value = BootstrapState.Installed
        } else {
            start(context)
        }
    }

    fun start(applicationContext: Context) {
        val context = applicationContext.applicationContext

        if (installing.get()) {
            logInfo(Strings.proot_install_already_running.strOr(context))
            return
        }

        val activeProfile = getActiveProfileOrNull(context)
        if (activeProfile != null && hasProfileShell(activeProfile)) {
            logInfo(Strings.proot_environment_ready.strOr(context))
            _state.value = BootstrapState.Installed
            return
        }

        if (!installing.compareAndSet(false, true)) return

        launchInstallJob(context, defaultDistroId())
    }

    fun startDistroInstall(applicationContext: Context, distroId: String) {
        val context = applicationContext.applicationContext
        if (!installing.compareAndSet(false, true)) return

        launchInstallJob(context, distroId)
    }

    private fun launchInstallJob(context: Context, distroId: String) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            var terminalState: BootstrapState? = null
            try {
                installLogManager()?.clear()
                logInfo(Strings.proot_install_begin.strOr(context))
                terminalState = installConfiguredDistro(context, distroId)
            } catch (e: CancellationException) {
                terminalState = BootstrapState.Idle
                throw e
            } finally {
                finishBootstrapInstall(
                    terminalState = terminalState,
                    clearInstalling = { installing.set(false) },
                    clearCurrentJob = {
                        synchronized(jobLock) {
                            if (currentJob === job) {
                                currentJob = null
                            }
                        }
                    },
                    publishTerminalState = { state ->
                        _state.value = state
                        if (state is BootstrapState.Installed) {
                            logSuccess(Strings.proot_install_success.strOr(context))
                        }
                    },
                )
            }
        }
        synchronized(jobLock) { currentJob = job }
        job.start()
    }

    private suspend fun installConfiguredDistro(
        context: Context,
        distroId: String,
    ): BootstrapState = installSelfHostedLinuxDistro(context, distroId)

    private suspend fun installSelfHostedLinuxDistro(
        context: Context,
        distroId: String,
    ): BootstrapState {
        val packages = getSelfHostedLinuxDistroPackages(context).toMutableList()
        fun allPackages() = packages.toList()

        try {
            packages[0] = packages[0].copy(status = PackageStatus.INSTALLING)
            _state.value = BootstrapState.Installing(
                progress = 0f,
                message = Strings.linux_distro_install_phase_preparing.strOr(context),
                stage = InstallStage.PREPARING_RUNTIME,
                packages = allPackages(),
                currentPackage = PACKAGE_LINUX_DISTRO_RUNTIME,
            )

            val config = requireConfigManager("installSelfHostedLinuxDistro")
                ?: error("PRootBootstrap.bindDependencies must be called before install")
            val runtime = SelfHostedLinuxDistroRuntime.createForExplicitInstall(context, config)
            runtime.installDistro(distroId) { progress ->
                val stage = when (progress.phase) {
                    SelfHostedLinuxDistroRuntime.Phase.PREPARING,
                    SelfHostedLinuxDistroRuntime.Phase.RESOLVING_ARTIFACT -> InstallStage.PREPARING_RUNTIME
                    SelfHostedLinuxDistroRuntime.Phase.DOWNLOADING,
                    SelfHostedLinuxDistroRuntime.Phase.VERIFYING,
                    SelfHostedLinuxDistroRuntime.Phase.EXTRACTING,
                    SelfHostedLinuxDistroRuntime.Phase.CONFIGURING,
                    SelfHostedLinuxDistroRuntime.Phase.BOOTSTRAPPING -> InstallStage.INSTALLING_DISTRO
                    SelfHostedLinuxDistroRuntime.Phase.REGISTERING -> InstallStage.REGISTERING_PROFILE
                    SelfHostedLinuxDistroRuntime.Phase.COMPLETED -> InstallStage.COMPLETED
                }
                when (progress.phase) {
                    SelfHostedLinuxDistroRuntime.Phase.PREPARING,
                    SelfHostedLinuxDistroRuntime.Phase.RESOLVING_ARTIFACT -> {
                        packages[0] = packages[0].copy(status = PackageStatus.INSTALLING)
                        packages[1] = packages[1].copy(status = PackageStatus.PENDING)
                    }
                    SelfHostedLinuxDistroRuntime.Phase.DOWNLOADING -> {
                        packages[0] = packages[0].copy(status = PackageStatus.COMPLETED)
                        packages[1] = packages[1].copy(status = PackageStatus.DOWNLOADING)
                    }
                    SelfHostedLinuxDistroRuntime.Phase.VERIFYING,
                    SelfHostedLinuxDistroRuntime.Phase.EXTRACTING,
                    SelfHostedLinuxDistroRuntime.Phase.CONFIGURING,
                    SelfHostedLinuxDistroRuntime.Phase.BOOTSTRAPPING,
                    SelfHostedLinuxDistroRuntime.Phase.REGISTERING -> {
                        packages[0] = packages[0].copy(status = PackageStatus.COMPLETED)
                        packages[1] = packages[1].copy(status = PackageStatus.INSTALLING)
                    }
                    SelfHostedLinuxDistroRuntime.Phase.COMPLETED -> {
                        packages[0] = packages[0].copy(status = PackageStatus.COMPLETED)
                        packages[1] = packages[1].copy(status = PackageStatus.COMPLETED)
                    }
                }
                _state.value = BootstrapState.Installing(
                    progress = progress.progress.coerceIn(0f, 1f),
                    message = progress.message,
                    stage = stage,
                    packages = allPackages(),
                    currentPackage = when (progress.phase) {
                        SelfHostedLinuxDistroRuntime.Phase.PREPARING,
                        SelfHostedLinuxDistroRuntime.Phase.RESOLVING_ARTIFACT -> PACKAGE_LINUX_DISTRO_RUNTIME
                        SelfHostedLinuxDistroRuntime.Phase.DOWNLOADING,
                        SelfHostedLinuxDistroRuntime.Phase.VERIFYING,
                        SelfHostedLinuxDistroRuntime.Phase.EXTRACTING,
                        SelfHostedLinuxDistroRuntime.Phase.CONFIGURING,
                        SelfHostedLinuxDistroRuntime.Phase.BOOTSTRAPPING,
                        SelfHostedLinuxDistroRuntime.Phase.REGISTERING -> PACKAGE_LINUX_ROOTFS
                        SelfHostedLinuxDistroRuntime.Phase.COMPLETED -> null
                    },
                )
            }.getOrThrow()

            _state.value = BootstrapState.Installing(
                progress = 1f,
                message = Strings.proot_install_complete.strOr(context),
                stage = InstallStage.COMPLETED,
                packages = allPackages(),
                currentPackage = null,
            )
            return BootstrapState.Installed
        } catch (e: CancellationException) {
            logWarning(e.message ?: "PRoot installation cancelled")
            throw e
        } catch (t: Throwable) {
            val message = t.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
            val isNetworkError = message.contains("download", ignoreCase = true) ||
                message.contains("network", ignoreCase = true) ||
                message.contains("HTTP") ||
                message.contains("timeout") ||
                message.contains("connect")
            logError(Strings.proot_install_failed.strOr(context, message), t)
            return BootstrapState.Failed(message, isNetworkError)
        }
    }

    fun isEnvironmentReady(context: Context): Boolean {
        val activeProfile = getActiveProfileOrNull(context.applicationContext) ?: return false
        return hasProfileShell(activeProfile)
    }

    private fun getSelfHostedLinuxDistroPackages(context: Context) = listOf(
        PackageInfo(PACKAGE_LINUX_DISTRO_RUNTIME, Strings.linux_distro_package_runtime.strOr(context)),
        PackageInfo(PACKAGE_LINUX_ROOTFS, Strings.linux_package_rootfs.strOr(context)),
    )

    private const val PACKAGE_LINUX_DISTRO_RUNTIME = "linux-distro-runtime"
    private const val PACKAGE_LINUX_ROOTFS = "linux-rootfs"
}

internal fun finishBootstrapInstall(
    terminalState: PRootBootstrap.BootstrapState?,
    clearInstalling: () -> Unit,
    clearCurrentJob: () -> Unit,
    publishTerminalState: (PRootBootstrap.BootstrapState) -> Unit,
) {
    clearInstalling()
    clearCurrentJob()
    terminalState?.let(publishTerminalState)
}

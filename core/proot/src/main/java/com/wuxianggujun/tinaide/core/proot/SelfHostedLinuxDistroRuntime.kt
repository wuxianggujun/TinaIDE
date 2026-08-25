package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArchitecture
import com.wuxianggujun.tinaide.core.linuxdistro.InstalledLinuxDistro
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroCatalog
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroIds
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroInstallLayout
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroInstallPhase
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroInstallProgress
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManager
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroRootfsConfig
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SelfHostedLinuxDistroRuntime(
    context: Context,
    configManager: IConfigManager,
    private val manager: LinuxDistroManager,
    private val profileStore: RootfsProfileStore = RootfsProfileStore(context.applicationContext, configManager),
    private val clock: () -> Long = System::currentTimeMillis,
    private val runtimeDir: File = defaultRuntimeDir(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val rootfsBootstrapper = LinuxDistroRootfsBootstrapper()
    private val operationMutex = operationMutexFor(runtimeDir)

    data class InstallProgress(
        val phase: Phase,
        val progress: Float,
        val message: String,
    )

    enum class Phase {
        PREPARING,
        RESOLVING_ARTIFACT,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        CONFIGURING,
        BOOTSTRAPPING,
        REGISTERING,
        COMPLETED,
    }

    data class RuntimeLayout(
        val runtimeDir: File,
        val downloadCacheDir: File,
        val installedRootfsDir: File,
        val stagingDir: File,
        val registryFile: File,
    )

    fun layout(): RuntimeLayout {
        val installLayout = LinuxDistroInstallLayout(runtimeDir = runtimeDir)
        return RuntimeLayout(
            runtimeDir = runtimeDir,
            downloadCacheDir = installLayout.downloadCacheDir,
            installedRootfsDir = installLayout.installedRootfsDir,
            stagingDir = installLayout.stagingDir,
            registryFile = File(runtimeDir, "linux-distro-registry.json"),
        )
    }

    fun installedRootfsDir(distroId: String): File = File(layout().installedRootfsDir, distroId)

    fun listAvailableDistros() = manager.listAvailable()

    fun isDistroInstalled(distroId: String): Boolean = manager.isInstalled(distroId)

    fun syncInstalledProfiles(): List<RootfsProfile> = manager.listInstalled(syncFromDisk = true)
        .map { installation -> registerInstallation(installation, makeActive = false) }

    suspend fun installDistro(
        distroId: String = DEFAULT_DISTRO_ID,
        architecture: DistroArchitecture = defaultArchitecture(),
        releaseId: String? = null,
        reinstall: Boolean = false,
        rootfsConfig: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig(),
        progress: (InstallProgress) -> Unit = {},
    ): Result<RootfsProfile> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            runCatchingPreservingCancellation {
                val displayName = resolveDisplayName(distroId)
                val result = manager.install(
                    distroId = distroId,
                    architecture = architecture,
                    releaseId = releaseId,
                    reinstall = reinstall,
                    rootfsConfig = rootfsConfig,
                ) { installProgress ->
                    if (installProgress.phase != LinuxDistroInstallPhase.REGISTERING &&
                        installProgress.phase != LinuxDistroInstallPhase.COMPLETED
                    ) {
                        progress(installProgress.toRuntimeProgress(displayName))
                    }
                }
                progress(
                    InstallProgress(
                        phase = Phase.CONFIGURING,
                        progress = 0.90f,
                        message = Strings.linux_distro_install_phase_configuring.strOr(appContext),
                    )
                )
                bootstrapInstallation(result.installation, result.rootfsDir, progress)
                progress(
                    InstallProgress(
                        phase = Phase.REGISTERING,
                        progress = 0.96f,
                        message = Strings.linux_distro_install_phase_registering.strOr(appContext),
                    )
                )
                val savedProfile = registerInstallation(result.installation, makeActive = true)
                progress(
                    InstallProgress(
                        phase = Phase.COMPLETED,
                        progress = 1f,
                        message = Strings.linux_distro_install_phase_completed.strOr(appContext, savedProfile.displayName),
                    )
                )
                savedProfile
            }
        }
    }

    suspend fun uninstallDistro(distroId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            runCatchingPreservingCancellation {
                val removedRootfs = manager.uninstall(distroId)
                val profileId = LinuxDistroIds.profileIdFor(distroId)
                val removedProfile = profileStore.getProfile(profileId)?.let {
                    profileStore.deleteProfile(profileId)
                    true
                } ?: false
                removedProfile || removedRootfs
            }
        }
    }

    private suspend fun bootstrapInstallation(
        installation: InstalledLinuxDistro,
        rootfsDir: File,
        progress: (InstallProgress) -> Unit,
    ) {
        val packageManager = LinuxDistroRootfsProfileMapper.run {
            installation.packageManager.toRootfsPackageManager()
        }
        val linuxEnvironment = PRootRootfsLinuxEnvironment(
            context = appContext,
            rootfsPath = rootfsDir.absolutePath,
        )
        rootfsBootstrapper.bootstrap(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
        ) { bootstrapProgress ->
            progress(bootstrapProgress.toRuntimeProgress())
        }
    }

    private fun registerInstallation(
        installation: InstalledLinuxDistro,
        makeActive: Boolean,
    ): RootfsProfile {
        val profile = LinuxDistroRootfsProfileMapper.toRootfsProfile(
            installation = installation,
            now = clock(),
        )
        return profileStore.upsertProfile(profile, makeActive = makeActive)
    }

    private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun LinuxDistroInstallProgress.toRuntimeProgress(displayName: String): InstallProgress {
        val runtimePhase = when (phase) {
            LinuxDistroInstallPhase.PREPARING -> Phase.PREPARING
            LinuxDistroInstallPhase.RESOLVING_ARTIFACT -> Phase.RESOLVING_ARTIFACT
            LinuxDistroInstallPhase.DOWNLOADING -> Phase.DOWNLOADING
            LinuxDistroInstallPhase.VERIFYING -> Phase.VERIFYING
            LinuxDistroInstallPhase.EXTRACTING -> Phase.EXTRACTING
            LinuxDistroInstallPhase.CONFIGURING -> Phase.CONFIGURING
            LinuxDistroInstallPhase.REGISTERING -> Phase.REGISTERING
            LinuxDistroInstallPhase.COMPLETED -> Phase.COMPLETED
        }
        return InstallProgress(
            phase = runtimePhase,
            progress = fraction.coerceIn(0f, 1f),
            message = messageFor(runtimePhase, displayName),
        )
    }

    private fun LinuxDistroRootfsBootstrapProgress.toRuntimeProgress(): InstallProgress {
        val scaledProgress = BOOTSTRAP_PROGRESS_START + progress.coerceIn(0f, 1f) * BOOTSTRAP_PROGRESS_RANGE
        return InstallProgress(
            phase = Phase.BOOTSTRAPPING,
            progress = scaledProgress,
            message = toDisplayMessage(),
        )
    }

    private fun messageFor(phase: Phase, displayName: String): String = when (phase) {
        Phase.PREPARING -> Strings.linux_distro_install_phase_preparing.strOr(appContext)
        Phase.RESOLVING_ARTIFACT -> Strings.linux_distro_install_phase_resolving.strOr(appContext)
        Phase.DOWNLOADING -> Strings.linux_distro_install_phase_downloading.strOr(appContext, displayName)
        Phase.VERIFYING -> Strings.linux_distro_install_phase_verifying.strOr(appContext)
        Phase.EXTRACTING -> Strings.linux_distro_install_phase_extracting.strOr(appContext)
        Phase.CONFIGURING -> Strings.linux_distro_install_phase_configuring.strOr(appContext)
        Phase.BOOTSTRAPPING -> Strings.linux_distro_bootstrap_phase_checking_package_manager.strOr(appContext)
        Phase.REGISTERING -> Strings.linux_distro_install_phase_registering.strOr(appContext)
        Phase.COMPLETED -> Strings.linux_distro_install_phase_completed.strOr(appContext, displayName)
    }

    private fun LinuxDistroRootfsBootstrapProgress.toDisplayMessage(): String = when (phase) {
        LinuxDistroRootfsBootstrapPhase.CHECKING_PACKAGE_MANAGER ->
            Strings.linux_distro_bootstrap_phase_checking_package_manager.strOr(appContext)
        LinuxDistroRootfsBootstrapPhase.CHECKING_COMMANDS ->
            Strings.linux_distro_bootstrap_phase_checking_commands.strOr(appContext)
        LinuxDistroRootfsBootstrapPhase.UPDATING_INDEX ->
            Strings.linux_distro_bootstrap_phase_updating_index.strOr(appContext)
        LinuxDistroRootfsBootstrapPhase.RESOLVING_PACKAGES ->
            Strings.linux_distro_bootstrap_phase_resolving_packages.strOr(appContext)
        LinuxDistroRootfsBootstrapPhase.INSTALLING_PACKAGES ->
            Strings.linux_distro_bootstrap_phase_installing_packages.strOr(
                appContext,
                packages.joinToString().ifBlank { "-" },
            )
        LinuxDistroRootfsBootstrapPhase.VERIFYING_COMMANDS ->
            Strings.linux_distro_bootstrap_phase_verifying_commands.strOr(appContext)
        LinuxDistroRootfsBootstrapPhase.COMPLETED ->
            Strings.linux_distro_bootstrap_phase_completed.strOr(appContext)
    }

    private fun resolveDisplayName(distroId: String): String = manager.resolveDistro(distroId)?.displayName ?: distroId

    companion object {
        private const val BOOTSTRAP_PROGRESS_START = 0.90f
        private const val BOOTSTRAP_PROGRESS_RANGE = 0.06f

        const val DEFAULT_DISTRO_ID = "alpine"

        fun createForExplicitInstall(
            context: Context,
            configManager: IConfigManager,
        ): SelfHostedLinuxDistroRuntime {
            val catalog = loadRemoteOrCachedCatalog(context.applicationContext)
            return create(context, configManager, catalog)
        }

        fun createForStartup(
            context: Context,
            configManager: IConfigManager,
        ): SelfHostedLinuxDistroRuntime {
            val catalog = loadBundledCatalog(context.applicationContext)
            return create(context, configManager, catalog)
        }

        fun createForLocalRead(
            context: Context,
            configManager: IConfigManager,
        ): SelfHostedLinuxDistroRuntime {
            val catalog = loadCachedOrBundledCatalog(context.applicationContext)
            return create(context, configManager, catalog)
        }

        fun loadBundledCatalog(context: Context): LinuxDistroCatalog =
            LinuxDistroCatalogRepository.create(context.applicationContext).loadBundledCatalog()

        fun loadCachedOrBundledCatalog(context: Context): LinuxDistroCatalog =
            LinuxDistroCatalogRepository.create(context.applicationContext).loadCachedOrBundledCatalog()

        fun loadRemoteOrCachedCatalog(context: Context): LinuxDistroCatalog =
            LinuxDistroCatalogRepository.create(context.applicationContext).loadRemoteOrCachedCatalog()

        fun create(
            context: Context,
            configManager: IConfigManager,
            catalog: LinuxDistroCatalog,
        ): SelfHostedLinuxDistroRuntime {
            val appContext = context.applicationContext
            val installLayout = LinuxDistroInstallLayout(runtimeDir = defaultRuntimeDir(appContext))
            return SelfHostedLinuxDistroRuntime(
                context = appContext,
                configManager = configManager,
                manager = LinuxDistroManager(
                    catalog = catalog,
                    layout = installLayout,
                ),
                runtimeDir = installLayout.runtimeDir,
            )
        }

        fun defaultRuntimeDir(context: Context): File = File(ProjectPaths.getPRootRoot(context.applicationContext), "linux-distro")

        fun defaultArchitecture(): DistroArchitecture = Build.SUPPORTED_ABIS
            .asSequence()
            .mapNotNull { abi -> DistroArchitecture.fromAndroidAbi(abi) }
            .firstOrNull() ?: DistroArchitecture.AARCH64

        private val operationMutexes = ConcurrentHashMap<String, Mutex>()

        private fun operationMutexFor(runtimeDir: File): Mutex {
            val key = runtimeDir.toPath().toAbsolutePath().normalize().toString()
            return operationMutexes.computeIfAbsent(key) { Mutex() }
        }
    }
}

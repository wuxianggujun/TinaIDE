package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * PRoot guest 工具链安装器。
 *
 * 在当前 Linux rootfs 内安装真实会被 PRoot 编译/LSP/调试链路使用的工具，
 * 不再依赖旧的 manifest / symlink 兼容层。
 */
class PRootGuestToolchainInstaller(
    context: Context,
    private val configManager: IConfigManager = ConfigManager(context.applicationContext),
) {

    private companion object {
        const val TAG = "PRootGuestToolchain"
    }

    data class InstallProgress(
        val current: Int,
        val total: Int,
        val displayName: String,
        val resolvedPackageName: String,
    )

    private data class PackageRequest(
        val id: String,
        val displayName: String,
        val candidates: List<String>,
        val required: Boolean = true,
        val installAllCandidates: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val prootEnvironment by lazy { PRootEnvironment(appContext, configManager) }
    private val pathResolver by lazy { ToolchainPathResolver(appContext) }

    suspend fun isInstalled(config: ToolchainConfig): Boolean = withContext(Dispatchers.IO) {
        val environmentAvailable = prootEnvironment.isAvailable()
        logEnvironmentReadiness("check", environmentAvailable)
        if (!environmentAvailable) return@withContext false

        val checks = mutableListOf<Boolean>()
        if (config.installClang) {
            checks += prootEnvironment.isCommandAvailable(
                pathResolver.getCCompiler(CompilerType.CLANG)
            )
        }
        if (config.installClangd) {
            checks += prootEnvironment.isCommandAvailable(pathResolver.getClangd())
        }
        if (config.installClangFormat) {
            checks += prootEnvironment.isCommandAvailable(pathResolver.getClangFormat())
        }
        if (config.installLldb) {
            checks += prootEnvironment.isCommandAvailable(pathResolver.getLldb())
        }
        if (config.installGcc) {
            checks += prootEnvironment.isCommandAvailable("gcc")
            checks += prootEnvironment.isCommandAvailable("g++")
        }
        if (config.installGdb) {
            checks += prootEnvironment.isCommandAvailable("gdb")
        }
        if (config.installCmake) {
            checks += prootEnvironment.isCommandAvailable("cmake")
        }
        if (config.installNinja) {
            checks += prootEnvironment.isCommandAvailable("ninja")
        }
        if (config.installMake) {
            checks += prootEnvironment.isCommandAvailable("make")
        }
        if (config.installGit) {
            checks += prootEnvironment.isCommandAvailable("git")
        }

        checks.all { it }.also { installed ->
            Timber.tag(TAG).i("PRoot guest toolchain check completed: installed=%s", installed)
        }
    }

    suspend fun install(
        config: ToolchainConfig,
        onProgress: (InstallProgress) -> Unit = {},
    ): Result<List<String>> = runCatching {
        withContext(Dispatchers.IO) {
            val environmentAvailable = prootEnvironment.isAvailable()
            logEnvironmentReadiness("install", environmentAvailable)
            check(environmentAvailable) {
                Strings.proot_toolchain_error_not_installed.strOr(appContext)
            }

            val packageManager = prootEnvironment.getActiveGuestPackageManager()
            Timber.tag(TAG).i("PRoot guest toolchain installation started: packageManager=%s", packageManager)
            updatePackageIndex(packageManager)

            val requests = buildRequests(config, packageManager)
            val installedPackages = mutableListOf<String>()

            requests.forEachIndexed { index, request ->
                val resolvedPackages = resolvePackageNames(packageManager, request)
                if (resolvedPackages.isEmpty()) {
                    if (request.required) {
                        error(Strings.proot_toolchain_error_unavailable.strOr(appContext, request.displayName))
                    } else {
                        return@forEachIndexed
                    }
                }
                val missingPackages = resolvedPackages.filterNot { packageName ->
                    isPackageInstalled(packageName, packageManager)
                }

                onProgress(
                    InstallProgress(
                        current = index + 1,
                        total = requests.size,
                        displayName = request.displayName,
                        resolvedPackageName = resolvedPackages.joinToString(),
                    )
                )

                if (missingPackages.isEmpty()) {
                    installedPackages += resolvedPackages
                    return@forEachIndexed
                }

                val result = GuestSystemPackageManager.installPackages(
                    linuxEnvironment = prootEnvironment,
                    packageManager = packageManager,
                    packages = missingPackages,
                    timeoutMs = 300_000,
                )
                if (!result.isSuccess) {
                    error(
                        result.combinedOutput.ifBlank {
                            Strings.proot_toolchain_error_unavailable.strOr(appContext, request.displayName)
                        }
                    )
                }

                installedPackages += resolvedPackages
            }

            pathResolver.invalidateCache()

            check(isInstalled(config)) {
                Strings.proot_toolchain_error_not_installed.strOr(appContext)
            }

            installedPackages.distinct().also { packages ->
                Timber.tag(TAG).i(
                    "PRoot guest toolchain installation completed: packageCount=%d",
                    packages.size,
                )
            }
        }
    }.onFailure { error ->
        Timber.tag(TAG).e(error, "PRoot guest toolchain installation failed")
    }

    private fun logEnvironmentReadiness(operation: String, environmentAvailable: Boolean) {
        Timber.tag(TAG).i(
            "PRoot guest toolchain %s readiness: bootstrapState=%s, installing=%s, environmentAvailable=%s",
            operation,
            bootstrapStateName(PRootBootstrap.state.value),
            PRootBootstrap.isInstalling(),
            environmentAvailable,
        )
    }

    private fun bootstrapStateName(state: PRootBootstrap.BootstrapState): String = when (state) {
        PRootBootstrap.BootstrapState.Idle -> "Idle"
        is PRootBootstrap.BootstrapState.Installing -> "Installing"
        PRootBootstrap.BootstrapState.Installed -> "Installed"
        is PRootBootstrap.BootstrapState.Failed -> "Failed"
        PRootBootstrap.BootstrapState.NeedsToolchainRepair -> "NeedsToolchainRepair"
    }

    private suspend fun updatePackageIndex(packageManager: RootfsPackageManager) {
        val result = GuestSystemPackageManager.updateIndex(
            linuxEnvironment = prootEnvironment,
            packageManager = packageManager,
            timeoutMs = 120_000,
        )
        check(result.exitCode == 0) {
            (result.stdout + result.stderr).ifBlank { "Package index update failed: $packageManager" }
        }
    }

    private suspend fun resolvePackageNames(
        packageManager: RootfsPackageManager,
        request: PackageRequest,
    ): List<String> {
        val candidates = request.candidates.distinct()
        if (candidates.isEmpty()) return emptyList()

        if (request.installAllCandidates) {
            val existingPackages = candidates.filter { candidate -> packageExists(packageManager, candidate) }
            return existingPackages.takeIf { it.size == candidates.size }.orEmpty()
        }

        return candidates.firstOrNull { candidate -> packageExists(packageManager, candidate) }
            ?.let(::listOf)
            .orEmpty()
    }

    private suspend fun packageExists(
        packageManager: RootfsPackageManager,
        packageName: String,
    ): Boolean = GuestSystemPackageManager.packageExists(
        linuxEnvironment = prootEnvironment,
        packageManager = packageManager,
        packageName = packageName,
    )

    private suspend fun isPackageInstalled(packageName: String, packageManager: RootfsPackageManager): Boolean = GuestSystemPackageManager.queryInstalledVersions(
        linuxEnvironment = prootEnvironment,
        packageManager = packageManager,
        packages = listOf(packageName),
        timeoutMs = 15_000,
    )[packageName] != null

    private fun buildRequests(
        config: ToolchainConfig,
        packageManager: RootfsPackageManager,
    ): List<PackageRequest> {
        val requests = mutableListOf<PackageRequest>()

        requests += PackageRequest(
            id = "build-base",
            displayName = Strings.toolchain_pkg_build_base.strOr(appContext),
            candidates = buildBasePackages(packageManager),
            installAllCandidates = packageManager == RootfsPackageManager.DNF,
        )

        if (config.installClang) {
            requests += PackageRequest(
                id = "clang",
                displayName = Strings.toolchain_pkg_clang.strOr(appContext),
                candidates = clangPackages(packageManager),
            )
        }

        if (config.installLlvm) {
            requests += PackageRequest(
                id = "llvm",
                displayName = Strings.toolchain_pkg_llvm.strOr(appContext),
                candidates = llvmPackages(packageManager),
                required = false,
            )
        }

        if (config.installClangd) {
            requests += PackageRequest(
                id = "clangd",
                displayName = Strings.toolchain_pkg_clangd.strOr(appContext),
                candidates = clangdPackages(packageManager),
            )
        }

        if (config.installClangFormat) {
            requests += PackageRequest(
                id = "clang-format",
                displayName = Strings.toolchain_pkg_clang_format.strOr(appContext),
                candidates = clangFormatPackages(packageManager),
            )
        }

        if (config.installLibcxx) {
            requests += PackageRequest(
                id = "libcxx",
                displayName = Strings.toolchain_pkg_libcxx_dev.strOr(appContext),
                candidates = libcxxPackages(packageManager),
                required = false,
            )
            requests += PackageRequest(
                id = "libcxxabi",
                displayName = Strings.toolchain_pkg_libcxxabi_dev.strOr(appContext),
                candidates = libcxxAbiPackages(packageManager),
                required = false,
            )
        }

        if (config.installCmake) {
            requests += PackageRequest(
                id = "cmake",
                displayName = Strings.toolchain_pkg_cmake.strOr(appContext),
                candidates = listOf("cmake"),
            )
        }

        if (config.installNinja) {
            requests += PackageRequest(
                id = "ninja-build",
                displayName = Strings.toolchain_pkg_ninja.strOr(appContext),
                candidates = ninjaPackages(packageManager),
            )
        }

        if (config.installMake) {
            requests += PackageRequest(
                id = "make",
                displayName = Strings.toolchain_pkg_make.strOr(appContext),
                candidates = listOf("make"),
            )
        }

        if (config.installGit) {
            requests += PackageRequest(
                id = "git",
                displayName = Strings.toolchain_pkg_git.strOr(appContext),
                candidates = listOf("git"),
            )
        }

        if (config.installLld) {
            requests += PackageRequest(
                id = "lld",
                displayName = Strings.toolchain_pkg_lld.strOr(appContext),
                candidates = lldPackages(packageManager),
                required = false,
            )
        }

        if (config.installGcc) {
            requests += PackageRequest(
                id = "gcc",
                displayName = Strings.toolchain_pkg_gcc.strOr(appContext),
                candidates = listOf("gcc"),
            )
            requests += PackageRequest(
                id = "gxx",
                displayName = Strings.toolchain_pkg_gxx.strOr(appContext),
                candidates = gxxPackages(packageManager),
            )
        }

        if (config.installGnuLd) {
            requests += PackageRequest(
                id = "binutils",
                displayName = Strings.toolchain_pkg_binutils.strOr(appContext),
                candidates = listOf("binutils"),
            )
        }

        if (config.installLldb) {
            requests += PackageRequest(
                id = "lldb",
                displayName = Strings.toolchain_pkg_lldb.strOr(appContext),
                candidates = lldbPackages(packageManager),
                required = false,
            )
            requests += PackageRequest(
                id = "py3-lldb",
                displayName = Strings.toolchain_pkg_python3_lldb.strOr(appContext),
                candidates = pythonLldbPackages(packageManager),
                required = false,
            )
        }

        if (config.installGdb) {
            requests += PackageRequest(
                id = "gdb",
                displayName = Strings.toolchain_pkg_gdb.strOr(appContext),
                candidates = listOf("gdb"),
                required = false,
            )
        }

        return requests.distinctBy { it.id }
    }

    private fun buildBasePackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> listOf("build-base")
        RootfsPackageManager.APT -> listOf("build-essential")
        RootfsPackageManager.PACMAN -> listOf("base-devel")
        RootfsPackageManager.DNF -> listOf("gcc", "gcc-c++", "make")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun clangPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> candidates("clang", preferGeneric = true)
        RootfsPackageManager.APT -> hyphenatedCandidates("clang", preferGeneric = true)
        RootfsPackageManager.PACMAN,
        RootfsPackageManager.DNF -> listOf("clang")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun llvmPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> candidates("llvm", preferGeneric = true)
        RootfsPackageManager.APT -> hyphenatedCandidates("llvm", preferGeneric = true)
        RootfsPackageManager.PACMAN,
        RootfsPackageManager.DNF -> listOf("llvm")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun clangdPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK ->
            candidates("clang-extra-tools", preferGeneric = true) +
                candidates("clang-tools-extra", preferGeneric = true)
        RootfsPackageManager.APT -> hyphenatedCandidates("clangd", preferGeneric = true)
        RootfsPackageManager.PACMAN -> listOf("clang")
        RootfsPackageManager.DNF -> listOf("clang-tools-extra", "clang")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun clangFormatPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK ->
            candidates("clang-extra-tools", preferGeneric = true) +
                candidates("clang-tools-extra", preferGeneric = true)
        RootfsPackageManager.APT -> hyphenatedCandidates("clang-format", preferGeneric = true)
        RootfsPackageManager.PACMAN -> listOf("clang")
        RootfsPackageManager.DNF -> listOf("clang-tools-extra", "clang")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun libcxxPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK,
        RootfsPackageManager.APT -> listOf("libc++-dev", "libc++", "libstdc++-dev")
        RootfsPackageManager.PACMAN -> listOf("libc++")
        RootfsPackageManager.DNF -> listOf("libcxx-devel", "libstdc++-devel")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun libcxxAbiPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK,
        RootfsPackageManager.APT -> listOf("libc++abi-dev", "libc++abi")
        RootfsPackageManager.PACMAN -> listOf("libc++abi")
        RootfsPackageManager.DNF -> listOf("libcxxabi-devel")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun ninjaPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APT -> listOf("ninja-build")
        RootfsPackageManager.APK,
        RootfsPackageManager.PACMAN,
        RootfsPackageManager.DNF -> listOf("ninja", "ninja-build")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun lldPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> candidates("lld", preferGeneric = true)
        RootfsPackageManager.APT -> hyphenatedCandidates("lld", preferGeneric = true)
        RootfsPackageManager.PACMAN,
        RootfsPackageManager.DNF -> listOf("lld")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun gxxPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK,
        RootfsPackageManager.APT -> listOf("g++")
        RootfsPackageManager.PACMAN -> listOf("gcc")
        RootfsPackageManager.DNF -> listOf("gcc-c++")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun lldbPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> listOf("lldb") + candidates("lldb")
        RootfsPackageManager.APT -> hyphenatedCandidates("lldb", preferGeneric = true)
        RootfsPackageManager.PACMAN,
        RootfsPackageManager.DNF -> listOf("lldb")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun pythonLldbPackages(packageManager: RootfsPackageManager): List<String> = when (packageManager) {
        RootfsPackageManager.APK -> listOf("py3-lldb", "python3-lldb")
        RootfsPackageManager.APT -> hyphenatedCandidates("python3-lldb", preferGeneric = true)
        RootfsPackageManager.PACMAN -> listOf("lldb")
        RootfsPackageManager.DNF -> listOf("python3-lldb", "lldb")
        RootfsPackageManager.UNKNOWN -> emptyList()
    }

    private fun candidates(baseName: String, preferGeneric: Boolean = false): List<String> {
        val versioned = listOf(20, 19, 18, 17, 16, 15, 14, 13, 12).map { "$baseName$it" }
        return if (preferGeneric) {
            listOf(baseName) + versioned
        } else {
            versioned + baseName
        }
    }

    private fun hyphenatedCandidates(baseName: String, preferGeneric: Boolean = false): List<String> {
        val versioned = listOf(20, 19, 18, 17, 16, 15, 14, 13, 12).map { "$baseName-$it" }
        return if (preferGeneric) {
            listOf(baseName) + versioned
        } else {
            versioned + baseName
        }
    }
}

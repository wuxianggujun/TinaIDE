package com.wuxianggujun.tinaide.terminal.shell

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.terminal.preferences.TerminalPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class TerminalBackend {
    PROOT,
    HOST
}

data class ShellResolution(
    val backend: TerminalBackend,
    val resolvedShellType: TerminalPreferences.ShellType,
    val resolvedShellName: String,
    val shellPath: String,
    val argv: Array<String>,
    val env: Array<String>,
    val cwd: String,
)

sealed class ShellResolveResult {
    data class Success(val resolution: ShellResolution) : ShellResolveResult()
    data class Error(val message: String) : ShellResolveResult()
}

data class ShellAvailability(
    val backend: TerminalBackend,
    val availableShells: Set<TerminalPreferences.ShellType>,
    val autoResolved: TerminalPreferences.ShellType?,
)

class TerminalShellResolver(
    private val context: Context
) {
    private val terminalPrefs by lazy { TerminalPreferences.get(context) }
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider by lazy {
        runCatching {
            org.koin.core.context.GlobalContext.get().getOrNull<LinuxEnvironmentProvider>()
        }.getOrNull() ?: UnavailableLinuxEnvironmentProvider
    }

    private fun resolvePRootEnvironment(): PRootEnvironment? = linuxEnvironmentProvider.get() as? PRootEnvironment

    /**
     * 根据用户配置和系统状态决定使用的后端
     *
     * 设置页的可用性探测仍使用全局偏好来选择探测后端。
     * 终端会话启动流程直接传入 TerminalBackend，不经过此方法。
     */
    private fun resolveBackend(): TerminalBackend = when (terminalPrefs.terminalBackendMode) {
        TerminalPreferences.BackendMode.AUTO -> {
            // 自动模式：默认 HOST（PRoot 由用户在新建标签时主动选择）
            TerminalBackend.HOST
        }
        TerminalPreferences.BackendMode.PROOT -> {
            // 强制 PRoot 模式：如果未安装 PRoot，回退到 HOST
            if (isPRootInstalled()) TerminalBackend.PROOT else TerminalBackend.HOST
        }
        TerminalPreferences.BackendMode.HOST -> {
            TerminalBackend.HOST
        }
    }

    /**
     * 检查 PRoot 是否已安装
     */
    fun isPRootInstalled(): Boolean = linuxEnvironmentProvider.get().isAvailable()

    suspend fun probeAvailability(): ShellAvailability = withContext(Dispatchers.IO) {
        val backend = resolveBackend()

        val available = buildSet {
            TerminalPreferences.ShellType.entries
                .filter { it != TerminalPreferences.ShellType.AUTO }
                .forEach { type ->
                    if (isShellAvailable(backend, type)) add(type)
                }
        }

        val autoResolved = resolveAutoShell(backend, available)
        ShellAvailability(
            backend = backend,
            availableShells = available,
            autoResolved = autoResolved
        )
    }

    suspend fun isShellAvailable(shellType: TerminalPreferences.ShellType): Boolean = withContext(Dispatchers.IO) {
        val backend = resolveBackend()
        isShellAvailable(backend, shellType)
    }

    /**
     * 使用指定后端解析 Shell（per-session backend）
     */
    suspend fun resolveForSession(
        backend: TerminalBackend,
        workDir: String,
        rows: Int,
        cols: Int,
    ): ShellResolveResult = withContext(Dispatchers.IO) {
        // rows/cols 目前由 Termux TerminalSession 在后续 resize/updateSize 中处理，这里仅用于保持接口稳定。
        val configured = terminalPrefs.terminalShellType
        val preferredOrder = when (configured) {
            TerminalPreferences.ShellType.AUTO -> listOf(
                TerminalPreferences.ShellType.ZSH,
                TerminalPreferences.ShellType.BASH,
                TerminalPreferences.ShellType.SH
            )

            TerminalPreferences.ShellType.ZSH -> listOf(TerminalPreferences.ShellType.ZSH)
            TerminalPreferences.ShellType.BASH -> listOf(TerminalPreferences.ShellType.BASH)
            TerminalPreferences.ShellType.SH -> listOf(TerminalPreferences.ShellType.SH)
        }

        var resolvedType: TerminalPreferences.ShellType? = null
        for (type in preferredOrder) {
            if (isShellAvailable(backend, type)) {
                resolvedType = type
                break
            }
        }
        val resolved = resolvedType ?: return@withContext ShellResolveResult.Error(buildNoShellMessage(context, backend, configured))

        val cwd = normalizeExistingDir(workDir)

        when (backend) {
            TerminalBackend.PROOT -> buildPRootResolution(resolvedType = resolved, cwd = cwd)
            TerminalBackend.HOST -> buildHostResolution(resolvedType = resolved, cwd = cwd)
        }
    }

    private suspend fun resolveAutoShell(
        backend: TerminalBackend,
        available: Set<TerminalPreferences.ShellType>
    ): TerminalPreferences.ShellType? {
        val order = listOf(
            TerminalPreferences.ShellType.ZSH,
            TerminalPreferences.ShellType.BASH,
            TerminalPreferences.ShellType.SH
        )
        for (type in order) {
            if (available.contains(type) && isShellAvailable(backend, type)) return type
        }
        return null
    }

    private suspend fun isShellAvailable(
        backend: TerminalBackend,
        shellType: TerminalPreferences.ShellType
    ): Boolean {
        if (shellType == TerminalPreferences.ShellType.AUTO) return false
        return try {
            when (backend) {
                TerminalBackend.PROOT -> {
                    val prootManager = resolvePRootEnvironment()?.getPRootManager() ?: return false
                    for (path in guestShellCandidates(shellType)) {
                        val probeCommand = buildGuestCommandAvailabilityProbe(path) ?: continue
                        val result = prootManager.execute(
                            command = probeCommand,
                            workDir = "/",
                            timeout = 8_000
                        )
                        if (result.exitCode == 0) return true
                    }
                    false
                }

                TerminalBackend.HOST -> {
                    hostShellCandidates(shellType).any { path ->
                        File(path).isFile && File(path).canExecute()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("TerminalShellResolver").w(e, "Failed to probe shell availability: %s (%s)", shellType, backend)
            false
        }
    }

    private suspend fun buildPRootResolution(
        resolvedType: TerminalPreferences.ShellType,
        cwd: String
    ): ShellResolveResult {
        val prootManager = resolvePRootEnvironment()?.getPRootManager()
            ?: return ShellResolveResult.Error(
                buildNoShellMessage(context, TerminalBackend.PROOT, terminalPrefs.terminalShellType)
            )

        val guestShellPath = run {
            for (candidate in guestShellCandidates(resolvedType)) {
                val probeCommand = buildGuestCommandAvailabilityProbe(candidate) ?: continue
                val result = prootManager.execute(
                    command = probeCommand,
                    workDir = "/",
                    timeout = 8_000
                )
                if (result.exitCode == 0) return@run candidate
            }
            null
        } ?: return ShellResolveResult.Error(buildNoShellMessage(context, TerminalBackend.PROOT, terminalPrefs.terminalShellType))

        val guestArgs = buildGuestShellArgs(resolvedType, guestShellPath)
        val prootCommand = prootManager.buildPRootCommandLine(
            command = listOf(guestShellPath) + guestArgs,
            workDir = cwd
        )

        val envMap = prootManager.buildExecEnvironment(
            extraEnv = mapOf(
                "LANG" to terminalPrefs.locale,
                "LC_ALL" to terminalPrefs.locale
            )
        ).toMutableMap()

        envMap["WORK_DIR"] = prootManager.toGuestPath(cwd)

        if (resolvedType == TerminalPreferences.ShellType.ZSH) {
            envMap["ZDOTDIR"] = prootManager.toGuestPath(cwd)
        }

        val resolution = ShellResolution(
            backend = TerminalBackend.PROOT,
            resolvedShellType = resolvedType,
            resolvedShellName = File(guestShellPath).name,
            shellPath = prootCommand.first(),
            argv = prootCommand.toTypedArray(),
            env = envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            cwd = normalizeExistingDir(cwd)
        )
        return ShellResolveResult.Success(resolution)
    }

    private fun buildHostResolution(
        resolvedType: TerminalPreferences.ShellType,
        cwd: String
    ): ShellResolveResult {
        val shellPath = hostShellCandidates(resolvedType).firstOrNull { candidate ->
            val file = File(candidate)
            file.isFile && file.canExecute()
        } ?: return ShellResolveResult.Error(buildNoShellMessage(context, TerminalBackend.HOST, terminalPrefs.terminalShellType))

        val argv = buildHostShellArgs(shellPath).toTypedArray()

        val env = buildList {
            add("TERM=xterm-256color")
            add("HOME=$cwd")
            add("PWD=$cwd")
            add("LANG=${terminalPrefs.locale}")
            add("LC_ALL=${terminalPrefs.locale}")
            val path = listOf(
                "/system/bin",
                "/system/xbin",
                "/bin",
                "/usr/bin",
                "/usr/local/bin",
                "/data/data/com.termux/files/usr/bin"
            ).joinToString(":")
            add("PATH=$path")
            if (File(shellPath).name == "zsh") {
                add("ZDOTDIR=$cwd")
            }
        }.toTypedArray()

        val resolution = ShellResolution(
            backend = TerminalBackend.HOST,
            resolvedShellType = resolvedType,
            resolvedShellName = File(shellPath).name,
            shellPath = shellPath,
            argv = argv,
            env = env,
            cwd = cwd
        )
        return ShellResolveResult.Success(resolution)
    }

    private fun buildGuestShellArgs(
        shellType: TerminalPreferences.ShellType,
        guestShellPath: String
    ): List<String> {
        val shellName = File(guestShellPath).name
        return when (shellName) {
            "zsh" -> listOf("-i", "-l")
            "bash" -> listOf("-i", "-l")
            "sh" -> listOf("-i")
            else -> when (shellType) {
                TerminalPreferences.ShellType.ZSH,
                TerminalPreferences.ShellType.BASH -> listOf("-i", "-l")
                TerminalPreferences.ShellType.SH -> listOf("-i")
                TerminalPreferences.ShellType.AUTO -> listOf("-i")
            }
        }
    }

    private fun buildHostShellArgs(shellPath: String): List<String> {
        val shellName = File(shellPath).name
        return when (shellName) {
            "zsh" -> listOf(shellPath, "-i", "-l")
            "bash" -> listOf(shellPath, "-i", "-l")
            "sh" -> listOf(shellPath, "-i")
            else -> listOf(shellPath, "-i")
        }
    }

    private fun guestShellCandidates(shellType: TerminalPreferences.ShellType): List<String> = when (shellType) {
        TerminalPreferences.ShellType.ZSH -> listOf("/usr/bin/zsh", "/bin/zsh")
        TerminalPreferences.ShellType.BASH -> listOf("/usr/bin/bash", "/bin/bash")
        TerminalPreferences.ShellType.SH -> listOf("/bin/sh", "/usr/bin/sh")
        TerminalPreferences.ShellType.AUTO -> emptyList()
    }

    private fun hostShellCandidates(shellType: TerminalPreferences.ShellType): List<String> {
        val name = when (shellType) {
            TerminalPreferences.ShellType.ZSH -> "zsh"
            TerminalPreferences.ShellType.BASH -> "bash"
            TerminalPreferences.ShellType.SH -> "sh"
            TerminalPreferences.ShellType.AUTO -> "sh"
        }
        return listOf(
            "/system/bin/$name",
            "/system/xbin/$name",
            "/bin/$name",
            "/usr/bin/$name",
            "/data/data/com.termux/files/usr/bin/$name"
        )
    }

    private fun normalizeExistingDir(path: String): String {
        if (path.isBlank()) return "/"
        return try {
            if (File(path).exists()) path else "/"
        } catch (e: Exception) {
            Timber.d(e, "TerminalShellResolver: normalizeExistingDir failed for: %s", path)
            "/"
        }
    }

    private fun buildNoShellMessage(
        context: Context,
        backend: TerminalBackend,
        configured: TerminalPreferences.ShellType
    ): String {
        val prefix = when (backend) {
            TerminalBackend.PROOT -> Strings.shell_not_found_proot.strOr(context)
            TerminalBackend.HOST -> Strings.shell_not_found_host.strOr(context)
        }
        val hint = when (configured) {
            TerminalPreferences.ShellType.AUTO -> Strings.shell_hint_auto.strOr(context)
            TerminalPreferences.ShellType.ZSH -> Strings.shell_hint_zsh.strOr(context)
            TerminalPreferences.ShellType.BASH -> Strings.shell_hint_bash.strOr(context)
            TerminalPreferences.ShellType.SH -> Strings.shell_hint_sh.strOr(context)
        }
        return Strings.shell_error_format.strOr(context, prefix, configured.value, hint)
    }
}

internal fun buildGuestCommandAvailabilityProbe(command: String): List<String>? {
    val normalized = command.trim()
    if (normalized.isEmpty()) return null
    return if (normalized.contains('/')) {
        listOf("/bin/sh", "-lc", "[ -x ${shellEscape(normalized)} ]")
    } else {
        listOf("/bin/sh", "-lc", "command -v ${shellEscape(normalized)} >/dev/null 2>&1")
    }
}

private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"

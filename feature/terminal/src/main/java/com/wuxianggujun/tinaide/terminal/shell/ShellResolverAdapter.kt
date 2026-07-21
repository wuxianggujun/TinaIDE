package com.wuxianggujun.tinaide.terminal.shell

import android.content.Context
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.terminal.IShellResolver
import com.wuxianggujun.tinaide.core.terminal.ShellAvailabilityInfo
import com.wuxianggujun.tinaide.core.terminal.ShellType
import com.wuxianggujun.tinaide.core.terminal.TerminalBackendType
import com.wuxianggujun.tinaide.terminal.preferences.TerminalPreferences

/**
 * Shell 解析器适配器
 *
 * 将 TerminalShellResolver 适配为 IShellResolver 接口。
 */
class ShellResolverAdapter(
    private val context: Context,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) : IShellResolver {

    private val resolver = TerminalShellResolver(context, linuxEnvironmentProvider)

    override suspend fun isShellAvailable(shellType: String): Boolean {
        val type = TerminalPreferences.ShellType.fromValue(shellType)
        return resolver.isShellAvailable(type)
    }

    override suspend fun probeAvailability(): ShellAvailabilityInfo {
        val availability = resolver.probeAvailability()
        return ShellAvailabilityInfo(
            backend = when (availability.backend) {
                TerminalBackend.PROOT -> TerminalBackendType.PROOT
                TerminalBackend.HOST -> TerminalBackendType.HOST
            },
            autoResolved = availability.autoResolved?.let { resolved ->
                ShellType.fromValue(resolved.value)
            },
            availableShells = availability.availableShells.map { shell ->
                ShellType.fromValue(shell.value)
            }
        )
    }

    override fun isPRootInstalled(): Boolean = resolver.isPRootInstalled()
}

package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import kotlin.coroutines.cancellation.CancellationException

/**
 * 在启动 XFCE 之前把 guest 侧的会话前置状态补齐。
 *
 * 这些都不是 `apt-get install xfce4` 会带来的东西，但缺了任何一项 XFCE 都起不来
 * 或起来后功能残缺，而失败信息又都很难定位：
 *
 * 1. `XDG_RUNTIME_DIR`：[UbuntuDesktopSessionLauncher] 会注入
 *    `/tmp/runtime-<user>`，但没人创建它。dbus / GTK / xfce4-notifyd 要求该目录存在
 *    且权限为 `0700`，否则会退化到 `/tmp` 并打印 "XDG_RUNTIME_DIR not owned by us"。
 * 2. `machine-id`：ubuntu-base tarball 里 `/etc/machine-id` 是空文件，
 *    `dbus-run-session` 在读到空值时会失败退出。
 * 3. `.ICEauthority` 的父目录（`$HOME`）：`startxfce4` 会在 `$HOME` 下写 ICE 授权文件。
 *
 * 所有命令都写成幂等的，重复调用不会破坏已有状态。
 */
class UbuntuDesktopSessionPreparer(
    private val linuxEnvironment: LinuxEnvironment,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
) {
    /**
     * @return 失败时带上 guest 的合并输出——这里的错误几乎都来自文件权限，
     *   原始 stderr 比包装后的消息有用得多。
     */
    suspend fun prepare(options: UbuntuDesktopSessionOptions): Result<Unit> = try {
        val runtimeDir = runtimeDirFor(options.username)
        val script = buildScript(
            runtimeDir = runtimeDir,
            homeDir = options.workingDirectory,
        )
        val result = linuxEnvironment.execute(
            command = listOf("/bin/sh", "-c", script),
            workDir = "/",
            timeout = commandTimeoutMs,
        )
        if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    result.combinedOutput.ifBlank { "Ubuntu desktop session preparation failed" },
                )
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun buildScript(runtimeDir: String, homeDir: String): String {
        val quotedRuntimeDir = shellSingleQuoted(runtimeDir)
        val quotedHomeDir = shellSingleQuoted(homeDir)
        // set -e：任一步失败就带非零退出码返回，避免"准备成功但目录没建出来"。
        return listOf(
            "set -e",
            "mkdir -p $quotedRuntimeDir",
            "chmod 700 $quotedRuntimeDir",
            "mkdir -p $quotedHomeDir",
            // machine-id 只在缺失或为空时生成；已有值必须保留，
            // 否则每次启动都会让 dbus 认为换了机器。
            "if [ ! -s /etc/machine-id ]; then",
            "  if command -v dbus-uuidgen >/dev/null 2>&1; then",
            "    dbus-uuidgen > /etc/machine-id",
            "  else",
            "    cat /proc/sys/kernel/random/uuid | tr -d '-' > /etc/machine-id",
            "  fi",
            "fi",
            // dbus 实际读的是 /var/lib/dbus/machine-id，Debian 系约定它是前者的软链。
            "mkdir -p /var/lib/dbus",
            "if [ ! -s /var/lib/dbus/machine-id ]; then",
            "  ln -sf /etc/machine-id /var/lib/dbus/machine-id",
            "fi",
        ).joinToString("\n")
    }

    private fun shellSingleQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /**
         * 必须与 [UbuntuDesktopSessionLauncher] 注入的 `XDG_RUNTIME_DIR` 完全一致，
         * 否则准备的目录和会话实际使用的目录会对不上。
         */
        fun runtimeDirFor(username: String): String = "/tmp/runtime-$username"
    }
}

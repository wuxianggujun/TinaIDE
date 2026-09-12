package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 一条已经组装完毕、可以直接 `exec` 的 host 命令行。
 *
 * 存在的理由是进程边界：guest 桌面会话必须由 `:x11` 进程 spawn（init-proot.sh 带
 * `--kill-on-exit`，proot 树的存亡跟着 spawn 它的进程），但只有主进程有
 * `PRootManager`——它知道 proot 二进制在哪、init 脚本在哪、要注入哪些环境变量。
 * 让本模块反过来依赖 `:core:proot` 会构成 Gradle 循环（proot 已经依赖本模块），
 * 而在 `:x11` 里起 Koin 又会把 database/plugin/editor 一并拖进 X server 进程。
 *
 * 所以主进程负责"算出要跑什么"，`:x11` 只负责"跑"。本类就是两者之间传输的那份计划。
 */
data class LinuxDesktopHostProcessPlan(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
) {
    init {
        require(argv.isNotEmpty() && argv.first().isNotBlank()) {
            "Host process plan must define a non-blank executable"
        }
        require(argv.none { argument -> argument.containsControlCharacter() }) {
            "Host process arguments must not contain control characters"
        }
        require(workingDirectory.isNotBlank()) { "Host working directory must not be blank" }
        environment.forEach { (key, value) ->
            require(ENVIRONMENT_KEY_PATTERN.matches(key)) {
                "Invalid environment variable name: $key"
            }
            // 环境是以 `KEY=VALUE` 数组形式跨 binder 传的，NUL 与换行都会破坏这个编码。
            require(!value.containsControlCharacter()) {
                "Environment variable $key contains an unsupported control character"
            }
        }
    }

    /** 编码成 `Runtime.exec` 需要的 `KEY=VALUE` 数组。 */
    fun toEnvironmentArray(): Array<String> =
        environment.map { (key, value) -> "$key=$value" }.toTypedArray()

    companion object {
        private val ENVIRONMENT_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /**
         * 从跨进程传来的 `KEY=VALUE` 数组还原。
         *
         * 忽略缺少 `=` 或键为空的条目，而不是抛异常：这些条目对 `exec` 没有意义，
         * 为它们让整个桌面启动失败不值得。
         */
        fun decodeEnvironment(environment: Array<String>): Map<String, String> =
            environment.mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    entry.substring(0, separator) to entry.substring(separator + 1)
                }
            }.toMap()
    }
}

/** NUL / CR / LF：跨 binder 的 `KEY=VALUE` 编码和 `execve` 的 argv 都容不下它们。 */
private fun String.containsControlCharacter(): Boolean = any { character ->
    character.code == 0 || character == '\r' || character == '\n'
}

/**
 * 把一份 [LinuxDesktopHostProcessPlan] 变成真实进程。
 *
 * 用 `Runtime.exec` 而不是 `ProcessBuilder`：环境需要整体替换（proot 依赖一组精确的
 * 变量，继承 `:x11` 进程的环境会带进 Android 的 `ANDROID_*` 等干扰项），
 * `exec(cmdarray, envp, dir)` 正是替换语义。
 */
internal object LinuxDesktopHostProcessSpawner {
    fun spawn(plan: LinuxDesktopHostProcessPlan): LinuxDesktopSession {
        val workingDirectory = File(plan.workingDirectory).apply { mkdirs() }
        val process = Runtime.getRuntime().exec(
            plan.argv.toTypedArray(),
            plan.toEnvironmentArray(),
            workingDirectory,
        )
        return LinuxDesktopSession(HostInteractiveProcess(process))
    }
}

/** [LinuxInteractiveProcess] 到 JVM [Process] 的适配，让 `:x11` 侧复用既有会话语义。 */
internal class HostInteractiveProcess(
    private val process: Process,
) : LinuxInteractiveProcess {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    override fun isRunning(): Boolean = process.isAlive

    override fun waitFor(timeout: Long): Int = if (timeout > 0L) {
        if (process.waitFor(timeout, TimeUnit.MILLISECONDS)) process.exitValue() else -1
    } else {
        process.waitFor()
    }

    override fun destroy() {
        process.destroyForcibly()
    }
}

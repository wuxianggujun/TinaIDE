package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime endpoints supplied by the Android display/audio backend. */
data class LinuxDesktopEndpoint(
    val display: String,
    /**
     * guest 的 `PULSE_SERVER`，形如 `tcp:127.0.0.1:4713`。
     *
     * **当前没有任何调用方会填这个值。** 我们内置的 lorie X server 只搬像素和输入，
     * 不带任何音频通道，宿主这边也没有在跑 PulseAudio daemon；填了只会让 guest 里的
     * 客户端连一个不存在的端口。留着这个参数是因为它就是一条直通的环境变量映射：
     * 哪天真接了宿主音频端点，从这里传进来即可，不用改 planner。
     */
    val audioServer: String? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(X11_DISPLAY_PATTERN.matches(display)) { "Invalid X11 display: $display" }
        audioServer?.let {
            require(it.isNotBlank()) { "Audio server must not be blank" }
            requireSafeEnvironmentValue("audioServer", it)
        }
        requireSafeEnvironment(environment)
    }
}

/** Guest command and environment for one desktop session. */
data class LinuxDesktopLaunchSpec(
    val command: List<String>,
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotEmpty() && command.first().isNotBlank()) {
            "Desktop command must define a non-blank executable"
        }
        require(command.none { argument -> '\u0000' in argument }) {
            "Desktop command arguments must not contain NUL"
        }
        require(workingDirectory.isNotBlank()) { "Desktop working directory must not be blank" }
        requireSafeEnvironment(environment)
    }
}

/**
 * 把 endpoint + spec 归并成一份 guest 命令与环境。
 *
 * 只做计算，不 spawn 进程：真正的 `exec` 由 `:x11` 进程完成（见
 * [LinuxDesktopHostProcessPlan] 说明的进程归属），主进程这边只负责算清楚
 * "要在 guest 里跑什么、带哪些环境变量"。
 */
class LinuxDesktopSessionPlanner {
    fun plan(
        endpoint: LinuxDesktopEndpoint,
        spec: LinuxDesktopLaunchSpec,
    ): LinuxDesktopGuestPlan = LinuxDesktopGuestPlan(
        command = spec.command,
        workingDirectory = spec.workingDirectory,
        environment = buildDesktopEnvironment(endpoint, spec.environment),
    )
}

/** guest 侧要跑的命令与环境；host 侧的 proot 包装由 [LinuxDesktopHostProcessPlan] 负责。 */
data class LinuxDesktopGuestPlan(
    val command: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
)

/** Ubuntu-specific session planner using the stable generic desktop contract. */
class UbuntuDesktopSessionPlanner {
    private val delegate = LinuxDesktopSessionPlanner()

    fun plan(options: UbuntuDesktopSessionOptions): LinuxDesktopGuestPlan {
        val managedEnvironment = buildMap {
            putAll(options.environment)
            // These values are part of the Ubuntu desktop contract and must
            // win over caller-provided overrides; otherwise a malformed
            // launch can silently escape the X11/FCITX session boundary.
            // 目录本身由 UbuntuDesktopSessionPreparer 创建（0700）；共用同一函数
            // 计算路径，避免两边各写一份字符串后走偏。
            put("XDG_RUNTIME_DIR", UbuntuDesktopSessionPreparer.runtimeDirFor(options.username))
            put("XDG_SESSION_TYPE", "x11")
            put("GDK_BACKEND", "x11")
            put("QT_QPA_PLATFORM", "xcb")
            put("LANG", options.locale)
            put("LC_ALL", options.locale)
            put("XMODIFIERS", "@im=fcitx")
            put("GTK_IM_MODULE", "fcitx")
            put("QT_IM_MODULE", "fcitx")
            putAll(options.gpuBackend.environment())
        }
        val endpoint = options.endpoint.copy(
            environment = options.endpoint.environment + managedEnvironment,
        )
        return delegate.plan(
            endpoint = endpoint,
            spec = LinuxDesktopLaunchSpec(
                command = options.command,
                workingDirectory = options.workingDirectory,
            ),
        )
    }
}

/**
 * 一个 guest 桌面会话。
 *
 * 注意进程归属：真正跑 XFCE 的 proot 树由 `:x11` 进程 spawn（见
 * [LinuxDesktopHostProcessPlan]），所以本类的实例只在 `:x11` 内部存在。
 * 主进程通过 [IX11ServerController] 观察会话状态，不持有它。
 */
class LinuxDesktopSession internal constructor(
    private val process: LinuxInteractiveProcess,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)

    fun isRunning(): Boolean = !stopped.get() && process.isRunning()

    fun waitFor(timeoutMs: Long = 0): Int = process.waitFor(timeoutMs)

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            process.destroy()
        }
    }

    override fun close() = stop()
}

internal fun buildDesktopEnvironment(
    endpoint: LinuxDesktopEndpoint,
    sessionEnvironment: Map<String, String>,
): Map<String, String> = buildMap {
    sessionEnvironment
        .filterKeys { key -> key !in MANAGED_ENDPOINT_KEYS }
        .forEach(::put)
    endpoint.environment
        .filterKeys { key -> key !in MANAGED_ENDPOINT_KEYS }
        .forEach(::put)
    put(ENV_DISPLAY, endpoint.display)
    endpoint.audioServer?.let { server -> put(ENV_PULSE_SERVER, server) }
}

private fun requireSafeEnvironment(environment: Map<String, String>) {
    environment.forEach { (key, value) ->
        require(ENVIRONMENT_KEY_PATTERN.matches(key)) { "Invalid environment variable name: $key" }
        requireSafeEnvironmentValue(key, value)
    }
}

private fun requireSafeEnvironmentValue(name: String, value: String) {
    require(value.none { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
        "Environment variable $name contains an unsupported control character"
    }
}

private const val ENV_DISPLAY = "DISPLAY"
private const val ENV_PULSE_SERVER = "PULSE_SERVER"
private val MANAGED_ENDPOINT_KEYS = setOf(ENV_DISPLAY, ENV_PULSE_SERVER)
private val ENVIRONMENT_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val X11_DISPLAY_PATTERN = Regex("^(?:[A-Za-z0-9._-]+)?:[0-9]+(?:\\.[0-9]+)?$")

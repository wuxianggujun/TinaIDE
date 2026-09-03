package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime endpoints supplied by the Android display/audio backend. */
data class LinuxDesktopEndpoint(
    val display: String,
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

class LinuxDesktopSessionLauncher(
    private val linuxEnvironment: LinuxEnvironment,
) {
    fun launch(
        endpoint: LinuxDesktopEndpoint,
        spec: LinuxDesktopLaunchSpec,
    ): Result<LinuxDesktopSession> = runCatching {
        check(linuxEnvironment.isAvailable()) { "Linux environment is unavailable" }

        val process = linuxEnvironment.startInteractive(
            command = spec.command,
            workDir = spec.workingDirectory,
            env = buildDesktopEnvironment(endpoint, spec.environment),
        )
        LinuxDesktopSession(process)
    }
}

/** Ubuntu-specific session launcher using the stable generic desktop contract. */
class UbuntuDesktopSessionLauncher(
    private val linuxEnvironment: LinuxEnvironment,
) {
    private val delegate = LinuxDesktopSessionLauncher(linuxEnvironment)

    fun launch(options: UbuntuDesktopSessionOptions): Result<LinuxDesktopSession> {
        val managedEnvironment = buildMap {
            putAll(options.environment)
            // These values are part of the Ubuntu desktop contract and must
            // win over caller-provided overrides; otherwise a malformed
            // launch can silently escape the X11/FCITX session boundary.
            put("XDG_RUNTIME_DIR", "/tmp/runtime-${options.username}")
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
        return delegate.launch(
            endpoint = endpoint,
            spec = LinuxDesktopLaunchSpec(
                command = options.command,
                workingDirectory = options.workingDirectory,
            ),
        )
    }
}

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

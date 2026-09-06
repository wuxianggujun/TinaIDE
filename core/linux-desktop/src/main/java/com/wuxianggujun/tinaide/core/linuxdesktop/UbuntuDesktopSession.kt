package com.wuxianggujun.tinaide.core.linuxdesktop

/**
 * Rendering strategy used by the Ubuntu guest.
 *
 * The Android host decides whether a real GPU bridge is available. The guest
 * only receives the small set of Mesa variables needed by that decision.
 */
enum class UbuntuDesktopGpuBackend {
    SOFTWARE,
    VIRGL,
    ZINK,
}

/** Immutable launch options for an Ubuntu XFCE session. */
data class UbuntuDesktopSessionOptions(
    val endpoint: LinuxDesktopEndpoint,
    val username: String = "root",
    val workingDirectory: String = "/root",
    val locale: String = "C.UTF-8",
    val gpuBackend: UbuntuDesktopGpuBackend = UbuntuDesktopGpuBackend.SOFTWARE,
    val environment: Map<String, String> = emptyMap(),
    val command: List<String> = DEFAULT_SESSION_COMMAND,
) {
    init {
        require(USERNAME_PATTERN.matches(username)) {
            "Ubuntu desktop username must be a simple POSIX name"
        }
        require(LOCALE_PATTERN.matches(locale)) {
            "Ubuntu desktop locale contains unsupported characters"
        }
        require(command.isNotEmpty() && command.first().isNotBlank()) {
            "Ubuntu desktop command must not be empty"
        }
        require(command.none { argument -> '\u0000' in argument }) {
            "Ubuntu desktop command arguments must not contain NUL"
        }
        requireSafeDesktopEnvironment(environment)
    }

    companion object {
        val DEFAULT_SESSION_COMMAND: List<String> = listOf(
            "dbus-run-session",
            "--",
            "startxfce4",
        )

        private val USERNAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,31}$")
        private val LOCALE_PATTERN = Regex("^[A-Za-z0-9_.@+-]+$")
    }
}

internal fun UbuntuDesktopGpuBackend.environment(): Map<String, String> = when (this) {
    UbuntuDesktopGpuBackend.SOFTWARE -> mapOf(
        "LIBGL_ALWAYS_SOFTWARE" to "1",
    )

    UbuntuDesktopGpuBackend.VIRGL -> mapOf(
        "GALLIUM_DRIVER" to "virpipe",
        "MESA_LOADER_DRIVER_OVERRIDE" to "virpipe",
    )

    UbuntuDesktopGpuBackend.ZINK -> mapOf(
        "GALLIUM_DRIVER" to "zink",
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
        "LIBGL_ALWAYS_SOFTWARE" to "0",
    )
}

internal fun requireSafeDesktopEnvironment(environment: Map<String, String>) {
    environment.forEach { (key, value) ->
        require(DESKTOP_ENVIRONMENT_KEY_PATTERN.matches(key)) {
            "Invalid desktop environment variable name: $key"
        }
        require(value.none { character ->
            character == '\u0000' || character == '\r' || character == '\n'
        }) {
            "Desktop environment variable $key contains an unsupported control character"
        }
    }
}

private val DESKTOP_ENVIRONMENT_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

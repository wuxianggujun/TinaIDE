package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.config.ClangdSettings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfig
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMethod
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode

internal enum class LspSettingsStatusTone {
    Normal,
    Positive,
    Negative
}

internal data class LspSettingsOptionSpec(
    val value: String,
    @param:StringRes @get:StringRes val labelRes: Int
)

internal data class RemoteLspDetectedModeState(
    @param:StringRes @get:StringRes val modeLabelRes: Int,
    val reason: String
)

internal data class RemoteLspSectionState(
    @param:StringRes @get:StringRes val syncModeLabelRes: Int,
    val syncModeShowDivider: Boolean,
    val detectedMode: RemoteLspDetectedModeState? = null,
    val showSyncMethod: Boolean,
    @param:StringRes @get:StringRes val syncMethodLabelRes: Int? = null,
    val syncMethodShowDivider: Boolean = false,
    val showRsyncSettings: Boolean = false,
    @param:StringRes @get:StringRes val connectionStatusLabelRes: Int,
    val connectionStatusTone: LspSettingsStatusTone,
    val testButtonEnabled: Boolean
)

internal object LspSettingsSectionSupport {

    fun buildClangdRunModeOptions(linuxEnvironmentEnabled: Boolean): List<LspSettingsOptionSpec> = buildList {
        add(
            LspSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_clangd_run_mode_native
            )
        )
        if (linuxEnvironmentEnabled) {
            add(
                LspSettingsOptionSpec(
                    value = "proot",
                    labelRes = Strings.settings_clangd_run_mode_proot
                )
            )
        }
    }

    @StringRes
    fun resolveClangdRunModeLabel(runMode: String): Int = if (runMode == "proot") {
        Strings.settings_clangd_run_mode_proot
    } else {
        Strings.settings_clangd_run_mode_native
    }

    @StringRes
    fun resolveClangdHeaderInsertionLabel(headerInsertion: String): Int = if (headerInsertion == ClangdSettings.HeaderInsertionMode.NEVER.value) {
        Strings.settings_clangd_header_insertion_never
    } else {
        Strings.settings_clangd_header_insertion_iwyu
    }

    @StringRes
    fun resolveClangdCompletionStyleLabel(completionStyle: String): Int = if (completionStyle == ClangdSettings.CompletionStyle.BUNDLED.value) {
        Strings.settings_clangd_completion_style_bundled
    } else {
        Strings.settings_clangd_completion_style_detailed
    }

    fun resolveRemoteLspSectionState(
        config: RemoteLspConfig,
        connectionState: RemoteLspConnectionState,
        detectedSyncMode: Pair<RemoteLspSyncMode, String>?
    ): RemoteLspSectionState {
        val detectedModeState = if (config.syncMode == RemoteLspSyncMode.AUTO &&
            detectedSyncMode != null
        ) {
            RemoteLspDetectedModeState(
                modeLabelRes = resolveSyncModeLabel(detectedSyncMode.first),
                reason = detectedSyncMode.second
            )
        } else {
            null
        }
        val showSyncMethod = config.syncMode == RemoteLspSyncMode.PROJECT ||
            (
                config.syncMode == RemoteLspSyncMode.AUTO &&
                    detectedSyncMode?.first == RemoteLspSyncMode.PROJECT
                )
        return RemoteLspSectionState(
            syncModeLabelRes = resolveSyncModeLabel(config.syncMode),
            syncModeShowDivider = detectedModeState == null,
            detectedMode = detectedModeState,
            showSyncMethod = showSyncMethod,
            syncMethodLabelRes = if (showSyncMethod) {
                resolveSyncMethodLabel(config.syncMethod)
            } else {
                null
            },
            syncMethodShowDivider = showSyncMethod &&
                config.syncMethod == RemoteLspSyncMethod.RSYNC,
            showRsyncSettings = showSyncMethod &&
                config.syncMethod == RemoteLspSyncMethod.RSYNC,
            connectionStatusLabelRes = resolveConnectionStatusLabel(connectionState),
            connectionStatusTone = resolveConnectionStatusTone(connectionState),
            testButtonEnabled = config.enabled
        )
    }

    @StringRes
    fun validateRemoteHost(input: String): Int? {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> null
            !RemoteLspConfig(host = trimmed).hasValidHostSyntax() -> Strings.editor_lsp_hint_host_input
            else -> null
        }
    }

    @StringRes
    fun resolveRemoteHostHint(input: String): Int? {
        val trimmed = input.trim()
        return if (
            trimmed == "127.0.0.1" ||
            trimmed == "::1" ||
            trimmed == "[::1]" ||
            trimmed.equals("localhost", ignoreCase = true)
        ) {
            Strings.editor_lsp_hint_localhost
        } else {
            null
        }
    }

    fun normalizeRemoteHost(input: String): String = input.trim()

    @StringRes
    fun validatePortInput(input: String): Int? {
        val port = input.toIntOrNull()
        return if (port == null || port !in 1..65535) {
            Strings.editor_lsp_error_port_range
        } else {
            null
        }
    }

    fun parsePortInput(input: String, fallback: Int): Int = input.toIntOrNull() ?: fallback

    @StringRes
    fun validateRemoteWorkspaceRootUri(input: String): Int? {
        val trimmed = input.trim()
        return if (trimmed.isNotEmpty() &&
            !trimmed.startsWith("file://") &&
            !trimmed.matches(Regex("^[A-Za-z]:[\\\\/].+")) &&
            !trimmed.startsWith("/")
        ) {
            Strings.settings_remote_lsp_workspace_root_uri_error
        } else {
            null
        }
    }

    fun normalizeRemoteWorkspaceRootUri(input: String): String = input.trim()

    @StringRes
    fun validateRsyncModule(input: String): Int? {
        val trimmed = input.trim()
        return when {
            trimmed.isBlank() -> Strings.editor_lsp_error_module_name
            trimmed.contains(" ") || trimmed.contains("/") -> Strings.editor_lsp_error_module_name
            else -> null
        }
    }

    fun normalizeRsyncModule(input: String): String = input.trim()

    @StringRes
    private fun resolveSyncModeLabel(syncMode: RemoteLspSyncMode): Int = when (syncMode) {
        RemoteLspSyncMode.AUTO -> Strings.settings_remote_lsp_sync_mode_auto
        RemoteLspSyncMode.LIGHTWEIGHT -> Strings.settings_remote_lsp_sync_mode_lightweight
        RemoteLspSyncMode.PROJECT -> Strings.settings_remote_lsp_sync_mode_project
    }

    @StringRes
    private fun resolveSyncMethodLabel(syncMethod: RemoteLspSyncMethod): Int = when (syncMethod) {
        RemoteLspSyncMethod.BUILTIN -> Strings.settings_remote_lsp_sync_method_builtin
        RemoteLspSyncMethod.RSYNC -> Strings.settings_remote_lsp_sync_method_rsync
        RemoteLspSyncMethod.MANUAL -> Strings.settings_remote_lsp_sync_method_manual
    }

    @StringRes
    private fun resolveConnectionStatusLabel(
        connectionState: RemoteLspConnectionState
    ): Int = when (connectionState) {
        RemoteLspConnectionState.DISCONNECTED -> Strings.settings_remote_lsp_status_disconnected
        RemoteLspConnectionState.CONNECTING -> Strings.settings_remote_lsp_status_connecting
        RemoteLspConnectionState.CONNECTED -> Strings.settings_remote_lsp_status_connected
        RemoteLspConnectionState.ERROR -> Strings.settings_remote_lsp_status_error
    }

    private fun resolveConnectionStatusTone(
        connectionState: RemoteLspConnectionState
    ): LspSettingsStatusTone = when (connectionState) {
        RemoteLspConnectionState.CONNECTED -> LspSettingsStatusTone.Positive
        RemoteLspConnectionState.ERROR -> LspSettingsStatusTone.Negative
        RemoteLspConnectionState.DISCONNECTED,
        RemoteLspConnectionState.CONNECTING -> LspSettingsStatusTone.Normal
    }
}

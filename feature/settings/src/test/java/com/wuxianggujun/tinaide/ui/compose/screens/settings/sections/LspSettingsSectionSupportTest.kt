package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ClangdSettings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfig
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMethod
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode
import org.junit.Test

class LspSettingsSectionSupportTest {

    @Test
    fun buildClangdRunModeOptions_shouldOnlyExposeNativeWhenLinuxEnvironmentDisabled() {
        assertThat(
            LspSettingsSectionSupport.buildClangdRunModeOptions(
                linuxEnvironmentEnabled = false
            )
        ).containsExactly(
            LspSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_clangd_run_mode_native
            )
        )
    }

    @Test
    fun buildClangdRunModeOptions_shouldExposeProotWhenLinuxEnvironmentEnabled() {
        assertThat(
            LspSettingsSectionSupport.buildClangdRunModeOptions(
                linuxEnvironmentEnabled = true
            )
        ).containsExactly(
            LspSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_clangd_run_mode_native
            ),
            LspSettingsOptionSpec(
                value = "proot",
                labelRes = Strings.settings_clangd_run_mode_proot
            )
        ).inOrder()
    }

    @Test
    fun resolveClangdLabels_shouldFallbackToDefaultOptions() {
        assertThat(
            LspSettingsSectionSupport.resolveClangdRunModeLabel("proot")
        ).isEqualTo(Strings.settings_clangd_run_mode_proot)
        assertThat(
            LspSettingsSectionSupport.resolveClangdRunModeLabel("unexpected")
        ).isEqualTo(Strings.settings_clangd_run_mode_native)

        assertThat(
            LspSettingsSectionSupport.resolveClangdHeaderInsertionLabel(
                ClangdSettings.HeaderInsertionMode.NEVER.value
            )
        ).isEqualTo(Strings.settings_clangd_header_insertion_never)
        assertThat(
            LspSettingsSectionSupport.resolveClangdHeaderInsertionLabel("unexpected")
        ).isEqualTo(Strings.settings_clangd_header_insertion_iwyu)

        assertThat(
            LspSettingsSectionSupport.resolveClangdCompletionStyleLabel(
                ClangdSettings.CompletionStyle.BUNDLED.value
            )
        ).isEqualTo(Strings.settings_clangd_completion_style_bundled)
        assertThat(
            LspSettingsSectionSupport.resolveClangdCompletionStyleLabel("unexpected")
        ).isEqualTo(Strings.settings_clangd_completion_style_detailed)
    }

    @Test
    fun resolveRemoteLspSectionState_shouldHideProjectControlsWhenAutoModeUndetected() {
        val state = LspSettingsSectionSupport.resolveRemoteLspSectionState(
            config = RemoteLspConfig(
                enabled = true,
                syncMode = RemoteLspSyncMode.AUTO,
                syncMethod = RemoteLspSyncMethod.BUILTIN
            ),
            connectionState = RemoteLspConnectionState.DISCONNECTED,
            detectedSyncMode = null
        )

        assertThat(state.syncModeLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_sync_mode_auto)
        assertThat(state.syncModeShowDivider).isTrue()
        assertThat(state.detectedMode).isNull()
        assertThat(state.showSyncMethod).isFalse()
        assertThat(state.syncMethodLabelRes).isNull()
        assertThat(state.syncMethodShowDivider).isFalse()
        assertThat(state.showRsyncSettings).isFalse()
        assertThat(state.connectionStatusLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_status_disconnected)
        assertThat(state.connectionStatusTone).isEqualTo(LspSettingsStatusTone.Normal)
        assertThat(state.testButtonEnabled).isTrue()
    }

    @Test
    fun resolveRemoteLspSectionState_shouldShowDetectedProjectModeAndRsyncControls() {
        val state = LspSettingsSectionSupport.resolveRemoteLspSectionState(
            config = RemoteLspConfig(
                enabled = true,
                syncMode = RemoteLspSyncMode.AUTO,
                syncMethod = RemoteLspSyncMethod.RSYNC
            ),
            connectionState = RemoteLspConnectionState.CONNECTED,
            detectedSyncMode = RemoteLspSyncMode.PROJECT to "workspace too large"
        )

        assertThat(state.syncModeShowDivider).isFalse()
        assertThat(state.detectedMode).isNotNull()
        assertThat(state.detectedMode?.modeLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_sync_mode_project)
        assertThat(state.detectedMode?.reason).isEqualTo("workspace too large")
        assertThat(state.showSyncMethod).isTrue()
        assertThat(state.syncMethodLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_sync_method_rsync)
        assertThat(state.syncMethodShowDivider).isTrue()
        assertThat(state.showRsyncSettings).isTrue()
        assertThat(state.connectionStatusLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_status_connected)
        assertThat(state.connectionStatusTone).isEqualTo(LspSettingsStatusTone.Positive)
        assertThat(state.testButtonEnabled).isTrue()
    }

    @Test
    fun resolveRemoteLspSectionState_shouldRespectExplicitProjectModeAndErrorStatus() {
        val state = LspSettingsSectionSupport.resolveRemoteLspSectionState(
            config = RemoteLspConfig(
                enabled = false,
                syncMode = RemoteLspSyncMode.PROJECT,
                syncMethod = RemoteLspSyncMethod.MANUAL
            ),
            connectionState = RemoteLspConnectionState.ERROR,
            detectedSyncMode = RemoteLspSyncMode.LIGHTWEIGHT to "ignored"
        )

        assertThat(state.syncModeLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_sync_mode_project)
        assertThat(state.detectedMode).isNull()
        assertThat(state.showSyncMethod).isTrue()
        assertThat(state.syncMethodLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_sync_method_manual)
        assertThat(state.syncMethodShowDivider).isFalse()
        assertThat(state.showRsyncSettings).isFalse()
        assertThat(state.connectionStatusLabelRes)
            .isEqualTo(Strings.settings_remote_lsp_status_error)
        assertThat(state.connectionStatusTone).isEqualTo(LspSettingsStatusTone.Negative)
        assertThat(state.testButtonEnabled).isFalse()
    }

    @Test
    fun validateRemoteHost_shouldRejectSchemesAndSpaces() {
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("ws://example.com")
        ).isEqualTo(Strings.editor_lsp_hint_host_input)
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("bad host")
        ).isEqualTo(Strings.editor_lsp_hint_host_input)
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("example.com")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("example.com/path")
        ).isEqualTo(Strings.editor_lsp_hint_host_input)
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("user@example.com")
        ).isEqualTo(Strings.editor_lsp_hint_host_input)
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("a".repeat(254))
        ).isEqualTo(Strings.editor_lsp_hint_host_input)
        assertThat(
            LspSettingsSectionSupport.validateRemoteHost("::1")
        ).isNull()
    }

    @Test
    fun resolveRemoteHostHintAndNormalization_shouldHandleLoopbackHosts() {
        assertThat(
            LspSettingsSectionSupport.resolveRemoteHostHint("localhost")
        ).isEqualTo(Strings.editor_lsp_hint_localhost)
        assertThat(
            LspSettingsSectionSupport.resolveRemoteHostHint("127.0.0.1")
        ).isEqualTo(Strings.editor_lsp_hint_localhost)
        assertThat(
            LspSettingsSectionSupport.resolveRemoteHostHint("example.com")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.normalizeRemoteHost("  example.com  ")
        ).isEqualTo("example.com")
    }

    @Test
    fun validatePortInputAndParse_shouldEnforceRangeAndFallback() {
        assertThat(
            LspSettingsSectionSupport.validatePortInput("0")
        ).isEqualTo(Strings.editor_lsp_error_port_range)
        assertThat(
            LspSettingsSectionSupport.validatePortInput("65536")
        ).isEqualTo(Strings.editor_lsp_error_port_range)
        assertThat(
            LspSettingsSectionSupport.validatePortInput("8080")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.parsePortInput("7000", fallback = 6789)
        ).isEqualTo(7000)
        assertThat(
            LspSettingsSectionSupport.parsePortInput("bad", fallback = 6789)
        ).isEqualTo(6789)
    }

    @Test
    fun validateRemoteWorkspaceRootUri_shouldSupportFileUnixAndWindowsPaths() {
        assertThat(
            LspSettingsSectionSupport.validateRemoteWorkspaceRootUri("")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.validateRemoteWorkspaceRootUri("file:///workspace")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.validateRemoteWorkspaceRootUri("/workspace")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.validateRemoteWorkspaceRootUri("C:/workspace")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.validateRemoteWorkspaceRootUri("workspace")
        ).isEqualTo(Strings.settings_remote_lsp_workspace_root_uri_error)
        assertThat(
            LspSettingsSectionSupport.normalizeRemoteWorkspaceRootUri("  /workspace  ")
        ).isEqualTo("/workspace")
    }

    @Test
    fun validateRsyncModuleAndNormalization_shouldRejectInvalidNames() {
        assertThat(
            LspSettingsSectionSupport.validateRsyncModule("")
        ).isEqualTo(Strings.editor_lsp_error_module_name)
        assertThat(
            LspSettingsSectionSupport.validateRsyncModule("bad module")
        ).isEqualTo(Strings.editor_lsp_error_module_name)
        assertThat(
            LspSettingsSectionSupport.validateRsyncModule("bad/module")
        ).isEqualTo(Strings.editor_lsp_error_module_name)
        assertThat(
            LspSettingsSectionSupport.validateRsyncModule("workspace")
        ).isNull()
        assertThat(
            LspSettingsSectionSupport.normalizeRsyncModule("  workspace  ")
        ).isEqualTo("workspace")
    }
}

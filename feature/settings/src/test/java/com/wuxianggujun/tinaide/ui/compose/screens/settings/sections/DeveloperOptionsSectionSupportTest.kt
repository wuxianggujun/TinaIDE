package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.server.ClientConfig
import com.wuxianggujun.tinaide.core.network.server.FeatureFlags
import com.wuxianggujun.tinaide.core.network.server.ServerConfigResponse
import org.junit.Test

class DeveloperOptionsSectionSupportTest {

    @Test
    fun resolveDiagnosticsControlsState_shouldDisableAllDependentControlsWhenDiagnosticsOff() {
        val state = DeveloperOptionsSectionSupport.resolveDiagnosticsControlsState(
            diagnosticsEnabled = false,
            editorTouchDiagnosticsEnabled = true
        )

        assertThat(state.lspCompileCommandsSelectionLogControlEnabled).isFalse()
        assertThat(state.buildDiagnosticsLogControlEnabled).isFalse()
        assertThat(state.lspClangdStartupLogControlEnabled).isFalse()
        assertThat(state.editorTouchDiagnosticsControlEnabled).isFalse()
        assertThat(state.gestureTraceControlEnabled).isFalse()
        assertThat(state.editorInternalTouchLogControlEnabled).isFalse()
        assertThat(state.editorScaleLogControlEnabled).isFalse()
        assertThat(state.editorFocusLogControlEnabled).isFalse()
        assertThat(state.editorScrollLogControlEnabled).isFalse()
        assertThat(state.editorFlingLogControlEnabled).isFalse()
    }

    @Test
    fun resolveDiagnosticsControlsState_shouldEnableOnlyTopLevelControlsWhenTouchDiagnosticsOff() {
        val state = DeveloperOptionsSectionSupport.resolveDiagnosticsControlsState(
            diagnosticsEnabled = true,
            editorTouchDiagnosticsEnabled = false
        )

        assertThat(state.lspCompileCommandsSelectionLogControlEnabled).isTrue()
        assertThat(state.buildDiagnosticsLogControlEnabled).isTrue()
        assertThat(state.lspClangdStartupLogControlEnabled).isTrue()
        assertThat(state.editorTouchDiagnosticsControlEnabled).isTrue()
        assertThat(state.gestureTraceControlEnabled).isTrue()
        assertThat(state.editorInternalTouchLogControlEnabled).isFalse()
        assertThat(state.editorScaleLogControlEnabled).isFalse()
        assertThat(state.editorFocusLogControlEnabled).isFalse()
        assertThat(state.editorScrollLogControlEnabled).isFalse()
        assertThat(state.editorFlingLogControlEnabled).isFalse()
    }

    @Test
    fun resolveDiagnosticsControlsState_shouldEnableAllDependentControlsWhenPrerequisitesMet() {
        val state = DeveloperOptionsSectionSupport.resolveDiagnosticsControlsState(
            diagnosticsEnabled = true,
            editorTouchDiagnosticsEnabled = true
        )

        assertThat(state.lspCompileCommandsSelectionLogControlEnabled).isTrue()
        assertThat(state.buildDiagnosticsLogControlEnabled).isTrue()
        assertThat(state.lspClangdStartupLogControlEnabled).isTrue()
        assertThat(state.editorTouchDiagnosticsControlEnabled).isTrue()
        assertThat(state.gestureTraceControlEnabled).isTrue()
        assertThat(state.editorInternalTouchLogControlEnabled).isTrue()
        assertThat(state.editorScaleLogControlEnabled).isTrue()
        assertThat(state.editorFocusLogControlEnabled).isTrue()
        assertThat(state.editorScrollLogControlEnabled).isTrue()
        assertThat(state.editorFlingLogControlEnabled).isTrue()
    }

    @Test
    fun resolveTestingToolsState_shouldKeepBuiltinCmakeToggleEnabled() {
        assertThat(
            DeveloperOptionsSectionSupport.resolveTestingToolsState()
                .builtinCmakeLspControlEnabled
        ).isTrue()
    }

    @Test
    fun createServerUrlDialogState_shouldResetFeedbackAndKeepCurrentUrl() {
        val state = DeveloperOptionsSectionSupport.createServerUrlDialogState(
            "https://mirror.example.com"
        )

        assertThat(state.urlText).isEqualTo("https://mirror.example.com")
        assertThat(state.feedback).isNull()
    }

    @Test
    fun normalizeServerUrlInput_shouldTrimBlankAndTrailingSlash() {
        assertThat(
            DeveloperOptionsSectionSupport.normalizeServerUrlInput("  https://mirror.example.com/  ")
        ).isEqualTo("https://mirror.example.com")
        assertThat(DeveloperOptionsSectionSupport.normalizeServerUrlInput("   ")).isNull()
    }

    @Test
    fun isServerUrlValid_shouldRequireHttpsExceptForSupportedLoopbackHosts() {
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid(null)).isTrue()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("https://server.example.com")).isTrue()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("http://localhost:8080")).isTrue()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("http://127.0.0.1:8080")).isTrue()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("http://server.example.com")).isFalse()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("https://user@server.example.com")).isFalse()
        assertThat(DeveloperOptionsSectionSupport.isServerUrlValid("https://server.example.com?token=x")).isFalse()
    }

    @Test
    fun resolveServerUrlSaveResult_shouldKeepPersistedUrlAndExposeConnectionStatus() {
        val successState = DeveloperOptionsSectionSupport.resolveServerUrlSaveResult(
            persistedServerUrl = "https://ok.example.com",
            connectionOk = true
        )
        val failureState = DeveloperOptionsSectionSupport.resolveServerUrlSaveResult(
            persistedServerUrl = "https://fail.example.com",
            connectionOk = false
        )

        assertThat(successState.urlText).isEqualTo("https://ok.example.com")
        assertThat(successState.feedback).isEqualTo(DeveloperServerUrlFeedback.TestSuccess)
        assertThat(failureState.urlText).isEqualTo("https://fail.example.com")
        assertThat(failureState.feedback).isEqualTo(DeveloperServerUrlFeedback.TestFailure)
    }

    @Test
    fun resolveServerUrlRestoreResult_shouldExposeDefaultRestoreFeedback() {
        val state = DeveloperOptionsSectionSupport.resolveServerUrlRestoreResult(
            "https://tinaide.wuxianggujun.com"
        )

        assertThat(state.urlText).isEqualTo("https://tinaide.wuxianggujun.com")
        assertThat(state.feedback).isEqualTo(DeveloperServerUrlFeedback.RestoredDefault)
    }

    @Test
    fun resolveServerUrlFeedbackMessage_shouldMapStableMessageResources() {
        val successMessage = DeveloperOptionsSectionSupport.resolveServerUrlFeedbackMessage(
            DeveloperServerUrlFeedback.TestSuccess
        )
        val failureMessage = DeveloperOptionsSectionSupport.resolveServerUrlFeedbackMessage(
            DeveloperServerUrlFeedback.TestFailure
        )
        val invalidMessage = DeveloperOptionsSectionSupport.resolveServerUrlFeedbackMessage(
            DeveloperServerUrlFeedback.Invalid
        )
        val restoredMessage = DeveloperOptionsSectionSupport.resolveServerUrlFeedbackMessage(
            DeveloperServerUrlFeedback.RestoredDefault
        )

        assertThat(
            DeveloperOptionsSectionSupport.resolveServerUrlFeedbackMessage(null)
        ).isNull()
        assertThat(successMessage)
            .isEqualTo(DeveloperMessageSpec(Strings.toast_tina_server_test_ok))
        assertThat(failureMessage)
            .isEqualTo(DeveloperMessageSpec(Strings.toast_tina_server_test_failed))
        assertThat(invalidMessage)
            .isEqualTo(DeveloperMessageSpec(Strings.toast_tina_server_url_invalid))
        assertThat(restoredMessage)
            .isEqualTo(DeveloperMessageSpec(Strings.toast_tina_server_restored_default))
    }

    @Test
    fun resolveServerConfigPreview_shouldNormalizeUpdatedAtAndPreserveFeatureFlags() {
        val preview = DeveloperOptionsSectionSupport.resolveServerConfigPreview(
            ServerConfigResponse(
                version = 12,
                updatedAt = "   ",
                configRefreshIntervalSecs = 600,
                features = FeatureFlags(
                    pluginMarketEnabled = false,
                    packageManagerEnabled = true,
                    developerOptionsEnabled = false
                ),
                client = ClientConfig(
                    minClientVersion = "1.0.0",
                    recommendedClientVersion = "1.2.0",
                    forceUpdate = true
                )
            )
        )

        assertThat(preview.version).isEqualTo(12)
        assertThat(preview.updatedAt).isNull()
        assertThat(preview.configRefreshIntervalSecs).isEqualTo(600)
        assertThat(preview.pluginMarketEnabled).isFalse()
        assertThat(preview.packageManagerEnabled).isTrue()
        assertThat(preview.developerOptionsEnabled).isFalse()
        assertThat(preview.minClientVersion).isEqualTo("1.0.0")
        assertThat(preview.recommendedClientVersion).isEqualTo("1.2.0")
        assertThat(preview.forceUpdate).isTrue()
    }

    @Test
    fun resolveActionEffect_shouldKeepCriticalNavigationActionsStable() {
        val disableEffect = DeveloperOptionsSectionSupport.resolveActionEffect(
            DeveloperOptionsAction.DisableDeveloperOptions
        )

        assertThat(disableEffect.disableDeveloperOptions).isTrue()
        assertThat(disableEffect.navigateBack).isTrue()
    }
}

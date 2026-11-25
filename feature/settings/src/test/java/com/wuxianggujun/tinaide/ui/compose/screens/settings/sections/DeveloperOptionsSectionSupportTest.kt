package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
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
    fun resolveActionEffect_shouldKeepCriticalNavigationActionsStable() {
        val disableEffect = DeveloperOptionsSectionSupport.resolveActionEffect(
            DeveloperOptionsAction.DisableDeveloperOptions
        )

        assertThat(disableEffect.disableDeveloperOptions).isTrue()
        assertThat(disableEffect.navigateBack).isTrue()
    }
}

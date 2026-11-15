package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

internal data class DeveloperDiagnosticsControlsState(
    val buildDiagnosticsLogControlEnabled: Boolean,
    val lspCompileCommandsSelectionLogControlEnabled: Boolean,
    val lspClangdStartupLogControlEnabled: Boolean,
    val editorTouchDiagnosticsControlEnabled: Boolean,
    val gestureTraceControlEnabled: Boolean,
    val editorInternalTouchLogControlEnabled: Boolean,
    val editorScaleLogControlEnabled: Boolean,
    val editorFocusLogControlEnabled: Boolean,
    val editorScrollLogControlEnabled: Boolean,
    val editorFlingLogControlEnabled: Boolean
)

internal data class DeveloperTestingToolsState(
    val builtinCmakeLspControlEnabled: Boolean
)

internal enum class DeveloperOptionsAction {
    DisableDeveloperOptions
}

internal data class DeveloperOptionsActionEffect(
    val disableDeveloperOptions: Boolean = false,
    val navigateBack: Boolean = false
)

internal object DeveloperOptionsSectionSupport {
    fun resolveDiagnosticsControlsState(
        diagnosticsEnabled: Boolean,
        editorTouchDiagnosticsEnabled: Boolean
    ): DeveloperDiagnosticsControlsState {
        val diagnosticsControlsEnabled = diagnosticsEnabled
        val touchSubControlsEnabled = diagnosticsEnabled && editorTouchDiagnosticsEnabled
        return DeveloperDiagnosticsControlsState(
            buildDiagnosticsLogControlEnabled = diagnosticsControlsEnabled,
            lspCompileCommandsSelectionLogControlEnabled = diagnosticsControlsEnabled,
            lspClangdStartupLogControlEnabled = diagnosticsControlsEnabled,
            editorTouchDiagnosticsControlEnabled = diagnosticsControlsEnabled,
            gestureTraceControlEnabled = diagnosticsControlsEnabled,
            editorInternalTouchLogControlEnabled = touchSubControlsEnabled,
            editorScaleLogControlEnabled = touchSubControlsEnabled,
            editorFocusLogControlEnabled = touchSubControlsEnabled,
            editorScrollLogControlEnabled = touchSubControlsEnabled,
            editorFlingLogControlEnabled = touchSubControlsEnabled
        )
    }

    fun resolveTestingToolsState(): DeveloperTestingToolsState = DeveloperTestingToolsState(
        builtinCmakeLspControlEnabled = true
    )

    fun resolveActionEffect(action: DeveloperOptionsAction): DeveloperOptionsActionEffect = when (action) {
        DeveloperOptionsAction.DisableDeveloperOptions -> DeveloperOptionsActionEffect(
            disableDeveloperOptions = true,
            navigateBack = true
        )
    }
}

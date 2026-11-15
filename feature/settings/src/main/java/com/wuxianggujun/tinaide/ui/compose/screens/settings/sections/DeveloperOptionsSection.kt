package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsMenuItemWithIcon
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem

/**
 * 开发者选项页面
 *
 * 提供各种测试工具和调试功能的入口
 */
@Composable
internal fun DeveloperOptionsSection(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    val diagnosticsSettings by Prefs.devDiagnosticsSettingsFlow.collectAsState()
    val diagnosticsEnabled = diagnosticsSettings.diagnosticsEnabled
    val buildDiagnosticsLogEnabled = diagnosticsSettings.buildDiagnosticsLogEnabled
    val lspCompileCommandsSelectionLogEnabled =
        diagnosticsSettings.lspCompileCommandsSelectionLogEnabled
    val lspClangdStartupLogEnabled = diagnosticsSettings.lspClangdStartupLogEnabled
    val editorTouchDiagnosticsEnabled = diagnosticsSettings.editorTouchDiagnosticsEnabled
    val gestureTraceEnabled = diagnosticsSettings.gestureTraceEnabled
    val editorInternalTouchLogEnabled = diagnosticsSettings.editorInternalTouchLogEnabled
    val editorScaleLogEnabled = diagnosticsSettings.editorScaleLogEnabled
    val editorFocusLogEnabled = diagnosticsSettings.editorFocusLogEnabled
    val editorScrollLogEnabled = diagnosticsSettings.editorScrollLogEnabled
    val editorFlingLogEnabled = diagnosticsSettings.editorFlingLogEnabled
    val editorLspEnabled by Prefs.devEditorLspEnabledFlow.collectAsState()
    val builtinCmakeLspEnabled by Prefs.devBuiltinCmakeLspEnabledFlow.collectAsState()
    val diagnosticsControlsState = remember(diagnosticsEnabled, editorTouchDiagnosticsEnabled) {
        DeveloperOptionsSectionSupport.resolveDiagnosticsControlsState(
            diagnosticsEnabled = diagnosticsEnabled,
            editorTouchDiagnosticsEnabled = editorTouchDiagnosticsEnabled
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 开发者选项管理
    SettingsCategoryTitle(stringResource(Strings.dev_options_management))
    SettingsCard {
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_developer,
            iconBackgroundColor = Color(0xFFFF5722),
            title = stringResource(Strings.dev_options_disable),
            subtitle = stringResource(Strings.dev_options_disable_desc),
            onClick = {
                val effect = DeveloperOptionsSectionSupport.resolveActionEffect(
                    DeveloperOptionsAction.DisableDeveloperOptions
                )
                if (effect.disableDeveloperOptions) {
                    Prefs.setDeveloperOptionsEnabled(false)
                }
                Toast.makeText(context, Strings.dev_options_disabled.strOr(context), Toast.LENGTH_SHORT).show()
                if (effect.navigateBack) {
                    onNavigateBack()
                }
            },
            showDivider = false,
            showArrow = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 诊断日志
    SettingsCategoryTitle(stringResource(Strings.dev_options_diagnostics))
    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_diagnostics_enabled),
            subtitle = stringResource(Strings.dev_options_diagnostics_enabled_desc),
            checked = diagnosticsEnabled,
            onCheckedChange = {
                Prefs.devDiagnosticsEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_build_diagnostics_log),
            subtitle = stringResource(Strings.dev_options_build_diagnostics_log_desc),
            checked = buildDiagnosticsLogEnabled,
            enabled = diagnosticsControlsState.buildDiagnosticsLogControlEnabled,
            onCheckedChange = {
                Prefs.devBuildDiagnosticsLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_lsp_compile_commands_selection_log),
            subtitle = stringResource(Strings.dev_options_lsp_compile_commands_selection_log_desc),
            checked = lspCompileCommandsSelectionLogEnabled,
            enabled = diagnosticsControlsState.lspCompileCommandsSelectionLogControlEnabled,
            onCheckedChange = {
                Prefs.devLspCompileCommandsSelectionLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_lsp_clangd_startup_log),
            subtitle = stringResource(Strings.dev_options_lsp_clangd_startup_log_desc),
            checked = lspClangdStartupLogEnabled,
            enabled = diagnosticsControlsState.lspClangdStartupLogControlEnabled,
            onCheckedChange = {
                Prefs.devLspClangdStartupLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_touch_diagnostics),
            subtitle = stringResource(Strings.dev_options_editor_touch_diagnostics_desc),
            checked = editorTouchDiagnosticsEnabled,
            enabled = diagnosticsControlsState.editorTouchDiagnosticsControlEnabled,
            onCheckedChange = {
                Prefs.editorTouchDiagnosticsEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_gesture_trace),
            subtitle = stringResource(Strings.dev_options_gesture_trace_desc),
            checked = gestureTraceEnabled,
            enabled = diagnosticsControlsState.gestureTraceControlEnabled,
            onCheckedChange = {
                Prefs.devGestureTraceEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_internal_touch_log),
            subtitle = stringResource(Strings.dev_options_editor_internal_touch_log_desc),
            checked = editorInternalTouchLogEnabled,
            enabled = diagnosticsControlsState.editorInternalTouchLogControlEnabled,
            onCheckedChange = {
                Prefs.devEditorTouchInternalLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_scale_touch_log),
            subtitle = stringResource(Strings.dev_options_editor_scale_touch_log_desc),
            checked = editorScaleLogEnabled,
            enabled = diagnosticsControlsState.editorScaleLogControlEnabled,
            onCheckedChange = {
                Prefs.devEditorTouchScaleLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_focus_touch_log),
            subtitle = stringResource(Strings.dev_options_editor_focus_touch_log_desc),
            checked = editorFocusLogEnabled,
            enabled = diagnosticsControlsState.editorFocusLogControlEnabled,
            onCheckedChange = {
                Prefs.devEditorTouchFocusLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_scroll_touch_log),
            subtitle = stringResource(Strings.dev_options_editor_scroll_touch_log_desc),
            checked = editorScrollLogEnabled,
            enabled = diagnosticsControlsState.editorScrollLogControlEnabled,
            onCheckedChange = {
                Prefs.devEditorTouchScrollLogEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_fling_touch_log),
            subtitle = stringResource(Strings.dev_options_editor_fling_touch_log_desc),
            checked = editorFlingLogEnabled,
            enabled = diagnosticsControlsState.editorFlingLogControlEnabled,
            onCheckedChange = {
                Prefs.devEditorTouchFlingLogEnabled = it
            },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 测试工具（统一入口）
    SettingsCategoryTitle(stringResource(Strings.dev_options_runtime_tools))
    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_editor_lsp_enabled),
            subtitle = stringResource(Strings.dev_options_editor_lsp_enabled_desc),
            checked = editorLspEnabled,
            onCheckedChange = {
                Prefs.devEditorLspEnabled = it
            },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.dev_options_builtin_cmake_lsp_enabled),
            subtitle = stringResource(Strings.dev_options_builtin_cmake_lsp_enabled_desc),
            checked = builtinCmakeLspEnabled,
            onCheckedChange = {
                Prefs.devBuiltinCmakeLspEnabled = it
            },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
}

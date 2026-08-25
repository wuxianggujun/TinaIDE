package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsMenuItemWithIcon
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import kotlinx.coroutines.launch

// 图标颜色
private val TestIconColor = Color(0xFF2196F3)

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
    val coroutineScope = rememberCoroutineScope()
    val serverConfig = remember { TinaServerConfig.getInstance(context) }

    var showServerUrlDialog by remember { mutableStateOf(false) }
    var serverUrlText by remember { mutableStateOf("") }
    var serverUrlFeedback by remember { mutableStateOf<DeveloperServerUrlFeedback?>(null) }
    var showServerConfigDialog by remember { mutableStateOf(false) }

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
    val serverUrlStatusText = DeveloperOptionsSectionSupport
        .resolveServerUrlFeedbackMessage(serverUrlFeedback)
        ?.resolve(context)

    if (showServerUrlDialog) {
        LaunchedEffect(Unit) {
            val dialogState = DeveloperOptionsSectionSupport.createServerUrlDialogState(
                serverConfig.getServerUrl()
            )
            serverUrlText = dialogState.urlText
            serverUrlFeedback = dialogState.feedback
        }

        TinaAlertDialog(
            onDismissRequest = { showServerUrlDialog = false },
            title = { TinaDialogTitleText(stringResource(Strings.dialog_title_tina_server_url)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverUrlText,
                        onValueChange = { serverUrlText = it },
                        label = { Text(stringResource(Strings.label_tina_server_url)) },
                        singleLine = true,
                    )
                    serverUrlStatusText?.let {
                        TinaDialogMessageCard(message = it)
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save_and_test),
                    onClick = {
                        coroutineScope.launch {
                            serverUrlFeedback = null
                            val url = DeveloperOptionsSectionSupport.normalizeServerUrlInput(
                                serverUrlText
                            )
                            if (!DeveloperOptionsSectionSupport.isServerUrlValid(url)) {
                                serverUrlFeedback = DeveloperServerUrlFeedback.Invalid
                                DeveloperOptionsSectionSupport
                                    .resolveServerUrlFeedbackMessage(serverUrlFeedback)
                                    ?.let { message ->
                                        DeveloperToastSpec(message = message).show(context)
                                    }
                                return@launch
                            }
                            serverConfig.setServerUrl(url)
                            val ok = serverConfig.checkServerConnection()
                            val dialogState = DeveloperOptionsSectionSupport.resolveServerUrlSaveResult(
                                persistedServerUrl = serverConfig.getServerUrl(),
                                connectionOk = ok
                            )
                            serverUrlText = dialogState.urlText
                            serverUrlFeedback = dialogState.feedback
                            DeveloperOptionsSectionSupport
                                .resolveServerUrlFeedbackMessage(dialogState.feedback)
                                ?.let { message ->
                                    DeveloperToastSpec(message = message).show(context)
                                }
                        }
                    }
                )
            },
            dismissButton = {
                Row {
                    TinaTextButton(
                        text = stringResource(Strings.btn_cancel),
                        onClick = { showServerUrlDialog = false }
                    )
                    TinaTextButton(
                        text = stringResource(Strings.btn_restore_default),
                        onClick = {
                            coroutineScope.launch {
                                serverConfig.setServerUrl(null)
                                val dialogState = DeveloperOptionsSectionSupport.resolveServerUrlRestoreResult(
                                    serverConfig.getServerUrl()
                                )
                                serverUrlText = dialogState.urlText
                                serverUrlFeedback = dialogState.feedback
                                DeveloperOptionsSectionSupport
                                    .resolveServerUrlFeedbackMessage(dialogState.feedback)
                                    ?.let { message ->
                                        DeveloperToastSpec(message = message).show(context)
                                    }
                            }
                        }
                    )
                }
            }
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

    SettingsCategoryTitle(stringResource(Strings.dev_options_backend))
    SettingsCard {
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_developer,
            iconBackgroundColor = TestIconColor,
            title = stringResource(Strings.dev_options_tina_server_url),
            subtitle = stringResource(Strings.dev_options_tina_server_url_desc),
            onClick = { showServerUrlDialog = true },
            showDivider = true
        )

        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_developer,
            iconBackgroundColor = TestIconColor,
            title = stringResource(Strings.dev_options_view_server_config),
            subtitle = stringResource(Strings.dev_options_view_server_config_desc),
            onClick = { showServerConfigDialog = true },
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

    // 服务器配置预览对话框
    if (showServerConfigDialog) {
        val configPreview = remember(showServerConfigDialog) {
            DeveloperOptionsSectionSupport.resolveServerConfigPreview(
                com.wuxianggujun.tinaide.core.config.ServerConfigManager.getCurrentConfig()
            )
        }

        val yesNo: @Composable (Boolean) -> String = { value ->
            stringResource(if (value) Strings.common_yes else Strings.common_no)
        }
        TinaAlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            title = { TinaDialogTitleText(stringResource(Strings.dev_options_server_config_title)) },
            text = {
                TinaDialogContentColumn(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                ) {
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Strings.dev_options_config_version, configPreview.version))
                        Text(
                            stringResource(
                                Strings.dev_options_config_updated_at,
                                configPreview.updatedAt
                                    ?: stringResource(Strings.dev_options_config_unknown)
                            )
                        )
                        Text(
                            stringResource(
                                Strings.dev_options_config_refresh_interval,
                                configPreview.configRefreshIntervalSecs
                            )
                        )
                    }

                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Strings.dev_options_config_features), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(stringResource(Strings.dev_options_config_plugin_market, yesNo(configPreview.pluginMarketEnabled)))
                        Text(stringResource(Strings.dev_options_config_package_manager, yesNo(configPreview.packageManagerEnabled)))
                        Text(stringResource(Strings.dev_options_config_developer_options, yesNo(configPreview.developerOptionsEnabled)))
                    }

                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Strings.dev_options_config_client), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(stringResource(Strings.dev_options_config_min_version, configPreview.minClientVersion))
                        Text(stringResource(Strings.dev_options_config_recommended_version, configPreview.recommendedClientVersion))
                        Text(stringResource(Strings.dev_options_config_force_update, yesNo(configPreview.forceUpdate)))
                    }
                }
            },
            confirmButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = { showServerConfigDialog = false }
                )
            }
        )
    }
}

private fun DeveloperMessageSpec.resolve(context: Context): String = messageRes.strOr(context, *formatArgs.toTypedArray())

private fun DeveloperToastSpec.show(context: Context) {
    Toast.makeText(
        context,
        message.resolve(context),
        duration.toPlatformToastDuration()
    ).show()
}

private fun DeveloperToastDuration.toPlatformToastDuration(): Int = when (this) {
    DeveloperToastDuration.Short -> Toast.LENGTH_SHORT
    DeveloperToastDuration.Long -> Toast.LENGTH_LONG
}

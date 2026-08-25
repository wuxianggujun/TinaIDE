package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.ClangdSettings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lsp.RemoteLspAuthenticationTokenPolicy
import com.wuxianggujun.tinaide.core.lsp.RemoteLspAuthenticationTokenValidation
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfigManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMethod
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSliderDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaValidatedInputDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/**
 * LSP (Language Server Protocol) 设置页面
 *
 * 包含：
 * - Clangd 配置
 * - LSP 行为设置
 * - 远程 LSP 配置
 */
@Composable
internal fun LspSettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val itemsSuffix = stringResource(Strings.items_format, 1).substringAfter(" ")
    val clangdRunModeOptions = remember(state.linuxEnvironmentEnabled) {
        LspSettingsSectionSupport.buildClangdRunModeOptions(
            linuxEnvironmentEnabled = state.linuxEnvironmentEnabled
        )
    }

    // 远程 LSP 配置状态
    val remoteLspConfig by RemoteLspConfigManager.configFlow.collectAsState()
    val remoteLspConnectionState by RemoteLspConfigManager.connectionStateFlow.collectAsState()
    val detectedSyncMode by RemoteLspConfigManager.detectedSyncModeFlow.collectAsState()
    val remoteLspSectionState = remember(
        remoteLspConfig,
        remoteLspConnectionState,
        detectedSyncMode
    ) {
        LspSettingsSectionSupport.resolveRemoteLspSectionState(
            config = remoteLspConfig,
            connectionState = remoteLspConnectionState,
            detectedSyncMode = detectedSyncMode
        )
    }

    // Clangd 设置对话框状态
    var showClangdRunModeDialog by remember { mutableStateOf(false) }
    var showClangdHeaderInsertionDialog by remember { mutableStateOf(false) }
    var showClangdCompletionStyleDialog by remember { mutableStateOf(false) }

    // LSP 行为对话框状态
    var showCompletionLimitDialog by remember { mutableStateOf(false) }

    // 远程 LSP 对话框状态
    var showHostDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showAuthenticationTokenDialog by remember { mutableStateOf(false) }
    var showRemoteWorkspaceRootDialog by remember { mutableStateOf(false) }
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showSyncMethodDialog by remember { mutableStateOf(false) }
    var showRsyncModuleDialog by remember { mutableStateOf(false) }
    var showRsyncPortDialog by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val remoteLspConfigInvalid = stringResource(Strings.settings_remote_lsp_config_invalid)
    Spacer(modifier = Modifier.height(8.dp))

    // ==================== Clangd 配置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_clangd))

    SettingsCard {
        if (state.linuxEnvironmentEnabled) {
            SettingsClickableItem(
                title = stringResource(Strings.settings_clangd_run_mode),
                subtitle = stringResource(Strings.settings_clangd_run_mode_desc),
                value = stringResource(
                    LspSettingsSectionSupport.resolveClangdRunModeLabel(state.clangdRunMode)
                ),
                onClick = { showClangdRunModeDialog = true },
                showDivider = true
            )
        }

        SettingsSwitchItem(
            title = stringResource(Strings.settings_clangd_background_index),
            subtitle = stringResource(Strings.settings_clangd_background_index_desc),
            checked = state.clangdBackgroundIndex,
            onCheckedChange = { viewModel.setClangdBackgroundIndex(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_clangd_clang_tidy),
            subtitle = stringResource(Strings.settings_clangd_clang_tidy_desc),
            checked = state.clangdClangTidy,
            onCheckedChange = { viewModel.setClangdClangTidy(it) },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_clangd_header_insertion),
            value = stringResource(
                LspSettingsSectionSupport.resolveClangdHeaderInsertionLabel(
                    state.clangdHeaderInsertion
                )
            ),
            onClick = { showClangdHeaderInsertionDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_clangd_completion_style),
            value = stringResource(
                LspSettingsSectionSupport.resolveClangdCompletionStyleLabel(
                    state.clangdCompletionStyle
                )
            ),
            onClick = { showClangdCompletionStyleDialog = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_clangd_function_arg_placeholders),
            subtitle = stringResource(Strings.settings_clangd_function_arg_placeholders_desc),
            checked = state.clangdFunctionArgPlaceholders,
            onCheckedChange = { viewModel.setClangdFunctionArgPlaceholders(it) },
            showDivider = false
        )
    }

    // 提示信息
    Text(
        text = stringResource(Strings.settings_clangd_restart_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // ==================== LSP 行为设置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_lsp_behavior))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_completion_limit),
            value = stringResource(Strings.items_format, state.lspCompletionLimit),
            onClick = { showCompletionLimitDialog = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_completion_case_sensitive),
            subtitle = stringResource(Strings.settings_completion_case_sensitive_desc),
            checked = state.completionCaseSensitive,
            onCheckedChange = { viewModel.setCompletionCaseSensitive(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_lsp_signature_help),
            subtitle = stringResource(Strings.settings_lsp_signature_help_desc),
            checked = state.lspSignatureHelpEnabled,
            onCheckedChange = { viewModel.setLspSignatureHelpEnabled(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_lsp_inlay_hints),
            subtitle = stringResource(Strings.settings_lsp_inlay_hints_desc),
            checked = state.lspInlayHintsEnabled,
            onCheckedChange = { viewModel.setLspInlayHintsEnabled(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_lsp_semantic_tokens),
            subtitle = stringResource(Strings.settings_lsp_semantic_tokens_desc),
            checked = state.lspSemanticTokensEnabled,
            onCheckedChange = { viewModel.setLspSemanticTokensEnabled(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_lsp_folding_range),
            subtitle = stringResource(Strings.settings_lsp_folding_range_desc),
            checked = state.lspFoldingRangeEnabled,
            onCheckedChange = { viewModel.setLspFoldingRangeEnabled(it) },
            showDivider = false
        )
    }

    // ==================== 远程 LSP 服务器设置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_remote_lsp))

    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.settings_remote_lsp_enable),
            subtitle = stringResource(Strings.settings_remote_lsp_enable_desc),
            checked = remoteLspConfig.enabled,
            onCheckedChange = { enabled ->
                RemoteLspConfigManager.setEnabled(enabled)
                if (!enabled) {
                    testResult = null
                }
            },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_remote_lsp_host),
            value = remoteLspConfig.host.ifEmpty { stringResource(Strings.settings_remote_lsp_host_hint) },
            onClick = { showHostDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_remote_lsp_port),
            value = remoteLspConfig.port.toString(),
            onClick = { showPortDialog = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_remote_lsp_secure_transport),
            subtitle = stringResource(Strings.settings_remote_lsp_secure_transport_desc),
            checked = remoteLspConfig.secureTransport,
            onCheckedChange = RemoteLspConfigManager::setSecureTransport,
            showDivider = true,
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_remote_lsp_auth_token),
            value = stringResource(
                if (remoteLspConfig.hasAuthenticationToken) {
                    Strings.settings_remote_lsp_auth_token_configured
                } else {
                    Strings.settings_remote_lsp_auth_token_not_configured
                },
            ),
            onClick = { showAuthenticationTokenDialog = true },
            showDivider = true,
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_remote_lsp_workspace_root_uri),
            value = remoteLspConfig.remoteWorkspaceRootUri.ifEmpty {
                stringResource(Strings.settings_remote_lsp_workspace_root_uri_hint)
            },
            onClick = { showRemoteWorkspaceRootDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_remote_lsp_sync_mode),
            value = stringResource(remoteLspSectionState.syncModeLabelRes),
            onClick = { showSyncModeDialog = true },
            showDivider = remoteLspSectionState.syncModeShowDivider
        )

        // 显示自动检测结果（仅在 AUTO 模式且有检测结果时显示）
        remoteLspSectionState.detectedMode?.let { detectedMode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(Strings.settings_remote_lsp_detected_mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(detectedMode.modeLabelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = detectedMode.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 同步方案选择（仅在项目模式下显示）
        if (remoteLspSectionState.showSyncMethod) {
            SettingsClickableItem(
                title = stringResource(Strings.settings_remote_lsp_sync_method),
                value = stringResource(requireNotNull(remoteLspSectionState.syncMethodLabelRes)),
                onClick = { showSyncMethodDialog = true },
                showDivider = remoteLspSectionState.syncMethodShowDivider
            )

            // rsync 配置（仅在选择 rsync 方案时显示）
            if (remoteLspSectionState.showRsyncSettings) {
                SettingsClickableItem(
                    title = stringResource(Strings.settings_remote_lsp_rsync_module),
                    value = remoteLspConfig.rsyncModule.ifEmpty { stringResource(Strings.settings_remote_lsp_rsync_module_hint) },
                    onClick = { showRsyncModuleDialog = true },
                    showDivider = true
                )

                SettingsClickableItem(
                    title = stringResource(Strings.settings_remote_lsp_rsync_port),
                    value = remoteLspConfig.rsyncPort.toString(),
                    onClick = { showRsyncPortDialog = true },
                    showDivider = false
                )
            }
        }

        // 连接状态显示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val connectionStatusColor = when (remoteLspSectionState.connectionStatusTone) {
                LspSettingsStatusTone.Positive -> MaterialTheme.colorScheme.primary
                LspSettingsStatusTone.Negative -> MaterialTheme.colorScheme.error
                LspSettingsStatusTone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Strings.settings_remote_lsp_status),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(remoteLspSectionState.connectionStatusLabelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = connectionStatusColor
                )
            }

            // 测试连接按钮
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = {
                        if (!remoteLspConfig.isValid()) {
                            Toast.makeText(
                                context,
                                remoteLspConfigInvalid,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@OutlinedButton
                        }
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val result = runCatching {
                                testWebSocketConnection(
                                    context = context,
                                    host = remoteLspConfig.host,
                                    port = remoteLspConfig.port,
                                    secureTransport = remoteLspConfig.secureTransport,
                                    authenticationToken = RemoteLspConfigManager.getAuthenticationToken(),
                                )
                            }.getOrElse { error ->
                                Timber.tag("LspSettingsSection").e(error, "Remote LSP connection test failed")
                                WebSocketConnectionTestResult(
                                    message = Strings.editor_lsp_connection_failed.strOr(
                                        context,
                                        Strings.error_unknown.strOr(context),
                                    ),
                                    success = false,
                                )
                            }
                            isTesting = false
                            testResult = result.message

                            // 测试成功时更新连接状态
                            if (result.success) {
                                RemoteLspConfigManager.updateConnectionState(RemoteLspConnectionState.CONNECTED)
                            }

                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = remoteLspSectionState.testButtonEnabled
                ) {
                    Text(stringResource(Strings.settings_remote_lsp_test))
                }
            }
        }
    }

    // 提示信息
    Text(
        text = stringResource(Strings.settings_remote_lsp_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // ==================== 对话框 ====================

    // Clangd 运行模式对话框
    if (showClangdRunModeDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_clangd_run_mode),
            options = clangdRunModeOptions.map { option ->
                option.value to stringResource(option.labelRes)
            },
            selectedValue = state.clangdRunMode,
            onSelected = { value ->
                viewModel.setClangdRunMode(value)
                showClangdRunModeDialog = false
            },
            onDismiss = { showClangdRunModeDialog = false }
        )
    }

    // Clangd 头文件插入模式对话框
    if (showClangdHeaderInsertionDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_clangd_header_insertion),
            options = listOf(
                ClangdSettings.HeaderInsertionMode.IWYU.value to stringResource(Strings.settings_clangd_header_insertion_iwyu),
                ClangdSettings.HeaderInsertionMode.NEVER.value to stringResource(Strings.settings_clangd_header_insertion_never),
            ),
            selectedValue = state.clangdHeaderInsertion,
            onSelected = { value ->
                viewModel.setClangdHeaderInsertion(value)
                showClangdHeaderInsertionDialog = false
            },
            onDismiss = { showClangdHeaderInsertionDialog = false }
        )
    }

    // Clangd 补全样式对话框
    if (showClangdCompletionStyleDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_clangd_completion_style),
            options = listOf(
                ClangdSettings.CompletionStyle.DETAILED.value to stringResource(Strings.settings_clangd_completion_style_detailed),
                ClangdSettings.CompletionStyle.BUNDLED.value to stringResource(Strings.settings_clangd_completion_style_bundled),
            ),
            selectedValue = state.clangdCompletionStyle,
            onSelected = { value ->
                viewModel.setClangdCompletionStyle(value)
                showClangdCompletionStyleDialog = false
            },
            onDismiss = { showClangdCompletionStyleDialog = false }
        )
    }

    // 补全限制对话框
    if (showCompletionLimitDialog) {
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_completion_limit),
            value = state.lspCompletionLimit.toFloat(),
            valueRange = 10f..200f,
            steps = 18,
            valueLabel = { "${it.toInt()} $itemsSuffix" },
            onValueSelected = { value ->
                viewModel.setLspCompletionLimit(value.toInt())
                showCompletionLimitDialog = false
            },
            onDismiss = { showCompletionLimitDialog = false }
        )
    }

    // 远程 LSP 服务器地址对话框
    if (showHostDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_remote_lsp_host),
            label = stringResource(Strings.settings_remote_lsp_host),
            placeholder = stringResource(Strings.settings_remote_lsp_host_hint),
            initialValue = remoteLspConfig.host,
            validator = { input ->
                LspSettingsSectionSupport.validateRemoteHost(input)?.let { stringResource(it) }
            },
            hint = { value ->
                LspSettingsSectionSupport.resolveRemoteHostHint(value)?.let { stringResource(it) }
            },
            allowEmpty = true,
            onConfirm = { host ->
                RemoteLspConfigManager.setHost(
                    LspSettingsSectionSupport.normalizeRemoteHost(host)
                )
                showHostDialog = false
            },
            onDismiss = { showHostDialog = false }
        )
    }

    // 远程 LSP 端口对话框
    if (showPortDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_remote_lsp_port),
            label = stringResource(Strings.settings_remote_lsp_port),
            placeholder = stringResource(Strings.settings_remote_lsp_port_hint),
            initialValue = remoteLspConfig.port.toString(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validator = { input ->
                LspSettingsSectionSupport.validatePortInput(input)?.let { stringResource(it) }
            },
            onConfirm = { portText ->
                val port = LspSettingsSectionSupport.parsePortInput(
                    input = portText,
                    fallback = remoteLspConfig.port
                )
                RemoteLspConfigManager.setPort(port)
                showPortDialog = false
            },
            onDismiss = { showPortDialog = false }
        )
    }

    if (showAuthenticationTokenDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_remote_lsp_auth_token),
            label = stringResource(Strings.settings_remote_lsp_auth_token),
            placeholder = stringResource(Strings.settings_remote_lsp_auth_token_hint),
            initialValue = "",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            allowEmpty = true,
            validator = { token ->
                when (RemoteLspAuthenticationTokenPolicy.validate(token)) {
                    RemoteLspAuthenticationTokenValidation.VALID -> null
                    RemoteLspAuthenticationTokenValidation.TOO_LONG ->
                        stringResource(Strings.settings_remote_lsp_auth_token_too_long)
                    RemoteLspAuthenticationTokenValidation.CONTROL_CHARACTER ->
                        stringResource(Strings.settings_remote_lsp_auth_token_control_character)
                }
            },
            onConfirm = { token ->
                runCatching {
                    RemoteLspConfigManager.setAuthenticationToken(token.takeIf { it.isNotBlank() })
                }.onSuccess {
                    showAuthenticationTokenDialog = false
                }.onFailure { error ->
                    Timber.tag("LspSettingsSection").e(error, "Failed to persist Remote LSP authentication token")
                    Toast.makeText(
                        context,
                        Strings.settings_remote_lsp_auth_token_save_failed.strOr(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onDismiss = { showAuthenticationTokenDialog = false },
        )
    }

    // 远程工作区根路径对话框
    if (showRemoteWorkspaceRootDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_remote_lsp_workspace_root_uri),
            label = stringResource(Strings.settings_remote_lsp_workspace_root_uri),
            placeholder = stringResource(Strings.settings_remote_lsp_workspace_root_uri_hint),
            initialValue = remoteLspConfig.remoteWorkspaceRootUri,
            validator = { input ->
                LspSettingsSectionSupport.validateRemoteWorkspaceRootUri(input)
                    ?.let { stringResource(it) }
            },
            hint = { stringResource(Strings.settings_remote_lsp_workspace_root_uri_desc) },
            allowEmpty = true,
            onConfirm = { uri ->
                RemoteLspConfigManager.setRemoteWorkspaceRootUri(
                    LspSettingsSectionSupport.normalizeRemoteWorkspaceRootUri(uri)
                )
                showRemoteWorkspaceRootDialog = false
            },
            onDismiss = { showRemoteWorkspaceRootDialog = false }
        )
    }

    // 远程 LSP 同步模式对话框
    if (showSyncModeDialog) {
        RemoteLspSyncModeDialog(
            currentMode = remoteLspConfig.syncMode,
            onModeSelected = { mode ->
                RemoteLspConfigManager.setSyncMode(mode)
                showSyncModeDialog = false
            },
            onDismiss = { showSyncModeDialog = false }
        )
    }

    // 远程 LSP 同步方案对话框
    if (showSyncMethodDialog) {
        RemoteLspSyncMethodDialog(
            currentMethod = remoteLspConfig.syncMethod,
            onMethodSelected = { method ->
                RemoteLspConfigManager.setSyncMethod(method)
                showSyncMethodDialog = false
            },
            onDismiss = { showSyncMethodDialog = false }
        )
    }

    // rsync 模块名称对话框
    if (showRsyncModuleDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_rsync_module),
            label = stringResource(Strings.settings_remote_lsp_rsync_module),
            placeholder = stringResource(Strings.settings_remote_lsp_rsync_module_hint),
            initialValue = remoteLspConfig.rsyncModule,
            validator = { input ->
                LspSettingsSectionSupport.validateRsyncModule(input)?.let { stringResource(it) }
            },
            onConfirm = { module ->
                RemoteLspConfigManager.setRsyncModule(
                    LspSettingsSectionSupport.normalizeRsyncModule(module)
                )
                showRsyncModuleDialog = false
            },
            onDismiss = { showRsyncModuleDialog = false }
        )
    }

    // rsync 端口对话框
    if (showRsyncPortDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.dialog_title_rsync_port),
            label = stringResource(Strings.settings_remote_lsp_rsync_port),
            placeholder = stringResource(Strings.settings_remote_lsp_rsync_port_hint),
            initialValue = remoteLspConfig.rsyncPort.toString(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validator = { input ->
                LspSettingsSectionSupport.validatePortInput(input)?.let { stringResource(it) }
            },
            onConfirm = { portText ->
                val port = LspSettingsSectionSupport.parsePortInput(
                    input = portText,
                    fallback = remoteLspConfig.rsyncPort
                )
                RemoteLspConfigManager.setRsyncPort(port)
                showRsyncPortDialog = false
            },
            onDismiss = { showRsyncPortDialog = false }
        )
    }
}

/**
 * 测试 WebSocket 连接
 *
 * @param host 服务器地址
 * @param port 端口号
 * @return 测试结果消息
 */
@Composable
private fun RemoteLspSyncModeDialog(
    currentMode: RemoteLspSyncMode,
    onModeSelected: (RemoteLspSyncMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        RemoteLspSyncMode.AUTO.name to stringResource(Strings.settings_remote_lsp_sync_mode_auto),
        RemoteLspSyncMode.LIGHTWEIGHT.name to stringResource(Strings.settings_remote_lsp_sync_mode_lightweight),
        RemoteLspSyncMode.PROJECT.name to stringResource(Strings.settings_remote_lsp_sync_mode_project)
    )

    TinaSingleChoiceDialog(
        title = stringResource(Strings.dialog_title_remote_lsp_sync_mode),
        options = options,
        selectedValue = currentMode.name,
        onSelected = { modeName ->
            val mode = RemoteLspSyncMode.valueOf(modeName)
            onModeSelected(mode)
        },
        onDismiss = onDismiss
    )
}

/**
 * 远程 LSP 同步方案选择对话框
 */
@Composable
private fun RemoteLspSyncMethodDialog(
    currentMethod: RemoteLspSyncMethod,
    onMethodSelected: (RemoteLspSyncMethod) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        RemoteLspSyncMethod.BUILTIN.name to stringResource(Strings.settings_remote_lsp_sync_method_builtin),
        RemoteLspSyncMethod.RSYNC.name to stringResource(Strings.settings_remote_lsp_sync_method_rsync),
        RemoteLspSyncMethod.MANUAL.name to stringResource(Strings.settings_remote_lsp_sync_method_manual)
    )

    TinaSingleChoiceDialog(
        title = stringResource(Strings.dialog_title_remote_lsp_sync_method),
        options = options,
        selectedValue = currentMethod.name,
        onSelected = { methodName ->
            val method = RemoteLspSyncMethod.valueOf(methodName)
            onMethodSelected(method)
        },
        onDismiss = onDismiss
    )
}

/**
 * 测试 WebSocket 连接
 *
 * @param host 服务器地址
 * @param port 端口号
 * @return 测试结果消息
 */
private data class WebSocketConnectionTestResult(
    val message: String,
    val success: Boolean
)

private suspend fun testWebSocketConnection(
    context: Context,
    host: String,
    port: Int,
    secureTransport: Boolean,
    authenticationToken: String?,
): WebSocketConnectionTestResult {
    return withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val trimmedHost = host.trim()
        val url = com.wuxianggujun.tinaide.core.lsp.RemoteLspConfig(
            host = trimmedHost,
            port = port,
            secureTransport = secureTransport,
        ).getWebSocketUrl()
        val isLoopbackHost = trimmedHost == "127.0.0.1" || trimmedHost.equals("localhost", ignoreCase = true)

        val client = com.wuxianggujun.tinaide.core.network.OkHttpClientProvider.probe

        val request = Request.Builder()
            .url(url)
            .apply {
                authenticationToken?.takeIf { it.isNotBlank() }?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val errorThrowable = AtomicReference<Throwable?>(null)
        val errorMessage = AtomicReference<String?>(null)
        val connectTime = AtomicLong(0)

        val webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connectTime.set(System.currentTimeMillis() - startTime)
                    success.set(true)
                    webSocket.close(1000, "Test completed")
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    errorThrowable.set(t)
                    errorMessage.set(t.message ?: Strings.error_unknown.strOr(context))
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!success.get()) {
                        errorMessage.set(
                            Strings.editor_lsp_connection_closed.strOr(
                                context,
                                reason.ifBlank { Strings.error_unknown.strOr(context) }
                            )
                        )
                    }
                    latch.countDown()
                }
            }
        )

        // 等待连接结果，最多 6 秒
        val completed = latch.await(6, TimeUnit.SECONDS)

        if (!completed) {
            webSocket.cancel()
            return@withContext WebSocketConnectionTestResult(
                message = Strings.editor_lsp_connection_timeout.strOr(context),
                success = false
            )
        }

        if (success.get()) {
            WebSocketConnectionTestResult(
                message = Strings.editor_lsp_connection_success.strOr(context, connectTime.get()),
                success = true
            )
        } else {
            val rawError = errorMessage.get()
                ?.trim()
                .orEmpty()
                .ifBlank { Strings.about_error_unknown.strOr(context) }
            val t = errorThrowable.get()

            val hints = buildList {
                val isCleartextBlocked = rawError.contains("CLEARTEXT communication", ignoreCase = true) ||
                    (t is java.net.UnknownServiceException && rawError.contains("not permitted", ignoreCase = true))
                if (isCleartextBlocked) {
                    add(Strings.editor_lsp_hint_cleartext.strOr(context))
                }
                if (isLoopbackHost) {
                    add(Strings.editor_lsp_hint_localhost_with_port.strOr(context, port))
                }
            }

            WebSocketConnectionTestResult(
                message = if (hints.isEmpty()) {
                    Strings.editor_lsp_connection_failed.strOr(context, rawError)
                } else {
                    buildString {
                        append(Strings.editor_lsp_connection_failed_prefix.strOr(context))
                        append(rawError)
                        hints.forEach { hint ->
                            append('\n')
                            append(hint)
                        }
                    }
                },
                success = false
            )
        }
    }
}

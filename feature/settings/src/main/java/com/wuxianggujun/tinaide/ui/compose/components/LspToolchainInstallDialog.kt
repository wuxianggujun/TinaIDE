package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.lsp.LspInstallProgress
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainEnvironmentStatus
import com.wuxianggujun.tinaide.plugin.lsp.ToolchainInstallState
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import java.net.URI

/**
 * LSP 工具链安装确认对话框
 *
 * 显示需要安装的工具链列表，让用户确认安装
 */
@Composable
fun LspToolchainConfirmDialog(
    pluginName: String,
    toolchains: List<LspToolchainConfig>,
    toolchainStates: Map<String, ToolchainInstallState>,
    environmentStatus: LspToolchainEnvironmentStatus? = null,
    isRepairMode: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val canConfirm = environmentStatus?.canInstall(toolchains) ?: true
    val titleRes = if (isRepairMode) {
        Strings.lsp_plugin_repair_deps_title
    } else {
        Strings.lsp_plugin_install_deps_title
    }
    val messageRes = if (isRepairMode) {
        Strings.lsp_plugin_repair_deps_dialog_desc
    } else {
        Strings.lsp_plugin_install_deps_dialog_desc
    }
    val confirmTextRes = if (isRepairMode) {
        Strings.lsp_plugin_repair_deps
    } else {
        Strings.lsp_plugin_install_deps
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(titleRes)) },
        text = {
            TinaDialogContentColumn(modifier = Modifier.verticalScroll(scrollState)) {
                TinaDialogMessageCard(
                    message = stringResource(messageRes, pluginName)
                )
                environmentStatus?.let { status ->
                    TinaDialogMessageCard(
                        message = status.summaryText(toolchains),
                    )
                }
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    toolchains.forEach { toolchain ->
                        val state = toolchainStates[toolchain.id] ?: ToolchainInstallState.NOT_INSTALLED
                        ToolchainItem(
                            toolchain = toolchain,
                            state = state
                        )
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(confirmTextRes),
                onClick = onConfirm,
                enabled = canConfirm
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

/**
 * LSP 工具链安装进度对话框
 *
 * 显示安装进度，不可取消
 */
@Composable
fun LspToolchainProgressDialog(
    pluginName: String,
    toolchains: List<LspToolchainConfig>,
    toolchainStates: Map<String, ToolchainInstallState>,
    currentProgress: LspInstallProgress?,
    onDismiss: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    TinaAlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { TinaDialogTitleText(stringResource(Strings.lsp_plugin_install_progress_title)) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .animateContentSize()
            ) {
                // 总体进度
                currentProgress?.let { progress ->
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = progress.phase,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        progress.message?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    toolchains.forEach { toolchain ->
                        val state = toolchainStates[toolchain.id] ?: ToolchainInstallState.NOT_INSTALLED
                        ToolchainItem(
                            toolchain = toolchain,
                            state = state
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 安装中不显示确认按钮
            if (onDismiss != null) {
                TinaTextButton(
                    text = stringResource(Strings.btn_confirm),
                    onClick = onDismiss
                )
            }
        }
    )
}

@Composable
private fun ToolchainItem(
    toolchain: LspToolchainConfig,
    state: ToolchainInstallState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态图标
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        ToolchainInstallState.INSTALLED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ToolchainInstallState.INSTALLING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                        ToolchainInstallState.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                        ToolchainInstallState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                ToolchainInstallState.INSTALLED -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                ToolchainInstallState.INSTALLING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                ToolchainInstallState.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                ToolchainInstallState.NOT_INSTALLED -> {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 工具链信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolchain.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = toolchain.summaryText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (toolchain.type.trim().equals("download", ignoreCase = true)) {
                ToolchainDetailText(
                    stringResource(
                        Strings.lsp_plugin_toolchain_source_host,
                        runCatching { URI(toolchain.url.orEmpty()).host }.getOrNull().orEmpty(),
                    )
                )
                ToolchainDetailText(stringResource(Strings.lsp_plugin_toolchain_checksum_verified))
                ToolchainDetailText(
                    stringResource(Strings.lsp_plugin_toolchain_target_path, toolchain.extractTo.orEmpty())
                )
            }
            ToolchainDetailText(
                stringResource(Strings.lsp_plugin_toolchain_verify_command, toolchain.verifyCommand.orEmpty())
            )
        }

        // 状态文字
        Text(
            text = when (state) {
                ToolchainInstallState.INSTALLED -> stringResource(Strings.lsp_plugin_toolchain_installed)
                ToolchainInstallState.INSTALLING -> stringResource(Strings.lsp_plugin_toolchain_installing)
                ToolchainInstallState.FAILED -> stringResource(Strings.lsp_plugin_toolchain_failed)
                ToolchainInstallState.NOT_INSTALLED -> stringResource(Strings.lsp_plugin_toolchain_not_installed)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (state) {
                ToolchainInstallState.INSTALLED -> MaterialTheme.colorScheme.primary
                ToolchainInstallState.INSTALLING -> MaterialTheme.colorScheme.tertiary
                ToolchainInstallState.FAILED -> MaterialTheme.colorScheme.error
                ToolchainInstallState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
            }
        )
    }
}

@Composable
private fun ToolchainDetailText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LspToolchainConfig.summaryText(): String {
    val packageSummary = packageSummary()
    return when (type.trim().lowercase()) {
        "system" -> stringResource(Strings.lsp_plugin_toolchain_type_system_package, packageSummary)
        "pip" -> stringResource(Strings.lsp_plugin_toolchain_type_pip, packageSummary)
        "npm" -> stringResource(Strings.lsp_plugin_toolchain_type_npm, packageSummary)
        "download" -> stringResource(Strings.lsp_plugin_toolchain_type_download)
        else -> type
    }
}

private fun LspToolchainConfig.packageSummary(): String = packages
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.takeIf(List<String>::isNotEmpty)
    ?.joinToString(", ")
    ?: packagesByManager
        .orEmpty()
        .values
        .flatten()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(", ")

@Composable
private fun LspToolchainEnvironmentStatus.summaryText(
    toolchains: List<LspToolchainConfig>,
): String {
    if (!linuxAvailable) {
        return stringResource(Strings.lsp_plugin_env_unavailable)
    }
    val requiresSystemPackages = toolchains.any { toolchain ->
        toolchain.type.trim().equals("system", ignoreCase = true)
    }
    if (requiresSystemPackages && !systemPackageManagerAvailable) {
        return stringResource(Strings.lsp_plugin_env_package_manager_unavailable, packageManagerName)
    }
    return if (requiresSystemPackages) {
        stringResource(Strings.lsp_plugin_env_ready_with_package_manager, packageManagerName)
    } else {
        stringResource(Strings.lsp_plugin_env_ready)
    }
}

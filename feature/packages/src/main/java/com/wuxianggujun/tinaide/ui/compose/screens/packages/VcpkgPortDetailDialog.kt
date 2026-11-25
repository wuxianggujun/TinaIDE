package com.wuxianggujun.tinaide.ui.compose.screens.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgPlatformSupport
import com.wuxianggujun.tinaide.core.packages.VcpkgPort
import com.wuxianggujun.tinaide.ui.compose.components.BadgeStatus
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaStatusBadge
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton

/** Read-only port details plus the dependency actions that also exist on the card. */
@Composable
internal fun VcpkgPortDetailDialog(
    port: VcpkgPort,
    declared: Boolean,
    installedVersion: String?,
    support: VcpkgPlatformSupport,
    verified: Boolean,
    androidAbi: String,
    canModify: Boolean,
    busy: Boolean,
    onToggleDependency: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val upgradable = installedVersion != null && port.version.isNotBlank() && installedVersion != port.version
    val statusText = when {
        installedVersion != null -> stringResource(Strings.vcpkg_detail_status_installed, installedVersion)
        declared -> stringResource(Strings.vcpkg_detail_status_declared)
        else -> stringResource(Strings.vcpkg_detail_status_not_declared)
    }
    val compatibilityText = when (support) {
        VcpkgPlatformSupport.SUPPORTED -> stringResource(Strings.vcpkg_detail_supported)
        VcpkgPlatformSupport.UNSUPPORTED -> stringResource(Strings.vcpkg_port_unsupported)
        VcpkgPlatformSupport.UNKNOWN -> stringResource(Strings.vcpkg_port_unknown)
    }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText("${port.name} ${port.version}".trim()) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (declared || verified || installedVersion != null || upgradable) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (declared) TinaStatusBadge(stringResource(Strings.vcpkg_declared_badge), BadgeStatus.INFO)
                            if (verified) TinaStatusBadge(stringResource(Strings.vcpkg_verified_badge), BadgeStatus.SUCCESS)
                        }
                        if (installedVersion != null) {
                            TinaStatusBadge(stringResource(Strings.vcpkg_installed_version, installedVersion), BadgeStatus.SUCCESS)
                        }
                        if (upgradable) {
                            TinaStatusBadge(
                                stringResource(Strings.vcpkg_upgradable, installedVersion.orEmpty(), port.version),
                                BadgeStatus.WARNING,
                            )
                        }
                    }
                }
                if (port.description.isNotBlank()) {
                    Text(
                        text = port.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailField(stringResource(Strings.vcpkg_detail_status), statusText)
                    DetailField(stringResource(Strings.vcpkg_detail_compatibility), compatibilityText)
                    DetailField(stringResource(Strings.vcpkg_detail_abi), androidAbi)
                    DetailField(
                        stringResource(Strings.vcpkg_detail_supports),
                        port.supports.ifBlank { stringResource(Strings.vcpkg_detail_supports_empty) },
                        monospace = port.supports.isNotBlank(),
                    )
                }
            }
        },
        confirmButton = {
            TinaDialogActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinaTextButton(text = stringResource(Strings.vcpkg_copy_name), onClick = onCopy)
                if (declared || support == VcpkgPlatformSupport.SUPPORTED) {
                    if (declared) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.vcpkg_remove_dependency),
                            enabled = canModify && !busy,
                            onClick = onToggleDependency,
                        )
                    } else {
                        TinaPrimaryButton(
                            text = stringResource(Strings.vcpkg_add_dependency),
                            enabled = canModify && !busy,
                            onClick = onToggleDependency,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TinaTextButton(text = stringResource(Strings.btn_close), onClick = onDismiss)
        },
    )
}

@Composable
private fun DetailField(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

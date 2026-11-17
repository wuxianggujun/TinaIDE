package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgPlatformSupport
import com.wuxianggujun.tinaide.core.packages.VcpkgPort
import com.wuxianggujun.tinaide.ui.compose.components.BadgeStatus
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaStatusBadge
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import kotlinx.coroutines.launch

/** Clipboard helper shared by the market card and the detail dialog. */
@Composable
internal fun rememberVcpkgNameCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedText = stringResource(Strings.vcpkg_port_name_copied)
    return remember(clipboard, scope, context, copiedText) {
        { name ->
            scope.launch { clipboard.setClipEntry(ClipData.newPlainText("vcpkg-port", name).toClipEntry()) }
            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VcpkgPortCard(
    port: VcpkgPort,
    declared: Boolean,
    installedVersion: String?,
    support: VcpkgPlatformSupport,
    verified: Boolean,
    canModify: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onToggleDependency: () -> Unit,
    onCopy: () -> Unit,
) {
    val upgradable = installedVersion != null && port.version.isNotBlank() && installedVersion != port.version
    TinaCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${port.name} ${port.version}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(Strings.vcpkg_copy_name),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (port.description.isNotBlank()) {
                Text(
                    text = port.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (declared || verified || installedVersion != null || upgradable) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (declared) TinaStatusBadge(stringResource(Strings.vcpkg_declared_badge), BadgeStatus.INFO)
                    if (verified) TinaStatusBadge(stringResource(Strings.vcpkg_verified_badge), BadgeStatus.SUCCESS)
                    installedVersion?.let {
                        TinaStatusBadge(stringResource(Strings.vcpkg_installed_version, it), BadgeStatus.SUCCESS)
                    }
                    if (upgradable) {
                        TinaStatusBadge(
                            stringResource(Strings.vcpkg_upgradable, installedVersion.orEmpty(), port.version),
                            BadgeStatus.WARNING,
                        )
                    }
                }
            }
            if (support != VcpkgPlatformSupport.SUPPORTED) {
                Text(
                    text = stringResource(
                        if (support == VcpkgPlatformSupport.UNSUPPORTED) Strings.vcpkg_port_unsupported
                        else Strings.vcpkg_port_unknown,
                    ),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (declared || support == VcpkgPlatformSupport.SUPPORTED) {
                    TinaTextButton(
                        text = stringResource(if (declared) Strings.vcpkg_remove_dependency else Strings.vcpkg_add_dependency),
                        enabled = canModify && !busy,
                        onClick = onToggleDependency,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(Strings.vcpkg_detail_open),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

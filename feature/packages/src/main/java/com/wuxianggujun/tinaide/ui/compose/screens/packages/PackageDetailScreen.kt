package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.ui.compose.components.DetailHeaderCard
import com.wuxianggujun.tinaide.ui.compose.components.DetailIconPlaceholder
import com.wuxianggujun.tinaide.ui.compose.components.DetailInfoCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDangerOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import timber.log.Timber

private const val TAG = "PackageDetailScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PackageDetailScreen(
    pkg: GUIPackage,
    installedMetadata: InstalledPackageMetadata?,
    installState: PackageInstallState,
    installEvent: InstallProgressEvent?,
    onInstall: (Platform) -> Unit,
    onUninstall: (Platform) -> Unit,
    onNavigateBack: () -> Unit,
    onDependencyClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TinaTopBar(
                title = pkg.name,
                onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(TinaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.xl)
        ) {
            // 包头部信息
            item {
                DetailHeaderCard(
                    icon = {
                        DetailIconPlaceholder(text = pkg.name)
                    },
                    title = pkg.name,
                    subtitle = pkg.description
                )
            }

            // 基本信息
            item {
                DetailInfoCard(
                    title = stringResource(Strings.pkg_detail_info)
                ) {
                    pkg.category?.let { category ->
                        InfoRow(label = stringResource(Strings.pkg_detail_category), value = category)
                    }

                    pkg.homepage?.let { homepage ->
                        InfoRow(label = stringResource(Strings.pkg_detail_homepage), value = homepage, isLink = true) {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(homepage)))
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(
                                    "Failed to open package homepage host=%s error=%s",
                                    Uri.parse(homepage).host.orEmpty(),
                                    e::class.java.simpleName,
                                )
                                Toast.makeText(
                                    context,
                                    Strings.toast_open_with_failed.strOr(context, homepage),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }

            // Linux 平台
            pkg.linux?.let { linuxPkg ->
                item {
                    PlatformDetailCard(
                        platformName = stringResource(Strings.pkg_detail_platform_linux),
                        platformIcon = "\uD83D\uDC27",
                        platformPackage = linuxPkg,
                        installState = installState.linux,
                        installEvent = installEvent,
                        onInstall = { onInstall(Platform.LINUX) },
                        onUninstall = { onUninstall(Platform.LINUX) },
                        onDependencyClick = onDependencyClick
                    )
                }
            }

            // Android 平台
            pkg.android?.let { androidPkg ->
                item {
                    PlatformDetailCard(
                        platformName = stringResource(Strings.pkg_detail_platform_android),
                        platformIcon = "\uD83E\uDD16",
                        platformPackage = androidPkg,
                        installedMetadata = installedMetadata,
                        installState = installState.android,
                        installEvent = installEvent,
                        onInstall = { onInstall(Platform.ANDROID) },
                        onUninstall = { onUninstall(Platform.ANDROID) },
                        onDependencyClick = onDependencyClick
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformDetailCard(
    platformName: String,
    platformIcon: String,
    platformPackage: PlatformPackage,
    installedMetadata: InstalledPackageMetadata? = null,
    installState: PlatformInstallState,
    installEvent: InstallProgressEvent?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onDependencyClick: ((String) -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = platformIcon, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = platformName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "v${platformPackage.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                InstallStatusChip(installState)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            val installTypeLabel = when (platformPackage.installType) {
                InstallType.APT -> stringResource(Strings.pkg_install_type_linux_package)
                InstallType.DOWNLOAD -> stringResource(Strings.pkg_install_type_download)
                InstallType.SCRIPT -> stringResource(Strings.pkg_install_type_script)
            }
            InfoRow(label = stringResource(Strings.pkg_detail_install_type), value = installTypeLabel)
            InfoRow(
                label = stringResource(Strings.pkg_detail_artifact_type),
                value = stringResource(platformPackage.artifactType.labelResId())
            )

            platformPackage.aptPackage?.let { apt ->
                InfoRow(label = stringResource(Strings.pkg_detail_apt_package), value = apt)
            }

            platformPackage.size?.let { size ->
                InfoRow(label = stringResource(Strings.pkg_detail_size), value = formatSize(size))
            }

            platformPackage.abi?.let { abi ->
                InfoRow(label = stringResource(Strings.pkg_detail_supported_abi), value = abi.joinToString(", "))
            }

            installedMetadata?.let { metadata ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Strings.pkg_detail_version_info),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = stringResource(Strings.pkg_detail_package_version_label), value = metadata.version)
                metadata.packageRevision?.let {
                    InfoRow(label = stringResource(Strings.pkg_detail_package_revision_label), value = it.toString())
                }
                metadata.upstreamName?.let {
                    InfoRow(label = stringResource(Strings.pkg_detail_upstream_name_label), value = it)
                }
                metadata.upstreamVersion?.let {
                    InfoRow(label = stringResource(Strings.pkg_detail_library_version_label), value = it)
                }
                metadata.upstreamTag?.let {
                    InfoRow(label = stringResource(Strings.pkg_detail_upstream_tag_label), value = it)
                }
            }

            platformPackage.dependencies?.takeIf { it.isNotEmpty() }?.let { deps ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Strings.pkg_detail_dependencies),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    deps.forEach { dep ->
                        DependencyChip(
                            packageId = dep,
                            onClick = onDependencyClick?.let { { it(dep) } }
                        )
                    }
                }
            }

            platformPackage.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Strings.pkg_detail_release_notes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (installState) {
                is PlatformInstallState.NotInstalled -> {
                    TinaPrimaryButton(
                        text = stringResource(Strings.pkg_detail_install),
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is PlatformInstallState.Installed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TinaDangerOutlinedButton(
                            text = stringResource(Strings.pkg_detail_uninstall),
                            onClick = onUninstall,
                            leadingIcon = Icons.Default.Delete,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is PlatformInstallState.Installing -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { installState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Strings.pkg_detail_installing, (installState.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is PlatformInstallState.UpdateAvailable -> {
                    Column {
                        Text(
                            text = stringResource(Strings.pkg_detail_update_available, installState.currentVersion, installState.newVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TinaPrimaryButton(
                                text = stringResource(Strings.pkg_detail_update),
                                onClick = onInstall,
                                modifier = Modifier.weight(1f)
                            )
                            TinaDangerOutlinedButton(
                                text = stringResource(Strings.pkg_detail_uninstall),
                                onClick = onUninstall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            installEvent?.let { event ->
                when (event) {
                    is InstallProgressEvent.Preparing -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(event.message, style = MaterialTheme.typography.bodySmall)
                    }
                    is InstallProgressEvent.Downloading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Downloading: ${(event.progress * 100).toInt()}% - ${formatSize(event.speed)}/s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    is InstallProgressEvent.Extracting -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(Strings.pkg_detail_extracting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is InstallProgressEvent.Installing -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(event.message, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun InstallStatusChip(state: PlatformInstallState) {
    when (state) {
        is PlatformInstallState.Installed -> {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(Strings.pkg_detail_installed)) },
                leadingIcon = {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        is PlatformInstallState.UpdateAvailable -> {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(Strings.pkg_detail_update)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
        is PlatformInstallState.Installing -> {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(Strings.pkg_detail_installing_status)) }
            )
        }
        else -> {}
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isLink: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isLink && onClick != null) {
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(text = value, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun DependencyChip(
    packageId: String,
    onClick: (() -> Unit)?
) {
    if (onClick != null) {
        AssistChip(
            onClick = onClick,
            label = { Text(packageId) }
        )
    } else {
        AssistChip(
            onClick = {},
            label = { Text(packageId) },
            enabled = false
        )
    }
}

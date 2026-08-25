package com.wuxianggujun.tinaide.ui.compose.screens.packages
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.ui.compose.components.PluginCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaDangerButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaInfoDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSearchField
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import org.koin.androidx.compose.koinViewModel

/**
 * Package manager install/uninstall/batch/details dialogs.
 */

@Composable
internal fun InstallConfirmDialog(
    packageInfo: GUIPackage,
    platform: Platform,
    plan: PackageInstallPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dependenciesToInstall = plan.packages.filterNot { it.isRoot }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_install_confirm_title, packageInfo.name)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_install_confirm_message,
                        platformDisplayName(platform),
                        packageInfo.name
                    )
                )
                if (dependenciesToInstall.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_install_confirm_dependencies_title),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependenciesToInstall.forEach { dependency ->
                            Text(
                                text = "\u2022 " + stringResource(
                                    Strings.pkg_manager_install_confirm_dependency_item,
                                    dependency.packageName,
                                    dependency.version
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.pkg_manager_install_confirm_button),
                onClick = onConfirm
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

@Composable
internal fun BatchPlanConfirmDialog(
    title: String,
    message: String,
    plans: List<PackageInstallPlan>,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dependenciesToInstall = remember(plans) {
        collectBatchPlanDependencies(plans)
    }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(title) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(message = message)
                if (dependenciesToInstall.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_install_confirm_dependencies_title),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependenciesToInstall.forEach { dependency ->
                            Text(
                                text = "\u2022 " + stringResource(
                                    Strings.pkg_manager_install_confirm_dependency_item,
                                    dependency.packageName,
                                    dependency.version
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = confirmText,
                onClick = onConfirm
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

internal fun collectBatchPlanDependencies(plans: List<PackageInstallPlan>): List<PackageInstallPlanItem> = plans.asSequence()
    .flatMap { it.packages.asSequence() }
    .filterNot { it.isRoot }
    .filterNot { it.isAlreadyInstalled }
    .distinctBy { it.packageId }
    .sortedBy { it.packageName.lowercase() }
    .toList()

@Composable
internal fun platformDisplayName(platform: Platform): String = when (platform) {
    Platform.LINUX -> stringResource(Strings.pkg_manager_platform_linux)
    Platform.ANDROID -> stringResource(Strings.pkg_manager_platform_android)
}

@Composable
internal fun InstallProgressDialog(
    packageName: String,
    platform: Platform,
    event: InstallProgressEvent,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_installing_title, packageName)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                    when (event) {
                        is InstallProgressEvent.Preparing -> {
                            Text(stringResource(Strings.pkg_manager_progress_preparing, event.message))
                        }
                        is InstallProgressEvent.Downloading -> {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                stringResource(
                                    Strings.pkg_manager_progress_downloading,
                                    (event.progress * 100).toInt()
                                )
                            )
                        }
                        is InstallProgressEvent.Verifying -> {
                            Text(stringResource(Strings.pkg_manager_progress_verifying, event.message))
                        }
                        is InstallProgressEvent.Extracting -> {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(stringResource(Strings.pkg_manager_progress_extracting))
                        }
                        is InstallProgressEvent.Installing -> {
                            Text(stringResource(Strings.pkg_manager_progress_installing, event.message))
                        }
                        is InstallProgressEvent.Completed -> {
                            Text(stringResource(Strings.pkg_manager_progress_completed))
                        }
                        is InstallProgressEvent.Failed -> {
                            Text(
                                stringResource(
                                    Strings.pkg_manager_progress_failed,
                                    event.error.toDisplayMessage()
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (event is InstallProgressEvent.Completed || event is InstallProgressEvent.Failed) {
                TinaTextButton(
                    text = stringResource(Strings.btn_close),
                    onClick = onDismiss
                )
            } else {
                // 下载/安装进行中：允许取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel_install),
                    onClick = onCancel
                )
            }
        }
    )
}

@Composable
internal fun InstallCompleteDialog(
    result: InstallResult,
    onDismiss: () -> Unit
) {
    when (result) {
        is InstallResult.Success -> {
            // 安装成功：提示用户重新打开文件
            TinaAlertDialog(
                onDismissRequest = onDismiss,
                title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_install_complete)) },
                text = {
                    TinaDialogContentColumn {
                        TinaDialogMessageCard(
                            message = stringResource(
                                Strings.pkg_manager_install_success_msg,
                                result.packageId,
                                result.version
                            )
                        )
                        TinaDialogMessageCard(
                            message = stringResource(Strings.pkg_manager_reopen_file_hint),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                confirmButton = {
                    TinaPrimaryButton(
                        text = stringResource(Strings.btn_confirm),
                        onClick = onDismiss
                    )
                }
            )
        }
        is InstallResult.Failure -> {
            // 安装失败：只显示错误信息
            TinaInfoDialog(
                title = stringResource(Strings.pkg_manager_install_failed),
                message = result.error.toDisplayMessage(),
                confirmText = stringResource(Strings.btn_confirm),
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun UninstallConfirmDialog(
    packageInfo: GUIPackage,
    platform: Platform,
    dependentPackages: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_uninstall_title, packageInfo.name)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_uninstall_message,
                        platformDisplayName(platform),
                        packageInfo.name
                    )
                )
                if (dependentPackages.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_uninstall_warning),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependentPackages.forEach { dependentPackage ->
                            Text(
                                text = "\u2022 $dependentPackage",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaDangerButton(
                text = stringResource(Strings.pkg_manager_btn_uninstall),
                onClick = onConfirm
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

@Composable
internal fun UninstallCompleteDialog(
    result: UninstallResult,
    onDismiss: () -> Unit
) {
    when (result) {
        is UninstallResult.Success -> {
            // 卸载成功：提示用户重新打开文件
            TinaAlertDialog(
                onDismissRequest = onDismiss,
                title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_uninstall_complete)) },
                text = {
                    TinaDialogContentColumn {
                        TinaDialogMessageCard(
                            message = stringResource(
                                Strings.pkg_manager_uninstall_success_msg,
                                result.packageId
                            )
                        )
                        TinaDialogMessageCard(
                            message = stringResource(Strings.pkg_manager_reopen_file_hint),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                confirmButton = {
                    TinaPrimaryButton(
                        text = stringResource(Strings.btn_confirm),
                        onClick = onDismiss
                    )
                }
            )
        }
        is UninstallResult.Failure -> {
            // 卸载失败：只显示错误信息
            TinaInfoDialog(
                title = stringResource(Strings.pkg_manager_uninstall_failed),
                message = result.error.toDisplayMessage(),
                confirmText = stringResource(Strings.btn_confirm),
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun PackageDetailsDialog(
    pkg: GUIPackage,
    installedMetadata: InstalledPackageMetadata?,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(pkg.name) },
        text = {
            TinaDialogContentColumn {
                pkg.description?.let { description ->
                    TinaDialogMessageCard(message = description)
                }

                if (pkg.homepage != null || pkg.category != null) {
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pkg.homepage?.let {
                            Text(
                                text = stringResource(Strings.pkg_manager_detail_homepage, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        pkg.category?.let {
                            Text(
                                text = stringResource(Strings.pkg_manager_detail_category, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (pkg.linux != null || pkg.android != null) {
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pkg.linux?.let { linuxPkg ->
                            val linuxArtifactTypeLabel = stringResource(linuxPkg.artifactType.labelResId())
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_linux,
                                    linuxPkg.version,
                                    linuxPkg.installType.name.lowercase()
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_artifact_type,
                                    linuxArtifactTypeLabel
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        pkg.android?.let { androidPkg ->
                            val androidArtifactTypeLabel = stringResource(androidPkg.artifactType.labelResId())
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_android,
                                    androidPkg.version,
                                    androidPkg.installType.name.lowercase()
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_artifact_type,
                                    androidArtifactTypeLabel
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            androidPkg.abi?.let { abi ->
                                Text(
                                    text = stringResource(Strings.pkg_manager_detail_abi, abi.joinToString()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                installedMetadata?.let { metadata ->
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(Strings.pkg_detail_package_version, metadata.version),
                            style = MaterialTheme.typography.bodySmall
                        )
                        metadata.packageRevision?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_package_revision, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamName?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_upstream_name, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamVersion?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_library_version, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamTag?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_upstream_tag, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun BatchInstallProgressDialog(
    currentIndex: Int,
    totalCount: Int,
    currentPackageName: String,
    platform: Platform,
    event: InstallProgressEvent
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = {
            TinaDialogTitleText(
                stringResource(Strings.pkg_manager_batch_install_title, currentIndex + 1, totalCount)
            )
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_batch_install_msg,
                        currentPackageName,
                        platformDisplayName(platform)
                    )
                )
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { (currentIndex.toFloat() + 0.5f) / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProgressEventContent(event)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
internal fun BatchUpdateProgressDialog(
    currentIndex: Int,
    totalCount: Int,
    currentPackageName: String,
    event: InstallProgressEvent
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = {
            TinaDialogTitleText(
                stringResource(Strings.pkg_manager_updating_title, currentIndex + 1, totalCount)
            )
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.pkg_manager_updating_msg, currentPackageName)
                )
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { (currentIndex.toFloat() + 0.5f) / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProgressEventContent(event)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
internal fun ProgressEventContent(event: InstallProgressEvent) {
    when (event) {
        is InstallProgressEvent.Preparing -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Downloading -> {
            LinearProgressIndicator(
                progress = { event.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(Strings.pkg_manager_progress_downloading_simple, (event.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        }
        is InstallProgressEvent.Verifying -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Extracting -> {
            LinearProgressIndicator(
                progress = { event.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(Strings.pkg_manager_progress_extracting_simple), style = MaterialTheme.typography.bodySmall)
        }
        is InstallProgressEvent.Installing -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Completed -> Text(stringResource(Strings.pkg_manager_progress_completed_simple), style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Failed -> Text(
            stringResource(Strings.pkg_manager_progress_failed_simple, event.error.toDisplayMessage()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

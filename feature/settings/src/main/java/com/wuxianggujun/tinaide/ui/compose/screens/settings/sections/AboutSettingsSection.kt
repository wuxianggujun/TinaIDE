package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.common.AppVersionInfoReader
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.config.ServerConfigManager
import com.wuxianggujun.tinaide.core.crash.CrashLogUploadScheduler
import com.wuxianggujun.tinaide.core.crash.CrashUploadState
import com.wuxianggujun.tinaide.core.device.DeviceFingerprint
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.logging.LogExportProfile
import com.wuxianggujun.tinaide.core.logging.LogExportUtils
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsDisplayItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import java.util.Locale
import kotlinx.coroutines.launch

private const val GITHUB_URL = "https://github.com/wuxianggujun/TinaIDE"
private const val QQ_GROUP_NUMBER = "557484133"
private const val QQ_GROUP_JOIN_URL = ""

@Composable
internal fun AboutSettingsSection(
    showPRootDiagnostics: Boolean = true,
    onNavigateToPRootLog: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showClearLogsDialog by remember { mutableStateOf(false) }
    var showUploadLogsDialog by remember { mutableStateOf(false) }
    var isExportingLogs by remember { mutableStateOf(false) }
    var isClearingLogs by remember { mutableStateOf(false) }
    var isUploadingLogs by remember { mutableStateOf(false) }
    var crashAutoUploadEnabled by remember { mutableStateOf(Prefs.crashAutoUploadEnabled) }
    var crashUploadSnapshot by remember {
        mutableStateOf(CrashUploadState.getLastUploadSnapshot(context.applicationContext))
    }

    // 开发者选项激活逻辑
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    val developerOptionsEnabled = remember { Prefs.developerOptionsEnabled }

    val errorGetVersionFailed = stringResource(Strings.error_get_version_failed)
    val versionInfo = remember(context, errorGetVersionFailed) {
        try {
            AppVersionInfoReader.read(context).displayText
        } catch (_: Exception) {
            errorGetVersionFailed
        }
    }

    val cannotOpenLinkError = stringResource(Strings.error_cannot_open_link)
    val qqGroupNotConfiguredError = stringResource(Strings.about_qq_group_not_configured)
    val qqGroupOpenUrls = remember {
        AboutSettingsSectionSupport.resolveQqGroupOpenUrls(
            groupNumber = QQ_GROUP_NUMBER,
            joinUrl = QQ_GROUP_JOIN_URL,
        )
    }

    val toastExportingLogs = stringResource(Strings.toast_exporting_logs)
    val toastLogsExportedTemplate = stringResource(Strings.toast_logs_exported)
    val toastExportLogsFailedTemplate = stringResource(Strings.toast_export_logs_failed)
    val toastClearingLogs = stringResource(Strings.toast_clearing_logs)
    val toastLogsCleared = stringResource(Strings.toast_logs_cleared)
    val toastClearLogsFailedTemplate = stringResource(Strings.toast_clear_logs_failed)
    val toastUploadingLogs = stringResource(Strings.toast_uploading_logs)
    val toastUploadLogsFailedTemplate = stringResource(Strings.toast_upload_logs_failed)
    val toastLogsUploadedTemplate = stringResource(Strings.toast_logs_uploaded)
    val crashUploadStatusSpec = remember(crashAutoUploadEnabled, crashUploadSnapshot) {
        AboutSettingsSectionSupport.resolveCrashUploadStatus(
            autoUploadEnabled = crashAutoUploadEnabled,
            snapshot = crashUploadSnapshot,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 版本信息
    SettingsCategoryTitle(stringResource(Strings.settings_cat_about))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_version_info),
            value = versionInfo,
            onClick = {
                val result = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
                    serverAllowsDeveloperOptions = ServerConfigManager.isDeveloperOptionsEnabled(),
                    developerOptionsEnabled = developerOptionsEnabled,
                    currentState = AboutDeveloperOptionsTapState(
                        clickCount = clickCount,
                        lastClickTimeMillis = lastClickTime,
                    ),
                    currentTimeMillis = System.currentTimeMillis(),
                )
                clickCount = result.nextState.clickCount
                lastClickTime = result.nextState.lastClickTimeMillis
                if (result.enableDeveloperOptions) {
                    Prefs.setDeveloperOptionsEnabled(true)
                }
                result.toast?.let { toast ->
                    Toast.makeText(
                        context,
                        context.resolveAboutMessage(toast.message),
                        if (toast.duration == AboutToastDuration.Long) {
                            Toast.LENGTH_LONG
                        } else {
                            Toast.LENGTH_SHORT
                        }
                    ).show()
                }
            },
            showDivider = false
        )
    }

    // 社区
    SettingsCategoryTitle(stringResource(Strings.settings_cat_community))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_github),
            subtitle = stringResource(Strings.settings_github_desc),
            onClick = {
                if (!context.openFirstAvailableExternalUrl(listOf(GITHUB_URL))) {
                    Toast.makeText(context, cannotOpenLinkError, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_qq_group),
            subtitle = stringResource(Strings.settings_qq_group_desc),
            onClick = {
                when {
                    qqGroupOpenUrls.isEmpty() -> {
                        Toast.makeText(context, qqGroupNotConfiguredError, Toast.LENGTH_SHORT).show()
                    }

                    !context.openFirstAvailableExternalUrl(qqGroupOpenUrls) -> {
                        Toast.makeText(context, cannotOpenLinkError, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            showDivider = false
        )
    }

    // 诊断与调试
    SettingsCategoryTitle(stringResource(Strings.settings_cat_diagnostics))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_export_logs),
            subtitle = stringResource(Strings.settings_export_logs_desc),
            onClick = {
                if (AboutSettingsSectionSupport.shouldStartLogExport(isExportingLogs)) {
                    isExportingLogs = true
                    Toast.makeText(context, toastExportingLogs, Toast.LENGTH_SHORT).show()

                    scope.launch {
                        try {
                            val logFile = LogExportUtils.exportLogs(context)
                            isExportingLogs = false

                            if (logFile != null) {
                                Toast.makeText(
                                    context,
                                    String.format(Locale.getDefault(), toastLogsExportedTemplate, logFile.name),
                                    Toast.LENGTH_LONG
                                ).show()

                                // 分享日志文件
                                LogExportUtils.shareLogs(context, logFile)
                            } else {
                                Toast.makeText(
                                    context,
                                    String.format(Locale.getDefault(), toastExportLogsFailedTemplate, Strings.about_error_unknown.strOr(context)),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            isExportingLogs = false
                            Toast.makeText(
                                context,
                                String.format(Locale.getDefault(), toastExportLogsFailedTemplate, e.message ?: Strings.about_error_unknown.strOr(context)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_upload_logs),
            subtitle = stringResource(Strings.settings_upload_logs_desc),
            onClick = {
                if (AboutSettingsSectionSupport.shouldShowUploadLogsDialog(isUploadingLogs)) {
                    showUploadLogsDialog = true
                }
            },
            showDivider = true
        )

        if (showPRootDiagnostics) {
            SettingsClickableItem(
                title = stringResource(Strings.settings_view_proot_logs),
                subtitle = stringResource(Strings.settings_view_proot_logs_desc),
                onClick = {
                    onNavigateToPRootLog()
                },
                showDivider = true
            )
        }

        SettingsSwitchItem(
            title = stringResource(Strings.settings_crash_auto_upload),
            subtitle = stringResource(Strings.settings_crash_auto_upload_desc),
            checked = crashAutoUploadEnabled,
            onCheckedChange = {
                crashAutoUploadEnabled = it
                Prefs.crashAutoUploadEnabled = it
                if (it) {
                    CrashLogUploadScheduler.scheduleIfNeeded(context.applicationContext)
                } else {
                    CrashLogUploadScheduler.cancel(context.applicationContext)
                }
                crashUploadSnapshot = CrashUploadState.getLastUploadSnapshot(context.applicationContext)
            },
            showDivider = true
        )

        SettingsDisplayItem(
            title = stringResource(Strings.settings_crash_upload_status),
            value = stringResource(crashUploadStatusSpec.messageRes),
            showDivider = true,
            valueMaxLines = 3,
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_clear_logs),
            subtitle = stringResource(Strings.settings_clear_logs_desc),
            onClick = {
                showClearLogsDialog = true
            },
            showDivider = false
        )
    }

    if (showUploadLogsDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.dialog_title_upload_logs),
            message = stringResource(Strings.dialog_message_upload_logs),
            onConfirm = {
                showUploadLogsDialog = false
                if (AboutSettingsSectionSupport.shouldStartLogsUpload(isUploadingLogs)) {
                    isUploadingLogs = true
                    Toast.makeText(context, toastUploadingLogs, Toast.LENGTH_SHORT).show()
                    scope.launch {
                        try {
                            val zip = LogExportUtils.exportLogs(
                                context = context,
                                profile = LogExportProfile.SERVER_UPLOAD
                            )
                            if (zip == null) {
                                Toast.makeText(
                                    context,
                                    String.format(
                                        Locale.getDefault(),
                                        toastUploadLogsFailedTemplate,
                                        Strings.about_export_logs_failed.strOr(context)
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            val serverConfig = TinaServerConfig.getInstance(context)
                            val api = serverConfig.getApi()
                            val deviceInfo = serverConfig.getDeviceInfo()
                            val fingerprint = DeviceFingerprint.get(context)

                            val upload = api.uploadLog(
                                logType = "runtime",
                                deviceFingerprint = fingerprint,
                                deviceInfo = deviceInfo,
                                title = Strings.about_user_report_log.strOr(context),
                                content = null,
                                file = zip,
                                extraFields = emptyMap()
                            )

                            when (upload) {
                                is ApiResult.Success -> {
                                    Toast.makeText(
                                        context,
                                        String.format(
                                            Locale.getDefault(),
                                            toastLogsUploadedTemplate,
                                            upload.data.id
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                is ApiResult.NetworkError -> {
                                    Toast.makeText(
                                        context,
                                        String.format(
                                            Locale.getDefault(),
                                            toastUploadLogsFailedTemplate,
                                            upload.message
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is ApiResult.Error -> {
                                    Toast.makeText(
                                        context,
                                        String.format(
                                            Locale.getDefault(),
                                            toastUploadLogsFailedTemplate,
                                            upload.message
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                String.format(
                                    Locale.getDefault(),
                                    toastUploadLogsFailedTemplate,
                                    e.message ?: Strings.about_error_unknown.strOr(context)
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            isUploadingLogs = false
                        }
                    }
                }
            },
            onDismiss = { showUploadLogsDialog = false }
        )
    }

    // 清空日志确认对话框
    if (showClearLogsDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.dialog_title_clear_logs),
            message = stringResource(Strings.dialog_message_clear_logs),
            onConfirm = {
                showClearLogsDialog = false
                if (AboutSettingsSectionSupport.shouldStartLogsClear(isClearingLogs)) {
                    isClearingLogs = true
                    Toast.makeText(context, toastClearingLogs, Toast.LENGTH_SHORT).show()

                    scope.launch {
                        try {
                            val success = LogExportUtils.clearLogs(context)
                            isClearingLogs = false

                            if (success) {
                                Toast.makeText(
                                    context,
                                    toastLogsCleared,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    String.format(Locale.getDefault(), toastClearLogsFailedTemplate, Strings.about_clear_logs_failed.strOr(context)),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            isClearingLogs = false
                            Toast.makeText(
                                context,
                                String.format(Locale.getDefault(), toastClearLogsFailedTemplate, e.message ?: Strings.about_error_unknown.strOr(context)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onDismiss = { showClearLogsDialog = false }
        )
    }

    // 法律信息
    SettingsCategoryTitle(stringResource(Strings.settings_cat_legal))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_open_source_licenses),
            subtitle = stringResource(Strings.settings_licenses_desc),
            onClick = {
                onNavigateToLicenses()
            },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
}

private fun android.content.Context.resolveAboutMessage(spec: AboutMessageSpec): String =
    if (spec.formatArgs.isEmpty()) {
        getString(spec.messageRes)
    } else {
        getString(spec.messageRes, *spec.formatArgs.toTypedArray())
    }

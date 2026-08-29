package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.AlpineMirrorManager
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.proot.RootfsProfile
import com.wuxianggujun.tinaide.ui.compose.components.TinaErrorDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaLoadingDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun AlpineMirrorSettingsItem(
    profile: RootfsProfile?,
    showDivider: Boolean,
) {
    if (profile != null && profile.packageManager != RootfsPackageManager.APK) return

    val context = LocalContext.current
    val configManager: IConfigManager = koinInject()
    val manager = remember(context, configManager) { AlpineMirrorManager(context, configManager) }
    val selectedMirror by manager.selectedMirrorFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val rootfsPath = profile?.rootfsPath
    var appliedMirror by remember(rootfsPath) {
        mutableStateOf(rootfsPath?.let(manager::detectMirror))
    }
    var showDialog by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rootfsPath, selectedMirror) {
        appliedMirror = rootfsPath?.let(manager::detectMirror)
    }

    val mirrorNames = AlpineMirrorManager.Mirror.entries.associateWith { mirror ->
        stringResource(mirror.displayNameRes())
    }
    val mirrorDescriptions = AlpineMirrorManager.Mirror.entries.associateWith { mirror ->
        stringResource(mirror.descriptionRes())
    }
    val installedMirror = appliedMirror
    val displayedMirror = installedMirror ?: selectedMirror

    SettingsClickableItem(
        title = stringResource(Strings.settings_alpine_repository_mirror),
        subtitle = when {
            rootfsPath == null -> stringResource(Strings.settings_alpine_repository_preinstall_desc)
            installedMirror != null -> mirrorDescriptions.getValue(installedMirror)
            else -> stringResource(Strings.settings_alpine_repository_custom_desc)
        },
        value = if (rootfsPath != null && installedMirror == null) {
            stringResource(Strings.settings_alpine_repository_custom)
        } else {
            mirrorNames.getValue(displayedMirror)
        },
        onClick = { showDialog = true },
        showDivider = showDivider,
    )

    if (showDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_select_alpine_mirror),
            options = AlpineMirrorManager.Mirror.entries.map { mirror ->
                mirror.id to mirrorNames.getValue(mirror)
            },
            selectedValue = displayedMirror.id,
            onSelected = { mirrorId ->
                showDialog = false
                val mirror = AlpineMirrorManager.Mirror.fromId(mirrorId)
                val mirrorName = mirrorNames.getValue(mirror)
                if (rootfsPath == null) {
                    manager.selectMirror(mirror)
                    Toast.makeText(
                        context,
                        context.getString(Strings.toast_alpine_mirror_selected, mirrorName),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (mirror == installedMirror) {
                    manager.selectMirror(mirror)
                    Toast.makeText(
                        context,
                        context.getString(Strings.toast_alpine_mirror_selected, mirrorName),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val installedRootfsPath = requireNotNull(rootfsPath)
                    isSwitching = true
                    scope.launch {
                        manager.switchMirror(installedRootfsPath, mirror).fold(
                            onSuccess = {
                                appliedMirror = it
                                isSwitching = false
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        Strings.toast_alpine_mirror_switched,
                                        mirrorNames.getValue(it),
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onFailure = { error ->
                                isSwitching = false
                                switchError = context.getString(
                                    Strings.error_alpine_mirror_switch_failed,
                                    error.message.orEmpty().take(MAX_ERROR_LENGTH),
                                )
                            },
                        )
                    }
                }
            },
            onDismiss = { showDialog = false },
        )
    }

    if (isSwitching) {
        TinaLoadingDialog(
            title = stringResource(Strings.dialog_title_switching_alpine_mirror),
            message = stringResource(Strings.progress_updating_apk_index),
        )
    }

    switchError?.let { message ->
        TinaErrorDialog(
            title = stringResource(Strings.error_switch_failed),
            message = message,
            onDismiss = { switchError = null },
        )
    }
}

private const val MAX_ERROR_LENGTH = 2_000

@StringRes
private fun AlpineMirrorManager.Mirror.displayNameRes(): Int = when (this) {
    AlpineMirrorManager.Mirror.OFFICIAL -> Strings.alpine_mirror_official
    AlpineMirrorManager.Mirror.TSINGHUA -> Strings.alpine_mirror_tsinghua
    AlpineMirrorManager.Mirror.ALIYUN -> Strings.alpine_mirror_aliyun
    AlpineMirrorManager.Mirror.USTC -> Strings.alpine_mirror_ustc
    AlpineMirrorManager.Mirror.HUAWEI -> Strings.alpine_mirror_huawei
    AlpineMirrorManager.Mirror.TENCENT -> Strings.alpine_mirror_tencent
}

@StringRes
private fun AlpineMirrorManager.Mirror.descriptionRes(): Int = when (this) {
    AlpineMirrorManager.Mirror.OFFICIAL -> Strings.alpine_mirror_official_desc
    AlpineMirrorManager.Mirror.TSINGHUA -> Strings.alpine_mirror_tsinghua_desc
    AlpineMirrorManager.Mirror.ALIYUN -> Strings.alpine_mirror_aliyun_desc
    AlpineMirrorManager.Mirror.USTC -> Strings.alpine_mirror_ustc_desc
    AlpineMirrorManager.Mirror.HUAWEI -> Strings.alpine_mirror_huawei_desc
    AlpineMirrorManager.Mirror.TENCENT -> Strings.alpine_mirror_tencent_desc
}

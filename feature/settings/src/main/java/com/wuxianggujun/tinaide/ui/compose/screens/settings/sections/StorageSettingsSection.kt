package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.RootfsProfile
import com.wuxianggujun.tinaide.ui.compose.components.TinaActionChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaInputDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaLoadingDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsDisplayItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem

@Composable
internal fun StorageSettingsSection(
    viewModel: SettingsViewModel,
    onNavigateToDependencyInstall: () -> Unit = {},
    onNavigateToStorageCleanup: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val distros = remember(context) {
        viewModel.listRootfsDistroOptions(context)
    }
    val activeProfile = StorageSettingsSectionSupport.resolveActiveProfile(
        profiles = state.rootfsProfiles,
        activeRootfsProfileId = state.activeRootfsProfileId,
    )
    var selectedProfile by remember { mutableStateOf<RootfsProfile?>(null) }
    var renameProfile by remember { mutableStateOf<RootfsProfile?>(null) }
    var profileNameInput by remember { mutableStateOf("") }
    var deleteProfile by remember { mutableStateOf<RootfsProfile?>(null) }
    var showPresetDialog by remember { mutableStateOf(false) }
    val renameInvalid = StorageSettingsSectionSupport.isRenameInputInvalid(profileNameInput)

    LaunchedEffect(context, state.linuxEnvironmentEnabled) {
        if (state.linuxEnvironmentEnabled) {
            viewModel.refreshRootfsProfiles(context)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (state.linuxEnvironmentEnabled) {
        SettingsCategoryTitle(stringResource(Strings.settings_cat_linux_system))

        SettingsCard {
            if (distros.isNotEmpty()) {
                SettingsClickableItem(
                    title = stringResource(Strings.settings_linux_install_preset),
                    subtitle = stringResource(Strings.settings_linux_install_preset_desc),
                    onClick = { showPresetDialog = true },
                    showDivider = true,
                )
            }

            SettingsDisplayItem(
                title = stringResource(Strings.settings_linux_system_current),
                value = activeProfile?.displayName ?: stringResource(Strings.value_not_installed),
                showDivider = true,
            )

            SettingsClickableItem(
                title = stringResource(Strings.settings_linux_health_status),
                subtitle = StorageSettingsSectionSupport.resolveRootfsHealthSubtitle(state.rootfsHealth),
                value = StorageSettingsSectionSupport.resolveRootfsHealthValue(
                    health = state.rootfsHealth,
                    fallbackValue = stringResource(Strings.settings_linux_health_unknown),
                ),
                onClick = { viewModel.refreshRootfsHealth(context) },
                showDivider = activeProfile != null,
            )

            if (activeProfile != null) {
                SettingsClickableItem(
                    title = stringResource(Strings.settings_linux_desktop_status),
                    subtitle = state.linuxDesktopStatusText.ifBlank {
                        stringResource(Strings.settings_linux_desktop_not_ready)
                    },
                    onClick = { viewModel.refreshLinuxDesktopStatus(context) },
                    showDivider = true,
                )

                SettingsClickableItem(
                    title = stringResource(Strings.settings_linux_desktop_install),
                    subtitle = stringResource(Strings.settings_linux_desktop_install_desc),
                    onClick = { viewModel.installUbuntuDesktop(context) },
                    showDivider = true,
                )

                SettingsClickableItem(
                    title = stringResource(Strings.settings_linux_desktop_open),
                    subtitle = stringResource(Strings.settings_linux_desktop_open_desc),
                    onClick = { viewModel.openLinuxDesktop(context) },
                    showDivider = false,
                )
            }
        }

        SettingsCategoryTitle(stringResource(Strings.settings_linux_profiles))

        SettingsCard {
            state.rootfsProfiles.forEachIndexed { index, profile ->
                SettingsClickableItem(
                    title = profile.displayName,
                    subtitle = profile.rootfsPath,
                    value = resolveStorageSettingsValue(
                        StorageSettingsSectionSupport.resolveProfileValue(
                            profile = profile,
                            activeRootfsProfileId = state.activeRootfsProfileId,
                        )
                    ),
                    onClick = {
                        selectedProfile = profile
                    },
                    showDivider = index != state.rootfsProfiles.lastIndex,
                )
            }
        }

        if (state.rootfsInstallMessage.isNotBlank() || state.linuxDesktopMessage.isNotBlank()) {
            SettingsCategoryTitle(stringResource(Strings.settings_linux_install_status))

            SettingsCard {
                if (state.rootfsInstallMessage.isNotBlank()) {
                    SettingsDisplayItem(
                        title = stringResource(Strings.settings_linux_install_status),
                        value = state.rootfsInstallMessage,
                        showDivider = state.linuxDesktopMessage.isNotBlank(),
                    )
                }
                if (state.linuxDesktopMessage.isNotBlank()) {
                    SettingsDisplayItem(
                        title = stringResource(Strings.settings_linux_desktop_status),
                        value = state.linuxDesktopMessage,
                        showDivider = false,
                    )
                }
            }
        }
    }

    SettingsCategoryTitle(stringResource(Strings.settings_cat_advanced))

    SettingsCard {
        SettingsDisplayItem(
            title = stringResource(Strings.settings_linux_rootfs_path),
            value = StorageSettingsSectionSupport.resolveRootfsPathValue(
                rootfsPath = state.rootfsPath,
                defaultValue = stringResource(Strings.value_default),
            ),
            showDivider = true,
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_mt_access),
            subtitle = stringResource(Strings.settings_mt_access_desc),
            checked = state.mtFileProviderEnabled,
            onCheckedChange = { viewModel.setMTFileProviderEnabled(context, it) },
            showDivider = false,
        )
    }

    SettingsCategoryTitle(stringResource(Strings.settings_cat_storage_cleanup))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.storage_cleanup_entry_title),
            subtitle = stringResource(Strings.storage_cleanup_entry_subtitle),
            onClick = onNavigateToStorageCleanup,
            showDivider = false,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (showPresetDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_select_linux_distro),
            options = StorageSettingsSectionSupport.buildDistroOptions(distros),
            selectedValue = "",
            onSelected = { distroId ->
                val distro = StorageSettingsSectionSupport.resolveDistro(
                    distros = distros,
                    distroId = distroId,
                ) ?: return@TinaSingleChoiceDialog
                showPresetDialog = false
                viewModel.installRootfsDistro(context, distro)
            },
            onDismiss = { showPresetDialog = false },
        )
    }

    if (state.rootfsInstallInProgress) {
        TinaLoadingDialog(
            title = stringResource(Strings.settings_linux_installing),
            message = state.rootfsInstallMessage,
            progress = state.rootfsInstallProgress,
        )
    }

    if (state.linuxDesktopBusy) {
        TinaLoadingDialog(
            title = stringResource(
                if (state.linuxDesktopStarting) {
                    Strings.settings_linux_desktop_starting
                } else {
                    Strings.settings_linux_desktop_installing
                },
            ),
            message = state.linuxDesktopMessage,
            progress = state.linuxDesktopProgress,
        )
    }

    selectedProfile?.let { profile ->
        val actions = StorageSettingsSectionSupport.buildProfileActionSpecs(
            profile = profile,
            activeRootfsProfileId = state.activeRootfsProfileId,
        ).map { spec ->
            stringResource(spec.labelRes) to {
                when (spec.action) {
                    StorageProfileAction.Switch -> {
                        selectedProfile = null
                        viewModel.switchActiveRootfsProfile(context, profile.id)
                    }

                    StorageProfileAction.Rename -> {
                        selectedProfile = null
                        profileNameInput = profile.displayName
                        renameProfile = profile
                    }

                    StorageProfileAction.Delete -> {
                        selectedProfile = null
                        deleteProfile = profile
                    }
                }
            }
        }

        TinaActionChoiceDialog(
            title = profile.displayName,
            message = profile.rootfsPath,
            actions = actions,
            onDismiss = { selectedProfile = null },
        )
    }

    renameProfile?.let { profile ->
        TinaInputDialog(
            title = stringResource(Strings.settings_linux_rename_title),
            value = profileNameInput,
            onValueChange = { profileNameInput = it },
            onConfirm = {
                if (renameInvalid) return@TinaInputDialog
                val newName = profileNameInput.trim()
                renameProfile = null
                profileNameInput = ""
                viewModel.renameRootfsProfile(context, profile.id, newName)
            },
            onDismiss = {
                renameProfile = null
                profileNameInput = ""
            },
            label = stringResource(Strings.settings_linux_profile_name),
            placeholder = stringResource(Strings.settings_linux_profile_name_hint),
            isError = renameInvalid,
            errorText = if (renameInvalid) stringResource(Strings.settings_linux_profile_name_invalid) else null,
        )
    }

    deleteProfile?.let { profile ->
        TinaConfirmDialog(
            title = stringResource(Strings.settings_linux_delete_title),
            message = stringResource(Strings.settings_linux_delete_message, profile.displayName),
            confirmText = stringResource(Strings.btn_delete),
            onConfirm = {
                deleteProfile = null
                viewModel.deleteRootfsProfile(context, profile.id)
            },
            onDismiss = { deleteProfile = null },
            isDanger = true,
        )
    }
}

@Composable
private fun resolveStorageSettingsValue(spec: StorageSettingsValueSpec): String = when (spec) {
    is StorageSettingsValueSpec.RawText -> spec.value
    is StorageSettingsValueSpec.LabelRes -> stringResource(spec.labelRes)
}

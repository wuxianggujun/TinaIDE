package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem

@Composable
internal fun ProjectVcpkgSettingsItem(arguments: List<String>, onSave: (List<String>) -> Unit) {
    val current = VcpkgProjectSettings.from(arguments)
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsClickableItem(
        title = stringResource(Strings.vcpkg_title),
        subtitle = stringResource(Strings.vcpkg_description),
        value = stringResource(modeTitle(current.mode)),
        onClick = { editing = true },
        showDivider = false,
    )
    if (editing) {
        var mode by rememberSaveable { mutableStateOf(current.mode) }
        var root by rememberSaveable { mutableStateOf(current.root) }
        var installedDir by rememberSaveable { mutableStateOf(current.installedDir) }
        var invalid by rememberSaveable { mutableStateOf(false) }
        TinaAlertDialog(
            onDismissRequest = { editing = false },
            title = { TinaDialogTitleText(stringResource(Strings.vcpkg_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(VcpkgProjectSettings.DISABLED, VcpkgProjectSettings.MANIFEST, VcpkgProjectSettings.PREBUILT).forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { mode = option },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = { mode = option })
                            Text(stringResource(modeTitle(option)))
                        }
                    }
                    if (mode != VcpkgProjectSettings.DISABLED) {
                        OutlinedTextField(
                            value = root,
                            onValueChange = { root = it; invalid = false },
                            label = { Text(stringResource(Strings.vcpkg_root)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = installedDir,
                            onValueChange = { installedDir = it; invalid = false },
                            label = { Text(stringResource(Strings.vcpkg_installed_dir)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(Strings.vcpkg_setup_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (invalid) Text(stringResource(Strings.vcpkg_root_required), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save),
                    onClick = {
                        invalid = mode != VcpkgProjectSettings.DISABLED &&
                            (root.isBlank() || (root + installedDir).any { it in "\u0000\r\n;" })
                        if (!invalid) {
                            onSave(VcpkgProjectSettings(mode, root, installedDir).applyTo(arguments))
                            editing = false
                        }
                    },
                )
            },
            dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { editing = false }) },
        )
    }
}

private fun modeTitle(mode: String): Int = when (mode) {
    VcpkgProjectSettings.MANIFEST -> Strings.vcpkg_manifest
    VcpkgProjectSettings.PREBUILT -> Strings.vcpkg_prebuilt
    else -> Strings.vcpkg_disabled
}

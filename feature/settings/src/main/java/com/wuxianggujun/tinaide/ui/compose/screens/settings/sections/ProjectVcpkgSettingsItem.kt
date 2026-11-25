package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
        var mode by rememberSaveable(current) { mutableStateOf(current.mode) }
        TinaAlertDialog(
            onDismissRequest = { editing = false },
            title = { TinaDialogTitleText(stringResource(Strings.vcpkg_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val modes = listOf(VcpkgProjectSettings.DISABLED, VcpkgProjectSettings.MANIFEST, VcpkgProjectSettings.SHARED)
                    modes.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { mode = option },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = { mode = option })
                            Text(stringResource(modeTitle(option)))
                        }
                    }
                    Text(stringResource(Strings.vcpkg_managed_paths), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save),
                    onClick = {
                        val updated = VcpkgProjectSettings(mode)
                        onSave(updated.applyTo(arguments))
                        editing = false
                    },
                )
            },
            dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { editing = false }) },
        )
    }
}

private fun modeTitle(mode: String): Int = when (mode) {
    VcpkgProjectSettings.MANIFEST -> Strings.vcpkg_manifest
    VcpkgProjectSettings.SHARED -> Strings.vcpkg_shared_mode
    else -> Strings.vcpkg_disabled
}

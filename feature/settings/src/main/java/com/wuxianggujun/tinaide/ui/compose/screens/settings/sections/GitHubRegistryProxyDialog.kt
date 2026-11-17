package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField

/** Shared by the market shortcut and Git settings; both edit the same saved configuration. */
@Composable
fun GitHubRegistryProxyDialog(onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val initial = remember { GitSettingsSectionSupport.createGitHubRegistryProxyEditorState(GitHubRegistryProxyConfig.load(context)) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var mirror by rememberSaveable { mutableStateOf(initial.customMirrorUrl) }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedMirror = GitHubRegistryConfig.normalizeGitHubProxyPrefix(mirror)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.market_github_proxy)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Strings.github_registry_proxy_scope_desc), style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mirror.isBlank(), onClick = { mirror = ""; errorRes = null },
                        label = { Text(stringResource(Strings.github_registry_mirror_auto)) })
                    GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES.forEach { prefix ->
                        FilterChip(selected = selectedMirror == prefix, onClick = { mirror = prefix; errorRes = null },
                            label = { Text(prefix.removePrefix("https://").trimEnd('/')) })
                    }
                }
                TinaTextField(
                    value = mirror, onValueChange = { mirror = it; errorRes = null },
                    label = stringResource(Strings.github_registry_mirror_url_label),
                    placeholder = stringResource(Strings.github_registry_mirror_url_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(stringResource(Strings.github_registry_mirror_selection_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Strings.github_registry_proxy_enabled_title), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; errorRes = null })
                }
                if (enabled) {
                    Text(stringResource(Strings.github_registry_proxy_enabled_desc), style = MaterialTheme.typography.bodySmall)
                    TinaTextField(value = host, onValueChange = { host = it; errorRes = null },
                        label = stringResource(Strings.github_registry_proxy_host_label),
                        placeholder = stringResource(Strings.github_registry_proxy_host_placeholder))
                    TinaTextField(value = port, onValueChange = { port = it; errorRes = null },
                        label = stringResource(Strings.github_registry_proxy_port_label),
                        placeholder = stringResource(Strings.github_registry_proxy_port_placeholder),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TinaPrimaryButton(text = stringResource(Strings.btn_save), onClick = {
                val result = GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(enabled, host, port, mirror)
                val settings = result.settings
                if (settings == null) {
                    errorRes = result.errorRes
                } else {
                    GitHubRegistryProxyConfig.save(context, settings)
                    onSaved()
                    onDismiss()
                }
            })
        },
        dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = onDismiss) },
    )
}

package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginConfigurationPropertyType
import com.wuxianggujun.tinaide.plugin.PluginConfigurationSchema
import com.wuxianggujun.tinaide.plugin.PluginConfigurationStore
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticCategory
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticEntry
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticIssue
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsSnapshotFactory
import com.wuxianggujun.tinaide.plugin.PluginDoctor
import com.wuxianggujun.tinaide.plugin.PluginFaultRecord
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.ResolvedPluginConfigurationProperty
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspInstallProgress
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInfo
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInstallState
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.lsp.ToolchainInstallState
import com.wuxianggujun.tinaide.plugin.script.PermissionLevel
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginInfo
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginManager
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.toDiagnosticsReport
import com.wuxianggujun.tinaide.ui.compose.components.DetailHeaderCard
import com.wuxianggujun.tinaide.ui.compose.components.DetailIconPlaceholder
import com.wuxianggujun.tinaide.ui.compose.components.DetailInfoCard
import com.wuxianggujun.tinaide.ui.compose.components.LspToolchainConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.LspToolchainProgressDialog
import com.wuxianggujun.tinaide.ui.compose.components.PluginPermissionDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaInfoDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * Plugin configuration settings card and property row widgets.
 */

@Composable
internal fun PluginConfigurationSettingsCard(
    manifest: PluginManifest,
    configurationSummary: PluginsConfigurationSummary,
) {
    val context = LocalContext.current
    val store = remember(context) { PluginConfigurationStore.getInstance(context) }
    DetailInfoCard(
        title = configurationSummary.title
            ?: stringResource(Strings.settings_plugins_configuration)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
            configurationSummary.properties.forEachIndexed { index, property ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                PluginConfigurationPropertyRow(
                    manifest = manifest,
                    property = property,
                    store = store,
                )
            }
        }
    }
}

@Composable
internal fun PluginConfigurationPropertyRow(
    manifest: PluginManifest,
    property: ResolvedPluginConfigurationProperty,
    store: PluginConfigurationStore,
) {
    when {
        property.isEnum -> PluginConfigurationEnumRow(
            manifest = manifest,
            property = property,
            store = store,
        )
        property.type == PluginConfigurationPropertyType.BOOLEAN -> PluginConfigurationBooleanRow(
            manifest = manifest,
            property = property,
            store = store,
        )
        property.type == PluginConfigurationPropertyType.NUMBER -> PluginConfigurationNumberRow(
            manifest = manifest,
            property = property,
            store = store,
        )
        else -> PluginConfigurationStringRow(
            manifest = manifest,
            property = property,
            store = store,
        )
    }
}

@Composable
internal fun PluginConfigurationBooleanRow(
    manifest: PluginManifest,
    property: ResolvedPluginConfigurationProperty,
    store: PluginConfigurationStore,
) {
    var value by remember(manifest.id, property.key) {
        mutableStateOf(store.getValue(manifest, property.key))
    }
    val checked = PluginConfigurationSchema.booleanValue(value)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PluginConfigurationLabel(
            property = property,
            modifier = Modifier.weight(1f),
        )
        TinaTextButton(
            text = stringResource(Strings.settings_cat_reset),
            onClick = {
                store.resetValue(manifest, property.key)
                value = store.getValue(manifest, property.key)
            },
        )
        Switch(
            checked = checked,
            onCheckedChange = { nextValue ->
                if (store.setValue(manifest, property.key, JsonPrimitive(nextValue))) {
                    value = JsonPrimitive(nextValue)
                }
            },
        )
    }
}

@Composable
internal fun PluginConfigurationStringRow(
    manifest: PluginManifest,
    property: ResolvedPluginConfigurationProperty,
    store: PluginConfigurationStore,
) {
    var value by remember(manifest.id, property.key) {
        mutableStateOf(store.getValue(manifest, property.key))
    }
    var text by remember(manifest.id, property.key) {
        mutableStateOf(PluginConfigurationSchema.stringValue(value).orEmpty())
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
    ) {
        PluginConfigurationHeader(
            property = property,
            onReset = {
                store.resetValue(manifest, property.key)
                value = store.getValue(manifest, property.key)
                text = PluginConfigurationSchema.stringValue(value).orEmpty()
            },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { nextText ->
                text = nextText
                val nextValue = JsonPrimitive(nextText)
                if (store.setValue(manifest, property.key, nextValue)) {
                    value = nextValue
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = property.description?.let { description ->
                { Text(text = description) }
            },
        )
    }
}

@Composable
internal fun PluginConfigurationNumberRow(
    manifest: PluginManifest,
    property: ResolvedPluginConfigurationProperty,
    store: PluginConfigurationStore,
) {
    var value by remember(manifest.id, property.key) {
        mutableStateOf(store.getValue(manifest, property.key))
    }
    var text by remember(manifest.id, property.key) {
        mutableStateOf(PluginConfigurationSchema.numberText(value))
    }
    var hasError by remember(manifest.id, property.key) { mutableStateOf(false) }
    val numberErrorText = stringResource(Strings.settings_plugins_configuration_number_error)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
    ) {
        PluginConfigurationHeader(
            property = property,
            onReset = {
                store.resetValue(manifest, property.key)
                value = store.getValue(manifest, property.key)
                text = PluginConfigurationSchema.numberText(value)
                hasError = false
            },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { nextText ->
                text = nextText
                val nextValue = PluginConfigurationSchema.toJsonPrimitive(property, nextText)
                if (nextValue == null) {
                    hasError = true
                } else if (store.setValue(manifest, property.key, nextValue)) {
                    value = nextValue
                    hasError = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hasError,
            supportingText = {
                Text(
                    text = if (hasError) {
                        numberErrorText
                    } else {
                        property.description.orEmpty()
                    },
                    color = if (hasError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        )
    }
}

@Composable
internal fun PluginConfigurationEnumRow(
    manifest: PluginManifest,
    property: ResolvedPluginConfigurationProperty,
    store: PluginConfigurationStore,
) {
    var value by remember(manifest.id, property.key) {
        mutableStateOf(store.getValue(manifest, property.key))
    }
    var showChoiceDialog by remember(manifest.id, property.key) { mutableStateOf(false) }
    val selectedValue = PluginConfigurationSchema.stringValue(value)
    val unsetText = stringResource(Strings.settings_plugins_configuration_unset)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showChoiceDialog = true },
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PluginConfigurationLabel(
            property = property,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = selectedValue ?: unsetText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TinaTextButton(
            text = stringResource(Strings.settings_cat_reset),
            onClick = {
                store.resetValue(manifest, property.key)
                value = store.getValue(manifest, property.key)
            },
        )
    }

    if (showChoiceDialog) {
        TinaSingleChoiceDialog(
            title = property.key,
            options = property.enumValues.map { option -> option to option },
            selectedValue = selectedValue,
            onSelected = { selected ->
                val nextValue = JsonPrimitive(selected)
                if (store.setValue(manifest, property.key, nextValue)) {
                    value = nextValue
                }
                showChoiceDialog = false
            },
            onDismiss = { showChoiceDialog = false },
        )
    }
}

@Composable
internal fun PluginConfigurationHeader(
    property: ResolvedPluginConfigurationProperty,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = property.key,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TinaTextButton(
            text = stringResource(Strings.settings_cat_reset),
            onClick = onReset,
        )
    }
}

@Composable
internal fun PluginConfigurationLabel(
    property: ResolvedPluginConfigurationProperty,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xxs),
    ) {
        Text(
            text = property.key,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        property.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


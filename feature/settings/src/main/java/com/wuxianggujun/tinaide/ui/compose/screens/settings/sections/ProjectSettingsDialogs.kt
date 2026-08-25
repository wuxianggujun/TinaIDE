package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.ui.compose.components.TinaActionChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaInputDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsDisplayItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Project settings dialogs and template/path/flag editor helpers.
 */

@Composable
internal fun resolveProjectSettingsText(spec: ProjectSettingsTextSpec): String = if (spec.formatArgs.isEmpty()) {
    stringResource(spec.labelRes)
} else {
    stringResource(spec.labelRes, *spec.formatArgs.toTypedArray())
}

internal fun copyUserProjectTemplateText(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun resolveUserProjectTemplateFailureMessageRes(error: Throwable): Int {
    val failure = (error as? UserProjectTemplateException)?.failure
        ?: (error.cause as? UserProjectTemplateException)?.failure
    return when (failure) {
        UserProjectTemplateFailure.NOT_ZIP -> Strings.settings_user_templates_error_not_zip
        UserProjectTemplateFailure.INVALID_NAME -> Strings.settings_user_templates_error_invalid_name
        UserProjectTemplateFailure.CANNOT_READ -> Strings.settings_user_templates_error_cannot_read
        UserProjectTemplateFailure.INVALID_ZIP -> Strings.settings_user_templates_error_invalid_zip
        UserProjectTemplateFailure.DELETE_FAILED -> Strings.settings_user_templates_error_delete_failed
        UserProjectTemplateFailure.RENAME_FAILED -> Strings.settings_user_templates_error_rename_failed
        UserProjectTemplateFailure.EXPORT_FAILED -> Strings.settings_user_templates_error_export_failed
        UserProjectTemplateFailure.UNSAFE_PATH -> Strings.settings_user_templates_error_unsafe_path
        UserProjectTemplateFailure.METADATA_UPDATE_FAILED -> Strings.settings_user_templates_error_metadata_update_failed
        UserProjectTemplateFailure.IMPORT_FAILED,
        null -> Strings.settings_user_templates_error_import_failed
    }
}

internal fun resolveUserProjectTemplateRenameErrorRes(
    templatesDir: java.io.File,
    currentName: String,
    input: String,
): Int? {
    if (input.isBlank()) {
        return Strings.settings_user_templates_error_invalid_name
    }
    val safeName = UserProjectTemplateManager.sanitizeTemplateFileName(input)
    val target = java.io.File(templatesDir, safeName)
    return if (safeName != currentName && target.exists()) {
        Strings.settings_user_templates_error_rename_exists
    } else {
        null
    }
}

internal fun resolveUserProjectTemplateVariableErrorRes(
    error: UserProjectTemplateVariableInputError
): Int = when (error) {
    UserProjectTemplateVariableInputError.MISSING_SEPARATOR ->
        Strings.settings_user_templates_variables_error_missing_separator
    UserProjectTemplateVariableInputError.INVALID_NAME ->
        Strings.settings_user_templates_variables_error_invalid_name
    UserProjectTemplateVariableInputError.EMPTY_VALUE ->
        Strings.settings_user_templates_variables_error_empty_value
}

internal fun resolveUserProjectTemplateBuildSystemLabelRes(buildSystem: ProjectBuildSystem): Int = when (buildSystem) {
    ProjectBuildSystem.SINGLE_FILE -> Strings.settings_user_templates_build_system_single_file
    ProjectBuildSystem.CMAKE -> Strings.tag_cmake
    ProjectBuildSystem.MAKE -> Strings.tag_makefile
    ProjectBuildSystem.PLUGIN -> Strings.tag_plugin
    ProjectBuildSystem.UNKNOWN -> Strings.settings_user_templates_build_system_unknown
}

internal fun buildUserProjectTemplateBuildSystemOptions(): List<ProjectSettingsOptionSpec<String>> = listOf(
    ProjectSettingsOptionSpec(USER_TEMPLATE_METADATA_AUTO_VALUE, Strings.settings_user_templates_metadata_auto_detect),
    ProjectSettingsOptionSpec(ProjectBuildSystem.SINGLE_FILE.name, Strings.settings_user_templates_build_system_single_file),
    ProjectSettingsOptionSpec(ProjectBuildSystem.CMAKE.name, Strings.tag_cmake),
    ProjectSettingsOptionSpec(ProjectBuildSystem.MAKE.name, Strings.tag_makefile),
    ProjectSettingsOptionSpec(ProjectBuildSystem.PLUGIN.name, Strings.tag_plugin),
)

internal fun resolveUserProjectTemplateBuildSystemValue(value: String): ProjectBuildSystem? = if (value == USER_TEMPLATE_METADATA_AUTO_VALUE) {
    null
} else {
    ProjectBuildSystem.entries.firstOrNull { it.name == value }
}

internal fun buildUserProjectTemplateLanguageOptions(): List<ProjectSettingsOptionSpec<String>> = listOf(
    ProjectSettingsOptionSpec(USER_TEMPLATE_METADATA_AUTO_VALUE, Strings.settings_user_templates_metadata_auto_detect),
    ProjectSettingsOptionSpec(ProjectLanguage.C.name, Strings.settings_user_templates_language_c),
    ProjectSettingsOptionSpec(ProjectLanguage.CPP.name, Strings.settings_user_templates_language_cpp),
    ProjectSettingsOptionSpec(ProjectLanguage.JAVA.name, Strings.tag_java),
    ProjectSettingsOptionSpec(ProjectLanguage.KOTLIN.name, Strings.tag_kotlin),
    ProjectSettingsOptionSpec(ProjectLanguage.PYTHON.name, Strings.tag_python),
    ProjectSettingsOptionSpec(ProjectLanguage.RUST.name, Strings.tag_rust),
    ProjectSettingsOptionSpec(ProjectLanguage.GO.name, Strings.tag_go),
    ProjectSettingsOptionSpec(ProjectLanguage.JAVASCRIPT.name, Strings.tag_javascript),
    ProjectSettingsOptionSpec(ProjectLanguage.TYPESCRIPT.name, Strings.tag_typescript),
    ProjectSettingsOptionSpec(ProjectLanguage.SHELL.name, Strings.tag_shell),
    ProjectSettingsOptionSpec(ProjectLanguage.MIXED.name, Strings.settings_user_templates_language_mixed),
)

internal fun resolveUserProjectTemplateLanguageValue(value: String): ProjectLanguage? = if (value == USER_TEMPLATE_METADATA_AUTO_VALUE) {
    null
} else {
    ProjectLanguage.entries.firstOrNull { it.name == value }
}

internal fun resolveUserProjectTemplateLanguageLabelRes(language: ProjectLanguage): Int = when (language) {
    ProjectLanguage.C -> Strings.settings_user_templates_language_c
    ProjectLanguage.CPP -> Strings.settings_user_templates_language_cpp
    ProjectLanguage.JAVA -> Strings.tag_java
    ProjectLanguage.KOTLIN -> Strings.tag_kotlin
    ProjectLanguage.PYTHON -> Strings.tag_python
    ProjectLanguage.RUST -> Strings.tag_rust
    ProjectLanguage.GO -> Strings.tag_go
    ProjectLanguage.JAVASCRIPT -> Strings.tag_javascript
    ProjectLanguage.TYPESCRIPT -> Strings.tag_typescript
    ProjectLanguage.SHELL -> Strings.tag_shell
    ProjectLanguage.MIXED -> Strings.settings_user_templates_language_mixed
    ProjectLanguage.UNKNOWN -> Strings.settings_user_templates_build_system_unknown
}

@Composable
internal fun UserProjectTemplateMetadataEditorDialog(
    templateName: String,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    author: String,
    onAuthorChange: (String) -> Unit,
    variableDefaults: String,
    onVariableDefaultsChange: (String) -> Unit,
    variableDefaultsErrorRes: Int?,
    buildSystemLabel: String,
    onSelectBuildSystem: () -> Unit,
    languageLabel: String,
    onSelectLanguage: () -> Unit,
    isNdkTemplate: Boolean,
    onNdkTemplateChange: (Boolean) -> Unit,
    metadataPreview: String,
    canSave: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.settings_user_templates_edit_title)) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TinaDialogCard {
                    Text(
                        text = stringResource(Strings.settings_user_templates_edit_file, templateName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(Strings.settings_user_templates_edit_name_label)) },
                    placeholder = { Text(stringResource(Strings.settings_user_templates_edit_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(Strings.settings_user_templates_edit_description_label)) },
                    placeholder = { Text(stringResource(Strings.settings_user_templates_edit_description_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    singleLine = false
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = onAuthorChange,
                    label = { Text(stringResource(Strings.settings_user_templates_edit_author_label)) },
                    placeholder = { Text(stringResource(Strings.settings_user_templates_edit_author_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = variableDefaults,
                    onValueChange = onVariableDefaultsChange,
                    label = { Text(stringResource(Strings.settings_user_templates_variables_label)) },
                    placeholder = { Text(stringResource(Strings.settings_user_templates_variables_placeholder)) },
                    supportingText = {
                        Text(
                            variableDefaultsErrorRes?.let { stringResource(it) }
                                ?: stringResource(Strings.settings_user_templates_variables_desc)
                        )
                    },
                    isError = variableDefaultsErrorRes != null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    singleLine = false
                )
                TinaDialogCard {
                    TinaTextButton(
                        text = stringResource(
                            Strings.settings_user_templates_edit_build_system_value,
                            buildSystemLabel
                        ),
                        onClick = onSelectBuildSystem,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TinaTextButton(
                        text = stringResource(
                            Strings.settings_user_templates_edit_primary_language_value,
                            languageLabel
                        ),
                        onClick = onSelectLanguage,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isNdkTemplate,
                            onCheckedChange = onNdkTemplateChange
                        )
                        Text(
                            text = stringResource(Strings.settings_user_templates_edit_ndk_template),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                TinaDialogCard {
                    Text(
                        text = stringResource(Strings.settings_user_templates_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = metadataPreview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_save),
                onClick = onConfirm,
                enabled = canSave
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
internal fun NativeDependencyPathEditorDialog(
    title: String,
    initialPaths: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    hintText: String,
    placeholderText: String
) {
    var inputText by remember(title, initialPaths) {
        mutableStateOf(initialPaths.joinToString(separator = "\n"))
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(title)
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 320.dp),
                    singleLine = false,
                    minLines = 6,
                    maxLines = 12,
                    placeholder = {
                        Text(placeholderText)
                    }
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_confirm),
                onClick = {
                    onConfirm(ProjectSettingsSectionSupport.parsePathLines(inputText))
                }
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
internal fun NativeBuildFlagEditorDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember(title, initialValue) { mutableStateOf(initialValue) }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(title)
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = stringResource(Strings.settings_project_native_flags_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 280.dp),
                    singleLine = false,
                    minLines = 4,
                    maxLines = 10,
                    placeholder = {
                        Text(stringResource(Strings.settings_project_native_flags_edit_placeholder))
                    }
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_confirm),
                onClick = { onConfirm(inputText) }
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

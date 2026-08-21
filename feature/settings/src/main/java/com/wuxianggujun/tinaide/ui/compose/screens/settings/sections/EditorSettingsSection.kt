package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.CUSTOM_EDITOR_THEME_ID
import com.wuxianggujun.tinaide.core.font.AppFontManager
import com.wuxianggujun.tinaide.core.i18n.Arrays
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaActionChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSliderDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaValidatedInputDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import java.io.File
import java.util.Locale
import timber.log.Timber

/**
 * 编辑器设置页面
 *
 * 仅包含纯编辑器相关的设置：
 * - 字体设置
 * - 显示设置
 * - 编辑行为
 * - 性能设置
 *
 * LSP 相关设置已移至 LspSettingsSection
 * 格式化设置已移至 CompilerSettingsSection
 */
@Composable
internal fun EditorSettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val toastTabWidthSetTemplate = stringResource(Strings.toast_tab_width_set)
    val toastFontRestored = stringResource(Strings.toast_font_restored)
    val errorInvalidFontFile = stringResource(Strings.error_invalid_font_file)
    val toastFontSetTemplate = stringResource(Strings.toast_font_set)
    val errorFontSetFailedTemplate = stringResource(Strings.error_font_set_failed)
    val state by viewModel.uiState.collectAsState()

    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showRainbowBracketsLimitDialog by remember { mutableStateOf(false) }
    var showEditorThemeDialog by remember { mutableStateOf(false) }
    var showEditorThemeCustomizer by remember { mutableStateOf(false) }
    var showRenderWhitespaceDialog by remember { mutableStateOf(false) }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFontSelected(
                    context,
                    uri,
                    errorInvalidFontFile,
                    toastFontSetTemplate,
                    errorFontSetFailedTemplate,
                    onSuccess = { path -> viewModel.setEditorFontPath(path) }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ==================== 字体设置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_font))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_font_size),
            value = "${state.editorFontSize.toInt()} sp",
            onClick = { showFontSizeDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_custom_font),
            value = EditorSettingsSectionSupport.resolveCustomFontDisplayName(
                fontPath = state.editorFontPath,
                defaultLabel = stringResource(Strings.value_default)
            ),
            onClick = { showFontDialog = true },
            showDivider = false
        )
    }

    // ==================== 显示设置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_display))

    val editorThemeEntries = stringArrayResource(Arrays.editor_theme_entries)
    val editorThemeValues = stringArrayResource(Arrays.editor_theme_values)
    val editorThemeDisplayName = EditorSettingsSectionSupport.resolveEditorThemeDisplayName(
        currentTheme = state.editorTheme,
        themeEntries = editorThemeEntries.toList(),
        themeValues = editorThemeValues.toList(),
        pluginThemesLabel = stringResource(Strings.theme_preview_test_plugin_themes),
        customThemeLabel = stringResource(Strings.settings_editor_theme_custom)
    )

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_editor_theme),
            subtitle = stringResource(Strings.settings_editor_theme_summary),
            value = editorThemeDisplayName,
            onClick = { showEditorThemeDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_editor_theme_customize),
            subtitle = stringResource(Strings.settings_editor_theme_customize_summary),
            value = if (state.editorTheme == CUSTOM_EDITOR_THEME_ID) {
                stringResource(Strings.settings_editor_theme_custom_active)
            } else {
                null
            },
            onClick = { showEditorThemeCustomizer = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_show_line_numbers),
            subtitle = stringResource(Strings.settings_show_line_numbers_desc),
            checked = state.editorShowLineNumbers,
            onCheckedChange = { viewModel.setEditorShowLineNumbers(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_word_wrap),
            subtitle = stringResource(Strings.settings_word_wrap_desc),
            checked = state.editorWordWrap,
            onCheckedChange = { viewModel.setEditorWordWrap(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_rainbow_brackets),
            subtitle = stringResource(Strings.settings_rainbow_brackets_desc),
            checked = state.editorRainbowBrackets,
            onCheckedChange = { viewModel.setEditorRainbowBrackets(it) },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_rainbow_brackets_max_lines),
            value = if (state.editorRainbowBracketsMaxLines == 0) {
                stringResource(Strings.value_unlimited)
            } else {
                stringResource(Strings.value_lines, state.editorRainbowBracketsMaxLines)
            },
            onClick = { showRainbowBracketsLimitDialog = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_code_folding),
            subtitle = stringResource(Strings.settings_code_folding_desc),
            checked = state.editorCodeFolding,
            onCheckedChange = { viewModel.setEditorCodeFolding(it) },
            showDivider = true
        )

        val whitespaceDisplayName = stringResource(
            EditorSettingsSectionSupport.resolveRenderWhitespaceLabel(
                state.editorRenderWhitespace
            )
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_render_whitespace),
            subtitle = stringResource(Strings.settings_render_whitespace_desc),
            value = whitespaceDisplayName,
            onClick = { showRenderWhitespaceDialog = true },
            showDivider = false
        )
    }

    // ==================== 编辑行为 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_editor_behavior))

    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.settings_auto_indent),
            subtitle = stringResource(Strings.settings_auto_indent_desc),
            checked = state.editorAutoIndent,
            onCheckedChange = { viewModel.setEditorAutoIndent(it) },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_insert_spaces_for_tabs),
            subtitle = stringResource(Strings.settings_insert_spaces_for_tabs_desc),
            checked = state.editorInsertSpacesForTabs,
            onCheckedChange = { viewModel.setEditorInsertSpacesForTabs(it) },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_tab_width),
            value = stringResource(Strings.tab_width_spaces, state.editorTabSize),
            onClick = {
                val newSize = EditorSettingsSectionSupport.resolveNextTabSize(
                    state.editorTabSize
                )
                viewModel.setEditorTabSize(newSize)
                Toast.makeText(
                    context,
                    String.format(Locale.getDefault(), toastTabWidthSetTemplate, newSize),
                    Toast.LENGTH_SHORT
                ).show()
            },
            showDivider = false
        )
    }

    // ==================== 性能设置 ====================
    SettingsCategoryTitle(stringResource(Strings.settings_cat_editor_performance))

    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.settings_editor_hardware_acceleration),
            subtitle = stringResource(Strings.settings_editor_hardware_acceleration_summary),
            checked = state.editorHardwareAcceleration,
            onCheckedChange = { viewModel.setEditorHardwareAcceleration(it) },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ==================== 对话框 ====================

    if (showEditorThemeDialog) {
        val options = EditorSettingsSectionSupport.buildEditorThemeOptions(
            themeEntries = editorThemeEntries.toList(),
            themeValues = editorThemeValues.toList(),
            customThemeLabel = stringResource(Strings.settings_editor_theme_custom)
        )

        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_editor_theme),
            options = options,
            selectedValue = state.editorTheme,
            onSelected = { value ->
                viewModel.setEditorTheme(value)
                showEditorThemeDialog = false
            },
            onDismiss = { showEditorThemeDialog = false }
        )
    }

    if (showEditorThemeCustomizer) {
        EditorThemeCustomizerDialog(
            initialTheme = state.customEditorTheme,
            onApply = { theme ->
                viewModel.setCustomEditorTheme(theme)
                showEditorThemeCustomizer = false
            },
            onDismiss = { showEditorThemeCustomizer = false }
        )
    }

    if (showFontSizeDialog) {
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_font_size),
            value = state.editorFontSize,
            valueRange = AppFontManager.MIN_FONT_SIZE..AppFontManager.MAX_FONT_SIZE,
            steps = (AppFontManager.MAX_FONT_SIZE - AppFontManager.MIN_FONT_SIZE).toInt() - 1,
            valueLabel = { "${it.toInt()} sp" },
            onValueSelected = { size ->
                viewModel.setEditorFontSize(size)
                showFontSizeDialog = false
            },
            onDismiss = { showFontSizeDialog = false }
        )
    }

    if (showFontDialog) {
        TinaActionChoiceDialog(
            title = stringResource(Strings.dialog_title_custom_font),
            message = stringResource(Strings.label_select_font_source),
            actions = listOf(
                stringResource(Strings.btn_use_default_font) to {
                    viewModel.setEditorFontPath("")
                    showFontDialog = false
                    Toast.makeText(context, toastFontRestored, Toast.LENGTH_SHORT).show()
                },
                stringResource(Strings.btn_select_font_file) to {
                    showFontDialog = false
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "font/otf", "application/x-font-ttf"))
                    }
                    fontPickerLauncher.launch(intent)
                }
            ),
            onDismiss = { showFontDialog = false }
        )
    }

    if (showRainbowBracketsLimitDialog) {
        TinaValidatedInputDialog(
            title = stringResource(Strings.settings_rainbow_brackets_max_lines),
            label = stringResource(Strings.settings_rainbow_brackets_max_lines),
            placeholder = "0 - 200000",
            initialValue = state.editorRainbowBracketsMaxLines.toString(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validator = { input ->
                if (EditorSettingsSectionSupport.validateRainbowBracketsMaxLines(input)) {
                    null
                } else {
                    stringResource(Strings.editor_lsp_error_number_range, 0, 200000)
                }
            },
            hint = { value ->
                val parsed = EditorSettingsSectionSupport.resolveRainbowBracketsHintValue(value)
                if (parsed == 0) {
                    stringResource(Strings.value_unlimited)
                } else {
                    stringResource(Strings.value_lines, parsed)
                }
            },
            onConfirm = { textValue ->
                val parsed = EditorSettingsSectionSupport.coerceRainbowBracketsMaxLines(
                    input = textValue,
                    fallback = state.editorRainbowBracketsMaxLines
                )
                viewModel.setEditorRainbowBracketsMaxLines(parsed)
                showRainbowBracketsLimitDialog = false
            },
            onDismiss = { showRainbowBracketsLimitDialog = false }
        )
    }
    if (showRenderWhitespaceDialog) {
        val wsOptions = EditorSettingsSectionSupport.buildRenderWhitespaceOptions().map { option ->
            option.value to stringResource(option.labelRes)
        }

        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_render_whitespace),
            options = wsOptions,
            selectedValue = state.editorRenderWhitespace,
            onSelected = { value ->
                viewModel.setEditorRenderWhitespace(value)
                showRenderWhitespaceDialog = false
            },
            onDismiss = { showRenderWhitespaceDialog = false }
        )
    }
}

// ==================== 辅助函数 ====================

private fun handleFontSelected(
    context: android.content.Context,
    uri: Uri,
    errorInvalidFontFile: String,
    toastFontSetTemplate: String,
    errorFontSetFailedTemplate: String,
    onSuccess: (String) -> Unit
) {
    try {
        val fontsDir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
            com.wuxianggujun.tinaide.storage.ProjectPaths.getEditorFontsRoot(context)
        )
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_font.ttf"
        val destFile = File(fontsDir, fileName)

        // 先复制文件
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        // 验证字体文件是否有效
        if (!AppFontManager.isValidFontFile(destFile.absolutePath)) {
            // 删除无效的字体文件
            destFile.delete()
            Toast.makeText(context, errorInvalidFontFile, Toast.LENGTH_LONG).show()
            return
        }

        onSuccess(destFile.absolutePath)
        Toast.makeText(
            context,
            String.format(Locale.getDefault(), toastFontSetTemplate, destFile.name),
            Toast.LENGTH_SHORT
        ).show()
    } catch (e: Exception) {
        Timber.tag("EditorSettings").e(e, "Failed to set custom font")
        Toast.makeText(
            context,
            String.format(Locale.getDefault(), errorFontSetFailedTemplate, e.message ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }
}

package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wuxianggujun.tinaide.core.config.CustomEditorTheme
import com.wuxianggujun.tinaide.core.config.EditorThemeBase
import com.wuxianggujun.tinaide.core.config.EditorThemeColorChannels
import com.wuxianggujun.tinaide.core.config.EditorThemeColorGroup
import com.wuxianggujun.tinaide.core.config.EditorThemeColorKey
import com.wuxianggujun.tinaide.core.config.normalizeEditorThemeColor
import com.wuxianggujun.tinaide.core.config.parseEditorThemeColorChannels
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField

@Composable
internal fun EditorThemeCustomizerDialog(
    initialTheme: CustomEditorTheme,
    onApply: (CustomEditorTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var draftTheme by remember(initialTheme) { mutableStateOf(initialTheme.sanitized()) }
    var selectedGroupIndex by remember { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<EditorThemeColorKey?>(null) }

    val groups = listOf(
        EditorThemeColorGroup.EDITOR,
        EditorThemeColorGroup.SYNTAX,
        EditorThemeColorGroup.DIAGNOSTICS
    )
    val fallback = remember(draftTheme.base) { builtinScheme(draftTheme.base) }
    val resolvedScheme = remember(draftTheme) {
        EditorColorScheme.fromThemeColors(draftTheme.colors, fallback)
    }
    val resolvedColors = remember(resolvedScheme) { resolvedScheme.toThemeColors() }

    TinaCustomDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f)
            .widthIn(max = 720.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Strings.settings_editor_theme_customize_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Strings.btn_cancel)
                )
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(Strings.settings_editor_theme_base),
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorThemeBase.entries.forEach { base ->
                    FilterChip(
                        selected = draftTheme.base == base,
                        onClick = { draftTheme = draftTheme.copy(base = base) },
                        label = { Text(stringResource(base.labelRes())) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            EditorThemePreview(resolvedScheme)
        }

        SecondaryTabRow(
            selectedTabIndex = selectedGroupIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            groups.forEachIndexed { index, group ->
                Tab(
                    selected = selectedGroupIndex == index,
                    onClick = { selectedGroupIndex = index },
                    text = { Text(stringResource(group.labelRes())) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = EditorThemeColorKey.entries.filter { it.group == groups[selectedGroupIndex] },
                key = EditorThemeColorKey::wireName
            ) { key ->
                val colorValue = resolvedColors.getValue(key.wireName)
                EditorThemeColorRow(
                    key = key,
                    colorValue = colorValue,
                    overridden = draftTheme.colors.containsKey(key.wireName),
                    onClick = { editingKey = key },
                    onReset = { draftTheme = draftTheme.withColor(key, null) }
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TinaTextButton(
                text = stringResource(Strings.btn_restore_default),
                onClick = { draftTheme = CustomEditorTheme(base = draftTheme.base) }
            )
            Spacer(modifier = Modifier.weight(1f))
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
            TinaPrimaryButton(
                text = stringResource(Strings.btn_apply),
                onClick = { onApply(draftTheme.sanitized()) }
            )
        }
    }

    editingKey?.let { key ->
        EditorThemeColorValueDialog(
            label = stringResource(key.labelRes()),
            initialColor = resolvedColors.getValue(key.wireName),
            canReset = draftTheme.colors.containsKey(key.wireName),
            onApply = { color ->
                draftTheme = draftTheme.withColor(key, color)
                editingKey = null
            },
            onReset = {
                draftTheme = draftTheme.withColor(key, null)
                editingKey = null
            },
            onDismiss = { editingKey = null }
        )
    }
}

@Composable
private fun EditorThemePreview(scheme: EditorColorScheme) {
    val previewType = stringResource(Strings.settings_editor_theme_preview_type)
    val previewFunction = stringResource(Strings.settings_editor_theme_preview_function)
    val previewOpen = stringResource(Strings.settings_editor_theme_preview_open)
    val previewComment = stringResource(Strings.settings_editor_theme_preview_comment)
    val previewKeyword = stringResource(Strings.settings_editor_theme_preview_keyword)
    val previewNumber = stringResource(Strings.settings_editor_theme_preview_number)
    val previewStatementEnd = stringResource(Strings.settings_editor_theme_preview_statement_end)
    val previewClose = stringResource(Strings.settings_editor_theme_preview_close)
    val preview = buildAnnotatedString {
        withStyle(SpanStyle(color = scheme.syntax.type)) {
            append(previewType)
        }
        append(" ")
        withStyle(SpanStyle(color = scheme.syntax.function)) {
            append(previewFunction)
        }
        withStyle(SpanStyle(color = scheme.syntax.punctuation)) {
            append(previewOpen)
        }
        append("\n    ")
        withStyle(SpanStyle(color = scheme.syntax.comment)) {
            append(previewComment)
        }
        append("\n    ")
        withStyle(SpanStyle(color = scheme.syntax.keyword)) {
            append(previewKeyword)
        }
        append(" ")
        withStyle(SpanStyle(color = scheme.syntax.number)) {
            append(previewNumber)
        }
        withStyle(SpanStyle(color = scheme.syntax.punctuation)) {
            append(previewStatementEnd)
        }
        append("\n")
        withStyle(SpanStyle(color = scheme.syntax.punctuation)) {
            append(previewClose)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.gutterDivider, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = scheme.background
    ) {
        Row(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = "1\n2\n3\n4",
                modifier = Modifier
                    .background(scheme.gutterBackground)
                    .padding(horizontal = 10.dp),
                color = scheme.lineNumberForeground,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = preview,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = scheme.foreground,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EditorThemeColorRow(
    key: EditorThemeColorKey,
    colorValue: String,
    overridden: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    val channels = parseEditorThemeColorChannels(colorValue) ?: EditorThemeColorChannels(255, 0, 0, 0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(channels.toComposeColor(), RoundedCornerShape(4.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(key.labelRes()),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = colorValue,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        if (overridden) {
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = stringResource(Strings.btn_restore_default)
                )
            }
        }
    }
}

@Composable
private fun EditorThemeColorValueDialog(
    label: String,
    initialColor: String,
    canReset: Boolean,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialChannels = remember(initialColor) {
        parseEditorThemeColorChannels(initialColor) ?: EditorThemeColorChannels(255, 0, 0, 0)
    }
    var channels by remember(initialColor) { mutableStateOf(initialChannels) }
    var hexValue by remember(initialColor) { mutableStateOf(initialChannels.toHexColor()) }
    val normalizedHex = normalizeEditorThemeColor(hexValue)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(channels.toComposeColor(), RoundedCornerShape(6.dp))
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(6.dp)
                        )
                )
                TinaTextField(
                    value = hexValue,
                    onValueChange = { value ->
                        val nextValue = value.take(10)
                        hexValue = nextValue
                        parseEditorThemeColorChannels(nextValue)?.let { channels = it }
                    },
                    label = stringResource(Strings.settings_editor_theme_hex_color),
                    hint = stringResource(Strings.settings_editor_theme_hex_hint),
                    isError = hexValue.isNotEmpty() && normalizedHex == null,
                    errorText = if (hexValue.isNotEmpty() && normalizedHex == null) {
                        stringResource(Strings.settings_editor_theme_hex_invalid)
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                ColorChannelSlider("R", channels.red) { value ->
                    channels = channels.copy(red = value)
                    hexValue = channels.toHexColor()
                }
                ColorChannelSlider("G", channels.green) { value ->
                    channels = channels.copy(green = value)
                    hexValue = channels.toHexColor()
                }
                ColorChannelSlider("B", channels.blue) { value ->
                    channels = channels.copy(blue = value)
                    hexValue = channels.toHexColor()
                }
                ColorChannelSlider("A", channels.alpha) { value ->
                    channels = channels.copy(alpha = value)
                    hexValue = channels.toHexColor()
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_apply),
                enabled = normalizedHex != null,
                onClick = { normalizedHex?.let(onApply) }
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canReset) {
                    TinaTextButton(
                        text = stringResource(Strings.btn_restore_default),
                        onClick = onReset
                    )
                }
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = onDismiss
                )
            }
        }
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(20.dp),
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
            modifier = Modifier.weight(1f),
            valueRange = 0f..255f,
            steps = 254
        )
        Text(
            text = value.toString(),
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun builtinScheme(base: EditorThemeBase): EditorColorScheme = when (base) {
    EditorThemeBase.GRAY -> EditorColorScheme.builtinGray()
    EditorThemeBase.DARK -> EditorColorScheme.builtinDark()
    EditorThemeBase.LIGHT -> EditorColorScheme.builtinLight()
}

private fun EditorThemeColorChannels.toComposeColor(): Color = Color(
    red = red,
    green = green,
    blue = blue,
    alpha = alpha
)

@StringRes
private fun EditorThemeBase.labelRes(): Int = when (this) {
    EditorThemeBase.GRAY -> Strings.settings_editor_theme_base_gray
    EditorThemeBase.DARK -> Strings.settings_editor_theme_base_dark
    EditorThemeBase.LIGHT -> Strings.settings_editor_theme_base_light
}

@StringRes
private fun EditorThemeColorGroup.labelRes(): Int = when (this) {
    EditorThemeColorGroup.EDITOR -> Strings.settings_editor_theme_group_editor
    EditorThemeColorGroup.SYNTAX -> Strings.settings_editor_theme_group_syntax
    EditorThemeColorGroup.DIAGNOSTICS -> Strings.settings_editor_theme_group_diagnostics
}

@StringRes
private fun EditorThemeColorKey.labelRes(): Int = when (this) {
    EditorThemeColorKey.EDITOR_BACKGROUND -> Strings.settings_editor_color_background
    EditorThemeColorKey.EDITOR_FOREGROUND -> Strings.settings_editor_color_foreground
    EditorThemeColorKey.EDITOR_SELECTION -> Strings.settings_editor_color_selection
    EditorThemeColorKey.EDITOR_CURSOR_LINE -> Strings.settings_editor_color_cursor_line
    EditorThemeColorKey.EDITOR_CURSOR -> Strings.settings_editor_color_cursor
    EditorThemeColorKey.EDITOR_LINE_NUMBER -> Strings.settings_editor_color_line_number
    EditorThemeColorKey.EDITOR_LINE_NUMBER_ACTIVE -> Strings.settings_editor_color_line_number_active
    EditorThemeColorKey.GUTTER_BACKGROUND -> Strings.settings_editor_color_gutter_background
    EditorThemeColorKey.GUTTER_DIVIDER -> Strings.settings_editor_color_gutter_divider
    EditorThemeColorKey.EDITOR_WHITESPACE -> Strings.settings_editor_color_whitespace
    EditorThemeColorKey.BRACKET_PAIR_GUIDE -> Strings.settings_editor_color_bracket_guide
    EditorThemeColorKey.BRACKET_PAIR_GUIDE_ACTIVE -> Strings.settings_editor_color_bracket_guide_active
    EditorThemeColorKey.RAINBOW_BRACKET_1 -> Strings.settings_editor_color_rainbow_1
    EditorThemeColorKey.RAINBOW_BRACKET_2 -> Strings.settings_editor_color_rainbow_2
    EditorThemeColorKey.RAINBOW_BRACKET_3 -> Strings.settings_editor_color_rainbow_3
    EditorThemeColorKey.RAINBOW_BRACKET_4 -> Strings.settings_editor_color_rainbow_4
    EditorThemeColorKey.RAINBOW_BRACKET_5 -> Strings.settings_editor_color_rainbow_5
    EditorThemeColorKey.RAINBOW_BRACKET_6 -> Strings.settings_editor_color_rainbow_6
    EditorThemeColorKey.SYNTAX_KEYWORD -> Strings.settings_editor_color_keyword
    EditorThemeColorKey.SYNTAX_FUNCTION -> Strings.settings_editor_color_function
    EditorThemeColorKey.SYNTAX_VARIABLE -> Strings.settings_editor_color_variable
    EditorThemeColorKey.SYNTAX_PROPERTY -> Strings.settings_editor_color_property
    EditorThemeColorKey.SYNTAX_TYPE -> Strings.settings_editor_color_type
    EditorThemeColorKey.SYNTAX_STRING -> Strings.settings_editor_color_string
    EditorThemeColorKey.SYNTAX_NUMBER -> Strings.settings_editor_color_number
    EditorThemeColorKey.SYNTAX_COMMENT -> Strings.settings_editor_color_comment
    EditorThemeColorKey.SYNTAX_OPERATOR -> Strings.settings_editor_color_operator
    EditorThemeColorKey.SYNTAX_PUNCTUATION -> Strings.settings_editor_color_punctuation
    EditorThemeColorKey.SYNTAX_CONSTANT -> Strings.settings_editor_color_constant
    EditorThemeColorKey.SYNTAX_BUILTIN -> Strings.settings_editor_color_builtin
    EditorThemeColorKey.SYNTAX_DEPRECATED -> Strings.settings_editor_color_deprecated
    EditorThemeColorKey.DIAGNOSTIC_ERROR -> Strings.settings_editor_color_diagnostic_error
    EditorThemeColorKey.DIAGNOSTIC_WARNING -> Strings.settings_editor_color_diagnostic_warning
    EditorThemeColorKey.DIAGNOSTIC_INFO -> Strings.settings_editor_color_diagnostic_info
    EditorThemeColorKey.DIAGNOSTIC_HINT -> Strings.settings_editor_color_diagnostic_hint
}

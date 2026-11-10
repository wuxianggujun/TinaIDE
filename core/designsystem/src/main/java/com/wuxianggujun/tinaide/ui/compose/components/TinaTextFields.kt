package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * TinaIDE 输入框组件库
 */

/**
 * 统一的输入框组件
 *
 * 支持静态提示和动态提示两种模式：
 * - 静态提示：使用 `hint` 参数传入固定字符串
 * - 动态提示：使用 `dynamicHint` 参数传入 @Composable 函数，可根据当前输入值动态生成提示
 *
 * 优先级（从高到低）：errorText > supportingText > dynamicHint > hint
 *
 * @param value 输入值
 * @param onValueChange 值变化回调
 * @param modifier 修饰符
 * @param label 标签文本
 * @param placeholder 占位符文本
 * @param hint 静态提示文本（在没有错误时显示）
 * @param dynamicHint 动态提示函数，根据当前输入值返回提示文本，返回 null 则不显示（优先级高于 hint）
 * @param supportingText 支持文本 @Composable 函数（优先级高于 hint 和 dynamicHint）
 * @param isError 是否显示错误状态
 * @param errorText 错误文本（优先级最高）
 * @param singleLine 是否单行
 * @param maxLines 最大行数（仅在 singleLine = false 时生效）
 * @param minLines 最小行数（仅在 singleLine = false 时生效）
 * @param readOnly 是否只读
 * @param enabled 是否启用
 * @param visualTransformation 视觉转换（如密码）
 * @param keyboardOptions 键盘选项
 * @param keyboardActions 键盘动作
 * @param leadingIcon 前置图标
 * @param trailingIcon 后置图标
 * @param textStyle 文本样式
 */
@Composable
fun TinaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    dynamicHint: @Composable ((String) -> String?)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current
) {
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    // Preserve selection/composition on ordinary edits; external changes only clamp
    // the existing caret. The value overload below is used by variable completion.
    val displayed = if (input.text == value) input else input.copy(
        text = value,
        selection = TextRange(input.selection.start.coerceAtMost(value.length), input.selection.end.coerceAtMost(value.length)),
        composition = null,
    )
    SideEffect { input = displayed }
    TinaTextField(
        value = displayed,
        onValueChange = { updated ->
            input = updated
            if (updated.text != value) onValueChange(updated.text)
        },
        modifier = modifier, label = label, placeholder = placeholder, hint = hint,
        dynamicHint = dynamicHint, supportingText = supportingText, isError = isError,
        errorText = errorText, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        readOnly = readOnly, enabled = enabled, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, textStyle = textStyle,
    )
}

@Composable
fun TinaTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    dynamicHint: @Composable ((String) -> String?)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
) {
    // 在 Composable 上下文中计算动态提示
    val dynamicHintText = if (!isError) dynamicHint?.invoke(value.text) else null
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    val border = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outlineVariant
    }
    val shape = RoundedCornerShape(TinaShapes.TextFieldCorner)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics {
            if (label != null) contentDescription = label
            if (isError && errorText != null) error(errorText)
        },
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { innerTextField ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .background(colors.surfaceContainerLow, shape)
                        .border(1.dp, border, shape).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides foreground) {
                        leadingIcon?.invoke()
                        Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            if (value.text.isEmpty() && placeholder != null) Text(
                                placeholder, style = textStyle, color = colors.onSurfaceVariant,
                                maxLines = if (singleLine) 1 else maxLines,
                            )
                            innerTextField()
                        }
                        trailingIcon?.invoke()
                    }
                }
                val support: (@Composable () -> Unit)? = when {
            isError && errorText != null -> {
                { Text(errorText, color = MaterialTheme.colorScheme.error) }
            }
            supportingText != null -> supportingText
            dynamicHintText != null -> {
                {
                    Text(
                        text = dynamicHintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            hint != null -> {
                {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
                }
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) { support?.invoke() }
                }
            }
        },
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        readOnly = readOnly,
        enabled = enabled,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = textStyle.copy(color = foreground),
    )
}

/**
 * 搜索输入框
 *
 * @param value 输入值
 * @param onValueChange 值变化回调
 * @param modifier 修饰符
 * @param placeholder 占位符文本
 * @param leadingIcon 前置图标
 * @param trailingIcon 后置图标
 * @param onClearClick 清除按钮点击回调（当有值时自动显示清除按钮）
 * @param enabled 是否启用
 */
@Composable
fun TinaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(Strings.tina_search_placeholder),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onClearClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    TinaTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        singleLine = true,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = if (value.isNotEmpty() && onClearClick != null) {
            {
                IconButton(onClick = onClearClick) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(Strings.content_desc_clear_input)
                    )
                }
            }
        } else {
            trailingIcon
        },
    )
}

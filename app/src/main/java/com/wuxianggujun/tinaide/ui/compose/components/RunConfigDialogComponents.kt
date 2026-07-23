package com.wuxianggujun.tinaide.ui.compose.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildType
import com.wuxianggujun.tinaide.core.compile.BuildVariables
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.OutputMode
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.compile.SdlOrientation
import com.wuxianggujun.tinaide.core.compile.SourceFileMode
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.ndk.displayLabel
import com.wuxianggujun.tinaide.core.ndk.displayName
import com.wuxianggujun.tinaide.core.ndk.displayVersionLabel
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.getDisplayName
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter
import java.io.File

/**
 * Shared RunConfig dialog widgets, selector, and run menu.
 */

@Composable
internal fun RunConfigActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minSize: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minSize, minHeight = minSize),
        enabled = enabled,
        minHeight = minSize,
        color = color,
        contentPadding = contentPadding,
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
internal fun RunConfigSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentModifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    TinaDialogCard(
        modifier = modifier,
        contentModifier = contentModifier,
        contentPadding = contentPadding,
        color = color,
        verticalArrangement = verticalArrangement
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
internal fun RunConfigInfoCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    RunConfigSectionCard(
        title = title,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun RunConfigOptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RunConfigSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 带变量补全功能的文本输入框
 *
 * 当用户输入 $ 时，会显示变量补全列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    onShowHelp: () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1
) {
    var showSuggestions by remember { mutableStateOf(false) }
    var cursorPosition by remember { mutableIntStateOf(0) }

    // 检查是否应该显示变量建议
    // 当光标前面有 $ 且后面没有完整的变量时显示
    val shouldShowSuggestions = remember(value, cursorPosition) {
        if (value.isEmpty()) return@remember false

        // 查找光标前最近的 $
        val beforeCursor = value.take(cursorPosition.coerceAtMost(value.length))
        val lastDollar = beforeCursor.lastIndexOf('$')

        if (lastDollar == -1) return@remember false

        // 检查 $ 后面是否已经有完整的变量（以 $ 结尾）
        val afterDollar = value.substring(lastDollar)
        val nextDollar = afterDollar.indexOf('$', 1)

        // 如果 $ 后面没有另一个 $，或者光标在两个 $ 之间，显示建议
        nextDollar == -1 || (lastDollar + nextDollar + 1) > cursorPosition
    }

    // 获取当前输入的变量前缀（用于过滤）
    val variablePrefix = remember(value, cursorPosition) {
        if (value.isEmpty()) return@remember ""

        val beforeCursor = value.take(cursorPosition.coerceAtMost(value.length))
        val lastDollar = beforeCursor.lastIndexOf('$')

        if (lastDollar == -1) return@remember ""

        beforeCursor.substring(lastDollar)
    }

    // 过滤匹配的变量
    val filteredVariables = remember(variablePrefix) {
        if (variablePrefix.isEmpty()) {
            BuildVariables.ALL_VARIABLES
        } else {
            BuildVariables.ALL_VARIABLES.filter {
                it.name.startsWith(variablePrefix, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                // 简单估算光标位置（实际上 Compose 的 TextField 不直接暴露光标位置）
                cursorPosition = newValue.length
                showSuggestions = newValue.contains("$") && !newValue.endsWith("$")
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = {
                Text(stringResource(Strings.run_config_variable_hint))
            },
            trailingIcon = {
                RunConfigActionButton(
                    onClick = onShowHelp,
                    minSize = 28.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Help,
                        contentDescription = stringResource(Strings.run_config_variable_help),
                        modifier = Modifier.size(16.dp)
                    )
                }
            },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            modifier = Modifier.fillMaxWidth()
        )

        // 变量补全下拉菜单
        TinaDropdownMenu(
            expanded = showSuggestions && shouldShowSuggestions && filteredVariables.isNotEmpty(),
            onDismissRequest = { showSuggestions = false },
            modifier = Modifier
                .heightIn(max = 200.dp)
                .widthIn(min = 250.dp)
        ) {
            filteredVariables.forEach { variable ->
                TinaDropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = variable.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(variable.descriptionResId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        // 插入变量
                        val beforeCursor = value.take(cursorPosition.coerceAtMost(value.length))
                        val lastDollar = beforeCursor.lastIndexOf('$')

                        val newValue = if (lastDollar != -1) {
                            // 替换从 $ 开始的部分
                            value.substring(0, lastDollar) + variable.name + value.substring(cursorPosition.coerceAtMost(value.length))
                        } else {
                            // 直接插入
                            value + variable.name
                        }

                        onValueChange(newValue)
                        showSuggestions = false
                    }
                )
            }
        }
    }
}

/**
 * 变量帮助对话框
 */
@Composable
fun VariablesHelpDialog(
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.run_config_available_variables))
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.run_config_variables_desc)
                )
                RunConfigSectionCard(
                    contentModifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BuildVariables.ALL_VARIABLES.forEach { variable ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = variable.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(variable.descriptionResId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

/**
 * 运行配置选择器（编辑器优先：配置 + 主 Run，其余进菜单）。
 *
 * 常驻只保留配置名与运行主按钮，构建/调试/终端运行进入 Run 菜单，
 * 以适配手机窄顶栏与平板略宽布局。
 */
@Composable
fun RunConfigSelector(
    configManager: RunConfigurationManager,
    onSelectConfig: (String) -> Unit,
    onAddConfig: () -> Unit,
    onEditConfig: () -> Unit,
    onDuplicateConfig: (String) -> Unit,
    onDeleteConfig: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBuild: (() -> Unit)? = null,
    onRun: () -> Unit = {},
    onRebuildAndRun: () -> Unit = {},
    onRunInTerminal: () -> Unit = {},
    onDebug: () -> Unit = {},
    isBuildEnabled: Boolean = true,
    isRunEnabled: Boolean = true,
    isDebugEnabled: Boolean = true,
    buildIconRes: Int = 0,
    debugIconRes: Int = 0,
    runTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    disabledTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    configSegmentMaxWidth: Dp = 84.dp,
    showBuildButton: Boolean = false,
    showDebugButton: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentConfig = configManager.selectedConfig
    val showInlineBuild = showBuildButton && onBuild != null && buildIconRes != 0
    val showInlineDebug = showDebugButton && debugIconRes != 0

    Box(modifier = modifier) {
        // 配置 + Run（可选内联 Build/Debug，默认关闭以保持顶栏简洁）
        TinaOverlayPanelSurface(
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.height(36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：配置名称 + 下拉箭头（可点击展开菜单）
                TinaPanelSegmentButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .height(36.dp)
                        .widthIn(max = configSegmentMaxWidth),
                    contentPadding = PaddingValues(start = 8.dp, end = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentConfig.displayName(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 分隔线
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 主操作：运行（短按运行，长按/菜单含构建与调试）
                RunActionButton(
                    enabled = isRunEnabled,
                    runTint = runTint,
                    disabledTint = disabledTint,
                    onRun = onRun,
                    onRebuildAndRun = onRebuildAndRun,
                    onRunInTerminal = onRunInTerminal,
                    onEditConfig = onEditConfig,
                    onBuild = onBuild,
                    onDebug = onDebug,
                    isBuildEnabled = isBuildEnabled,
                    isDebugEnabled = isDebugEnabled,
                )

                if (showInlineBuild) {
                    VerticalDivider(
                        modifier = Modifier.height(20.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    TinaPanelSegmentButton(
                        onClick = onBuild!!,
                        enabled = isBuildEnabled,
                        modifier = Modifier.size(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            painter = rememberTinaPainter(buildIconRes),
                            contentDescription = stringResource(Strings.cmd_project_build),
                            tint = if (isBuildEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                disabledTint
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (showInlineDebug) {
                    VerticalDivider(
                        modifier = Modifier.height(20.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    TinaPanelSegmentButton(
                        onClick = onDebug,
                        enabled = isDebugEnabled,
                        modifier = Modifier.size(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            painter = rememberTinaPainter(debugIconRes),
                            contentDescription = stringResource(Strings.content_desc_debug),
                            tint = if (isDebugEnabled) runTint else disabledTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 下拉菜单 - 限制最大高度，支持滚动
        TinaDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp) // 限制下拉菜单最大高度
        ) {
            // 配置列表
            configManager.configurations.forEach { config ->
                val isSelected = config.id == configManager.selectedId
                TinaDropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.widthIn(max = 180.dp)) {
                            Text(
                                text = config.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (config.args.isNotEmpty()) {
                                Text(
                                    text = stringResource(Strings.run_config_args_label_with_variable, config.args),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectConfig(config.id)
                        expanded = false
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    trailingIcon = {
                        // 仅当有多个配置时显示删除按钮
                        if (configManager.configurations.size > 1) {
                            RunConfigActionButton(
                                onClick = {
                                    onDeleteConfig(config.id)
                                },
                                minSize = 24.dp,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(Strings.content_desc_delete),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            }

            TinaDropdownMenuDivider()
            TinaDropdownMenuSectionHeader {
                TinaDropdownMenuSectionTitle(
                    text = stringResource(Strings.action_more)
                )
            }

            // 添加新配置
            TinaDropdownMenuItem(
                text = { Text(stringResource(Strings.action_add_config)) },
                onClick = {
                    expanded = false
                    onAddConfig()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                }
            )

            // 复制当前配置
            TinaDropdownMenuItem(
                text = { Text(stringResource(Strings.action_copy_config)) },
                onClick = {
                    expanded = false
                    onDuplicateConfig(currentConfig.id)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null
                    )
                }
            )

            // 编辑当前配置
            TinaDropdownMenuItem(
                text = { Text(stringResource(Strings.action_edit_config)) },
                onClick = {
                    expanded = false
                    onEditConfig()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Composable
internal fun RunActionButton(
    enabled: Boolean,
    runTint: Color,
    disabledTint: Color,
    onRun: () -> Unit,
    onRebuildAndRun: () -> Unit,
    onRunInTerminal: () -> Unit,
    onEditConfig: () -> Unit,
    onBuild: (() -> Unit)? = null,
    onDebug: (() -> Unit)? = null,
    isBuildEnabled: Boolean = true,
    isDebugEnabled: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(enabled) {
        if (!enabled) {
            menuExpanded = false
        }
    }

    Box(
        modifier = Modifier.size(36.dp)
    ) {
        TinaPanelSegmentButton(
            onClick = {},
            enabled = enabled,
            modifier = Modifier.matchParentSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(Strings.action_run),
                tint = if (enabled) runTint else disabledTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onRun,
                    onLongClick = { menuExpanded = true },
                )
        )
        RunMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onRun = onRun,
            onRebuildAndRun = onRebuildAndRun,
            onRunInTerminal = onRunInTerminal,
            onEditConfig = onEditConfig,
            onBuild = onBuild,
            onDebug = onDebug,
            isBuildEnabled = isBuildEnabled,
            isDebugEnabled = isDebugEnabled,
        )
    }
}

/**
 * 运行菜单（长按运行按钮弹出）：运行变体 + 构建/调试
 */
@Composable
fun RunMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onRebuildAndRun: () -> Unit,
    onRunInTerminal: () -> Unit,
    onEditConfig: () -> Unit,
    onBuild: (() -> Unit)? = null,
    onDebug: (() -> Unit)? = null,
    isBuildEnabled: Boolean = true,
    isDebugEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_run)) },
            onClick = {
                onDismiss()
                onRun()
            }
        )
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_rebuild_and_run)) },
            onClick = {
                onDismiss()
                onRebuildAndRun()
            }
        )
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_run_in_terminal)) },
            onClick = {
                onDismiss()
                onRunInTerminal()
            }
        )
        if (onBuild != null || onDebug != null) {
            TinaDropdownMenuDivider()
            if (onBuild != null) {
                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.cmd_project_build)) },
                    enabled = isBuildEnabled,
                    onClick = {
                        onDismiss()
                        onBuild()
                    }
                )
            }
            if (onDebug != null) {
                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.content_desc_debug)) },
                    enabled = isDebugEnabled,
                    onClick = {
                        onDismiss()
                        onDebug()
                    }
                )
            }
        }
        TinaDropdownMenuDivider()
        TinaDropdownMenuSectionHeader {
            TinaDropdownMenuSectionTitle(
                text = stringResource(Strings.action_more)
            )
        }
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_edit_run_config)) },
            onClick = {
                onDismiss()
                onEditConfig()
            }
        )
    }
}

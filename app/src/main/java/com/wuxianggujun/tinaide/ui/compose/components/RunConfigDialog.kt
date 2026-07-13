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
 * 运行配置对话框
 *
 * @param config 当前配置
 * @param buildSystem 当前项目的构建系统类型
 * @param availableTargets 可用的构建目标列表（仅 CMake 项目有效）
 * @param availableSourceFiles 可用的源文件列表（仅单文件项目有效）
 * @param onSave 保存回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunConfigDialog(
    config: RunConfiguration,
    onSave: (RunConfiguration) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    buildSystem: BuildSystem = BuildSystem.SINGLE_FILE,
    availableTargets: List<TargetInfo> = emptyList(),
    availableSourceFiles: List<String> = emptyList()
) {
    var name by remember { mutableStateOf(config.name) }
    var args by remember { mutableStateOf(config.args) }
    var workDir by remember { mutableStateOf(config.workDir) }
    var buildType by remember { mutableStateOf(config.buildType) }
    var outputMode by remember { mutableStateOf(config.outputMode) }
    var targetName by remember { mutableStateOf(config.targetName) }
    var targetDropdownExpanded by remember { mutableStateOf(false) }

    // 编译器选择相关状态
    var compilerType by remember { mutableStateOf(config.compilerType) }
    var compilerDropdownExpanded by remember { mutableStateOf(false) }
    var toolchainId by remember { mutableStateOf(config.toolchainId) }
    var toolchainDropdownExpanded by remember { mutableStateOf(false) }
    var sysrootProfileId by remember {
        mutableStateOf(config.sysrootProfileId?.trim()?.takeIf { it.isNotEmpty() })
    }
    var sysrootProfileDropdownExpanded by remember { mutableStateOf(false) }
    var customCCompiler by remember { mutableStateOf(config.customCCompiler.orEmpty()) }
    var customCppCompiler by remember { mutableStateOf(config.customCppCompiler.orEmpty()) }

    // 编译器安装/可用状态（用于 UI 展示）
    val context = LocalContext.current
    val toolchainManager = remember { AndroidNativeToolchainManager(context.applicationContext) }
    val configManager = remember { toolchainManager.getConfigManager() }
    val sysrootManager = remember { AndroidSysrootManager(context.applicationContext) }
    val currentArch = remember { AndroidSysrootManager.Companion.Arch.current() }
    var toolchainConfig by remember { mutableStateOf(com.wuxianggujun.tinaide.core.ndk.InstalledToolchainConfig(null, emptyList())) }
    var sysrootProfiles by remember { mutableStateOf(emptyList<com.wuxianggujun.tinaide.core.ndk.SysrootProfileInfo>()) }
    var activeSysrootProfileId by remember { mutableStateOf<String?>(null) }
    var clangAvailable by remember { mutableStateOf<Boolean?>(null) }
    var gccAvailable by remember { mutableStateOf<Boolean?>(null) }
    var customAvailable by remember { mutableStateOf<Boolean?>(null) }

    fun isCustomCompilerPathAvailable(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        return if (file.isAbsolute) {
            file.isFile && file.canExecute()
        } else {
            // 相对路径/命令名交由运行时 PATH 解析
            true
        }
    }

    LaunchedEffect(Unit) {
        val loadedToolchainConfig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            configManager.readConfig()
        }
        val loadedSysrootProfiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sysrootManager.listProfiles(currentArch)
        }
        val loadedActiveSysrootProfileId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sysrootManager.getActiveProfile(currentArch)?.id
        }
        toolchainConfig = loadedToolchainConfig
        sysrootProfiles = loadedSysrootProfiles
        activeSysrootProfileId = loadedActiveSysrootProfileId
        if (sysrootProfileId != null && loadedSysrootProfiles.none { it.id == sysrootProfileId }) {
            sysrootProfileId = null
        }
        val installed = toolchainManager.isInstalled()
        val binDir = toolchainManager.getBinDir()
        clangAvailable = installed && File(binDir, "clang").isFile
        gccAvailable = installed && File(binDir, "gcc").isFile
    }

    LaunchedEffect(customCCompiler, customCppCompiler) {
        customAvailable = when {
            customCCompiler.isBlank() || customCppCompiler.isBlank() -> null
            else -> isCustomCompilerPathAvailable(customCCompiler.trim()) &&
                isCustomCompilerPathAvailable(customCppCompiler.trim())
        }
    }

    fun compilerStatusText(type: CompilerType): String = when (type) {
        CompilerType.CLANG -> when (clangAvailable) {
            true -> Strings.run_config_compiler_installed.strOr(context)
            false -> Strings.run_config_compiler_not_installed.strOr(context)
            null -> Strings.run_config_compiler_checking.strOr(context)
        }

        CompilerType.GCC -> when (gccAvailable) {
            true -> Strings.run_config_compiler_installed.strOr(context)
            false -> Strings.run_config_compiler_not_installed.strOr(context)
            null -> Strings.run_config_compiler_checking.strOr(context)
        }

        CompilerType.CUSTOM -> when {
            customCCompiler.isBlank() || customCppCompiler.isBlank() -> Strings.run_config_compiler_not_filled.strOr(context)
            customAvailable == true -> Strings.run_config_compiler_available.strOr(context)
            customAvailable == false -> Strings.run_config_compiler_unavailable.strOr(context)
            else -> Strings.run_config_compiler_checking.strOr(context)
        }
    }

    @Composable
    fun compilerStatusColor(type: CompilerType) = when (type) {
        CompilerType.CLANG -> when (clangAvailable) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        CompilerType.GCC -> when (gccAvailable) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        CompilerType.CUSTOM -> when {
            customCCompiler.isBlank() || customCppCompiler.isBlank() -> MaterialTheme.colorScheme.error
            customAvailable == true -> MaterialTheme.colorScheme.primary
            customAvailable == false -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    // 单文件编译相关状态
    var sourceFileMode by remember { mutableStateOf(config.sourceFileMode) }
    var sourceFilePath by remember { mutableStateOf(config.sourceFilePath) }
    var sourceFileDropdownExpanded by remember { mutableStateOf(false) }
    var singleFileCppStandard by remember {
        mutableStateOf(RunConfiguration.normalizeSingleFileCppStandard(config.singleFileCppStandard))
    }
    val selectedSingleFileCppStandard = remember(singleFileCppStandard) {
        RunConfiguration.parseSingleFileCppStandard(singleFileCppStandard)
    }
    var singleFileCppStandardDropdownExpanded by remember { mutableStateOf(false) }
    var sdlOrientation by remember { mutableStateOf(config.sdlOrientation) }
    var enableFloatingLog by remember { mutableStateOf(config.enableFloatingLog) }
    var showLinkerWarnings by remember { mutableStateOf(config.showLinkerWarnings) }
    var showVariablesHelp by remember { mutableStateOf(false) }

    // 目标过滤：SDL 图形运行只加载共享库，终端模式只运行可执行文件。
    val selectableTargets = remember(availableTargets, outputMode) {
        availableTargets.filter { target ->
            when {
                outputMode.isSdlGraphical() -> target.type == TargetInfo.Type.SHARED_LIBRARY
                else -> target.type == TargetInfo.Type.EXECUTABLE
            }
        }
    }
    val defaultTargetDescriptionRes = if (outputMode.isSdlGraphical()) {
        Strings.run_config_build_target_desc_sdl
    } else {
        Strings.run_config_build_target_desc
    }
    LaunchedEffect(outputMode, buildSystem, availableTargets, targetName) {
        if (buildSystem != BuildSystem.CMAKE || targetName.isBlank()) return@LaunchedEffect
        val selectedTargetType = availableTargets.firstOrNull { it.name == targetName }?.type ?: return@LaunchedEffect
        if (outputMode.isSdlGraphical() && selectedTargetType != TargetInfo.Type.SHARED_LIBRARY) {
            targetName = ""
        } else if (!outputMode.isSdlGraphical() && selectedTargetType == TargetInfo.Type.SHARED_LIBRARY) {
            targetName = ""
        }
    }
    val isNativeBuildSystem = buildSystem == BuildSystem.CMAKE ||
        buildSystem == BuildSystem.MAKE ||
        buildSystem == BuildSystem.SINGLE_FILE
    val showExplicitBuildType = buildSystem == BuildSystem.MAKE ||
        buildSystem == BuildSystem.SINGLE_FILE
    var sysrootApiLevelInput by remember { mutableStateOf(config.sysrootApiLevel?.toString().orEmpty()) }
    val parsedSysrootApiLevel = sysrootApiLevelInput.trim().toIntOrNull()
    val effectiveSysrootProfileId = sysrootProfileId ?: activeSysrootProfileId
    val effectiveSysrootProfile = remember(effectiveSysrootProfileId, sysrootProfiles) {
        effectiveSysrootProfileId
            ?.let { selectedId -> sysrootProfiles.firstOrNull { it.id == selectedId } }
    }
    val supportedSysrootApiLevels = remember(effectiveSysrootProfile) {
        effectiveSysrootProfile?.apiLevels.orEmpty().distinct().sorted()
    }
    val fallbackSysrootApiMin = 21
    val fallbackSysrootApiMax = 99
    val sysrootApiMin = supportedSysrootApiLevels.firstOrNull()
        ?: fallbackSysrootApiMin
    val sysrootApiMax = supportedSysrootApiLevels.lastOrNull()
        ?: fallbackSysrootApiMax
    val isSysrootApiLevelValid = sysrootApiLevelInput.isBlank() ||
        (
            parsedSysrootApiLevel != null &&
                parsedSysrootApiLevel in sysrootApiMin..sysrootApiMax &&
                (supportedSysrootApiLevels.isEmpty() || parsedSysrootApiLevel in supportedSysrootApiLevels)
            )
    val compilerConfirmEnabled = when (compilerType) {
        CompilerType.CLANG -> clangAvailable != false
        CompilerType.GCC -> gccAvailable != false
        CompilerType.CUSTOM -> customCCompiler.isNotBlank() &&
            customCppCompiler.isNotBlank() &&
            customAvailable != false
    }
    val confirmEnabled = compilerConfirmEnabled && isSysrootApiLevelValid

    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    TinaDialogTitleText(
                        title = stringResource(Strings.run_config_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    RunConfigActionButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Strings.btn_cancel),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = confirmEnabled,
                        onClick = {
                            onSave(
                                config.copy(
                                    name = name.trim().ifEmpty { "Debug" },
                                    args = args.trim(),
                                    workDir = workDir.trim(),
                                    buildType = buildType,
                                    outputMode = outputMode,
                                    targetName = targetName.trim(),
                                    sourceFileMode = sourceFileMode,
                                    sourceFilePath = sourceFilePath.trim(),
                                    compilerType = compilerType,
                                    toolchainId = toolchainId,
                                    sysrootProfileId = sysrootProfileId,
                                    customCCompiler = RunConfiguration.normalizeCompilerPath(customCCompiler),
                                    customCppCompiler = RunConfiguration.normalizeCompilerPath(customCppCompiler),
                                    sysrootApiLevel = parsedSysrootApiLevel,
                                    singleFileCppStandard = RunConfiguration
                                        .normalizeSingleFileCppStandard(singleFileCppStandard),
                                    sdlOrientation = sdlOrientation,
                                    enableFloatingLog = enableFloatingLog,
                                    showLinkerWarnings = showLinkerWarnings
                                )
                            )
                        }
                    ) {
                        Text(stringResource(Strings.content_desc_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        TinaDialogContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 配置名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Strings.run_config_name_label)) },
                placeholder = { Text(stringResource(Strings.run_config_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 编译器选择
            ExposedDropdownMenuBox(
                expanded = compilerDropdownExpanded,
                onExpandedChange = { compilerDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = when (compilerType) {
                        CompilerType.CUSTOM -> if (customCCompiler.isNotBlank()) {
                            stringResource(Strings.run_config_compiler_custom_desc) + " (${customCCompiler.substringAfterLast('/')})"
                        } else {
                            compilerType.getDisplayName(context)
                        }
                        else -> compilerType.getDisplayName(context)
                    },
                    onValueChange = {},
                    label = { Text(stringResource(Strings.run_config_compiler_label)) },
                    readOnly = true,
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = stringResource(Strings.run_config_compiler_status, compilerStatusText(compilerType)),
                            color = compilerStatusColor(compilerType),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = compilerDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                )
                TinaExposedDropdownMenu(
                    expanded = compilerDropdownExpanded,
                    onDismissRequest = { compilerDropdownExpanded = false }
                ) {
                    CompilerType.entries.forEach { type ->
                        TinaDropdownMenuItem(
                            text = {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = type.getDisplayName(context),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = compilerStatusText(type),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = compilerStatusColor(type)
                                        )
                                    }
                                    Text(
                                        text = when (type) {
                                            CompilerType.CLANG -> stringResource(Strings.run_config_compiler_clang_desc)
                                            CompilerType.GCC -> stringResource(Strings.run_config_compiler_gcc_desc)
                                            CompilerType.CUSTOM -> stringResource(Strings.run_config_compiler_custom_desc)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                compilerType = type
                                compilerDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (compilerType == type) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }

            // 工具链选择（仅当选择 CLANG 时显示）
            if (compilerType == CompilerType.CLANG && toolchainConfig.toolchains.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = toolchainDropdownExpanded,
                    onExpandedChange = { toolchainDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = run {
                            if (toolchainId == null) {
                                stringResource(Strings.run_config_toolchain_default)
                            } else {
                                val toolchain = toolchainConfig.toolchains.find { it.id == toolchainId }
                                toolchain?.displayLabel(context)
                                    ?: stringResource(Strings.run_config_toolchain_default)
                            }
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Strings.run_config_toolchain)) },
                        supportingText = {
                            Text(
                                text = stringResource(Strings.run_config_toolchain_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toolchainDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    TinaExposedDropdownMenu(
                        expanded = toolchainDropdownExpanded,
                        onDismissRequest = { toolchainDropdownExpanded = false }
                    ) {
                        // 默认选项（使用全局激活的工具链）
                        TinaDropdownMenuItem(
                            text = {
                                Text(stringResource(Strings.run_config_toolchain_default))
                            },
                            onClick = {
                                toolchainId = null
                                toolchainDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (toolchainId == null) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )

                        // 所有可用的工具链
                        toolchainConfig.toolchains.forEach { toolchain ->
                            TinaDropdownMenuItem(
                                text = {
                                    Column {
                                        Text(toolchain.displayName(context))
                                        toolchain.displayVersionLabel(context)?.let { versionLabel ->
                                            Text(
                                                text = versionLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    toolchainId = toolchain.id
                                    toolchainDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (toolchainId == toolchain.id) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Per-run NDK runtime override; empty means following the global active sysroot.
            if (isNativeBuildSystem && sysrootProfiles.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = sysrootProfileDropdownExpanded,
                    onExpandedChange = { sysrootProfileDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = sysrootProfileId
                            ?.let { selectedId ->
                                sysrootProfiles.firstOrNull { it.id == selectedId }?.displayLabel(context)
                                    ?: selectedId
                            }
                            ?: stringResource(Strings.run_config_sysroot_profile_global),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Strings.run_config_sysroot_profile_label)) },
                        supportingText = {
                            Text(
                                text = stringResource(Strings.run_config_sysroot_profile_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = sysrootProfileDropdownExpanded
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    TinaExposedDropdownMenu(
                        expanded = sysrootProfileDropdownExpanded,
                        onDismissRequest = { sysrootProfileDropdownExpanded = false }
                    ) {
                        TinaDropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(Strings.run_config_sysroot_profile_global))
                                    activeSysrootProfileId
                                        ?.let { activeId -> sysrootProfiles.firstOrNull { it.id == activeId } }
                                        ?.let { activeProfile ->
                                            Text(
                                                text = activeProfile.displayLabel(context),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                }
                            },
                            onClick = {
                                sysrootProfileId = null
                                sysrootProfileDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (sysrootProfileId == null) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                        TinaDropdownMenuDivider()
                        sysrootProfiles.forEach { profile ->
                            TinaDropdownMenuItem(
                                text = { Text(profile.displayLabel(context)) },
                                onClick = {
                                    sysrootProfileId = profile.id
                                    sysrootProfileDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (sysrootProfileId == profile.id) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 自定义编译器路径输入（仅当选择 CUSTOM 时显示）
            if (compilerType == CompilerType.CUSTOM) {
                val isCustomCInvalid = customCCompiler.isBlank()
                val isCustomCppInvalid = customCppCompiler.isBlank()

                OutlinedTextField(
                    value = customCCompiler,
                    onValueChange = { customCCompiler = it },
                    label = { Text(stringResource(Strings.run_config_custom_c_compiler_label)) },
                    placeholder = { Text(stringResource(Strings.run_config_custom_c_compiler_placeholder)) },
                    supportingText = {
                        Text(
                            text = if (isCustomCInvalid) stringResource(Strings.run_config_custom_compiler_error_empty) else stringResource(Strings.run_config_custom_compiler_hint),
                            color = if (isCustomCInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    isError = isCustomCInvalid,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = customCppCompiler,
                    onValueChange = { customCppCompiler = it },
                    label = { Text(stringResource(Strings.run_config_custom_cpp_compiler_label)) },
                    placeholder = { Text(stringResource(Strings.run_config_custom_cpp_compiler_placeholder)) },
                    supportingText = {
                        Text(
                            text = if (isCustomCppInvalid) stringResource(Strings.run_config_custom_compiler_error_empty) else stringResource(Strings.run_config_custom_compiler_hint),
                            color = if (isCustomCppInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    isError = isCustomCppInvalid,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (!isCustomCInvalid && !isCustomCppInvalid && customAvailable == false) {
                    Text(
                        text = stringResource(Strings.run_config_custom_compiler_error_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // CMake 项目显示目标选择
            if (buildSystem == BuildSystem.CMAKE && selectableTargets.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = targetDropdownExpanded,
                    onExpandedChange = { targetDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (targetName.isBlank()) stringResource(Strings.run_config_default_target) else targetName,
                        onValueChange = {},
                        label = { Text(stringResource(Strings.run_config_build_target)) },
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    TinaExposedDropdownMenu(
                        expanded = targetDropdownExpanded,
                        onDismissRequest = { targetDropdownExpanded = false }
                    ) {
                        // 默认目标选项
                        TinaDropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(Strings.run_config_default_target))
                                    Text(
                                        stringResource(defaultTargetDescriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                targetName = ""
                                targetDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (targetName.isBlank()) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                        TinaDropdownMenuDivider()
                        // 可用目标列表
                        selectableTargets.forEach { target ->
                            TinaDropdownMenuItem(
                                text = {
                                    Column {
                                        Text(target.name)
                                        if (target.sources.isNotEmpty()) {
                                            Text(
                                                stringResource(Strings.run_config_source_files_count, target.sources.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    targetName = target.name
                                    targetDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (targetName == target.name) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
            } else if (
                buildSystem == BuildSystem.CMAKE &&
                outputMode.isSdlGraphical() &&
                availableTargets.isNotEmpty()
            ) {
                Text(
                    text = stringResource(Strings.sdl_runtime_no_shared_library_target),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 单文件项目显示源文件选择
            if (buildSystem == BuildSystem.SINGLE_FILE) {
                HorizontalDivider()

                Text(
                    text = stringResource(Strings.run_config_source_file_selection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 源文件模式选择
                Column {
                    // 自动检测模式
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourceFileMode = SourceFileMode.AUTO }
                    ) {
                        RadioButton(
                            selected = sourceFileMode == SourceFileMode.AUTO,
                            onClick = { sourceFileMode = SourceFileMode.AUTO }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(Strings.run_config_auto_detect))
                            Text(
                                text = stringResource(Strings.run_config_auto_detect_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 当前文件模式
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourceFileMode = SourceFileMode.CURRENT_FILE }
                    ) {
                        RadioButton(
                            selected = sourceFileMode == SourceFileMode.CURRENT_FILE,
                            onClick = { sourceFileMode = SourceFileMode.CURRENT_FILE }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(Strings.run_config_current_file))
                            Text(
                                text = stringResource(Strings.run_config_current_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 指定文件模式
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourceFileMode = SourceFileMode.SPECIFIED_FILE }
                    ) {
                        RadioButton(
                            selected = sourceFileMode == SourceFileMode.SPECIFIED_FILE,
                            onClick = { sourceFileMode = SourceFileMode.SPECIFIED_FILE }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(Strings.run_config_specified_file))
                            Text(
                                text = stringResource(Strings.run_config_specified_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 指定文件模式下显示文件选择
                if (sourceFileMode == SourceFileMode.SPECIFIED_FILE) {
                    if (availableSourceFiles.isNotEmpty()) {
                        // 有可用源文件时显示下拉选择
                        ExposedDropdownMenuBox(
                            expanded = sourceFileDropdownExpanded,
                            onExpandedChange = { sourceFileDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = sourceFilePath.ifBlank { stringResource(Strings.run_config_select_source_file) },
                                onValueChange = { sourceFilePath = it },
                                label = { Text(stringResource(Strings.run_config_source_file_path)) },
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceFileDropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                            )
                            TinaExposedDropdownMenu(
                                expanded = sourceFileDropdownExpanded,
                                onDismissRequest = { sourceFileDropdownExpanded = false }
                            ) {
                                availableSourceFiles.forEach { file ->
                                    TinaDropdownMenuItem(
                                        text = { Text(file) },
                                        onClick = {
                                            sourceFilePath = file
                                            sourceFileDropdownExpanded = false
                                        },
                                        leadingIcon = {
                                            if (sourceFilePath == file) {
                                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 没有可用源文件时显示手动输入
                        OutlinedTextField(
                            value = sourceFilePath,
                            onValueChange = { sourceFilePath = it },
                            label = { Text(stringResource(Strings.run_config_source_file_path)) },
                            placeholder = { Text(stringResource(Strings.run_config_source_file_path_hint)) },
                            supportingText = { Text(stringResource(Strings.run_config_source_file_path_support)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = singleFileCppStandardDropdownExpanded,
                    onExpandedChange = { singleFileCppStandardDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedSingleFileCppStandard?.getDisplayName(context)
                            ?: stringResource(Strings.run_config_single_file_cpp_standard_project_default),
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(stringResource(Strings.run_config_single_file_cpp_standard_label))
                        },
                        supportingText = {
                            Text(stringResource(Strings.run_config_single_file_cpp_standard_desc))
                        },
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = singleFileCppStandardDropdownExpanded
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(
                                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true
                            )
                    )
                    TinaExposedDropdownMenu(
                        expanded = singleFileCppStandardDropdownExpanded,
                        onDismissRequest = { singleFileCppStandardDropdownExpanded = false }
                    ) {
                        TinaDropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        Strings.run_config_single_file_cpp_standard_project_default
                                    )
                                )
                            },
                            onClick = {
                                singleFileCppStandard = null
                                singleFileCppStandardDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedSingleFileCppStandard == null) {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                        TinaDropdownMenuDivider()
                        CppStandard.entries.forEach { standard ->
                            TinaDropdownMenuItem(
                                text = { Text(standard.getDisplayName(context)) },
                                onClick = {
                                    singleFileCppStandard = standard.name
                                    singleFileCppStandardDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (selectedSingleFileCppStandard == standard) {
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()
            }

            if (isNativeBuildSystem) {
                OutlinedTextField(
                    value = sysrootApiLevelInput,
                    onValueChange = { input ->
                        sysrootApiLevelInput = input.filter { it.isDigit() }.take(2)
                    },
                    label = {
                        Text(
                            stringResource(
                                com.wuxianggujun.tinaide.core.i18n.R.string.run_config_sysroot_api_level_label
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                com.wuxianggujun.tinaide.core.i18n.R.string.run_config_sysroot_api_level_placeholder
                            )
                        )
                    },
                    supportingText = {
                        if (isSysrootApiLevelValid) {
                            Text(
                                stringResource(
                                    com.wuxianggujun.tinaide.core.i18n.R.string.run_config_sysroot_api_level_desc
                                )
                            )
                        } else {
                            val errorText = if (supportedSysrootApiLevels.isNotEmpty()) {
                                stringResource(
                                    com.wuxianggujun.tinaide.core.i18n.R.string.run_config_sysroot_api_level_error_supported_values,
                                    supportedSysrootApiLevels.joinToString(", ")
                                )
                            } else {
                                stringResource(
                                    com.wuxianggujun.tinaide.core.i18n.R.string.run_config_sysroot_api_level_error_range,
                                    sysrootApiMin,
                                    sysrootApiMax
                                )
                            }
                            Text(
                                text = errorText,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isSysrootApiLevelValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 命令行参数（带变量补全）
            VariableTextField(
                value = args,
                onValueChange = { args = it },
                label = stringResource(Strings.run_config_args_label),
                placeholder = stringResource(Strings.run_config_args_placeholder),
                onShowHelp = { showVariablesHelp = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )

            // 工作目录（带变量补全）
            VariableTextField(
                value = workDir,
                onValueChange = { workDir = it },
                label = stringResource(Strings.run_config_workdir_label),
                placeholder = stringResource(Strings.run_config_workdir_placeholder),
                onShowHelp = { showVariablesHelp = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (showExplicitBuildType) {
                RunConfigSectionCard(
                    title = stringResource(Strings.run_config_build_type)
                ) {
                    RunConfigOptionRow(
                        selected = buildType == BuildType.DEBUG,
                        onClick = { buildType = BuildType.DEBUG },
                        title = stringResource(Strings.run_config_build_type_debug),
                        description = stringResource(Strings.run_config_build_type_debug_desc)
                    )
                    RunConfigOptionRow(
                        selected = buildType == BuildType.RELEASE,
                        onClick = { buildType = BuildType.RELEASE },
                        title = stringResource(Strings.run_config_build_type_release),
                        description = stringResource(Strings.run_config_build_type_release_desc)
                    )
                }
            }

            RunConfigSectionCard(
                title = stringResource(Strings.run_config_output_mode)
            ) {
                RunConfigOptionRow(
                    selected = outputMode == OutputMode.TERMINAL,
                    onClick = { outputMode = OutputMode.TERMINAL },
                    title = stringResource(Strings.run_config_output_terminal),
                    description = stringResource(Strings.run_config_output_terminal_desc)
                )
                RunConfigOptionRow(
                    selected = outputMode.isSdlGraphical(),
                    onClick = { outputMode = OutputMode.SDL },
                    title = stringResource(Strings.run_config_output_sdl),
                    description = stringResource(Strings.run_config_output_sdl_desc)
                )
            }

            if (outputMode == OutputMode.TERMINAL) {
                RunConfigSectionCard(
                    title = stringResource(Strings.run_config_terminal_options)
                ) {
                    RunConfigSwitchRow(
                        checked = showLinkerWarnings,
                        onCheckedChange = { showLinkerWarnings = it },
                        title = stringResource(Strings.run_config_show_linker_warnings),
                        description = stringResource(Strings.run_config_show_linker_warnings_desc)
                    )
                }
            }

            // SDL 图形运行选项（仅在 SDL 图形运行下显示）
            if (outputMode.isSdlGraphical()) {
                RunConfigSectionCard(
                    title = stringResource(Strings.run_config_sdl_options)
                ) {
                    Text(
                        text = stringResource(Strings.run_config_sdl_orientation),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    RunConfigOptionRow(
                        selected = sdlOrientation == SdlOrientation.AUTO,
                        onClick = { sdlOrientation = SdlOrientation.AUTO },
                        title = stringResource(Strings.run_config_sdl_orientation_auto),
                        description = stringResource(Strings.run_config_sdl_orientation_auto_desc)
                    )
                    RunConfigOptionRow(
                        selected = sdlOrientation == SdlOrientation.LANDSCAPE,
                        onClick = { sdlOrientation = SdlOrientation.LANDSCAPE },
                        title = stringResource(Strings.run_config_sdl_orientation_landscape),
                        description = stringResource(Strings.run_config_sdl_orientation_landscape_desc)
                    )
                    RunConfigOptionRow(
                        selected = sdlOrientation == SdlOrientation.PORTRAIT,
                        onClick = { sdlOrientation = SdlOrientation.PORTRAIT },
                        title = stringResource(Strings.run_config_sdl_orientation_portrait),
                        description = stringResource(Strings.run_config_sdl_orientation_portrait_desc)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    RunConfigSwitchRow(
                        checked = enableFloatingLog,
                        onCheckedChange = { enableFloatingLog = it },
                        title = stringResource(Strings.run_config_sdl_floating_log),
                        description = stringResource(Strings.run_config_sdl_floating_log_desc)
                    )
                }
            }

            // 提示：配置作用域说明
            RunConfigInfoCard(
                message = stringResource(Strings.run_config_scope_hint)
            )

            // 提示：运行方式说明
            RunConfigInfoCard(
                title = stringResource(Strings.run_config_run_method),
                message = stringResource(Strings.run_config_run_method_hint)
            )
        }
    }

    // 变量帮助对话框
    if (showVariablesHelp) {
        VariablesHelpDialog(
            onDismiss = { showVariablesHelp = false }
        )
    }
}

@Composable
private fun RunConfigActionButton(
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
private fun RunConfigSectionCard(
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
private fun RunConfigInfoCard(
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
private fun RunConfigOptionRow(
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
private fun RunConfigSwitchRow(
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
 * 运行配置选择器（CLion 风格，放在运行按钮左边）
 * 支持多配置选择、添加、复制、删除
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
    runTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
    disabledTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Gray,
    configSegmentMaxWidth: Dp = 84.dp,
    showBuildButton: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val currentConfig = configManager.selectedConfig

    Box(modifier = modifier) {
        // 配置 + 运行 + 构建 + 调试四段式工具条
        TinaOverlayPanelSurface(
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：配置名称 + 下拉箭头（可点击展开菜单）
                TinaPanelSegmentButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .height(32.dp)
                        .widthIn(max = configSegmentMaxWidth),
                    contentPadding = PaddingValues(start = 6.dp, end = 2.dp),
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

                // 中间：运行按钮（▶）
                RunActionButton(
                    enabled = isRunEnabled,
                    runTint = runTint,
                    disabledTint = disabledTint,
                    onRun = onRun,
                    onRebuildAndRun = onRebuildAndRun,
                    onRunInTerminal = onRunInTerminal,
                    onEditConfig = onEditConfig
                )

                // 分隔线
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                if (showBuildButton && onBuild != null && buildIconRes != 0) {
                    TinaPanelSegmentButton(
                        onClick = onBuild,
                        enabled = isBuildEnabled,
                        modifier = Modifier.size(32.dp),
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

                    VerticalDivider(
                        modifier = Modifier.height(20.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                // 右侧：调试按钮（🪲）
                TinaPanelSegmentButton(
                    onClick = onDebug,
                    enabled = isDebugEnabled,
                    modifier = Modifier.size(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (debugIconRes != 0) {
                        Icon(
                            painter = rememberTinaPainter(debugIconRes),
                            contentDescription = null,
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
private fun RunActionButton(
    enabled: Boolean,
    runTint: Color,
    disabledTint: Color,
    onRun: () -> Unit,
    onRebuildAndRun: () -> Unit,
    onRunInTerminal: () -> Unit,
    onEditConfig: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(enabled) {
        if (!enabled) {
            menuExpanded = false
        }
    }

    Box(
        modifier = Modifier.size(32.dp)
    ) {
        TinaPanelSegmentButton(
            onClick = {},
            enabled = enabled,
            modifier = Modifier.matchParentSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (enabled) runTint else disabledTint,
                modifier = Modifier.size(20.dp)
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
        )
    }
}

/**
 * 运行菜单（长按运行按钮弹出）
 */
@Composable
fun RunMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onRebuildAndRun: () -> Unit,
    onRunInTerminal: () -> Unit,
    onEditConfig: () -> Unit,
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

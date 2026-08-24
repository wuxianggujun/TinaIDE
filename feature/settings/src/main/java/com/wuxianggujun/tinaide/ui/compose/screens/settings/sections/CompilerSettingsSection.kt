package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.format.FormatStyle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.ndk.SysrootProfileInfo
import com.wuxianggujun.tinaide.core.ndk.displayLabel
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSliderDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.ToolchainImportDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.ToolchainImportState
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun CompilerSettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appConfigManager: IConfigManager = koinInject()
    val prootEnv = remember(context, appConfigManager) {
        PRootEnvironment(context.applicationContext, appConfigManager)
    }
    val state by viewModel.uiState.collectAsState()
    val threadsSuffix = stringResource(Strings.threads_format, 1).substringAfter(" ")
    val tasksSuffix = stringResource(Strings.tasks_format, 1).substringAfter(" ")
    val runtimeModeOptions = remember(state.linuxEnvironmentEnabled) {
        CompilerSettingsSectionSupport.buildRuntimeModeOptions(
            linuxEnvironmentEnabled = state.linuxEnvironmentEnabled
        )
    }

    val toastRedeploying = stringResource(Strings.toast_redeploying)
    val toastDeployComplete = stringResource(Strings.toast_deploy_complete)
    val toastDeployFailedTemplate = stringResource(Strings.toast_deploy_failed)
    val toastToolchainImporting = stringResource(Strings.toolchain_import_in_progress)
    val toastToolchainImportSuccess = stringResource(Strings.toolchain_import_success)
    val toastToolchainImportFailedTemplate = stringResource(Strings.toolchain_import_failed)
    val toastSysrootImporting = stringResource(Strings.sysroot_import_in_progress)
    val toastSysrootImportSuccessTemplate = stringResource(Strings.sysroot_import_success_with_profile)
    val toastSysrootImportFailedTemplate = stringResource(Strings.sysroot_import_failed)

    var showOptimizationDialog by remember { mutableStateOf(false) }
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showReinstallDialog by remember { mutableStateOf(false) }
    var showCmakeRunModeDialog by remember { mutableStateOf(false) }
    var showClangFormatRunModeDialog by remember { mutableStateOf(false) }
    var showMakeRunModeDialog by remember { mutableStateOf(false) }
    var showCmakeGeneratorDialog by remember { mutableStateOf(false) }
    var showCmakeParallelJobsDialog by remember { mutableStateOf(false) }
    var showFormatStyleDialog by remember { mutableStateOf(false) }
    var nativeEnvironmentRefreshKey by remember { mutableStateOf(0) }

    // 工具链导入状态
    var toolchainImportState by remember { mutableStateOf<ToolchainImportState>(ToolchainImportState.Idle) }

    // 工具链导入文件选择器
    val toolchainImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val logs = mutableListOf<String>()
                val logErrorTemplate = context.getString(Strings.toolchain_import_log_error)
                try {
                    val logExtractDone = context.getString(Strings.toolchain_import_log_extract_done)
                    val logValidateStart = context.getString(Strings.toolchain_import_log_validate_start)
                    val logValidateDone = context.getString(Strings.toolchain_import_log_validate_done)
                    val logInstallStart = context.getString(Strings.toolchain_import_log_install_start)
                    val logInstallDone = context.getString(Strings.toolchain_import_log_install_done)

                    // 获取文件信息
                    val (fileName, fileSize) = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "toolchain-import.tar.gz"
                            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                            name to size
                        } else {
                            "toolchain-import.tar.gz" to 0L
                        }
                    } ?: ("toolchain-import.tar.gz" to 0L)

                    // 确定文件类型
                    val fileType = CompilerSettingsSectionSupport.resolveArchiveFileType(fileName)
                    val extension = CompilerSettingsSectionSupport.resolveArchiveExtension(fileName)

                    val timeLabel = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(java.util.Date())
                    logs.add(context.getString(Strings.toolchain_import_log_start, timeLabel))
                    logs.add(context.getString(Strings.toolchain_import_log_file_name, fileName))
                    logs.add(context.getString(Strings.toolchain_import_log_file_size, fileSize / 1024 / 1024))
                    logs.add(context.getString(Strings.toolchain_import_log_file_type, fileType))

                    // 复制文件到临时目录
                    logs.add(context.getString(Strings.toolchain_import_log_copying))
                    val tempFile = File(context.cacheDir, "toolchain-import-${System.currentTimeMillis()}$extension")
                    val targetPath = context.filesDir.absolutePath + "/toolchains"

                    toolchainImportState = ToolchainImportState.Importing(
                        fileName = fileName,
                        fileSize = fileSize,
                        fileType = fileType,
                        targetPath = targetPath,
                        progress = 0.05f,
                        currentStep = context.getString(Strings.toolchain_import_step_extracting),
                        logs = logs.toList()
                    )

                    val inputStream = context.contentResolver.openInputStream(uri)
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    logs.add(context.getString(Strings.toolchain_import_log_copy_done, tempFile.absolutePath))

                    // 开始导入
                    val toolchainManager = AndroidNativeToolchainManager(context)
                    val toolchainId = toolchainManager.importFromFile(
                        archiveFile = tempFile,
                        onProgress = { progress ->
                            val currentStep = when {
                                progress < 0.7f -> context.getString(Strings.toolchain_import_step_extracting)
                                progress < 0.8f -> context.getString(Strings.toolchain_import_step_validating)
                                progress < 0.9f -> context.getString(Strings.toolchain_import_step_installing)
                                progress < 1.0f -> context.getString(Strings.toolchain_import_step_registering)
                                else -> context.getString(Strings.toolchain_import_step_completed)
                            }

                            val newLogs = logs.toMutableList()
                            if (progress >= 0.7f && !newLogs.any { it.contains(logExtractDone) }) {
                                newLogs.add(logExtractDone)
                            }
                            if (progress >= 0.8f && !newLogs.any { it.contains(logValidateDone) }) {
                                newLogs.add(logValidateStart)
                                newLogs.add(logValidateDone)
                            }
                            if (progress >= 0.9f && !newLogs.any { it.contains(logInstallDone) }) {
                                newLogs.add(logInstallStart)
                                newLogs.add(logInstallDone)
                            }

                            toolchainImportState = ToolchainImportState.Importing(
                                fileName = fileName,
                                fileSize = fileSize,
                                fileType = fileType,
                                targetPath = targetPath,
                                progress = progress,
                                currentStep = currentStep,
                                logs = newLogs
                            )
                        }
                    ).getOrThrow()

                    tempFile.delete()
                    logs.add(context.getString(Strings.toolchain_import_log_temp_cleanup))

                    // 切换到新导入的工具链
                    logs.add(context.getString(Strings.toolchain_import_log_switching))
                    val configManager = toolchainManager.getConfigManager()
                    configManager.switchToolchain(toolchainId).getOrThrow()
                    logs.add(context.getString(Strings.toolchain_import_log_switch_success))

                    val config = configManager.readConfig()
                    val toolchainInfo = config.toolchains.find { it.id == toolchainId }
                    logs.add(context.getString(Strings.toolchain_import_log_finished, toolchainId))

                    toolchainImportState = ToolchainImportState.Success(
                        toolchainId = toolchainId,
                        toolchainName = toolchainInfo?.name ?: context.getString(Strings.toolchain_unknown_name),
                        logs = logs
                    )
                } catch (e: Exception) {
                    val rawMessage = e.message?.trim().orEmpty()
                    val errorMessage = when {
                        rawMessage.isBlank() -> context.getString(Strings.error_unknown)
                        rawMessage.contains("bin/clang not found", ignoreCase = true) ->
                            context.getString(Strings.toolchain_import_error_invalid_archive)
                        rawMessage.contains("missing required files", ignoreCase = true) ->
                            context.getString(Strings.toolchain_import_error_missing_files)
                        else -> rawMessage
                    }
                    logs.add(String.format(Locale.getDefault(), logErrorTemplate, errorMessage))
                    toolchainImportState = ToolchainImportState.Failed(
                        error = errorMessage,
                        logs = logs
                    )
                }
            }
        }
    }

    // Sysroot 导入文件选择器
    val sysrootImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    Toast.makeText(context, toastSysrootImporting, Toast.LENGTH_SHORT).show()

                    // 获取文件名以确定扩展名
                    val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                    } ?: "sysroot-import.tar.gz"

                    // 根据文件名确定扩展名
                    val extension = CompilerSettingsSectionSupport.resolveArchiveExtension(fileName)

                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "sysroot-import-${System.currentTimeMillis()}$extension")
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val sysrootManager = AndroidSysrootManager(context)
                    val profileId = sysrootManager.importFromFile(
                        archiveFile = tempFile,
                        profileName = CompilerSettingsSectionSupport.resolveArchiveBaseName(fileName)
                    ).getOrThrow()
                    val activeProfile = sysrootManager.getActiveProfile()
                    tempFile.delete()
                    Toast.makeText(
                        context,
                        String.format(
                            Locale.getDefault(),
                            toastSysrootImportSuccessTemplate,
                            activeProfile?.displayLabel(context) ?: profileId
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    nativeEnvironmentRefreshKey++
                } catch (e: Exception) {
                    val errorMessage = when {
                        e.message?.contains("invalid", ignoreCase = true) == true ->
                            context.getString(Strings.sysroot_import_error_invalid_archive)
                        else -> e.message ?: context.getString(Strings.error_unknown)
                    }
                    Toast.makeText(
                        context,
                        String.format(Locale.getDefault(), toastSysrootImportFailedTemplate, errorMessage),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 编译选项
    SettingsCategoryTitle(stringResource(Strings.settings_cat_compiler_options))

    SettingsCard {
        val optimizationDisplayName =
            CompilerSettingsSectionSupport.resolveOptimizationDisplayLabel(
                state.compilerOptimizationLevel
            )?.let { stringResource(it) } ?: state.compilerOptimizationLevel
        SettingsClickableItem(
            title = stringResource(Strings.settings_optimization_level),
            value = optimizationDisplayName,
            onClick = { showOptimizationDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_compiler_threads),
            value = stringResource(Strings.threads_format, state.compilerThreads),
            onClick = { showThreadsDialog = true },
            showDivider = false
        )
    }

    Text(
        text = stringResource(Strings.settings_compiler_scope_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // CMake 配置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_cmake_config))

    SettingsCard {
        if (state.linuxEnvironmentEnabled) {
            SettingsClickableItem(
                title = stringResource(Strings.settings_cmake_run_mode),
                subtitle = stringResource(Strings.settings_cmake_run_mode_desc),
                value = stringResource(
                    CompilerSettingsSectionSupport.resolveRuntimeModeLabel(state.cmakeRunMode)
                ),
                onClick = { showCmakeRunModeDialog = true },
                showDivider = true
            )

            SettingsClickableItem(
                title = stringResource(Strings.settings_clang_format_run_mode),
                subtitle = stringResource(Strings.settings_clang_format_run_mode_desc),
                value = stringResource(
                    CompilerSettingsSectionSupport.resolveRuntimeModeLabel(
                        state.clangFormatRunMode
                    )
                ),
                onClick = { showClangFormatRunModeDialog = true },
                showDivider = true
            )

            SettingsClickableItem(
                title = stringResource(Strings.settings_make_run_mode),
                subtitle = stringResource(Strings.settings_make_run_mode_desc),
                value = stringResource(
                    CompilerSettingsSectionSupport.resolveRuntimeModeLabel(state.makeRunMode)
                ),
                onClick = { showMakeRunModeDialog = true },
                showDivider = true
            )
        }

        val cmakeGeneratorDisplayName =
            CompilerSettingsSectionSupport.resolveCmakeGeneratorDisplayLabel(
                state.cmakeGenerator
            )?.let { stringResource(it) } ?: state.cmakeGenerator
        SettingsClickableItem(
            title = stringResource(Strings.settings_generator),
            value = cmakeGeneratorDisplayName,
            onClick = { showCmakeGeneratorDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_parallel_jobs),
            value = stringResource(Strings.items_format, state.cmakeParallelJobs),
            onClick = { showCmakeParallelJobsDialog = true },
            showDivider = false
        )
    }

    // 代码格式化
    SettingsCategoryTitle(stringResource(Strings.settings_cat_format))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_format_style),
            value = state.codeFormatStyle,
            onClick = { showFormatStyleDialog = true },
            showDivider = false
        )
    }

    // 提示信息
    Text(
        text = stringResource(Strings.settings_format_style_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // 工具链管理
    SettingsCategoryTitle(stringResource(Strings.settings_cat_toolchain_management))

    // 工具链状态显示
    val toolchainManager = remember { AndroidNativeToolchainManager(context) }
    val configManager = remember { toolchainManager.getConfigManager() }
    val sysrootManager = remember { AndroidSysrootManager(context) }
    val currentArch = remember { AndroidSysrootManager.Companion.Arch.current() }

    var toolchainConfig by remember { mutableStateOf(com.wuxianggujun.tinaide.core.ndk.InstalledToolchainConfig(null, emptyList())) }
    var sysrootProfiles by remember { mutableStateOf<List<SysrootProfileInfo>>(emptyList()) }
    var activeSysrootProfile by remember { mutableStateOf<SysrootProfileInfo?>(null) }
    var sysrootInstalled by remember { mutableStateOf(false) }
    var showToolchainSelectorDialog by remember { mutableStateOf(false) }
    var showSysrootProfileSelectorDialog by remember { mutableStateOf(false) }

    // 检查工具链状态
    androidx.compose.runtime.LaunchedEffect(nativeEnvironmentRefreshKey) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            toolchainConfig = configManager.readConfig()
            sysrootProfiles = sysrootManager.listProfiles(currentArch)
            activeSysrootProfile = sysrootManager.getActiveProfile(currentArch)
            sysrootInstalled = sysrootManager.isInstalled(currentArch)
        }
    }

    val activeToolchain = toolchainConfig.toolchains.find { it.id == toolchainConfig.activeToolchain }

    SettingsCard {
        // 当前激活的工具链
        SettingsClickableItem(
            title = stringResource(Strings.settings_active_toolchain),
            value = activeToolchain?.displayLabel(context)
                ?: stringResource(Strings.toolchain_status_not_installed),
            onClick = { showToolchainSelectorDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_active_sysroot_profile),
            subtitle = stringResource(Strings.settings_active_sysroot_profile_desc),
            value = activeSysrootProfile?.displayLabel(context)
                ?: stringResource(Strings.sysroot_status_not_installed),
            onClick = { showSysrootProfileSelectorDialog = true },
            showDivider = true
        )

        // Sysroot 状态
        SettingsClickableItem(
            title = stringResource(Strings.settings_sysroot_status),
            value = if (sysrootInstalled) {
                stringResource(Strings.sysroot_status_installed)
            } else {
                stringResource(Strings.sysroot_status_not_installed)
            },
            onClick = { },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_import_toolchain),
            subtitle = stringResource(Strings.settings_import_toolchain_desc),
            onClick = { toolchainImportLauncher.launch("*/*") },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_import_sysroot),
            subtitle = stringResource(Strings.settings_import_sysroot_desc),
            onClick = { sysrootImportLauncher.launch("*/*") },
            showDivider = false
        )
    }

    // Sysroot / NDK Runtime 选择对话框
    if (showSysrootProfileSelectorDialog) {
        val options = sysrootProfiles.map { profile ->
            profile.id to profile.displayLabel(context)
        }
        if (options.isNotEmpty()) {
            TinaSingleChoiceDialog(
                title = stringResource(Strings.dialog_title_select_sysroot_profile),
                options = options,
                selectedValue = activeSysrootProfile?.id ?: "",
                onSelected = { selectedId ->
                    scope.launch {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                sysrootManager.activateOrInstallProfile(selectedId, currentArch).getOrThrow()
                                sysrootProfiles = sysrootManager.listProfiles(currentArch)
                                activeSysrootProfile = sysrootManager.getActiveProfile(currentArch)
                                sysrootInstalled = sysrootManager.isInstalled(currentArch)
                            }
                            Toast.makeText(
                                context,
                                context.getString(Strings.sysroot_profile_switch_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(Strings.sysroot_profile_switch_failed, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    showSysrootProfileSelectorDialog = false
                },
                onDismiss = { showSysrootProfileSelectorDialog = false }
            )
        } else {
            showSysrootProfileSelectorDialog = false
            Toast.makeText(
                context,
                stringResource(Strings.sysroot_profile_no_available),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 工具链选择对话框
    if (showToolchainSelectorDialog) {
        val options = toolchainConfig.toolchains.map { toolchain ->
            toolchain.id to toolchain.displayLabel(context)
        }
        if (options.isNotEmpty()) {
            TinaSingleChoiceDialog(
                title = stringResource(Strings.dialog_title_select_toolchain),
                options = options,
                selectedValue = toolchainConfig.activeToolchain ?: "",
                onSelected = { selectedId ->
                    scope.launch {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                configManager.switchToolchain(selectedId).getOrThrow()
                                toolchainConfig = configManager.readConfig()
                            }
                            Toast.makeText(
                                context,
                                context.getString(Strings.toolchain_switch_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(Strings.toolchain_switch_failed, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    showToolchainSelectorDialog = false
                },
                onDismiss = { showToolchainSelectorDialog = false }
            )
        } else {
            // 没有可用的工具链
            showToolchainSelectorDialog = false
            Toast.makeText(
                context,
                stringResource(Strings.toolchain_no_available),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (state.linuxEnvironmentEnabled) {
        SettingsCategoryTitle(stringResource(Strings.settings_cat_toolchain))

        SettingsCard {
            SettingsClickableItem(
                title = stringResource(Strings.settings_redeploy_env),
                subtitle = stringResource(Strings.settings_redeploy_env_desc),
                onClick = { showReinstallDialog = true },
                showDivider = false
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 对话框
    if (showOptimizationDialog) {
        val options = CompilerSettingsSectionSupport.buildOptimizationOptions().map { option ->
            option.value to stringResource(option.labelRes)
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_opt_level),
            options = options,
            selectedValue = state.compilerOptimizationLevel,
            onSelected = { value ->
                viewModel.setCompilerOptimizationLevel(value)
                showOptimizationDialog = false
            },
            onDismiss = { showOptimizationDialog = false }
        )
    }

    if (showThreadsDialog) {
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_threads),
            value = state.compilerThreads.toFloat(),
            valueRange = 1f..8f,
            steps = 6,
            valueLabel = { "${it.toInt()} $threadsSuffix" },
            onValueSelected = { value ->
                viewModel.setCompilerThreads(value.toInt())
                showThreadsDialog = false
            },
            onDismiss = { showThreadsDialog = false }
        )
    }

    if (showFormatStyleDialog) {
        val options = FormatStyle.predefinedStyles.map { style ->
            FormatStyle.toString(style) to FormatStyle.getDisplayName(style)
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_format_style),
            options = options,
            selectedValue = state.codeFormatStyle,
            onSelected = { style ->
                viewModel.setCodeFormatStyle(style)
                showFormatStyleDialog = false
            },
            onDismiss = { showFormatStyleDialog = false }
        )
    }

    // CMake 运行模式对话框
    if (showCmakeRunModeDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_cmake_run_mode),
            options = runtimeModeOptions.map { option ->
                option.value to stringResource(option.labelRes)
            },
            selectedValue = state.cmakeRunMode,
            onSelected = { value ->
                viewModel.setCmakeRunMode(value)
                showCmakeRunModeDialog = false
            },
            onDismiss = { showCmakeRunModeDialog = false }
        )
    }

    // clang-format 运行模式对话框
    if (showClangFormatRunModeDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_clang_format_run_mode),
            options = runtimeModeOptions.map { option ->
                option.value to stringResource(option.labelRes)
            },
            selectedValue = state.clangFormatRunMode,
            onSelected = { value ->
                viewModel.setClangFormatRunMode(value)
                showClangFormatRunModeDialog = false
            },
            onDismiss = { showClangFormatRunModeDialog = false }
        )
    }

    // Make 运行模式对话框
    if (showMakeRunModeDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_make_run_mode),
            options = runtimeModeOptions.map { option ->
                option.value to stringResource(option.labelRes)
            },
            selectedValue = state.makeRunMode,
            onSelected = { value ->
                viewModel.setMakeRunMode(value)
                showMakeRunModeDialog = false
            },
            onDismiss = { showMakeRunModeDialog = false }
        )
    }

    if (showCmakeGeneratorDialog) {
        val options = CompilerSettingsSectionSupport.buildCmakeGeneratorOptions().map { option ->
            option.value to stringResource(option.labelRes)
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_cmake_generator),
            options = options,
            selectedValue = state.cmakeGenerator,
            onSelected = { value ->
                viewModel.setCmakeGenerator(value)
                showCmakeGeneratorDialog = false
            },
            onDismiss = { showCmakeGeneratorDialog = false }
        )
    }

    if (showCmakeParallelJobsDialog) {
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_cmake_parallel),
            value = state.cmakeParallelJobs.toFloat(),
            valueRange = 1f..8f,
            steps = 6,
            valueLabel = { "${it.toInt()} $tasksSuffix" },
            onValueSelected = { value ->
                viewModel.setCmakeParallelJobs(value.toInt())
                showCmakeParallelJobsDialog = false
            },
            onDismiss = { showCmakeParallelJobsDialog = false }
        )
    }

    if (showReinstallDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.dialog_title_redeploy),
            message = stringResource(Strings.dialog_message_redeploy),
            onConfirm = {
                showReinstallDialog = false
                scope.launch {
                    try {
                        Toast.makeText(context, toastRedeploying, Toast.LENGTH_SHORT).show()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            prootEnv.clean().getOrThrow()
                            prootEnv.initialize().getOrThrow()
                        }
                        Toast.makeText(context, toastDeployComplete, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            String.format(Locale.getDefault(), toastDeployFailedTemplate, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onDismiss = { showReinstallDialog = false }
        )
    }

    // 工具链导入对话框
    ToolchainImportDialog(
        state = toolchainImportState,
        onDismiss = {
            toolchainImportState = ToolchainImportState.Idle
            // 刷新工具链配置
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val toolchainManager = AndroidNativeToolchainManager(context)
                    val configManager = toolchainManager.getConfigManager()
                    toolchainConfig = configManager.readConfig()
                }
            }
        }
    )
}

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

private val userProjectTemplateMimeTypes = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
    "*/*"
)

internal const val USER_TEMPLATE_METADATA_AUTO_VALUE = "__auto__"

@Composable
internal fun ProjectSettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    var showNewProjectLocationDialog by remember { mutableStateOf(false) }
    var showAutoSaveDialog by remember { mutableStateOf(false) }
    var showApkExportDialog by remember { mutableStateOf(false) }
    var editingPathType by remember { mutableStateOf<NativeDependencyPathType?>(null) }
    var editingBuildFlagType by remember { mutableStateOf<NativeBuildFlagType?>(null) }
    var userTemplates by remember { mutableStateOf<List<UserProjectTemplateItem>>(emptyList()) }
    var isLoadingUserTemplates by remember { mutableStateOf(false) }
    var selectedUserTemplate by remember { mutableStateOf<UserProjectTemplateItem?>(null) }
    var renamingUserTemplate by remember { mutableStateOf<UserProjectTemplateItem?>(null) }
    var userTemplateRenameInput by remember { mutableStateOf("") }
    var pendingDeleteUserTemplate by remember { mutableStateOf<UserProjectTemplateItem?>(null) }
    var pendingExportUserTemplate by remember { mutableStateOf<UserProjectTemplateItem?>(null) }
    var editingUserTemplateMetadata by remember { mutableStateOf<UserProjectTemplateItem?>(null) }
    var userTemplateEditNameInput by remember { mutableStateOf("") }
    var userTemplateEditDescriptionInput by remember { mutableStateOf("") }
    var userTemplateEditAuthorInput by remember { mutableStateOf("") }
    var userTemplateEditVariablesInput by remember { mutableStateOf("") }
    var userTemplateEditBuildSystem by remember { mutableStateOf<ProjectBuildSystem?>(null) }
    var userTemplateEditLanguage by remember { mutableStateOf<ProjectLanguage?>(null) }
    var userTemplateEditIsNdkTemplate by remember { mutableStateOf(false) }
    var selectingUserTemplateBuildSystem by remember { mutableStateOf(false) }
    var selectingUserTemplateLanguage by remember { mutableStateOf(false) }

    val userTemplateRoot = remember(appContext) {
        ProjectPaths.getUserProjectTemplatesRoot(appContext)
    }

    fun refreshUserTemplates() {
        scope.launch {
            isLoadingUserTemplates = true
            try {
                userTemplates = withContext(Dispatchers.IO) {
                    UserProjectTemplateManager.listTemplates(userTemplateRoot)
                }
            } finally {
                isLoadingUserTemplates = false
            }
        }
    }

    fun startEditingUserTemplateMetadata(template: UserProjectTemplateItem) {
        val metadata = template.metadata
        userTemplateEditNameInput = metadata?.name.orEmpty()
        userTemplateEditDescriptionInput = metadata?.description.orEmpty()
        userTemplateEditAuthorInput = metadata?.author.orEmpty()
        userTemplateEditVariablesInput = UserProjectTemplateManager.formatVariableDefaults(
            metadata?.variables.orEmpty()
        )
        userTemplateEditBuildSystem = metadata?.buildSystem?.takeUnless { it == ProjectBuildSystem.UNKNOWN }
        userTemplateEditLanguage = metadata?.primaryLanguage?.takeUnless { it == ProjectLanguage.UNKNOWN }
        userTemplateEditIsNdkTemplate = metadata?.isNdkTemplate == true
        editingUserTemplateMetadata = template
    }

    val userTemplateImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = UserProjectTemplateManager.importTemplateFromUri(appContext, uri)
            result
                .onSuccess { item ->
                    Toast.makeText(
                        context,
                        context.getString(Strings.settings_user_templates_import_success, item.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshUserTemplates()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(resolveUserProjectTemplateFailureMessageRes(error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    val userTemplateExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val template = pendingExportUserTemplate
        pendingExportUserTemplate = null
        if (uri == null || template == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = appContext.contentResolver.openOutputStream(uri)
                        ?: throw UserProjectTemplateException(UserProjectTemplateFailure.CANNOT_READ)
                    output.use {
                        UserProjectTemplateManager.exportTemplate(
                            templatesDir = userTemplateRoot,
                            templateName = template.name,
                            output = it,
                        )
                    }
                }
            }
            result
                .onSuccess {
                    Toast.makeText(
                        context,
                        context.getString(Strings.settings_user_templates_export_success, template.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(resolveUserProjectTemplateFailureMessageRes(error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    LaunchedEffect(userTemplateRoot.absolutePath) {
        refreshUserTemplates()
    }

    Spacer(modifier = Modifier.height(8.dp))

    // "自动保存 / 备份 / 新项目默认源位置" 在语义上是全局偏好；
    // 当用户从项目列表菜单进入"指定项目"模式时不应渲染，避免误导。
    if (!state.isTargetProjectMode) {
        // 自动保存设置
        SettingsCategoryTitle(stringResource(Strings.settings_cat_auto_save))

        SettingsCard {
            val autoSaveDisplayName = resolveProjectSettingsText(
                ProjectSettingsSectionSupport.resolveAutoSaveIntervalText(state.projectAutoSaveInterval)
            )
            SettingsClickableItem(
                title = stringResource(Strings.settings_auto_save_interval),
                value = autoSaveDisplayName,
                onClick = { showAutoSaveDialog = true },
                showDivider = false
            )
        }

        // 备份设置
        SettingsCategoryTitle(stringResource(Strings.settings_cat_backup))

        SettingsCard {
            SettingsSwitchItem(
                title = stringResource(Strings.settings_auto_backup),
                subtitle = stringResource(Strings.settings_auto_backup_desc),
                checked = state.projectAutoBackup,
                onCheckedChange = { viewModel.setProjectAutoBackup(it) },
                showDivider = false
            )
        }

        SettingsCategoryTitle(stringResource(Strings.settings_cat_new_project))
        SettingsCard {
            SettingsClickableItem(
                title = stringResource(Strings.settings_new_project_default_source_location),
                subtitle = stringResource(Strings.settings_new_project_default_source_location_desc),
                value = stringResource(
                    ProjectSettingsSectionSupport.resolveNewProjectSourceLocationLabelRes(
                        state.newProjectDefaultSourceLocation
                    )
                ),
                onClick = { showNewProjectLocationDialog = true },
                showDivider = true
            )
            SettingsClickableItem(
                title = stringResource(Strings.settings_user_templates_import),
                subtitle = stringResource(Strings.settings_user_templates_import_desc),
                onClick = {
                    userTemplateImportLauncher.launch(userProjectTemplateMimeTypes)
                },
                showDivider = false
            )
        }

        SettingsCategoryTitle(stringResource(Strings.settings_cat_user_project_templates))
        SettingsCard {
            SettingsDisplayItem(
                title = stringResource(Strings.settings_user_templates_folder),
                value = userTemplateRoot.absolutePath,
                valueMaxLines = 1,
                showDivider = true
            )
            SettingsClickableItem(
                title = stringResource(Strings.settings_user_templates_copy_folder_path),
                subtitle = stringResource(Strings.settings_user_templates_copy_folder_path_desc),
                onClick = {
                    copyUserProjectTemplateText(
                        context = context,
                        label = context.getString(Strings.settings_user_templates_folder_clipboard_label),
                        text = userTemplateRoot.absolutePath,
                    )
                    Toast.makeText(context, context.getString(Strings.toast_path_copied), Toast.LENGTH_SHORT).show()
                },
                showDivider = true
            )
            SettingsClickableItem(
                title = stringResource(Strings.settings_user_templates_refresh),
                subtitle = stringResource(Strings.settings_user_templates_refresh_desc),
                value = if (isLoadingUserTemplates) {
                    stringResource(Strings.loading)
                } else {
                    stringResource(Strings.settings_user_templates_count, userTemplates.size)
                },
                onClick = { refreshUserTemplates() },
                showDivider = false
            )
        }

        SettingsCard {
            if (userTemplates.isEmpty() && !isLoadingUserTemplates) {
                SettingsClickableItem(
                    title = stringResource(Strings.settings_user_templates_empty_title),
                    subtitle = stringResource(Strings.settings_user_templates_empty_desc),
                    onClick = {
                        userTemplateImportLauncher.launch(userProjectTemplateMimeTypes)
                    },
                    showDivider = false
                )
            } else {
                userTemplates.forEachIndexed { index, template ->
                    SettingsClickableItem(
                        title = template.metadata?.name ?: template.name,
                        subtitle = template.metadata?.description ?: stringResource(
                            Strings.settings_user_template_item_subtitle,
                            UserProjectTemplateManager.formatTemplateSize(template.sizeBytes)
                        ),
                        value = stringResource(Strings.settings_user_templates_manage),
                        onClick = { selectedUserTemplate = template },
                        showDivider = index != userTemplates.lastIndex
                    )
                }
            }
        }
    }

    // 三块"项目专属"类目（项目总览 / 原生依赖路径 / 原生构建标志）
    // 只有在存在可操作项目时才有意义：
    //   - MainActivity 齿轮进入 → 会话项目
    //   - 项目列表菜单 → 项目设置 → 覆盖模式指向点击的项目
    //   - 主页齿轮进入（无会话） → 整块不渲染，避免出现一串"无可用"占位
    val hasProjectContext = ProjectSettingsSectionSupport.hasProjectOpened(
        state.currentProjectRootPath
    )

    if (hasProjectContext) {
        SettingsCategoryTitle(stringResource(Strings.settings_cat_project))
        SettingsCard {
            val currentProjectValue = ProjectSettingsSectionSupport.resolveCurrentProjectName(
                currentProjectName = state.currentProjectName,
                unavailableValue = stringResource(Strings.settings_project_native_paths_no_project_value)
            )
            SettingsDisplayItem(
                title = stringResource(Strings.settings_project_native_paths_current_project),
                value = currentProjectValue
            )
            SettingsClickableItem(
                title = stringResource(Strings.settings_project_apk_export_type),
                subtitle = stringResource(Strings.settings_project_apk_export_type_desc),
                value = stringResource(
                    ProjectSettingsSectionSupport.resolveProjectApkExportTypeLabelRes(
                        state.projectApkExportType
                    )
                ),
                onClick = { showApkExportDialog = true },
                showDivider = !state.isTargetProjectMode
            )
            // 覆盖模式下没有 buildDir，无法自动检测 APK 导出类型；该按钮不渲染。
            if (!state.isTargetProjectMode) {
                SettingsClickableItem(
                    title = stringResource(Strings.settings_project_apk_export_redetect),
                    subtitle = stringResource(Strings.settings_project_apk_export_redetect_desc),
                    onClick = viewModel::redetectProjectApkExportType,
                    showDivider = false
                )
            }
        }

        SettingsCategoryTitle(stringResource(Strings.settings_cat_native_dependency_paths))
        SettingsCard {
            val includeValue = resolveProjectSettingsText(
                ProjectSettingsSectionSupport.resolveCollectionSummary(
                    state.projectNativeIncludeDirs.size
                )
            )
            val libraryValue = resolveProjectSettingsText(
                ProjectSettingsSectionSupport.resolveCollectionSummary(
                    state.projectNativeLibraryDirs.size
                )
            )
            val runtimeValue = resolveProjectSettingsText(
                ProjectSettingsSectionSupport.resolveCollectionSummary(
                    state.projectNativeRuntimeDirs.size
                )
            )

            SettingsClickableItem(
                title = stringResource(NativeDependencyPathType.INCLUDE.titleRes),
                subtitle = stringResource(NativeDependencyPathType.INCLUDE.subtitleRes),
                value = includeValue,
                onClick = { editingPathType = NativeDependencyPathType.INCLUDE }
            )
            SettingsClickableItem(
                title = stringResource(NativeDependencyPathType.LIBRARY.titleRes),
                subtitle = stringResource(NativeDependencyPathType.LIBRARY.subtitleRes),
                value = libraryValue,
                onClick = { editingPathType = NativeDependencyPathType.LIBRARY }
            )
            SettingsClickableItem(
                title = stringResource(NativeDependencyPathType.RUNTIME.titleRes),
                subtitle = stringResource(NativeDependencyPathType.RUNTIME.subtitleRes),
                value = runtimeValue,
                onClick = { editingPathType = NativeDependencyPathType.RUNTIME },
                showDivider = false
            )
        }

        SettingsCategoryTitle(stringResource(Strings.settings_cat_native_build_flags))
        SettingsCard {
            val emptyText = stringResource(Strings.settings_project_native_paths_empty)
            val cFlagsValue = ProjectSettingsSectionSupport.summarizeFlagValue(
                state.projectNativeCFlags,
                emptyText
            )
            val cppFlagsValue = ProjectSettingsSectionSupport.summarizeFlagValue(
                state.projectNativeCppFlags,
                emptyText
            )
            val ldFlagsValue = ProjectSettingsSectionSupport.summarizeFlagValue(
                state.projectNativeLdFlags,
                emptyText
            )
            val ldLibsValue = ProjectSettingsSectionSupport.summarizeFlagValue(
                state.projectNativeLdLibs,
                emptyText
            )
            val cmakeArgsValue = resolveProjectSettingsText(
                ProjectSettingsSectionSupport.resolveCollectionSummary(
                    state.projectNativeCMakeArgs.size
                )
            )

            SettingsClickableItem(
                title = stringResource(NativeBuildFlagType.CFLAGS.titleRes),
                subtitle = stringResource(NativeBuildFlagType.CFLAGS.subtitleRes),
                value = cFlagsValue,
                onClick = { editingBuildFlagType = NativeBuildFlagType.CFLAGS }
            )
            SettingsClickableItem(
                title = stringResource(NativeBuildFlagType.CXXFLAGS.titleRes),
                subtitle = stringResource(NativeBuildFlagType.CXXFLAGS.subtitleRes),
                value = cppFlagsValue,
                onClick = { editingBuildFlagType = NativeBuildFlagType.CXXFLAGS }
            )
            SettingsClickableItem(
                title = stringResource(NativeBuildFlagType.LDFLAGS.titleRes),
                subtitle = stringResource(NativeBuildFlagType.LDFLAGS.subtitleRes),
                value = ldFlagsValue,
                onClick = { editingBuildFlagType = NativeBuildFlagType.LDFLAGS }
            )
            SettingsClickableItem(
                title = stringResource(NativeBuildFlagType.LDLIBS.titleRes),
                subtitle = stringResource(NativeBuildFlagType.LDLIBS.subtitleRes),
                value = ldLibsValue,
                onClick = { editingBuildFlagType = NativeBuildFlagType.LDLIBS }
            )
            SettingsClickableItem(
                title = stringResource(NativeBuildFlagType.CMAKE_ARGS.titleRes),
                subtitle = stringResource(NativeBuildFlagType.CMAKE_ARGS.subtitleRes),
                value = cmakeArgsValue,
                onClick = { editingBuildFlagType = NativeBuildFlagType.CMAKE_ARGS },
                showDivider = false
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (showNewProjectLocationDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_new_project_default_source_location),
            options = ProjectSettingsSectionSupport.buildNewProjectSourceLocationOptions()
                .map { it.value to stringResource(it.labelRes) },
            selectedValue = state.newProjectDefaultSourceLocation.value,
            onSelected = { value ->
                ProjectSettingsSectionSupport.resolveNewProjectSourceLocation(value)
                    ?.let(viewModel::setNewProjectDefaultSourceLocation)
                showNewProjectLocationDialog = false
            },
            onDismiss = { showNewProjectLocationDialog = false }
        )
    }

    if (showAutoSaveDialog) {
        val options = ProjectSettingsSectionSupport.buildAutoSaveIntervalOptions()
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_auto_save_interval),
            options = options.map { it.value.toString() to stringResource(it.labelRes) },
            selectedValue = state.projectAutoSaveInterval.toString(),
            onSelected = { value ->
                viewModel.setProjectAutoSaveInterval(value.toInt())
                showAutoSaveDialog = false
            },
            onDismiss = { showAutoSaveDialog = false }
        )
    }

    if (showApkExportDialog) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_project_apk_export_type),
            options = ProjectSettingsSectionSupport.buildProjectApkExportTypeOptions()
                .map { it.value to stringResource(it.labelRes) },
            selectedValue = state.projectApkExportType?.name,
            onSelected = { value ->
                ProjectSettingsSectionSupport.resolveProjectApkExportType(value)
                    ?.let(viewModel::updateProjectApkExportType)
                showApkExportDialog = false
            },
            onDismiss = { showApkExportDialog = false }
        )
    }

    selectedUserTemplate?.let { template ->
        val metadata = template.metadata
        val buildSystemLabel = metadata?.buildSystem?.let {
            stringResource(resolveUserProjectTemplateBuildSystemLabelRes(it))
        }
        val languageLabel = metadata?.primaryLanguage?.let {
            stringResource(resolveUserProjectTemplateLanguageLabelRes(it))
        }
        val detailMessage = listOfNotNull(
            metadata?.description?.let {
                stringResource(Strings.settings_user_templates_detail_description, it)
            },
            metadata?.author?.let {
                stringResource(Strings.settings_user_templates_detail_author, it)
            },
            buildSystemLabel?.let {
                stringResource(Strings.settings_user_templates_detail_build_system, it)
            },
            languageLabel?.let {
                stringResource(Strings.settings_user_templates_detail_language, it)
            },
            if (metadata?.isNdkTemplate == true) {
                stringResource(Strings.settings_user_templates_detail_ndk_template)
            } else {
                null
            },
            stringResource(Strings.settings_user_templates_detail_file, template.file.absolutePath)
        ).joinToString(separator = "\n")
        TinaActionChoiceDialog(
            title = metadata?.name ?: template.name,
            message = detailMessage,
            actions = listOf(
                stringResource(Strings.settings_user_templates_copy_file_path) to {
                    copyUserProjectTemplateText(
                        context = context,
                        label = context.getString(Strings.settings_user_templates_file_clipboard_label),
                        text = template.file.absolutePath,
                    )
                    selectedUserTemplate = null
                    Toast.makeText(context, context.getString(Strings.toast_path_copied), Toast.LENGTH_SHORT).show()
                },
                stringResource(Strings.settings_user_templates_rename) to {
                    userTemplateRenameInput = template.name
                    selectedUserTemplate = null
                    renamingUserTemplate = template
                },
                stringResource(Strings.settings_user_templates_edit_metadata) to {
                    selectedUserTemplate = null
                    startEditingUserTemplateMetadata(template)
                },
                stringResource(Strings.settings_user_templates_export) to {
                    selectedUserTemplate = null
                    pendingExportUserTemplate = template
                    userTemplateExportLauncher.launch(template.name)
                },
                stringResource(Strings.btn_delete) to {
                    selectedUserTemplate = null
                    pendingDeleteUserTemplate = template
                }
            ),
            onDismiss = { selectedUserTemplate = null }
        )
    }

    editingUserTemplateMetadata?.let { template ->
        val variableDefaultsError = UserProjectTemplateManager.validateVariableDefaultsInput(
            userTemplateEditVariablesInput
        )
        val updatedMetadata = UserProjectTemplateMetadataUpdate(
            name = userTemplateEditNameInput,
            description = userTemplateEditDescriptionInput,
            author = userTemplateEditAuthorInput,
            buildSystem = userTemplateEditBuildSystem,
            primaryLanguage = userTemplateEditLanguage,
            isNdkTemplate = userTemplateEditIsNdkTemplate,
            variables = UserProjectTemplateManager.parseVariableDefaults(userTemplateEditVariablesInput),
        )
        UserProjectTemplateMetadataEditorDialog(
            templateName = template.name,
            displayName = userTemplateEditNameInput,
            onDisplayNameChange = { userTemplateEditNameInput = it },
            description = userTemplateEditDescriptionInput,
            onDescriptionChange = { userTemplateEditDescriptionInput = it },
            author = userTemplateEditAuthorInput,
            onAuthorChange = { userTemplateEditAuthorInput = it },
            variableDefaults = userTemplateEditVariablesInput,
            onVariableDefaultsChange = { userTemplateEditVariablesInput = it },
            variableDefaultsErrorRes = variableDefaultsError?.let(::resolveUserProjectTemplateVariableErrorRes),
            buildSystemLabel = stringResource(
                userTemplateEditBuildSystem?.let(::resolveUserProjectTemplateBuildSystemLabelRes)
                    ?: Strings.settings_user_templates_metadata_auto_detect
            ),
            onSelectBuildSystem = { selectingUserTemplateBuildSystem = true },
            languageLabel = stringResource(
                userTemplateEditLanguage?.let(::resolveUserProjectTemplateLanguageLabelRes)
                    ?: Strings.settings_user_templates_metadata_auto_detect
            ),
            onSelectLanguage = { selectingUserTemplateLanguage = true },
            isNdkTemplate = userTemplateEditIsNdkTemplate,
            onNdkTemplateChange = { userTemplateEditIsNdkTemplate = it },
            metadataPreview = UserProjectTemplateManager.buildTemplateMetadataPreview(updatedMetadata),
            canSave = variableDefaultsError == null,
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            UserProjectTemplateManager.updateTemplateMetadata(
                                templatesDir = userTemplateRoot,
                                templateName = template.name,
                                metadata = updatedMetadata,
                            )
                        }
                    }
                    result
                        .onSuccess {
                            Toast.makeText(
                                context,
                                context.getString(Strings.settings_user_templates_edit_success, template.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            editingUserTemplateMetadata = null
                            refreshUserTemplates()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                context.getString(resolveUserProjectTemplateFailureMessageRes(error)),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            },
            onDismiss = { editingUserTemplateMetadata = null },
        )
    }

    if (selectingUserTemplateBuildSystem) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_user_templates_edit_build_system),
            options = buildUserProjectTemplateBuildSystemOptions()
                .map { it.value to stringResource(it.labelRes) },
            selectedValue = userTemplateEditBuildSystem?.name ?: USER_TEMPLATE_METADATA_AUTO_VALUE,
            onSelected = { value ->
                userTemplateEditBuildSystem = resolveUserProjectTemplateBuildSystemValue(value)
                selectingUserTemplateBuildSystem = false
            },
            onDismiss = { selectingUserTemplateBuildSystem = false }
        )
    }

    if (selectingUserTemplateLanguage) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_user_templates_edit_primary_language),
            options = buildUserProjectTemplateLanguageOptions()
                .map { it.value to stringResource(it.labelRes) },
            selectedValue = userTemplateEditLanguage?.name ?: USER_TEMPLATE_METADATA_AUTO_VALUE,
            onSelected = { value ->
                userTemplateEditLanguage = resolveUserProjectTemplateLanguageValue(value)
                selectingUserTemplateLanguage = false
            },
            onDismiss = { selectingUserTemplateLanguage = false }
        )
    }

    renamingUserTemplate?.let { template ->
        val renameErrorRes = remember(userTemplateRenameInput, template.name) {
            resolveUserProjectTemplateRenameErrorRes(
                templatesDir = userTemplateRoot,
                currentName = template.name,
                input = userTemplateRenameInput,
            )
        }
        TinaInputDialog(
            title = stringResource(Strings.settings_user_templates_rename_title),
            value = userTemplateRenameInput,
            onValueChange = { userTemplateRenameInput = it },
            confirmText = stringResource(Strings.btn_confirm),
            dismissText = stringResource(Strings.btn_cancel),
            onConfirm = {
                val desiredName = userTemplateRenameInput
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            UserProjectTemplateManager.renameTemplate(
                                templatesDir = userTemplateRoot,
                                currentName = template.name,
                                desiredName = desiredName,
                            )
                        }
                    }
                    result
                        .onSuccess { item ->
                            Toast.makeText(
                                context,
                                context.getString(Strings.settings_user_templates_rename_success, item.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            renamingUserTemplate = null
                            userTemplateRenameInput = ""
                            refreshUserTemplates()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                context.getString(resolveUserProjectTemplateFailureMessageRes(error)),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            },
            onDismiss = {
                renamingUserTemplate = null
                userTemplateRenameInput = ""
            },
            label = stringResource(Strings.settings_user_templates_rename_label),
            placeholder = stringResource(Strings.settings_user_templates_rename_placeholder),
            isError = renameErrorRes != null,
            errorText = renameErrorRes?.let { stringResource(it) },
            singleLine = true
        )
    }

    pendingDeleteUserTemplate?.let { template ->
        TinaConfirmDialog(
            title = stringResource(Strings.settings_user_templates_delete_title),
            message = stringResource(Strings.settings_user_templates_delete_message, template.name),
            confirmText = stringResource(Strings.btn_delete),
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            UserProjectTemplateManager.deleteTemplate(
                                templatesDir = userTemplateRoot,
                                templateName = template.name,
                            )
                        }
                    }
                    result
                        .onSuccess {
                            Toast.makeText(
                                context,
                                context.getString(Strings.settings_user_templates_delete_success, template.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshUserTemplates()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                context.getString(resolveUserProjectTemplateFailureMessageRes(error)),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    pendingDeleteUserTemplate = null
                }
            },
            onDismiss = { pendingDeleteUserTemplate = null },
            isDanger = true
        )
    }

    val activePathType = editingPathType
    if (activePathType != null) {
        val currentPaths = when (activePathType) {
            NativeDependencyPathType.INCLUDE -> state.projectNativeIncludeDirs
            NativeDependencyPathType.LIBRARY -> state.projectNativeLibraryDirs
            NativeDependencyPathType.RUNTIME -> state.projectNativeRuntimeDirs
        }
        NativeDependencyPathEditorDialog(
            title = stringResource(activePathType.titleRes),
            initialPaths = currentPaths,
            onConfirm = { updatedPaths ->
                when (activePathType) {
                    NativeDependencyPathType.INCLUDE -> {
                        viewModel.updateProjectNativeDependencyPaths(
                            includeDirs = updatedPaths,
                            libraryDirs = state.projectNativeLibraryDirs,
                            runtimeDirs = state.projectNativeRuntimeDirs
                        )
                    }

                    NativeDependencyPathType.LIBRARY -> {
                        viewModel.updateProjectNativeDependencyPaths(
                            includeDirs = state.projectNativeIncludeDirs,
                            libraryDirs = updatedPaths,
                            runtimeDirs = state.projectNativeRuntimeDirs
                        )
                    }

                    NativeDependencyPathType.RUNTIME -> {
                        viewModel.updateProjectNativeDependencyPaths(
                            includeDirs = state.projectNativeIncludeDirs,
                            libraryDirs = state.projectNativeLibraryDirs,
                            runtimeDirs = updatedPaths
                        )
                    }
                }
                editingPathType = null
            },
            onDismiss = { editingPathType = null },
            hintText = stringResource(Strings.settings_project_native_paths_edit_hint),
            placeholderText = stringResource(Strings.settings_project_native_paths_edit_placeholder)
        )
    }

    val activeBuildFlagType = editingBuildFlagType
    if (activeBuildFlagType != null) {
        if (activeBuildFlagType == NativeBuildFlagType.CMAKE_ARGS) {
            NativeDependencyPathEditorDialog(
                title = stringResource(activeBuildFlagType.titleRes),
                initialPaths = state.projectNativeCMakeArgs,
                onConfirm = { updatedArgs ->
                    viewModel.updateProjectNativeBuildFlags(
                        cFlags = state.projectNativeCFlags,
                        cppFlags = state.projectNativeCppFlags,
                        ldFlags = state.projectNativeLdFlags,
                        ldLibs = state.projectNativeLdLibs,
                        cmakeArgs = updatedArgs
                    )
                    editingBuildFlagType = null
                },
                onDismiss = { editingBuildFlagType = null },
                hintText = stringResource(Strings.settings_project_native_cmake_args_edit_hint),
                placeholderText = stringResource(Strings.settings_project_native_cmake_args_edit_placeholder)
            )
        } else {
            val initialValue = when (activeBuildFlagType) {
                NativeBuildFlagType.CFLAGS -> state.projectNativeCFlags
                NativeBuildFlagType.CXXFLAGS -> state.projectNativeCppFlags
                NativeBuildFlagType.LDFLAGS -> state.projectNativeLdFlags
                NativeBuildFlagType.LDLIBS -> state.projectNativeLdLibs
                NativeBuildFlagType.CMAKE_ARGS -> ""
            }
            NativeBuildFlagEditorDialog(
                title = stringResource(activeBuildFlagType.titleRes),
                initialValue = initialValue,
                onConfirm = { updatedValue ->
                    val normalizedValue = ProjectSettingsSectionSupport.normalizeFlagInput(
                        updatedValue
                    )
                    when (activeBuildFlagType) {
                        NativeBuildFlagType.CFLAGS -> viewModel.updateProjectNativeBuildFlags(
                            cFlags = normalizedValue,
                            cppFlags = state.projectNativeCppFlags,
                            ldFlags = state.projectNativeLdFlags,
                            ldLibs = state.projectNativeLdLibs,
                            cmakeArgs = state.projectNativeCMakeArgs
                        )

                        NativeBuildFlagType.CXXFLAGS -> viewModel.updateProjectNativeBuildFlags(
                            cFlags = state.projectNativeCFlags,
                            cppFlags = normalizedValue,
                            ldFlags = state.projectNativeLdFlags,
                            ldLibs = state.projectNativeLdLibs,
                            cmakeArgs = state.projectNativeCMakeArgs
                        )

                        NativeBuildFlagType.LDFLAGS -> viewModel.updateProjectNativeBuildFlags(
                            cFlags = state.projectNativeCFlags,
                            cppFlags = state.projectNativeCppFlags,
                            ldFlags = normalizedValue,
                            ldLibs = state.projectNativeLdLibs,
                            cmakeArgs = state.projectNativeCMakeArgs
                        )

                        NativeBuildFlagType.LDLIBS -> viewModel.updateProjectNativeBuildFlags(
                            cFlags = state.projectNativeCFlags,
                            cppFlags = state.projectNativeCppFlags,
                            ldFlags = state.projectNativeLdFlags,
                            ldLibs = normalizedValue,
                            cmakeArgs = state.projectNativeCMakeArgs
                        )

                        NativeBuildFlagType.CMAKE_ARGS -> Unit
                    }
                    editingBuildFlagType = null
                },
                onDismiss = { editingBuildFlagType = null }
            )
        }
    }
}


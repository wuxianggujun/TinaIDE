package com.wuxianggujun.tinaide.ui.wizard

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.AndroidApiLevel
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectTemplateInstaller
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import com.wuxianggujun.tinaide.project.getDisplayName
import com.wuxianggujun.tinaide.storage.compose.rememberStoragePermissionRequester
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.TinaLoadingDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaExposedDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaRecommendedBadge
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing

/**
 * 新建项目向导主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectWizardScreen(
    state: NewProjectWizardState,
    templateOptions: List<ProjectTemplateOption>,
    isPluginProjectMode: Boolean = false,
    onTemplateSelected: (ProjectTemplateOption) -> Unit,
    onProjectNameChanged: (String) -> Unit,
    onSourceLocationSelected: (NewProjectSourceLocation) -> Unit,
    onCppStandardSelected: (CppStandard) -> Unit,
    onNdkApiLevelSelected: (AndroidApiLevel) -> Unit,
    onNextStep: () -> Unit,
    onPreviousStep: () -> Unit,
    onCreateProject: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val selectedTemplate = remember(state.selectedTemplateId, templateOptions) {
        NewProjectWizardSupport.resolveSelectedTemplate(
            selectedTemplateId = state.selectedTemplateId,
            templateOptions = templateOptions,
        )
    }
    val permissionRequester = rememberStoragePermissionRequester { granted ->
        if (granted) {
            onCreateProject()
        } else {
            Toast.makeText(
                context,
                Strings.permission_storage_settings.strOr(context),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = "",
                onNavigateBack = onBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 标题区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TinaSpacing.xxxl)
            ) {
                Text(
                    text = stringResource(
                        if (isPluginProjectMode) {
                            Strings.wizard_title_new_plugin_project
                        } else {
                            Strings.wizard_title_new_project
                        }
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (isPluginProjectMode) {
                            Strings.wizard_subtitle_new_plugin_project
                        } else {
                            Strings.wizard_subtitle_new_project
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 步骤指示器
            StepIndicator(
                currentStep = state.currentStep,
                steps = listOf(
                    stringResource(Strings.wizard_step_template),
                    stringResource(Strings.wizard_step_config)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TinaSpacing.xxxl)
            )

            Spacer(modifier = Modifier.height(TinaSpacing.xxxl))

            // 内容区域（带动画切换）
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "wizard_content"
            ) { step ->
                when (step) {
                    0 -> TemplateSelectionStep(
                        templateOptions = templateOptions,
                        isPluginProjectMode = isPluginProjectMode,
                        selectedTemplateId = selectedTemplate?.id ?: state.selectedTemplateId,
                        onTemplateSelected = onTemplateSelected
                    )
                    1 -> ConfigurationStep(
                        selectedTemplate = selectedTemplate,
                        projectName = state.projectName,
                        cppStandard = state.cppStandard,
                        showsCppStandard = state.showsCppStandard,
                        isNdkTemplate = state.isNdkTemplate,
                        ndkApiLevel = state.ndkApiLevel,
                        sourceLocation = state.sourceLocation,
                        nameError = state.nameError,
                        onProjectNameChanged = onProjectNameChanged,
                        onSourceLocationSelected = onSourceLocationSelected,
                        onCppStandardSelected = onCppStandardSelected,
                        onNdkApiLevelSelected = onNdkApiLevelSelected
                    )
                }
            }

            // 底部按钮区域
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(TinaSpacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(TinaSpacing.lg, Alignment.End)
                ) {
                    if (state.currentStep > 0) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.wizard_btn_previous),
                            onClick = onPreviousStep
                        )
                    }

                    if (state.currentStep == 0) {
                        TinaPrimaryButton(
                            text = stringResource(Strings.wizard_btn_next),
                            onClick = onNextStep,
                            enabled = templateOptions.isNotEmpty(),
                        )
                    } else {
                        TinaPrimaryButton(
                            text = stringResource(Strings.wizard_btn_create),
                            onClick = {
                                if (state.sourceLocation == NewProjectSourceLocation.PUBLIC) {
                                    permissionRequester.request()
                                } else {
                                    onCreateProject()
                                }
                            },
                            enabled = !state.isCreating && state.projectName.isNotBlank()
                        )
                    }
                }
            }
        }
    }

    // 创建中的加载对话框
    if (state.isCreating) {
        TinaLoadingDialog(
            title = stringResource(Strings.wizard_btn_create),
            message = stringResource(Strings.progress_please_wait)
        )
    }
}

/**
 * 步骤指示器
 */
@Composable
private fun StepIndicator(
    currentStep: Int,
    steps: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, stepName ->
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep

            // 步骤圆点
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 步骤名称
            Text(
                text = stepName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent || isCompleted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal
            )

            // 连接线
            if (index < steps.size - 1) {
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(
                            if (isCompleted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
        }
    }
}

/**
 * 步骤 1: 模板选择
 */
@Composable
private fun TemplateSelectionStep(
    templateOptions: List<ProjectTemplateOption>,
    isPluginProjectMode: Boolean,
    selectedTemplateId: String,
    onTemplateSelected: (ProjectTemplateOption) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TinaSpacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
    ) {
        if (templateOptions.isEmpty()) {
            TemplateEmptyState(isPluginProjectMode = isPluginProjectMode)
            return@Column
        }

        templateOptions.forEach { option ->
            TemplateCard(
                icon = iconForTemplate(option),
                title = option.displayName,
                description = option.description,
                badgeRes = NewProjectWizardSupport.resolveTemplateBadgeRes(option),
                guideRes = NewProjectWizardSupport.resolveTemplateCardGuideRes(option),
                isSelected = selectedTemplateId == option.id,
                isRecommended = option.isRecommended,
                onClick = { onTemplateSelected(option) }
            )
        }
    }
}

@Composable
private fun TemplateEmptyState(isPluginProjectMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TinaShapes.CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TinaSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(
                    if (isPluginProjectMode) {
                        Strings.wizard_plugin_templates_empty_title
                    } else {
                        Strings.wizard_templates_empty_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(
                    if (isPluginProjectMode) {
                        Strings.wizard_plugin_templates_empty_body
                    } else {
                        Strings.wizard_templates_empty_body
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconForTemplate(option: ProjectTemplateOption): ImageVector {
    return when (val spec = option.spec) {
        is ProjectTemplateSpec.Asset -> when (spec.type) {
            ProjectTemplateInstaller.TemplateType.CPP_SINGLE_FILE -> Icons.Default.Description
            ProjectTemplateInstaller.TemplateType.CMAKE_EXECUTABLE -> Icons.Default.Code
            ProjectTemplateInstaller.TemplateType.CMAKE_LIBRARY -> Icons.Default.Folder
            ProjectTemplateInstaller.TemplateType.MAKE_EXECUTABLE -> Icons.Default.Construction
            ProjectTemplateInstaller.TemplateType.NDK_SHARED_LIBRARY -> Icons.Default.PhoneAndroid
        }
        is ProjectTemplateSpec.Zip -> when {
            NewProjectWizardSupport.isUserTemplate(option) -> Icons.Default.Folder
            option.displayName.contains("sdl", ignoreCase = true) -> Icons.Default.Code
            spec.isNdkTemplate -> Icons.Default.PhoneAndroid
            spec.buildSystem == ProjectBuildSystem.PLUGIN -> Icons.Default.Extension
            spec.buildSystem == ProjectBuildSystem.MAKE -> Icons.Default.Construction
            else -> Icons.Default.Code
        }
    }
}

/**
 * 模板选择卡片
 */
@Composable
private fun TemplateCard(
    icon: ImageVector,
    title: String,
    description: String,
    @StringRes badgeRes: Int?,
    @StringRes guideRes: Int?,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(TinaShapes.CardCorner),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(TinaShapes.SmallCorner))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(TinaSpacing.xl))

            // 文字内容
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(TinaSpacing.md))
                        TinaRecommendedBadge(
                            text = stringResource(Strings.wizard_template_recommended)
                        )
                    }
                    badgeRes?.let { resId ->
                        Spacer(modifier = Modifier.width(TinaSpacing.md))
                        TinaRecommendedBadge(text = stringResource(resId))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                guideRes?.let { resId ->
                    Spacer(modifier = Modifier.height(TinaSpacing.xs))
                    Text(
                        text = stringResource(resId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 选中指示
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 步骤 2: 项目配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationStep(
    selectedTemplate: ProjectTemplateOption?,
    projectName: String,
    cppStandard: CppStandard,
    showsCppStandard: Boolean,
    isNdkTemplate: Boolean,
    ndkApiLevel: AndroidApiLevel,
    sourceLocation: NewProjectSourceLocation,
    nameError: String?,
    onProjectNameChanged: (String) -> Unit,
    onSourceLocationSelected: (NewProjectSourceLocation) -> Unit,
    onCppStandardSelected: (CppStandard) -> Unit,
    onNdkApiLevelSelected: (AndroidApiLevel) -> Unit
) {
    val context = LocalContext.current
    var cppStandardExpanded by remember { mutableStateOf(false) }
    var apiLevelExpanded by remember { mutableStateOf(false) }
    val guideTitleRes = NewProjectWizardSupport.resolveConfigurationGuideTitleRes(selectedTemplate)
    val guideBodyRes = NewProjectWizardSupport.resolveConfigurationGuideBodyRes(selectedTemplate)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TinaSpacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xxl)
    ) {
        // 项目名称
        OutlinedTextField(
            value = projectName,
            onValueChange = onProjectNameChanged,
            label = { Text(stringResource(Strings.label_project_name)) },
            placeholder = { Text(stringResource(Strings.hint_project_name_example)) },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        guideTitleRes?.let { titleRes ->
            PluginProjectGuideCard(
                title = stringResource(titleRes),
                body = guideBodyRes?.let { bodyRes -> stringResource(bodyRes) }.orEmpty(),
            )
        }

        if (showsCppStandard) {
            // C++ 标准选择
            ExposedDropdownMenuBox(
                expanded = cppStandardExpanded,
                onExpandedChange = { cppStandardExpanded = it }
            ) {
                OutlinedTextField(
                    value = cppStandard.getDisplayName(context),
                    onValueChange = { },
                    label = { Text(stringResource(Strings.label_cpp_standard)) },
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = cppStandardExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )

                TinaExposedDropdownMenu(
                    expanded = cppStandardExpanded,
                    onDismissRequest = { cppStandardExpanded = false }
                ) {
                    CppStandard.entries.forEach { standard ->
                        TinaDropdownMenuItem(
                            text = {
                                Text(standard.getDisplayName(context))
                            },
                            onClick = {
                                onCppStandardSelected(standard)
                                cppStandardExpanded = false
                            },
                            trailingIcon = if (standard == cppStandard) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null
                        )
                    }
                }
            }

            // C++ 标准提示
            Text(
                text = stringResource(Strings.hint_cpp_standard),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // NDK API Level 选择（仅 NDK 模板显示）
        if (isNdkTemplate) {
            HorizontalDivider()

            ExposedDropdownMenuBox(
                expanded = apiLevelExpanded,
                onExpandedChange = { apiLevelExpanded = it }
            ) {
                OutlinedTextField(
                    value = ndkApiLevel.getDisplayName(context),
                    onValueChange = { },
                    label = { Text(stringResource(Strings.ndk_api_level_label)) },
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = apiLevelExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )

                TinaExposedDropdownMenu(
                    expanded = apiLevelExpanded,
                    onDismissRequest = { apiLevelExpanded = false }
                ) {
                    AndroidApiLevel.entries
                        .forEach { level ->
                            TinaDropdownMenuItem(
                                text = { Text(level.getDisplayName(context)) },
                                onClick = {
                                    onNdkApiLevelSelected(level)
                                    apiLevelExpanded = false
                                },
                                trailingIcon = if (level == ndkApiLevel) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null
                            )
                        }
                }
            }
        }

        SourceLocationSelector(
            selectedLocation = sourceLocation,
            onLocationSelected = onSourceLocationSelected
        )

        // 底部留白，防止被按钮遮挡
        Spacer(modifier = Modifier.height(TinaSpacing.xl))
    }
}

@Composable
private fun PluginProjectGuideCard(
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(TinaShapes.CardCorner),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TinaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SourceLocationSelector(
    selectedLocation: NewProjectSourceLocation,
    onLocationSelected: (NewProjectSourceLocation) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)
    ) {
        Text(
            text = stringResource(Strings.wizard_project_source_location),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        NewProjectSourceLocation.entries.forEach { location ->
            SourceLocationCard(
                location = location,
                isSelected = location == selectedLocation,
                onClick = { onLocationSelected(location) }
            )
        }
    }
}

@Composable
private fun SourceLocationCard(
    location: NewProjectSourceLocation,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(TinaShapes.CardCorner),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(resolveSourceLocationLabelRes(location)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(resolveSourceLocationDescRes(location)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun resolveSourceLocationLabelRes(location: NewProjectSourceLocation): Int {
    return when (location) {
        NewProjectSourceLocation.PUBLIC -> Strings.project_source_location_public
        NewProjectSourceLocation.PRIVATE -> Strings.project_source_location_private
    }
}

private fun resolveSourceLocationDescRes(location: NewProjectSourceLocation): Int {
    return when (location) {
        NewProjectSourceLocation.PUBLIC -> Strings.project_source_location_public_desc
        NewProjectSourceLocation.PRIVATE -> Strings.project_source_location_private_desc
    }
}

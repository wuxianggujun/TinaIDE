package com.wuxianggujun.tinaide.ui.wizard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.PackageManagerNavigation
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme

/**
 * 新建项目全屏向导
 *
 * 分步骤引导用户创建新项目：
 * 1. 选择项目模板
 * 2. 配置项目（名称、C++ 标准等）
 */
class NewProjectWizardActivity : ComponentActivity() {

    private val viewModel: NewProjectWizardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initializeSourceLocation(resolveInitialSourceLocation())

        // 设置沉浸式状态栏
        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(!Prefs.useDarkMode)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(!Prefs.useDarkMode)
        }

        setContent {
            TinaIDETheme {
                val pluginManager = remember { PluginManager.getInstance(this@NewProjectWizardActivity) }
                val state by viewModel.state.collectAsState()
                var missingRequiredPackages by remember { mutableStateOf<List<String>?>(null) }
                val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsState()
                val initialTemplateId = remember { intent.getStringExtra(EXTRA_INITIAL_TEMPLATE_ID) }
                val preferPluginTemplate = remember {
                    intent.getBooleanExtra(EXTRA_PREFER_PLUGIN_TEMPLATE, false)
                }
                val allTemplateOptions = remember(enabledPlugins, state.userTemplateOptions) {
                    val pluginTemplateOptions = pluginManager.listProjectTemplateOptions()
                    state.userTemplateOptions + pluginTemplateOptions
                }
                val templateOptions = remember(allTemplateOptions, preferPluginTemplate) {
                    NewProjectWizardSupport.resolveVisibleTemplateOptions(
                        preferPluginTemplate = preferPluginTemplate,
                        templateOptions = allTemplateOptions,
                    )
                }
                LaunchedEffect(templateOptions, initialTemplateId, preferPluginTemplate) {
                    if (!initialTemplateId.isNullOrBlank() || preferPluginTemplate) {
                        viewModel.initializeTemplateSelection(
                            initialTemplateId = initialTemplateId,
                            preferPluginTemplate = preferPluginTemplate,
                            templateOptions = templateOptions,
                        )
                    } else {
                        viewModel.syncTemplateSelection(templateOptions)
                    }
                }
                val selectedTemplate = remember(state.selectedTemplateId, templateOptions) {
                    NewProjectWizardSupport.resolveSelectedTemplate(
                        selectedTemplateId = state.selectedTemplateId,
                        templateOptions = templateOptions,
                    )
                }

                NewProjectWizardScreen(
                    state = state,
                    templateOptions = templateOptions,
                    isPluginProjectMode = preferPluginTemplate,
                    onTemplateSelected = viewModel::setTemplate,
                    onProjectNameChanged = viewModel::setProjectName,
                    onAuthorNameChanged = viewModel::setAuthorName,
                    onSourceLocationSelected = viewModel::setSourceLocation,
                    onCppStandardSelected = viewModel::setCppStandard,
                    onNdkApiLevelSelected = viewModel::setNdkApiLevel,
                    onNextStep = viewModel::nextStep,
                    onPreviousStep = viewModel::previousStep,
                    onCreateProject = {
                        viewModel.createProject(
                            context = this,
                            projectPath = resolveProjectRoot(state.sourceLocation),
                            availableTemplates = templateOptions,
                            onSuccess = { projectDir ->
                                Toast.makeText(
                                    this,
                                    NewProjectWizardSupport.resolveProjectCreatedMessageRes(
                                        selectedTemplate
                                    ).strOr(this),
                                    Toast.LENGTH_LONG
                                ).show()
                                setResult(
                                    RESULT_OK,
                                    Intent().apply {
                                        putExtra(EXTRA_PROJECT_PATH, projectDir.absolutePath)
                                    }
                                )
                                finish()
                            },
                            onError = { errorMessage ->
                                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                            },
                            onMissingPackages = { missingRequiredPackages = it },
                        )
                    },
                    onBack = {
                        if (state.currentStep > 0) {
                            viewModel.previousStep()
                        } else {
                            finish()
                        }
                    }
                )

                missingRequiredPackages?.let { packageIds ->
                    AlertDialog(
                        onDismissRequest = { missingRequiredPackages = null },
                        title = { Text(stringResource(Strings.wizard_missing_packages_title)) },
                        text = {
                            Text(
                                stringResource(
                                    Strings.wizard_missing_packages_message,
                                    packageIds.joinToString(", "),
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    missingRequiredPackages = null
                                    startActivity(
                                        PackageManagerNavigation.createIntent(
                                            this@NewProjectWizardActivity,
                                            packageIds.firstOrNull(),
                                        )
                                    )
                                }
                            ) {
                                Text(stringResource(Strings.wizard_open_package_manager))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { missingRequiredPackages = null }) {
                                Text(stringResource(Strings.btn_cancel))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadUserProjectTemplates(applicationContext)
    }

    private fun resolveInitialSourceLocation(): NewProjectSourceLocation {
        val intentValue = intent.getStringExtra(EXTRA_INITIAL_SOURCE_LOCATION)
        return if (intentValue.isNullOrBlank()) {
            Prefs.projectDefaultSourceLocation
        } else {
            NewProjectSourceLocation.fromValue(intentValue)
        }
    }

    private fun resolveProjectRoot(location: NewProjectSourceLocation): String = when (location) {
        NewProjectSourceLocation.PUBLIC -> ProjectPaths.getPublicProjectsRoot(this).absolutePath
        NewProjectSourceLocation.PRIVATE -> ProjectPaths.getPrivateProjectsRoot(this).absolutePath
    }

    companion object {
        const val EXTRA_INITIAL_SOURCE_LOCATION = "initial_source_location"
        const val EXTRA_INITIAL_TEMPLATE_ID = "initial_template_id"
        const val EXTRA_PREFER_PLUGIN_TEMPLATE = "prefer_plugin_template"
        const val EXTRA_PROJECT_PATH = "project_path"
        private const val DEFAULT_PLUGIN_STARTER_TEMPLATE_ID =
            "plugin:tinaide.plugin.starters:config-basic"

        fun createIntent(
            context: Context,
            initialSourceLocation: NewProjectSourceLocation? = null,
            initialTemplateId: String? = null,
            preferPluginTemplate: Boolean = false,
        ): Intent = Intent(context, NewProjectWizardActivity::class.java).apply {
            initialSourceLocation?.let {
                putExtra(EXTRA_INITIAL_SOURCE_LOCATION, it.value)
            }
            initialTemplateId?.takeIf { it.isNotBlank() }?.let {
                putExtra(EXTRA_INITIAL_TEMPLATE_ID, it)
            }
            if (preferPluginTemplate) {
                putExtra(EXTRA_PREFER_PLUGIN_TEMPLATE, true)
            }
        }

        fun createPluginProjectIntent(
            context: Context,
            initialSourceLocation: NewProjectSourceLocation? = null,
        ): Intent = createIntent(
            context = context,
            initialSourceLocation = initialSourceLocation,
            initialTemplateId = DEFAULT_PLUGIN_STARTER_TEMPLATE_ID,
            preferPluginTemplate = true,
        )
    }
}

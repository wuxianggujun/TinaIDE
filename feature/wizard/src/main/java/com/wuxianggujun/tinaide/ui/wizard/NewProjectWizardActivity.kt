package com.wuxianggujun.tinaide.ui.wizard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.project.BuiltInProjectTemplates
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
                val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsState()
                val initialTemplateId = remember { intent.getStringExtra(EXTRA_INITIAL_TEMPLATE_ID) }
                val preferPluginTemplate = remember {
                    intent.getBooleanExtra(EXTRA_PREFER_PLUGIN_TEMPLATE, false)
                }
                val allTemplateOptions = remember(enabledPlugins, state.userTemplateOptions) {
                    BuiltInProjectTemplates.createOptions(this@NewProjectWizardActivity) +
                        state.userTemplateOptions +
                        pluginManager.listProjectTemplateOptions()
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
                                setResult(RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_PROJECT_PATH, projectDir.absolutePath)
                                })
                                finish()
                            },
                            onError = { errorMessage ->
                                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                            }
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

    private fun resolveProjectRoot(location: NewProjectSourceLocation): String {
        return when (location) {
            NewProjectSourceLocation.PUBLIC -> ProjectPaths.getPublicProjectsRoot(this).absolutePath
            NewProjectSourceLocation.PRIVATE -> ProjectPaths.getPrivateProjectsRoot(this).absolutePath
        }
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
        ): Intent {
            return Intent(context, NewProjectWizardActivity::class.java).apply {
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
        }

        fun createPluginProjectIntent(
            context: Context,
            initialSourceLocation: NewProjectSourceLocation? = null,
        ): Intent {
            return createIntent(
                context = context,
                initialSourceLocation = initialSourceLocation,
                initialTemplateId = DEFAULT_PLUGIN_STARTER_TEMPLATE_ID,
                preferPluginTemplate = true,
            )
        }
    }
}

package com.wuxianggujun.tinaide.ui.wizard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.ProjectRunConfigBootstrapper
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.AndroidApiLevel
import com.wuxianggujun.tinaide.project.BuiltInProjectTemplates
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectCreationFailure
import com.wuxianggujun.tinaide.project.ProjectCreationResult
import com.wuxianggujun.tinaide.project.ProjectCreationService
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import timber.log.Timber

data class NewProjectWizardState(
    val currentStep: Int = 0,
    val selectedTemplateId: String = BuiltInProjectTemplates.defaultTemplateId,
    val projectName: String = "",
    val sourceLocation: NewProjectSourceLocation = NewProjectSourceLocation.PUBLIC,
    val cppStandard: CppStandard = CppStandard.DEFAULT,
    val ndkApiLevel: AndroidApiLevel = AndroidApiLevel.DEFAULT,
    val nameError: String? = null,
    val isCreating: Boolean = false,
    val isNdkTemplate: Boolean = false,
    val showsCppStandard: Boolean = true,
    val userTemplateOptions: List<ProjectTemplateOption> = emptyList(),
)

class NewProjectWizardViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private companion object {
        private const val TAG = "NewProjectWizardViewModel"
    }

    private val _state = MutableStateFlow(NewProjectWizardState())
    val state: StateFlow<NewProjectWizardState> = _state.asStateFlow()
    private var sourceLocationInitialized = false
    private var initialTemplateSelectionApplied = false

    fun initializeSourceLocation(defaultSourceLocation: NewProjectSourceLocation) {
        if (sourceLocationInitialized) return
        sourceLocationInitialized = true
        _state.update { it.copy(sourceLocation = defaultSourceLocation) }
    }

    fun loadUserProjectTemplates(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val options = withContext(ioDispatcher) {
                UserProjectTemplates.listOptions(appContext)
            }
            _state.update { it.copy(userTemplateOptions = options) }
        }
    }

    fun initializeTemplateSelection(
        initialTemplateId: String?,
        preferPluginTemplate: Boolean,
        templateOptions: List<ProjectTemplateOption>,
    ) {
        if (initialTemplateSelectionApplied) {
            syncTemplateSelection(templateOptions)
            return
        }

        val initialTemplate = NewProjectWizardSupport.resolveInitialTemplateSelection(
            initialTemplateId = initialTemplateId,
            preferPluginTemplate = preferPluginTemplate,
            templateOptions = templateOptions,
        ) ?: return

        initialTemplateSelectionApplied = true
        setTemplate(initialTemplate)
    }

    fun syncTemplateSelection(templateOptions: List<ProjectTemplateOption>) {
        val selectedTemplate = NewProjectWizardSupport.resolveSelectedTemplate(
            selectedTemplateId = _state.value.selectedTemplateId,
            templateOptions = templateOptions,
        ) ?: return

        setTemplate(selectedTemplate)
    }

    fun setTemplate(template: ProjectTemplateOption) {
        initialTemplateSelectionApplied = true
        _state.update {
            it.copy(
                selectedTemplateId = template.id,
                isNdkTemplate = template.spec.isNdkTemplate,
                showsCppStandard = NewProjectWizardSupport.shouldShowCppStandard(template),
            )
        }
    }

    fun setSourceLocation(location: NewProjectSourceLocation) {
        _state.update { it.copy(sourceLocation = location) }
    }

    fun setProjectName(name: String) {
        val filtered = name.filter { c ->
            c.isLetterOrDigit() || c == '_' || c == '-'
        }.filter { it.code <= 127 }

        _state.update {
            it.copy(
                projectName = filtered,
                nameError = null
            )
        }
    }

    fun setCppStandard(standard: CppStandard) {
        _state.update { it.copy(cppStandard = standard) }
    }

    fun setNdkApiLevel(apiLevel: AndroidApiLevel) {
        _state.update { it.copy(ndkApiLevel = apiLevel) }
    }

    fun nextStep() {
        _state.update {
            it.copy(currentStep = (it.currentStep + 1).coerceAtMost(1))
        }
    }

    fun previousStep() {
        _state.update {
            it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(0))
        }
    }

    fun createProject(
        context: Context,
        projectPath: String,
        availableTemplates: List<ProjectTemplateOption>,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val currentState = _state.value

        if (currentState.projectName.isBlank()) {
            _state.update {
                it.copy(nameError = Strings.error_project_name_empty.strOr(context))
            }
            return
        }

        val selectedTemplate = availableTemplates.firstOrNull {
            it.id == currentState.selectedTemplateId
        } ?: availableTemplates.firstOrNull()

        if (selectedTemplate == null) {
            onError(Strings.error_template_failed.strOr(context))
            return
        }

        val destDir = File(projectPath, currentState.projectName)
        if (destDir.exists()) {
            _state.update {
                it.copy(nameError = Strings.error_project_exists.strOr(context))
            }
            return
        }

        _state.update { it.copy(isCreating = true) }

        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                ProjectCreationService.createProject(
                    context = context,
                    projectRoot = File(projectPath),
                    projectName = currentState.projectName,
                    templateSpec = selectedTemplate.spec,
                    cppStandard = currentState.cppStandard,
                    ndkApiLevel = if (currentState.isNdkTemplate) currentState.ndkApiLevel else null
                )
            }

            _state.update { it.copy(isCreating = false) }

            when (result) {
                is ProjectCreationResult.Success -> {
                    withContext(ioDispatcher) {
                        runCatching {
                            ProjectRunConfigBootstrapper.initializeIfMissing(result.projectDir)
                        }.onFailure { throwable ->
                            Timber.tag(TAG).w(
                                throwable,
                                "Failed to initialize project run config: %s",
                                result.projectDir.absolutePath
                            )
                        }
                    }
                    onSuccess(result.projectDir)
                }
                is ProjectCreationResult.Failure -> {
                    val message = when (result.reason) {
                        ProjectCreationFailure.EMPTY_NAME -> Strings.error_project_name_empty.strOr(context)
                        ProjectCreationFailure.INVALID_NAME -> Strings.error_project_name_invalid_chars.strOr(context)
                        ProjectCreationFailure.ALREADY_EXISTS -> Strings.error_project_exists.strOr(context)
                        ProjectCreationFailure.PROJECT_ROOT_UNAVAILABLE,
                        ProjectCreationFailure.CREATE_DIRECTORY_FAILED -> Strings.error_create_project_dir.strOr(context)
                        ProjectCreationFailure.TEMPLATE_INSTALL_FAILED -> Strings.toast_create_project_failed.strOr(context)
                    }
                    onError(message)
                }
            }
        }
    }
}

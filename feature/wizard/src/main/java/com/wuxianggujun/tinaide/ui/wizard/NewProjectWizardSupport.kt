package com.wuxianggujun.tinaide.ui.wizard

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption

internal enum class ProjectTemplateCategory(@param:StringRes val labelRes: Int) {
    NATIVE(Strings.wizard_category_native_projects),
    PLUGIN(Strings.wizard_category_plugin_projects),
    USER(Strings.wizard_category_user_templates),
}

internal data class ProjectTemplateCategoryGroup(
    val category: ProjectTemplateCategory,
    val options: List<ProjectTemplateOption>,
)

internal object NewProjectWizardSupport {

    fun resolveSelectedTemplate(
        selectedTemplateId: String,
        templateOptions: List<ProjectTemplateOption>,
    ): ProjectTemplateOption? = templateOptions.firstOrNull { option -> option.id == selectedTemplateId }
        ?: templateOptions.firstOrNull()

    fun resolveVisibleTemplateOptions(
        preferPluginTemplate: Boolean,
        templateOptions: List<ProjectTemplateOption>,
    ): List<ProjectTemplateOption> = if (preferPluginTemplate) {
        templateOptions.filter { option -> isPluginTemplate(option) }
    } else {
        templateOptions
    }

    fun resolveInitialTemplateSelection(
        initialTemplateId: String?,
        preferPluginTemplate: Boolean,
        templateOptions: List<ProjectTemplateOption>,
    ): ProjectTemplateOption? {
        if (templateOptions.isEmpty()) return null

        if (!initialTemplateId.isNullOrBlank()) {
            val exactTemplate = templateOptions.firstOrNull { option -> option.id == initialTemplateId }
            if (exactTemplate != null) return exactTemplate
        }

        return if (preferPluginTemplate) {
            templateOptions.firstOrNull { option -> isPluginTemplate(option) }
        } else {
            null
        }
    }

    fun resolveTemplateCategory(option: ProjectTemplateOption?): ProjectTemplateCategory? {
        if (option == null) return null
        return when {
            isUserTemplate(option) -> ProjectTemplateCategory.USER
            isPluginTemplate(option) -> ProjectTemplateCategory.PLUGIN
            else -> ProjectTemplateCategory.NATIVE
        }
    }

    fun resolveTemplateCategoryGroups(
        templateOptions: List<ProjectTemplateOption>,
    ): List<ProjectTemplateCategoryGroup> = ProjectTemplateCategory.entries.mapNotNull { category ->
        val options = templateOptions.filter { option ->
            resolveTemplateCategory(option) == category
        }
        options.takeIf { it.isNotEmpty() }?.let {
            ProjectTemplateCategoryGroup(category = category, options = it)
        }
    }

    fun resolveSelectedTemplateCategory(
        selectedTemplateId: String,
        groups: List<ProjectTemplateCategoryGroup>,
    ): ProjectTemplateCategory? = groups.firstOrNull { group ->
        group.options.any { option -> option.id == selectedTemplateId }
    }?.category

    fun resolveFirstTemplateInCategory(
        category: ProjectTemplateCategory,
        groups: List<ProjectTemplateCategoryGroup>,
    ): ProjectTemplateOption? = groups.firstOrNull { group -> group.category == category }
        ?.options
        ?.firstOrNull()

    fun isPluginTemplate(option: ProjectTemplateOption?): Boolean = option?.spec?.buildSystem == ProjectBuildSystem.PLUGIN

    fun isUserTemplate(option: ProjectTemplateOption?): Boolean = option?.id?.startsWith(UserProjectTemplates.TEMPLATE_ID_PREFIX) == true

    fun shouldShowCppStandard(option: ProjectTemplateOption?): Boolean {
        val language = option?.spec?.primaryLanguage ?: return true
        return language == ProjectLanguage.C || language == ProjectLanguage.CPP
    }

    fun canUseVcpkg(option: ProjectTemplateOption?): Boolean = option?.spec?.buildSystem == ProjectBuildSystem.CMAKE

    @StringRes
    fun resolveTemplateBadgeRes(option: ProjectTemplateOption?): Int? = when {
        isPluginTemplate(option) -> Strings.wizard_plugin_template_badge
        isUserTemplate(option) -> Strings.wizard_user_template_badge
        else -> null
    }

    @StringRes
    fun resolveTemplateCardGuideRes(option: ProjectTemplateOption?): Int? = when {
        isPluginTemplate(option) -> Strings.wizard_plugin_template_card_hint
        isUserTemplate(option) -> Strings.wizard_user_template_card_hint
        else -> null
    }

    @StringRes
    fun resolveConfigurationGuideTitleRes(option: ProjectTemplateOption?): Int? = if (isPluginTemplate(option)) Strings.wizard_plugin_template_config_guide_title else null

    @StringRes
    fun resolveConfigurationGuideBodyRes(option: ProjectTemplateOption?): Int? = if (isPluginTemplate(option)) Strings.wizard_plugin_template_config_guide_body else null

    @StringRes
    fun resolveProjectCreatedMessageRes(option: ProjectTemplateOption?): Int = if (isPluginTemplate(option)) {
        Strings.success_plugin_project_created
    } else {
        Strings.success_project_created
    }
}

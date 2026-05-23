package com.wuxianggujun.tinaide.ui.wizard

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption

internal object NewProjectWizardSupport {

    fun resolveSelectedTemplate(
        selectedTemplateId: String,
        templateOptions: List<ProjectTemplateOption>,
    ): ProjectTemplateOption? {
        return templateOptions.firstOrNull { option -> option.id == selectedTemplateId }
            ?: templateOptions.firstOrNull()
    }

    fun resolveVisibleTemplateOptions(
        preferPluginTemplate: Boolean,
        templateOptions: List<ProjectTemplateOption>,
    ): List<ProjectTemplateOption> {
        return if (preferPluginTemplate) {
            templateOptions.filter { option -> isPluginTemplate(option) }
        } else {
            templateOptions
        }
    }

    fun resolveInitialTemplateSelection(
        initialTemplateId: String?,
        preferPluginTemplate: Boolean,
        templateOptions: List<ProjectTemplateOption>,
    ): ProjectTemplateOption? {
        if (templateOptions.isEmpty()) return null

        if (!initialTemplateId.isNullOrBlank()) {
            templateOptions
                .firstOrNull { option -> option.id == initialTemplateId }
                ?.let { option -> return option }
        }

        return if (preferPluginTemplate) {
            templateOptions.firstOrNull { option -> isPluginTemplate(option) }
        } else {
            null
        }
    }

    fun isPluginTemplate(option: ProjectTemplateOption?): Boolean {
        return option?.spec?.buildSystem == ProjectBuildSystem.PLUGIN
    }

    fun isUserTemplate(option: ProjectTemplateOption?): Boolean {
        return option?.id?.startsWith(UserProjectTemplates.TEMPLATE_ID_PREFIX) == true
    }

    fun shouldShowCppStandard(option: ProjectTemplateOption?): Boolean {
        val language = option?.spec?.primaryLanguage ?: return true
        return language == ProjectLanguage.C || language == ProjectLanguage.CPP
    }

    @StringRes
    fun resolveTemplateBadgeRes(option: ProjectTemplateOption?): Int? {
        return when {
            isPluginTemplate(option) -> Strings.wizard_plugin_template_badge
            isUserTemplate(option) -> Strings.wizard_user_template_badge
            else -> null
        }
    }

    @StringRes
    fun resolveTemplateCardGuideRes(option: ProjectTemplateOption?): Int? {
        return when {
            isPluginTemplate(option) -> Strings.wizard_plugin_template_card_hint
            isUserTemplate(option) -> Strings.wizard_user_template_card_hint
            else -> null
        }
    }

    @StringRes
    fun resolveConfigurationGuideTitleRes(option: ProjectTemplateOption?): Int? {
        return if (isPluginTemplate(option)) Strings.wizard_plugin_template_config_guide_title else null
    }

    @StringRes
    fun resolveConfigurationGuideBodyRes(option: ProjectTemplateOption?): Int? {
        return if (isPluginTemplate(option)) Strings.wizard_plugin_template_config_guide_body else null
    }

    @StringRes
    fun resolveProjectCreatedMessageRes(option: ProjectTemplateOption?): Int {
        return if (isPluginTemplate(option)) {
            Strings.success_plugin_project_created
        } else {
            Strings.success_project_created
        }
    }
}

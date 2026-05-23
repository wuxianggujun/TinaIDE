package com.wuxianggujun.tinaide.ui.wizard

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import java.io.File
import org.junit.Test

class NewProjectWizardSupportTest {

    @Test
    fun pluginTemplateHelpers_shouldResolvePluginGuidance() {
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(NewProjectWizardSupport.isPluginTemplate(pluginTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(pluginTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_badge)
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_card_hint)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_config_guide_title)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_config_guide_body)
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(pluginTemplate))
            .isEqualTo(Strings.success_plugin_project_created)
    }

    @Test
    fun nonPluginTemplateHelpers_shouldKeepDefaultWizardBehavior() {
        val cppTemplate = template(
            id = "builtin:cpp",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(NewProjectWizardSupport.isPluginTemplate(cppTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(cppTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(cppTemplate))
            .isEqualTo(Strings.success_project_created)
    }

    @Test
    fun userTemplateHelpers_shouldResolveCustomBadgeAndGuide() {
        val userTemplate = template(
            id = "${UserProjectTemplates.TEMPLATE_ID_PREFIX}cmake-demo",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(NewProjectWizardSupport.isUserTemplate(userTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.isPluginTemplate(userTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(userTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(userTemplate))
            .isEqualTo(Strings.wizard_user_template_badge)
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(userTemplate))
            .isEqualTo(Strings.wizard_user_template_card_hint)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(userTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(userTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(userTemplate))
            .isEqualTo(Strings.success_project_created)
    }

    @Test
    fun resolveSelectedTemplate_shouldFallbackToFirstOption() {
        val first = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val second = template(
            id = "plugin:second",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveSelectedTemplate(
                selectedTemplateId = "plugin:second",
                templateOptions = listOf(first, second),
            )
        ).isEqualTo(second)
        assertThat(
            NewProjectWizardSupport.resolveSelectedTemplate(
                selectedTemplateId = "missing",
                templateOptions = listOf(first, second),
            )
        ).isEqualTo(first)
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldKeepAllTemplatesByDefault() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = false,
                templateOptions = listOf(builtinTemplate, pluginTemplate),
            )
        ).containsExactly(builtinTemplate, pluginTemplate).inOrder()
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldOnlyShowPluginTemplatesForPluginEntry() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = true,
                templateOptions = listOf(builtinTemplate, pluginTemplate),
            )
        ).containsExactly(pluginTemplate)
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldReturnEmptyWhenPluginEntryHasNoPluginTemplates() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = true,
                templateOptions = listOf(builtinTemplate),
            )
        ).isEmpty()
    }

    @Test
    fun resolveInitialTemplateSelection_shouldUseExplicitTemplateFirst() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = builtinTemplate.id,
                preferPluginTemplate = true,
                templateOptions = listOf(builtinTemplate, pluginTemplate),
            )
        ).isEqualTo(builtinTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldPreferPluginTemplate() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = null,
                preferPluginTemplate = true,
                templateOptions = listOf(builtinTemplate, pluginTemplate),
            )
        ).isEqualTo(pluginTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldFallbackToFirstPluginWhenTargetMissing() {
        val pluginTemplate = template(
            id = "plugin:fallback",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = "plugin:missing",
                preferPluginTemplate = true,
                templateOptions = listOf(pluginTemplate),
            )
        ).isEqualTo(pluginTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldWaitWhenTargetMissing() {
        val builtinTemplate = template(
            id = "builtin:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = "plugin:missing",
                preferPluginTemplate = false,
                templateOptions = listOf(builtinTemplate),
            )
        ).isNull()
    }

    private fun template(
        id: String,
        buildSystem: ProjectBuildSystem,
        primaryLanguage: ProjectLanguage,
    ): ProjectTemplateOption {
        return ProjectTemplateOption(
            id = id,
            displayName = id,
            description = id,
            spec = ProjectTemplateSpec.Zip(
                id = id,
                zipFile = File("$id.zip"),
                buildSystem = buildSystem,
                primaryLanguage = primaryLanguage,
            ),
        )
    }
}

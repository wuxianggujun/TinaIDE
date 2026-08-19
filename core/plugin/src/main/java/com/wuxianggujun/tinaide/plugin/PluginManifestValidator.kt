package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.lsp.LspServerCommandPolicy
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectTemplateArchivePolicy
import java.io.File

internal object PluginManifestValidator {
    internal const val SUPPORTED_API_VERSION = 1
    private const val MAX_PANEL_COUNT = 16
    private const val MAX_PANEL_ID_LENGTH = 128
    private const val MAX_PANEL_TITLE_LENGTH = 128

    private val pluginIdPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val panelIdPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val activationEventPattern = Regex("^onLanguage:([a-zA-Z0-9][a-zA-Z0-9._+-]*)$")
    private val supportedLspToolchainTypes = setOf("system", "download", "pip", "npm")

    fun validate(
        context: Context,
        manifest: PluginManifest,
        pluginDir: File,
    ) {
        validatePluginId(context, manifest.id)
        require(manifest.name.isNotBlank()) { Strings.plugin_error_name_empty.strOr(context) }
        require(manifest.version.isNotBlank()) { Strings.plugin_error_version_empty.strOr(context) }
        require(manifest.apiVersion == SUPPORTED_API_VERSION) {
            Strings.plugin_error_api_version_unsupported.strOr(
                context,
                manifest.apiVersion,
                SUPPORTED_API_VERSION
            )
        }
        validatePermissionIds(context, manifest.permissions)
        validatePermissionIds(context, manifest.optionalPermissions)
        validateConfiguration(context, manifest.configuration)
        validateLocales(context, manifest.locales, pluginDir)
        validatePanels(context, manifest)
        validateActivationEvents(context, manifest)
        validateLspContributions(context, manifest)

        if (manifest.type.equals(PluginTypes.SCRIPT, ignoreCase = true) ||
            manifest.type.equals(PluginTypes.HYBRID, ignoreCase = true)
        ) {
            val mainEntry = manifest.main ?: "main.lua"
            require(isSafePluginRelativePath(mainEntry)) {
                Strings.plugin_error_main_path_invalid.strOr(context, mainEntry)
            }
            require(File(pluginDir, mainEntry).exists()) {
                Strings.plugin_error_main_file_not_exist.strOr(context, mainEntry)
            }
        }

        manifest.contributions?.themes?.forEach { path ->
            require(isSafePluginRelativePath(path)) {
                Strings.plugin_error_themes_path_invalid.strOr(context, path)
            }
            require(File(pluginDir, path).exists()) {
                Strings.plugin_error_theme_file_not_exist.strOr(context, path)
            }
        }

        manifest.contributions?.snippets?.forEach { path ->
            require(isSafePluginRelativePath(path)) {
                Strings.plugin_error_snippets_path_invalid.strOr(context, path)
            }
            require(File(pluginDir, path).exists()) {
                Strings.plugin_error_snippet_file_not_exist.strOr(context, path)
            }
        }

        manifest.contributions?.keybindings?.forEach { path ->
            require(isSafePluginRelativePath(path)) {
                Strings.plugin_error_keybindings_path_invalid.strOr(context, path)
            }
            require(File(pluginDir, path).isFile) {
                Strings.plugin_error_keybindings_file_not_exist.strOr(context, path)
            }
        }

        val projectTemplates = manifest.contributions?.projectTemplates.orEmpty()
        val duplicateTemplateIds = projectTemplates.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateTemplateIds.isEmpty()) {
            Strings.plugin_error_project_template_duplicate_id.strOr(
                context,
                duplicateTemplateIds.joinToString(", "),
            )
        }
        projectTemplates.forEach { template ->
            require(template.id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
            require(template.name.isNotBlank()) { Strings.plugin_error_name_empty.strOr(context) }
            require(isSafePluginRelativePath(template.templatePath)) {
                Strings.plugin_error_project_template_path_invalid.strOr(context, template.templatePath)
            }
            val templateFile = File(pluginDir, template.templatePath)
            require(templateFile.isFile) {
                Strings.plugin_error_project_template_file_not_exist.strOr(context, template.templatePath)
            }
            runCatching { ProjectTemplateArchivePolicy.validate(templateFile) }
                .getOrElse { error ->
                    throw IllegalArgumentException(
                        Strings.plugin_error_project_template_archive_invalid.strOr(
                            context,
                            template.templatePath,
                        ),
                        error,
                    )
                }
            require(parseProjectBuildSystem(template.buildSystem) != null) {
                Strings.plugin_error_project_template_build_system_invalid.strOr(
                    context,
                    template.buildSystem
                )
            }
            val invalidRequiredPackages = template.requiredPackages.filterNot(RegistryPackageId::isValid)
            require(invalidRequiredPackages.isEmpty()) {
                Strings.plugin_error_project_template_required_package_invalid.strOr(
                    context,
                    invalidRequiredPackages.joinToString(", "),
                )
            }
            require(template.requiredPackages.distinct().size == template.requiredPackages.size) {
                Strings.plugin_error_project_template_required_package_duplicate.strOr(context)
            }
        }

        manifest.contributions?.apkExports?.forEach { apkExport ->
            require(apkExport.id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
            require(apkExport.name.isNotBlank()) { Strings.plugin_error_name_empty.strOr(context) }
            require(isSafePluginRelativePath(apkExport.templatePath)) {
                Strings.plugin_error_apk_export_template_path_invalid.strOr(
                    context,
                    apkExport.templatePath
                )
            }
            require(File(pluginDir, apkExport.templatePath).exists()) {
                Strings.plugin_error_apk_export_template_file_not_exist.strOr(
                    context,
                    apkExport.templatePath
                )
            }
        }
    }

    private fun validatePanels(
        context: Context,
        manifest: PluginManifest,
    ) {
        val panels = manifest.contributions?.panels.orEmpty()
        if (panels.isEmpty()) return
        require(manifest.type.equals(PluginTypes.SCRIPT, ignoreCase = true) ||
            manifest.type.equals(PluginTypes.HYBRID, ignoreCase = true)) {
            Strings.plugin_error_panels_type_invalid.strOr(context)
        }
        require(panels.size <= MAX_PANEL_COUNT) {
            Strings.plugin_error_panels_too_many.strOr(context, MAX_PANEL_COUNT)
        }
        val duplicateIds = panels.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            Strings.plugin_error_panels_duplicate_id.strOr(context, duplicateIds.joinToString(", "))
        }
        panels.forEach { panel ->
            require(panel.id.length <= MAX_PANEL_ID_LENGTH && panelIdPattern.matches(panel.id)) {
                Strings.plugin_error_panel_id_invalid.strOr(context, panel.id)
            }
            require(panel.title.isNotBlank() && panel.title.length <= MAX_PANEL_TITLE_LENGTH) {
                Strings.plugin_error_panel_title_invalid.strOr(context, panel.id)
            }
        }
    }

    private fun validateActivationEvents(
        context: Context,
        manifest: PluginManifest,
    ) {
        val events = manifest.activationEvents.orEmpty()
        if (events.isEmpty()) return
        require(manifest.type.equals(PluginTypes.LSP, ignoreCase = true)) {
            Strings.plugin_error_activation_events_type_invalid.strOr(context)
        }
        require(events.distinct().size == events.size) {
            Strings.plugin_error_activation_events_duplicate.strOr(context)
        }
        val contributedLanguages = manifest.contributions?.languageServers.orEmpty()
            .flatMap { it.languages }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        events.forEach { event ->
            val languageId = activationEventPattern.matchEntire(event)?.groupValues?.get(1)
            require(languageId != null) {
                Strings.plugin_error_activation_event_invalid.strOr(context, event)
            }
            require(languageId in contributedLanguages) {
                Strings.plugin_error_activation_event_language_missing.strOr(context, languageId)
            }
        }
    }

    private fun validateLspContributions(
        context: Context,
        manifest: PluginManifest,
    ) {
        if (!manifest.type.equals(PluginTypes.LSP, ignoreCase = true)) return
        val contributions = manifest.contributions
        val languageServers = contributions?.languageServers.orEmpty()
        require(languageServers.isNotEmpty()) {
            Strings.plugin_error_lsp_language_servers_empty.strOr(context)
        }
        languageServers.forEach { server ->
            require(server.id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
            require(server.name.isNotBlank()) { Strings.plugin_error_name_empty.strOr(context) }
            require(server.languages.any { it.isNotBlank() }) {
                Strings.plugin_error_lsp_languages_empty.strOr(context, server.id)
            }
            require(server.fileExtensions.any { it.isNotBlank() } || !server.filePatterns.isNullOrEmpty()) {
                Strings.plugin_error_lsp_file_matches_empty.strOr(context, server.id)
            }
            require(server.server.type.trim().lowercase() == "stdio") {
                Strings.plugin_error_lsp_server_type_invalid.strOr(context, server.server.type)
            }
            require(!server.server.command.isNullOrBlank()) {
                Strings.plugin_error_lsp_server_command_empty.strOr(context, server.id)
            }
            require(
                LspServerCommandPolicy.isValid(
                    server.server.command,
                    server.server.args.orEmpty(),
                    server.server.env.orEmpty(),
                ),
            ) {
                Strings.plugin_error_lsp_server_command_invalid.strOr(context, server.id)
            }
        }
        contributions?.toolchains.orEmpty().forEach { toolchain ->
            val type = toolchain.type.trim().lowercase()
            require(type in supportedLspToolchainTypes) {
                Strings.plugin_error_lsp_toolchain_type_invalid.strOr(context, toolchain.type)
            }
            if (type == "system") {
                val hasGenericPackages = toolchain.packages.orEmpty().any { it.isNotBlank() }
                val hasManagerPackages = toolchain.packagesByManager.orEmpty().values.flatten().any { it.isNotBlank() }
                require(hasGenericPackages || hasManagerPackages) {
                    Strings.plugin_error_lsp_toolchain_system_packages_empty.strOr(context, toolchain.id)
                }
            }
        }
    }

    fun parseProjectBuildSystem(value: String): ProjectBuildSystem? = when (value.trim().lowercase()) {
        "single_file", "single-file", "singlefile" -> ProjectBuildSystem.SINGLE_FILE
        "cmake" -> ProjectBuildSystem.CMAKE
        "make" -> ProjectBuildSystem.MAKE
        "plugin", "tina_plugin", "tina-plugin", "tinaplugin" -> ProjectBuildSystem.PLUGIN
        else -> null
    }

    private fun validatePermissionIds(
        context: Context,
        permissions: List<String>?,
    ) {
        val unknownIds = PluginPermission.findUnknownIds(permissions)
        require(unknownIds.isEmpty()) {
            Strings.plugin_error_permission_unknown.strOr(context, unknownIds.joinToString(", "))
        }
    }

    private fun validateLocales(
        context: Context,
        locales: PluginLocales?,
        pluginDir: File,
    ) {
        if (locales == null) return
        locales.files.forEach { (localeKey, path) ->
            require(localeKey.isNotBlank()) {
                Strings.plugin_error_locale_key_invalid.strOr(context, localeKey)
            }
            require(isSafePluginRelativePath(path)) {
                Strings.plugin_error_locale_path_invalid.strOr(context, path)
            }
            require(path.replace('\\', '/').startsWith("locales/")) {
                Strings.plugin_error_locale_path_invalid.strOr(context, path)
            }
            val localeFile = File(pluginDir, path)
            require(localeFile.isFile) {
                Strings.plugin_error_locale_file_not_exist.strOr(context, path)
            }
            runCatching {
                JsonSerializer.decodeFromFile<PluginLocalization>(localeFile)
            }.getOrElse { throwable ->
                throw IllegalArgumentException(
                    Strings.plugin_error_locale_file_invalid.strOr(
                        context,
                        path,
                        throwable.message.orEmpty()
                    ),
                    throwable
                )
            }
        }
    }

    private fun validateConfiguration(
        context: Context,
        configuration: PluginConfiguration?,
    ) {
        val issues = PluginConfigurationSchema.validateConfiguration(configuration)
        require(issues.isEmpty()) {
            issues.joinToString(separator = "; ") { issue ->
                when (issue.reason) {
                    PluginConfigurationValidationReason.INVALID_KEY ->
                        Strings.plugin_error_configuration_key_invalid.strOr(context, issue.key)
                    PluginConfigurationValidationReason.UNSUPPORTED_TYPE ->
                        Strings.plugin_error_configuration_type_invalid.strOr(
                            context,
                            issue.key,
                            issue.value.orEmpty(),
                        )
                    PluginConfigurationValidationReason.INVALID_DEFAULT ->
                        Strings.plugin_error_configuration_default_invalid.strOr(
                            context,
                            issue.key,
                            issue.value.orEmpty(),
                        )
                    PluginConfigurationValidationReason.INVALID_ENUM ->
                        Strings.plugin_error_configuration_enum_invalid.strOr(
                            context,
                            issue.key,
                            issue.value.orEmpty(),
                        )
                }
            }
        }
    }

    private fun validatePluginId(
        context: Context,
        id: String,
    ) {
        require(id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
        require(pluginIdPattern.matches(id)) { Strings.plugin_error_id_invalid.strOr(context, id) }
        require(!id.contains("..")) { Strings.plugin_error_id_contains_dotdot.strOr(context, id) }
        require(!id.contains(File.separatorChar)) {
            Strings.plugin_error_id_contains_separator.strOr(context, id)
        }
    }
}

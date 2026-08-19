package com.wuxianggujun.tinaide.plugin

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.lsp.LspServerConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspServerConnectionConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class PluginManagerManifestValidationTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `validateManifest should reject unsupported apiVersion`() {
        val pluginDir = createScriptPluginDir("validate_api_version")
        val manifest = PluginManifest(
            id = "test.plugin.api-version",
            name = "Validate API Version",
            version = "1.0.0",
            apiVersion = 2,
            type = "script"
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error).isNotNull()
    }

    @Test
    fun `validateManifest should reject unknown permissions`() {
        val pluginDir = createScriptPluginDir("validate_permissions")
        val manifest = PluginManifest(
            id = "test.plugin.permissions",
            name = "Validate Permissions",
            version = "1.0.0",
            type = "script",
            permissions = listOf("workspace.unknown")
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error).isNotNull()
    }

    @Test
    fun `validateManifest should accept unique panels for script plugins`() {
        val pluginDir = createScriptPluginDir("validate_panels")
        val manifest = PluginManifest(
            id = "test.plugin.panels",
            name = "Validate Panels",
            version = "1.0.0",
            type = PluginTypes.SCRIPT,
            contributions = PluginContributions(
                panels = listOf(
                    PluginPanel("status", "Status"),
                    PluginPanel("trace", "Trace"),
                ),
            ),
        )

        PluginManifestValidator.validate(context, manifest, pluginDir)
    }

    @Test
    fun `validateManifest should reject duplicate or non-script panels`() {
        val scriptDir = createScriptPluginDir("validate_duplicate_panels")
        val duplicate = PluginManifest(
            id = "test.plugin.duplicate-panels",
            name = "Duplicate Panels",
            version = "1.0.0",
            type = PluginTypes.SCRIPT,
            contributions = PluginContributions(
                panels = listOf(PluginPanel("status", "One"), PluginPanel("status", "Two")),
            ),
        )
        val configDir = createConfigPluginDir("validate_config_panels")
        val config = PluginManifest(
            id = "test.plugin.config-panels",
            name = "Config Panels",
            version = "1.0.0",
            type = PluginTypes.CONFIG,
            contributions = PluginContributions(panels = listOf(PluginPanel("status", "Status"))),
        )

        assertThat(runValidationFailure(duplicate, scriptDir).message).contains("status")
        assertThat(runValidationFailure(config, configDir).message).contains("script")
    }

    @Test
    fun `validateManifest should reject invalid plugin configuration defaults`() {
        val pluginDir = createScriptPluginDir("validate_configuration")
        val manifest = PluginManifest(
            id = "test.plugin.configuration",
            name = "Validate Configuration",
            version = "1.0.0",
            type = "script",
            configuration = PluginConfiguration(
                properties = mapOf(
                    "feature.enabled" to PluginConfigurationProperty(
                        type = "boolean",
                        default = JsonPrimitive("true"),
                    ),
                ),
            ),
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("feature.enabled")
    }

    @Test
    fun `validateManifest should require script main entry to exist`() {
        val pluginDir = File(context.cacheDir, "validate_missing_main").apply {
            deleteRecursively()
            mkdirs()
        }
        val manifest = PluginManifest(
            id = "test.plugin.main-file",
            name = "Validate Main File",
            version = "1.0.0",
            type = "script",
            main = "missing.lua"
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error).isNotNull()
    }

    @Test
    fun `validateManifest should reject legacy lsp package manager toolchain type`() {
        val pluginDir = createLspPluginDir("validate_lsp_legacy_type")
        val manifest = createLspManifest(
            toolchains = listOf(
                LspToolchainConfig(
                    id = "python3",
                    name = "Python 3",
                    type = "apt",
                    packages = listOf("python3"),
                )
            )
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("apt")
    }

    @Test
    fun `validateManifest should reject system lsp toolchain without packages`() {
        val pluginDir = createLspPluginDir("validate_lsp_empty_system_packages")
        val manifest = createLspManifest(
            toolchains = listOf(
                LspToolchainConfig(
                    id = "python3",
                    name = "Python 3",
                    type = "system",
                )
            )
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("python3")
    }

    @Test
    fun `validateManifest should reject lsp socket and websocket transports`() {
        listOf("socket", "websocket").forEach { transport ->
            val pluginDir = createLspPluginDir("validate_lsp_transport_$transport")
            val manifest = createLspManifest(
                toolchains = emptyList(),
                serverType = transport,
            )

            val error = runValidationFailure(manifest, pluginDir)

            assertThat(error.message).contains(transport)
            assertThat(error.message).contains("stdio")
        }
    }

    @Test
    fun `validateManifest should accept lsp system toolchain package manager overrides`() {
        val pluginDir = createLspPluginDir("validate_lsp_system_packages")
        val manifest = createLspManifest(
            toolchains = listOf(
                LspToolchainConfig(
                    id = "python3",
                    name = "Python 3",
                    type = "system",
                    packagesByManager = mapOf(
                        "apk" to listOf("python3", "py3-pip"),
                        "apt" to listOf("python3", "python3-pip"),
                    ),
                )
            )
        )

        PluginManifestValidator.validate(
            context = context,
            manifest = manifest,
            pluginDir = pluginDir,
        )
    }

    @Test
    fun `validateManifest should accept matching lsp language activation event`() {
        val pluginDir = createLspPluginDir("validate_lsp_activation")
        val manifest = createLspManifest(
            toolchains = emptyList(),
            activationEvents = listOf("onLanguage:python"),
        )

        PluginManifestValidator.validate(context, manifest, pluginDir)
    }

    @Test
    fun `validateManifest should reject unknown lsp language activation event`() {
        val pluginDir = createLspPluginDir("validate_lsp_activation_unknown")
        val manifest = createLspManifest(
            toolchains = emptyList(),
            activationEvents = listOf("onLanguage:javascript"),
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("javascript")
    }

    @Test
    fun `validateManifest should reject activation events on script plugins`() {
        val pluginDir = createScriptPluginDir("validate_script_activation")
        val manifest = PluginManifest(
            id = "test.plugin.script-activation",
            name = "Script Activation",
            version = "1.0.0",
            type = PluginTypes.SCRIPT,
            activationEvents = listOf("onLanguage:python"),
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("LSP")
    }

    @Test
    fun `validateManifest should accept valid locale files`() {
        val pluginDir = createConfigPluginDir("validate_locale_valid")
        writeLocale(pluginDir, "zh-CN.json", """{"name":"中文插件"}""")
        val manifest = createConfigManifest(
            locales = PluginLocales(
                default = "en",
                files = mapOf(
                    "zh-CN" to "locales/zh-CN.json",
                    "zh" to "locales/zh-CN.json",
                )
            )
        )

        PluginManifestValidator.validate(
            context = context,
            manifest = manifest,
            pluginDir = pluginDir,
        )
    }

    @Test
    fun `validateManifest should reject missing locale file`() {
        val pluginDir = createConfigPluginDir("validate_locale_missing")
        val manifest = createConfigManifest(
            locales = PluginLocales(
                files = mapOf("zh-CN" to "locales/zh-CN.json")
            )
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("zh-CN.json")
    }

    @Test
    fun `validateManifest should reject locale path outside locales directory`() {
        val pluginDir = createConfigPluginDir("validate_locale_unsafe")
        writeLocale(pluginDir, "zh-CN.json", """{"name":"中文插件"}""")
        val manifest = createConfigManifest(
            locales = PluginLocales(
                files = mapOf("zh-CN" to "../zh-CN.json")
            )
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("../zh-CN.json")
    }

    @Test
    fun `validateManifest should reject invalid locale json`() {
        val pluginDir = createConfigPluginDir("validate_locale_invalid_json")
        writeLocale(pluginDir, "zh-CN.json", """{"name":""")
        val manifest = createConfigManifest(
            locales = PluginLocales(
                files = mapOf("zh-CN" to "locales/zh-CN.json")
            )
        )

        val error = runValidationFailure(manifest, pluginDir)

        assertThat(error.message).contains("zh-CN.json")
    }

    @Test
    fun `validateManifest should accept valid project template dependencies`() {
        val pluginDir = createConfigPluginDir("validate_project_template")
        createProjectTemplateZip(pluginDir)
        val manifest = createProjectTemplateManifest(requiredPackages = listOf("sdl3", "sdl3-image"))

        PluginManifestValidator.validate(context, manifest, pluginDir)
    }

    @Test
    fun `validateManifest should reject duplicate project template ids`() {
        val pluginDir = createConfigPluginDir("validate_duplicate_project_templates")
        createProjectTemplateZip(pluginDir)
        val template = createProjectTemplateManifest().contributions!!.projectTemplates!!.single()
        val manifest = createProjectTemplateManifest().copy(
            contributions = PluginContributions(projectTemplates = listOf(template, template))
        )

        assertThat(runValidationFailure(manifest, pluginDir).message).contains(template.id)
    }

    @Test
    fun `validateManifest should reject invalid required package id`() {
        val pluginDir = createConfigPluginDir("validate_project_template_package")
        createProjectTemplateZip(pluginDir)
        val manifest = createProjectTemplateManifest(requiredPackages = listOf("../sdl3"))

        assertThat(runValidationFailure(manifest, pluginDir).message).contains("../sdl3")
    }

    @Test
    fun `validateManifest should reject invalid project template archive`() {
        val pluginDir = createConfigPluginDir("validate_project_template_archive")
        File(pluginDir, "templates/project.zip").apply {
            parentFile?.mkdirs()
            writeText("not a zip")
        }

        assertThat(runValidationFailure(createProjectTemplateManifest(), pluginDir).message)
            .contains("templates/project.zip")
    }

    private fun createScriptPluginDir(name: String): File = File(context.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
        File(this, "main.lua").writeText("print('hello')")
    }

    private fun createConfigPluginDir(name: String): File = File(context.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    private fun createLspPluginDir(name: String): File = File(context.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    private fun createConfigManifest(
        locales: PluginLocales? = null,
    ): PluginManifest = PluginManifest(
        id = "test.plugin.config",
        name = "Validate Config Plugin",
        version = "1.0.0",
        type = "config",
        locales = locales,
    )

    private fun createProjectTemplateManifest(
        requiredPackages: List<String> = emptyList(),
    ): PluginManifest = PluginManifest(
        id = "test.plugin.project-template",
        name = "Validate Project Template",
        version = "1.0.0",
        type = PluginTypes.CONFIG,
        contributions = PluginContributions(
            projectTemplates = listOf(
                PluginProjectTemplate(
                    id = "cmake",
                    name = "CMake",
                    description = "CMake project",
                    templatePath = "templates/project.zip",
                    buildSystem = "cmake",
                    requiredPackages = requiredPackages,
                )
            )
        ),
    )

    private fun createProjectTemplateZip(pluginDir: File) {
        val templateFile = File(pluginDir, "templates/project.zip")
        templateFile.parentFile?.mkdirs()
        ZipOutputStream(templateFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("CMakeLists.txt"))
            zip.write("cmake_minimum_required(VERSION 3.20)".toByteArray())
            zip.closeEntry()
        }
    }

    private fun createLspManifest(
        toolchains: List<LspToolchainConfig>,
        serverType: String = "stdio",
        activationEvents: List<String>? = null,
    ): PluginManifest = PluginManifest(
        id = "test.plugin.lsp",
        name = "Validate LSP Plugin",
        version = "1.0.0",
        type = PluginTypes.LSP,
        activationEvents = activationEvents,
        contributions = PluginContributions(
            languageServers = listOf(
                LspServerConfig(
                    id = "pylsp",
                    name = "Python Language Server",
                    languages = listOf("python"),
                    fileExtensions = listOf("py"),
                    server = LspServerConnectionConfig(
                        type = serverType,
                        command = "pylsp",
                    ),
                )
            ),
            toolchains = toolchains,
        ),
    )

    private fun runValidationFailure(manifest: PluginManifest, pluginDir: File): Throwable {
        val thrown = runCatching {
            PluginManifestValidator.validate(
                context = context,
                manifest = manifest,
                pluginDir = pluginDir,
            )
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        return thrown!!
    }

    private fun writeLocale(
        pluginDir: File,
        fileName: String,
        content: String,
    ) {
        val localesDir = File(pluginDir, "locales").apply { mkdirs() }
        File(localesDir, fileName).writeText(content, Charsets.UTF_8)
    }
}

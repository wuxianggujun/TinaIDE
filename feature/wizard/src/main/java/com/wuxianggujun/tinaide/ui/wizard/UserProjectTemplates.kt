package com.wuxianggujun.tinaide.ui.wizard

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import timber.log.Timber

internal object UserProjectTemplates {
    const val TEMPLATE_ID_PREFIX = "user:"
    private const val TAG = "UserProjectTemplates"
    private const val MANIFEST_INSPECTION_LIMIT_BYTES = 64 * 1024

    fun listOptions(context: Context): List<ProjectTemplateOption> {
        return listOptionsFromDirectory(
            context = context,
            templatesDir = ProjectPaths.getUserProjectTemplatesRoot(context)
        )
    }

    internal fun listOptionsFromDirectory(
        context: Context,
        templatesDir: File
    ): List<ProjectTemplateOption> {
        val zipFiles = templatesDir
            .takeIf { it.isDirectory }
            ?.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .orEmpty()

        return zipFiles.mapNotNull { zipFile ->
            runCatching { zipFile.toTemplateOption(context) }
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Skip user project template: %s", zipFile.absolutePath)
                }
                .getOrNull()
        }
    }

    private fun File.toTemplateOption(context: Context): ProjectTemplateOption {
        val inspection = inspectTemplateZip(this)
        val optionId = TEMPLATE_ID_PREFIX + stableId()
        return ProjectTemplateOption(
            id = optionId,
            displayName = nameWithoutExtension.toDisplayName(),
            description = Strings.template_desc_user_zip.strOr(context, name),
            spec = ProjectTemplateSpec.Zip(
                id = optionId,
                zipFile = this,
                buildSystem = inspection.buildSystem,
                primaryLanguage = inspection.primaryLanguage,
                isNdkTemplate = false
            )
        )
    }

    private fun File.stableId(): String {
        val slug = nameWithoutExtension
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "template" }
        val pathHash = Integer.toHexString(absolutePath.hashCode())
        return "$slug-$pathHash"
    }

    private fun String.toDisplayName(): String {
        return replace(Regex("[_-]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }
            .ifBlank { this }
    }

    private fun inspectTemplateZip(zipFile: File): TemplateInspection {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name.replace('\\', '/').trimStart('/') }
                .toList()

            val lowerEntries = entries.map { it.lowercase(Locale.ROOT) }
            val buildSystem = when {
                hasPluginManifest(zip, entries) -> ProjectBuildSystem.PLUGIN
                lowerEntries.any { it.endsWith("cmakelists.txt") } -> ProjectBuildSystem.CMAKE
                lowerEntries.any { entry -> entry.substringAfterLast('/') in makefileNames } ->
                    ProjectBuildSystem.MAKE
                lowerEntries.any { entry -> entry.substringAfterLast('.', "") in nativeSourceExtensions } ->
                    ProjectBuildSystem.SINGLE_FILE
                else -> ProjectBuildSystem.UNKNOWN
            }

            return TemplateInspection(
                buildSystem = buildSystem,
                primaryLanguage = inferPrimaryLanguage(lowerEntries)
            )
        }
    }

    private fun hasPluginManifest(zip: ZipFile, entries: List<String>): Boolean {
        val manifestEntry = entries.firstOrNull { it.equals("manifest.json", ignoreCase = true) } ?: return false
        val entry = zip.getEntry(manifestEntry) ?: return false
        val content = zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(MANIFEST_INSPECTION_LIMIT_BYTES)
            var totalBytesRead = 0
            while (totalBytesRead < buffer.size) {
                val bytesRead = input.read(buffer, totalBytesRead, buffer.size - totalBytesRead)
                if (bytesRead <= 0) break
                totalBytesRead += bytesRead
            }
            String(buffer, 0, totalBytesRead, Charsets.UTF_8)
        }
        val requiredFields = listOf("\"id\"", "\"name\"", "\"version\"")
        val pluginSignals = listOf("\"type\"", "\"contributions\"", "\"activationEvents\"", "\"permissions\"")
        return requiredFields.all { content.contains(it) } && pluginSignals.any { content.contains(it) }
    }

    private fun inferPrimaryLanguage(entries: List<String>): ProjectLanguage {
        val extensions = entries
            .map { it.substringAfterLast('.', missingDelimiterValue = "") }
            .filter { it.isNotBlank() }
            .toSet()

        return when {
            extensions.any { it in cppExtensions } -> ProjectLanguage.CPP
            "c" in extensions || "h" in extensions -> ProjectLanguage.C
            "kt" in extensions || "kts" in extensions -> ProjectLanguage.KOTLIN
            "java" in extensions -> ProjectLanguage.JAVA
            "rs" in extensions -> ProjectLanguage.RUST
            "go" in extensions -> ProjectLanguage.GO
            "ts" in extensions -> ProjectLanguage.TYPESCRIPT
            "js" in extensions -> ProjectLanguage.JAVASCRIPT
            extensions.any { it in shellExtensions } -> ProjectLanguage.SHELL
            extensions.isNotEmpty() -> ProjectLanguage.MIXED
            else -> ProjectLanguage.UNKNOWN
        }
    }

    private data class TemplateInspection(
        val buildSystem: ProjectBuildSystem,
        val primaryLanguage: ProjectLanguage
    )

    private val makefileNames = setOf("makefile", "gnumakefile")
    private val cppExtensions = setOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++")
    private val nativeSourceExtensions = cppExtensions + setOf("c", "h")
    private val shellExtensions = setOf("sh", "bash", "zsh")
}

package com.wuxianggujun.tinaide.project

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import timber.log.Timber

object ProjectTemplateInstaller {
    private const val TAG = "ProjectTemplate"

    private val textFileExtensions = setOf(
        "txt", "md", "json", "xml", "gradle", "kts", "kt", "java",
        "c", "cpp", "cc", "cxx", "h", "hpp", "hh", "hxx",
        "cmake", "mk", "properties", "gitignore", "gitattributes",
        "yml", "yaml", "toml", "ini", "sh", "bat", "ps1", "pro",
    )

    private const val PROJECT_NAME_PLACEHOLDER = "{{PROJECT_NAME}}"
    private const val PROJECT_NAME_UPPER_PLACEHOLDER = "{{PROJECT_NAME_UPPER}}"
    private const val CPP_STANDARD_PLACEHOLDER = "{{CPP_STANDARD}}"
    private const val CPP_STANDARD_FLAG_PLACEHOLDER = "{{CPP_STANDARD_FLAG}}"
    private const val NDK_API_LEVEL_PLACEHOLDER = "{{NDK_API_LEVEL}}"
    private const val AUTHOR_PLACEHOLDER = "{{AUTHOR}}"
    private const val AUTHOR_VARIABLE_NAME = "AUTHOR"

    fun install(
        destDir: File,
        projectName: String,
        templateSpec: ProjectTemplateSpec.Zip,
        cppStandard: CppStandard = CppStandard.DEFAULT,
        ndkApiLevel: AndroidApiLevel? = null,
        authorName: String = "",
    ): Boolean {
        var stagingDir: File? = null
        return try {
            val effectiveNdkApiLevel = if (templateSpec.isNdkTemplate) {
                ndkApiLevel ?: AndroidApiLevel.DEFAULT
            } else {
                ndkApiLevel
            }
            val templateNativeApiLevel = effectiveNdkApiLevel?.level
            val resolvedAuthorName = authorName.trim().ifBlank {
                templateSpec.variables[AUTHOR_VARIABLE_NAME]?.trim().orEmpty()
            }
            val resolvedDefaultRunTargetName = replaceOptionalText(
                templateSpec.defaultRunTargetName,
                projectName,
                cppStandard,
                effectiveNdkApiLevel,
                resolvedAuthorName,
            )
            val resolvedDefaultSdlTargetName = replaceOptionalText(
                templateSpec.defaultSdlTargetName,
                projectName,
                cppStandard,
                effectiveNdkApiLevel,
                resolvedAuthorName,
            )
            val staging = createStagingDirectory(destDir).also { stagingDir = it }

            extractZipTemplate(
                destDir = staging,
                projectName = projectName,
                zipFile = templateSpec.zipFile,
                cppStandard = cppStandard,
                ndkApiLevel = effectiveNdkApiLevel,
                authorName = resolvedAuthorName,
            )
            copyStagedTemplate(staging, destDir)
            val detectedSupport = ProjectApkExportSupportResolver.detectSupport(destDir)
            ProjectMetadataStore.ensure(
                projectRoot = destDir,
                displayNameFallback = projectName,
                buildSystem = templateSpec.buildSystem,
                cppStandard = cppStandard,
                primaryLanguage = templateSpec.primaryLanguage,
                apkExportType = detectedSupport.apkExportType,
                sdlVersion = detectedSupport.sdlVersion,
                nativeApiLevel = templateNativeApiLevel,
                defaultRunTargetName = resolvedDefaultRunTargetName,
                defaultSdlTargetName = resolvedDefaultSdlTargetName,
            )
            Timber.tag(TAG).i(
                "Project created: $projectName, buildSystem: ${templateSpec.buildSystem}, cppStandard: $cppStandard, language: ${templateSpec.primaryLanguage}, ndkApiLevel: ${effectiveNdkApiLevel?.level}, nativeApiLevel: $templateNativeApiLevel",
            )
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Install template failed: $templateSpec")
            false
        } finally {
            stagingDir?.deleteRecursively()
        }
    }

    private fun createStagingDirectory(destDir: File): File {
        val parentDir = destDir.canonicalFile.parentFile
            ?: throw IOException("Project destination has no parent: ${destDir.absolutePath}")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Failed to create template staging parent: ${parentDir.absolutePath}")
        }
        val stagingDir = File.createTempFile("template-install-", ".tmp", parentDir)
        if (!stagingDir.delete() || !stagingDir.mkdirs()) {
            throw IOException("Failed to create template staging directory: ${stagingDir.absolutePath}")
        }
        return stagingDir.canonicalFile
    }

    private fun copyStagedTemplate(stagingDir: File, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Failed to create project directory: ${destDir.absolutePath}")
        }

        stagingDir.listFiles().orEmpty().forEach { entry ->
            val copied = entry.copyRecursively(
                target = File(destDir, entry.name),
                overwrite = true,
            )
            if (!copied) {
                throw IOException("Failed to copy template entry: ${entry.name}")
            }
        }
    }

    private fun extractZipTemplate(
        destDir: File,
        projectName: String,
        zipFile: File,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?,
        authorName: String,
    ) {
        zipFile.inputStream().use { inputStream ->
            extractTemplateStream(
                inputStream = inputStream,
                destDir = destDir,
                projectName = projectName,
                cppStandard = cppStandard,
                ndkApiLevel = ndkApiLevel,
                authorName = authorName,
            )
        }
    }

    private fun extractTemplateStream(
        inputStream: InputStream,
        destDir: File,
        projectName: String,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?,
        authorName: String,
    ) {
        val safeRoot = destDir.canonicalFile
        ZipInputStream(inputStream).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                if (ProjectTemplateMetadataReader.isMetadataEntry(entryName)) {
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                    continue
                }

                val destFileName = replaceText(entryName, projectName, cppStandard, ndkApiLevel, authorName)
                val destFile = resolveTemplateDestination(safeRoot, destFileName)

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    val entryBytes = zipStream.readBytes()
                    if (shouldReplaceTextContent(entryName, entryBytes)) {
                        val content = entryBytes.toString(Charsets.UTF_8)
                        val replacedContent = replaceText(content, projectName, cppStandard, ndkApiLevel, authorName)
                        destFile.writeText(replacedContent, Charsets.UTF_8)
                    } else {
                        destFile.writeBytes(entryBytes)
                    }
                }

                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
    }

    private fun resolveTemplateDestination(destRoot: File, entryName: String): File {
        val destFile = File(destRoot, entryName).canonicalFile
        val rootPath = destRoot.path
        val destPath = destFile.path
        if (destFile != destRoot && !destPath.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("Unsafe template entry path: $entryName")
        }
        return destFile
    }

    private fun shouldReplaceTextContent(entryName: String, entryBytes: ByteArray): Boolean {
        val normalizedName = entryName.substringAfterLast('/').lowercase()
        val extension = normalizedName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension in textFileExtensions) {
            return true
        }

        if (entryBytes.isEmpty()) {
            return true
        }

        if (entryBytes.any { it == 0.toByte() }) {
            return false
        }

        return runCatching {
            entryBytes.toString(Charsets.UTF_8)
        }.isSuccess
    }

    private fun replaceText(
        text: String,
        projectName: String,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?,
        authorName: String,
    ): String {
        var result = text
            .replace(PROJECT_NAME_PLACEHOLDER, projectName)
            .replace(PROJECT_NAME_UPPER_PLACEHOLDER, projectName.uppercase())
            .replace(CPP_STANDARD_PLACEHOLDER, cppStandard.cmakeValue)
            .replace(CPP_STANDARD_FLAG_PLACEHOLDER, cppStandard.flag)
            .replace(AUTHOR_PLACEHOLDER, authorName)
        if (ndkApiLevel != null) {
            result = result.replace(NDK_API_LEVEL_PLACEHOLDER, ndkApiLevel.level.toString())
        }
        return result
    }

    private fun replaceOptionalText(
        text: String?,
        projectName: String,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?,
        authorName: String,
    ): String? = text
        ?.let { replaceText(it, projectName, cppStandard, ndkApiLevel, authorName) }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

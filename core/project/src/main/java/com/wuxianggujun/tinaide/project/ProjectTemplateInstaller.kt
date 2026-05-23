package com.wuxianggujun.tinaide.project

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import timber.log.Timber

object ProjectTemplateInstaller {
    private const val TAG = "ProjectTemplate"
    private const val TEMPLATES_DIR = "templates"
    private val textFileExtensions = setOf(
        "txt", "md", "json", "xml", "gradle", "kts", "kt", "java",
        "c", "cpp", "cc", "cxx", "h", "hpp", "hh", "hxx",
        "cmake", "mk", "properties", "gitignore", "gitattributes",
        "yml", "yaml", "toml", "ini", "sh", "bat", "ps1", "pro"
    )

    // 模板占位符
    private const val PROJECT_NAME_PLACEHOLDER = "{{PROJECT_NAME}}"
    private const val PROJECT_NAME_UPPER_PLACEHOLDER = "{{PROJECT_NAME_UPPER}}"
    private const val CPP_STANDARD_PLACEHOLDER = "{{CPP_STANDARD}}"
    private const val CPP_STANDARD_FLAG_PLACEHOLDER = "{{CPP_STANDARD_FLAG}}"
    private const val NDK_API_LEVEL_PLACEHOLDER = "{{NDK_API_LEVEL}}"

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
                overwrite = true
            )
            if (!copied) {
                throw IOException("Failed to copy template entry: ${entry.name}")
            }
        }
    }

    /**
     * 项目模板类型
     */
    enum class TemplateType(
        val zipName: String,
        val buildSystem: ProjectBuildSystem,
        val primaryLanguage: ProjectLanguage = ProjectLanguage.CPP,
        /** 是否为 NDK 项目模板（支持自定义 API level） */
        val isNdkTemplate: Boolean = false
    ) {
        CPP_SINGLE_FILE("cpp_single_file.zip", ProjectBuildSystem.SINGLE_FILE, ProjectLanguage.CPP),
        CMAKE_EXECUTABLE("cmake_executable.zip", ProjectBuildSystem.CMAKE, ProjectLanguage.CPP),
        CMAKE_LIBRARY("cmake_library.zip", ProjectBuildSystem.CMAKE, ProjectLanguage.CPP),
        MAKE_EXECUTABLE("make_executable.zip", ProjectBuildSystem.MAKE, ProjectLanguage.CPP),
        NDK_SHARED_LIBRARY("ndk_shared_library.zip", ProjectBuildSystem.CMAKE, ProjectLanguage.CPP, isNdkTemplate = true)
    }

    /**
     * 创建项目
     *
     * 会自动创建项目元数据并设置构建系统类型
     *
     * @param context 上下文
     * @param destDir 目标目录
     * @param projectName 项目名称
     * @param type 模板类型
     * @param cppStandard C++ 标准版本
     * @param ndkApiLevel NDK API level（仅 NDK 模板使用）
     */
    fun install(
        context: Context,
        destDir: File,
        projectName: String,
        type: TemplateType,
        cppStandard: CppStandard = CppStandard.DEFAULT,
        ndkApiLevel: AndroidApiLevel? = null
    ): Boolean {
        return install(
            destDir = destDir,
            projectName = projectName,
            templateSpec = ProjectTemplateSpec.Asset(type),
            cppStandard = cppStandard,
            ndkApiLevel = ndkApiLevel,
            assetStreamProvider = { assetPath -> context.assets.open(assetPath) }
        )
    }

    fun install(
        destDir: File,
        projectName: String,
        templateSpec: ProjectTemplateSpec,
        cppStandard: CppStandard = CppStandard.DEFAULT,
        ndkApiLevel: AndroidApiLevel? = null,
        assetStreamProvider: ((String) -> InputStream)? = null
    ): Boolean {
        var stagingDir: File? = null
        return try {
            val effectiveNdkApiLevel = if (templateSpec.isNdkTemplate) {
                ndkApiLevel ?: AndroidApiLevel.DEFAULT
            } else {
                ndkApiLevel
            }
            val templateNativeApiLevel = if (effectiveNdkApiLevel != null) {
                effectiveNdkApiLevel.level
            } else {
                null
            }
            val staging = createStagingDirectory(destDir).also { stagingDir = it }
            when (templateSpec) {
                is ProjectTemplateSpec.Asset -> {
                    val provider = requireNotNull(assetStreamProvider) {
                        "Asset template requires assetStreamProvider"
                    }
                    extractAssetTemplate(
                        destDir = staging,
                        projectName = projectName,
                        type = templateSpec.type,
                        cppStandard = cppStandard,
                        ndkApiLevel = effectiveNdkApiLevel,
                        assetStreamProvider = provider
                    )
                }
                is ProjectTemplateSpec.Zip -> {
                    extractZipTemplate(
                        destDir = staging,
                        projectName = projectName,
                        zipFile = templateSpec.zipFile,
                        cppStandard = cppStandard,
                        ndkApiLevel = effectiveNdkApiLevel
                    )
                }
            }
            copyStagedTemplate(staging, destDir)
            ProjectMetadataStore.ensure(
                projectRoot = destDir,
                displayNameFallback = projectName,
                buildSystem = templateSpec.buildSystem,
                cppStandard = cppStandard,
                primaryLanguage = templateSpec.primaryLanguage,
                apkExportType = ProjectApkExportSupportResolver.detect(destDir),
                nativeApiLevel = templateNativeApiLevel
            )
            Timber.tag(TAG).i(
                "Project created: $projectName, buildSystem: ${templateSpec.buildSystem}, cppStandard: $cppStandard, language: ${templateSpec.primaryLanguage}, ndkApiLevel: ${effectiveNdkApiLevel?.level}, nativeApiLevel: $templateNativeApiLevel"
            )
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Install template failed: $templateSpec")
            false
        } finally {
            stagingDir?.deleteRecursively()
        }
    }

    /**
     * 从 assets 解压模板 zip 到目标目录
     */
    private fun extractAssetTemplate(
        destDir: File,
        projectName: String,
        type: TemplateType,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?,
        assetStreamProvider: (String) -> InputStream
    ) {
        val assetPath = "$TEMPLATES_DIR/${type.zipName}"
        assetStreamProvider(assetPath).use { inputStream ->
            extractTemplateStream(
                inputStream = inputStream,
                destDir = destDir,
                projectName = projectName,
                cppStandard = cppStandard,
                ndkApiLevel = ndkApiLevel
            )
        }
    }

    private fun extractZipTemplate(
        destDir: File,
        projectName: String,
        zipFile: File,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?
    ) {
        zipFile.inputStream().use { inputStream ->
            extractTemplateStream(
                inputStream = inputStream,
                destDir = destDir,
                projectName = projectName,
                cppStandard = cppStandard,
                ndkApiLevel = ndkApiLevel
            )
        }
    }

    private fun extractTemplateStream(
        inputStream: InputStream,
        destDir: File,
        projectName: String,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?
    ) {
        val safeRoot = destDir.canonicalFile
        ZipInputStream(inputStream).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                val destFileName = replaceText(entryName, projectName, cppStandard, ndkApiLevel)
                val destFile = resolveTemplateDestination(safeRoot, destFileName)

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    val entryBytes = zipStream.readBytes()
                    if (shouldReplaceTextContent(entryName, entryBytes)) {
                        val content = entryBytes.toString(Charsets.UTF_8)
                        val replacedContent = replaceText(content, projectName, cppStandard, ndkApiLevel)
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

    /**
     * 替换文本中的占位符
     */
    private fun replaceText(
        text: String,
        projectName: String,
        cppStandard: CppStandard,
        ndkApiLevel: AndroidApiLevel?
    ): String {
        var result = text
            .replace(PROJECT_NAME_PLACEHOLDER, projectName)
            .replace(PROJECT_NAME_UPPER_PLACEHOLDER, projectName.uppercase())
            .replace(CPP_STANDARD_PLACEHOLDER, cppStandard.cmakeValue)
            .replace(CPP_STANDARD_FLAG_PLACEHOLDER, cppStandard.flag)
        if (ndkApiLevel != null) {
            result = result.replace(NDK_API_LEVEL_PLACEHOLDER, ndkApiLevel.level.toString())
        }
        return result
    }
}

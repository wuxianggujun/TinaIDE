package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 构建系统检测器
 *
 * 优先从项目元数据读取构建系统类型，只有在元数据中没有指定时才进行文件检测。
 */
object BuildSystemDetector {

    private const val TAG = "BuildSystemDetector"
    private val SOURCE_EXTENSIONS: Set<String> = CxxFileSupport.singleFileBuildSourceExtensions

    /**
     * 检测项目的构建系统
     *
     * 检测顺序：
     * 1. 从项目元数据（.tinaide/project.json）读取
     * 2. 如果元数据中没有指定，则通过文件检测
     * 3. 如果检测成功，将结果保存到元数据中（避免下次再检测）
     */
    suspend fun detect(projectRoot: File): BuildSystem = withContext(Dispatchers.IO) {
        detectOnIo(projectRoot)
    }

    private suspend fun detectOnIo(projectRoot: File): BuildSystem {
        Timber.tag(TAG).d("Detecting build system: ${projectRoot.absolutePath}")

        // 首先检查项目目录是否存在且可读
        if (!projectRoot.exists()) {
            Timber.tag(TAG).w("Project directory does not exist: ${projectRoot.absolutePath}")
            return BuildSystem.UNKNOWN
        }

        if (!projectRoot.isDirectory) {
            Timber.tag(TAG).w("Project path is not a directory: ${projectRoot.absolutePath}")
            return BuildSystem.UNKNOWN
        }

        if (!projectRoot.canRead()) {
            Timber.tag(TAG).w("Project directory not readable: ${projectRoot.absolutePath}")
            return BuildSystem.UNKNOWN
        }

        // 1. 优先从元数据读取
        val metadata = ProjectMetadataStore.read(projectRoot)
        val metaBuildSystem = metadata?.buildSystem

        // 插件 starter 曾以 SINGLE_FILE 写入元数据；根目录 manifest.json 更能代表真实项目类型。
        if (hasPluginManifest(projectRoot) && metaBuildSystem != ProjectBuildSystem.PLUGIN) {
            if (ProjectMetadataStore.updateBuildSystem(projectRoot, ProjectBuildSystem.PLUGIN)) {
                Timber.tag(TAG).d("Updated detected plugin build system to metadata")
            }
            return BuildSystem.PLUGIN
        }

        if (metaBuildSystem != null && metaBuildSystem != ProjectBuildSystem.UNKNOWN) {
            val buildSystem = convertFromProjectBuildSystem(metaBuildSystem)
            Timber.tag(TAG).d("Build system from metadata: $buildSystem")
            return buildSystem
        }

        // 2. 元数据中没有指定，进行文件检测
        Timber.tag(TAG).d("No build system in metadata, detecting from files...")
        val detected = detectByFiles(projectRoot)

        // 3. 如果检测成功，保存到元数据中
        if (detected != BuildSystem.UNKNOWN) {
            val projectBuildSystem = convertToProjectBuildSystem(detected)
            if (ProjectMetadataStore.updateBuildSystem(projectRoot, projectBuildSystem)) {
                Timber.tag(TAG).d("Saved detected build system to metadata: $detected")
            }
        }

        return detected
    }

    /**
     * 通过文件检测构建系统（内部方法）
     */
    private fun detectByFiles(projectRoot: File): BuildSystem = try {
        when {
            hasPluginManifest(projectRoot) -> {
                Timber.tag(TAG).d("Detected TinaIDE plugin project")
                BuildSystem.PLUGIN
            }
            hasCMakeLists(projectRoot) -> {
                Timber.tag(TAG).d("Detected CMake project")
                BuildSystem.CMAKE
            }
            hasMakefile(projectRoot) -> {
                Timber.tag(TAG).d("Detected Makefile project")
                BuildSystem.MAKE
            }
            hasSourceFiles(projectRoot) -> {
                Timber.tag(TAG).d("Detected single/multi-file project")
                BuildSystem.SINGLE_FILE
            }
            else -> {
                Timber.tag(TAG).w("No source files detected")
                // 列出目录内容以便调试
                val files = projectRoot.listFiles()
                if (files == null) {
                    Timber.tag(TAG).w("Failed to list directory contents (listFiles returned null)")
                } else {
                    Timber.tag(TAG).d("Directory contents: ${files.map { it.name }}")
                }
                BuildSystem.UNKNOWN
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Exception while detecting build system")
        BuildSystem.UNKNOWN
    }

    /**
     * 将 ProjectBuildSystem 转换为 BuildSystem
     */
    private fun convertFromProjectBuildSystem(pbs: ProjectBuildSystem): BuildSystem = when (pbs) {
        ProjectBuildSystem.SINGLE_FILE -> BuildSystem.SINGLE_FILE
        ProjectBuildSystem.CMAKE -> BuildSystem.CMAKE
        ProjectBuildSystem.MAKE -> BuildSystem.MAKE
        ProjectBuildSystem.PLUGIN -> BuildSystem.PLUGIN
        ProjectBuildSystem.UNKNOWN -> BuildSystem.UNKNOWN
    }

    /**
     * 将 BuildSystem 转换为 ProjectBuildSystem
     */
    private fun convertToProjectBuildSystem(bs: BuildSystem): ProjectBuildSystem = when (bs) {
        BuildSystem.SINGLE_FILE -> ProjectBuildSystem.SINGLE_FILE
        BuildSystem.CMAKE -> ProjectBuildSystem.CMAKE
        BuildSystem.MAKE -> ProjectBuildSystem.MAKE
        BuildSystem.PLUGIN -> ProjectBuildSystem.PLUGIN
        BuildSystem.UNKNOWN -> ProjectBuildSystem.UNKNOWN
    }

    /**
     * 检查是否是 TinaIDE 插件项目。
     *
     * 只把具有插件清单关键字段和插件特征字段的 manifest.json 识别为插件项目，
     * 避免把普通 Web/包管理 manifest 误判为 TinaIDE 插件。
     */
    private fun hasPluginManifest(projectRoot: File): Boolean {
        val manifestFile = File(projectRoot, "manifest.json")
        if (!manifestFile.isFile) return false

        return runCatching {
            val manifest = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            val id = manifest.stringValue("id")
            val name = manifest.stringValue("name")
            val version = manifest.stringValue("version")
            val type = manifest.stringValue("type").lowercase()
            val hasRequiredFields = id.isNotBlank() && name.isNotBlank() && version.isNotBlank()
            val hasPluginType = type in setOf("config", "script", "hybrid", "lsp", "system")
            val hasPluginContributions = manifest.containsKey("contributions") ||
                manifest.containsKey("activationEvents") ||
                manifest.containsKey("permissions") ||
                manifest.containsKey("optionalPermissions") ||
                manifest.containsKey("networkHosts") ||
                manifest.containsKey("apiVersion")

            hasRequiredFields && (hasPluginType || hasPluginContributions)
        }.getOrElse { throwable ->
            Timber.tag(TAG).d(throwable, "manifest.json is not a TinaIDE plugin manifest")
            false
        }
    }

    private fun JsonObject.stringValue(name: String): String = this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    /**
     * 检查是否存在 CMakeLists.txt
     */
    private fun hasCMakeLists(projectRoot: File): Boolean = File(projectRoot, "CMakeLists.txt").exists()

    /**
     * 检查是否存在 Makefile
     */
    private fun hasMakefile(projectRoot: File): Boolean = File(projectRoot, "Makefile").exists() ||
        File(projectRoot, "makefile").exists() ||
        File(projectRoot, "GNUmakefile").exists()

    /**
     * 检查是否有源文件（单个或多个）
     */
    private fun hasSourceFiles(projectRoot: File): Boolean {
        val sourceFiles = findSourceFiles(projectRoot).take(1).toList()
        return sourceFiles.isNotEmpty()
    }

    /**
     * 检查是否是单文件项目
     */
    private fun hasSingleSourceFile(projectRoot: File): Boolean {
        val sourceFiles = findSourceFiles(projectRoot).take(2).toList()
        return sourceFiles.size == 1
    }

    /**
     * 检查是否有多个源文件
     */
    private fun hasMultipleSourceFiles(projectRoot: File): Boolean {
        val sourceFiles = findSourceFiles(projectRoot).take(2).toList()
        return sourceFiles.size > 1
    }

    /**
     * 查找源文件
     *
     * 使用更健壮的方式遍历文件，避免因权限或其他问题导致遍历失败
     */
    private fun findSourceFiles(projectRoot: File): Sequence<File> {
        return try {
            // 首先尝试直接列出顶层文件（最常见的情况）
            val topLevelFiles = projectRoot.listFiles()?.filter {
                it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS
            } ?: emptyList()

            if (topLevelFiles.isNotEmpty()) {
                Timber.tag(TAG).d("Found source files at top level: ${topLevelFiles.map { it.name }}")
                return topLevelFiles.asSequence()
            }

            // 如果顶层没有，再递归查找
            projectRoot.walkTopDown()
                .onFail { file, exception ->
                    Timber.tag(TAG).w("Failed to walk file: ${file.absolutePath}, error: ${exception.message}")
                }
                .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception while searching for source files")
            emptySequence()
        }
    }

    /**
     * 获取项目的主要源文件
     */
    fun findMainSourceFile(projectRoot: File): File? {
        val sourceFiles = findSourceFiles(projectRoot).toList()
        return sourceFiles.firstOrNull { it.nameWithoutExtension == "main" }
            ?: sourceFiles.firstOrNull()
    }

    /**
     * 获取所有源文件
     */
    fun findAllSourceFiles(projectRoot: File): List<File> = findSourceFiles(projectRoot).toList()
}

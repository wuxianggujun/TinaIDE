package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectApkExportSupportResolver
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import timber.log.Timber

private const val RUN_CONFIG_SCHEMA_CURRENT = 7

/**
 * 源文件模式 - 决定编译哪个源文件
 */
@Serializable
enum class SourceFileMode {
    /**
     * 自动检测（默认行为）
     * - CMake 项目：使用 CMakeLists.txt 定义的目标
     * - 单文件项目：优先查找 main.cpp/main.c，否则使用第一个源文件
     */
    AUTO,

    /**
     * 使用当前编辑的文件
     * 编译并运行当前在编辑器中打开的文件
     */
    CURRENT_FILE,

    /**
     * 使用指定的源文件
     * 编译并运行用户指定的源文件路径
     */
    SPECIFIED_FILE
}

/**
 * 运行配置
 *
 * 支持多种源文件选择模式、编译器选择和变量替换。
 *
 * 变量支持（在 args、workDir、sourceFilePath 中可用）：
 * - $ProjectDir$ - 项目根目录
 * - $CurrentFile$ - 当前编辑的文件
 * - $CurrentFileDir$ - 当前文件所在目录
 * - $BuildDir$ - 构建输出目录
 * 等，详见 BuildVariables.kt
 */
@Serializable
data class RunConfiguration(
    val id: String = UUID.randomUUID().toString(), // 唯一标识
    val name: String = "Debug", // 配置名称
    val args: String = "", // 命令行参数（支持变量）
    val workDir: String = "", // 工作目录（相对路径，支持变量）
    val buildType: BuildType = BuildType.DEBUG,
    val outputMode: OutputMode = OutputMode.TERMINAL,
    val targetName: String = "", // CMake 构建目标（留空表示默认目标）

    // 单文件编译相关配置
    val sourceFileMode: SourceFileMode = SourceFileMode.AUTO, // 源文件选择模式
    val sourceFilePath: String = "", // 指定的源文件路径（相对于项目根目录，支持变量）

    // 编译器配置
    val compilerType: CompilerType = CompilerType.CLANG, // 编译器类型（默认 Clang）

    /**
     * 工具链 ID（仅当 compilerType == CLANG 时使用）
     *
     * - null: 使用全局激活的工具链
     * - 非 null: 使用指定 ID 的工具链（如 "builtin-0.1.9", "custom-18.1.8"）
     */
    val toolchainId: String? = null,

    /**
     * Optional NDK Runtime/sysroot profile override.
     *
     * - null: follow the global active NDK Runtime
     * - non-null: pin this run configuration to the selected sysroot/libc++_shared.so
     */
    val sysrootProfileId: String? = null,

    /**
     * 自定义 C 编译器路径（仅当 compilerType == CUSTOM 时使用）
     *
     * 路径应为 PRoot guest rootfs 内的可执行文件路径（如 /usr/bin/gcc）。
     */
    val customCCompiler: String? = null,

    /**
     * 自定义 C++ 编译器路径（仅当 compilerType == CUSTOM 时使用）
     *
     * 路径应为 PRoot guest rootfs 内的可执行文件路径（如 /usr/bin/g++）。
     */
    val customCppCompiler: String? = null,

    /**
     * 可选 sysroot API level。
     *
     * - null: 自动读取项目 metadata 的 nativeApiLevel，若无则默认 API 28
     * - 非 null: 传递给构建策略用于拼装 --target/--sysroot
     */
    val sysrootApiLevel: Int? = null,

    /**
     * 单文件构建的 C++ 标准覆盖项（仅 buildSystem == SINGLE_FILE 生效）。
     *
     * 存储值优先使用 [com.wuxianggujun.tinaide.project.CppStandard.name]（如 "CPP_20"）。
     * - null/空字符串: 跟随项目 metadata（若无则使用策略默认 c++17）
     * - 非空: 作为单文件编译时的 `-std=...` 来源
     */
    val singleFileCppStandard: String? = null,

    /**
     * SDL 主版本覆盖项（仅 outputMode == SDL 时生效）。
     * null 表示优先从 ELF 依赖和项目元数据自动检测。
     */
    val sdlVersion: ProjectSdlVersion? = null,

    /**
     * SDL 图形运行的屏幕方向（仅 outputMode == SDL 时生效）。
     */
    val sdlOrientation: SdlOrientation = SdlOrientation.AUTO,

    /** 是否在图形运行宿主中显示悬浮日志窗口。悬浮返回按钮始终显示。 */
    val enableFloatingLog: Boolean = false,

    /**
     * 是否显示 Android linker 对 AArch64 Auth RELR 标签的兼容性告警。
     *
     * 默认关闭，仅隐藏已知的 0x70000011/12/13 告警。过滤期间 stderr 经 FIFO 转发；
     * 依赖 `isatty(stderr)` 的程序可开启本选项以保持原始 TTY 语义。
     */
    val showLinkerWarnings: Boolean = false
) {
    fun normalized(): RunConfiguration = copy(
        toolchainId = toolchainId?.trim()?.takeIf { it.isNotEmpty() },
        sysrootProfileId = sysrootProfileId?.trim()?.takeIf { it.isNotEmpty() },
        customCCompiler = normalizeCompilerPath(customCCompiler),
        customCppCompiler = normalizeCompilerPath(customCppCompiler),
        singleFileCppStandard = normalizeSingleFileCppStandard(singleFileCppStandard),
        sdlVersion = sdlVersion.takeIf { outputMode == OutputMode.SDL },
    )

    /**
     * 获取参数列表
     */
    fun getArgsList(): List<String> {
        if (args.isBlank()) return emptyList()
        return CommandLineArguments.parse(args)
    }

    /**
     * 获取参数列表（带变量替换）
     */
    fun getArgsList(context: BuildVariables.BuildContext): List<String> {
        if (args.isBlank()) return emptyList()
        val expandedArgs = BuildVariables.expand(args, context)
        return CommandLineArguments.parse(expandedArgs)
    }

    /**
     * 获取绝对工作目录
     */
    fun getAbsoluteWorkDir(projectPath: String): String = if (workDir.isBlank()) {
        projectPath
    } else {
        File(projectPath, workDir).absolutePath
    }

    /**
     * 获取绝对工作目录（带变量替换）
     */
    fun getAbsoluteWorkDir(projectPath: String, context: BuildVariables.BuildContext): String {
        if (workDir.isBlank()) {
            return projectPath
        }
        val expandedWorkDir = BuildVariables.expand(workDir, context)
        return if (File(expandedWorkDir).isAbsolute) {
            expandedWorkDir
        } else {
            File(projectPath, expandedWorkDir).absolutePath
        }
    }

    /**
     * 获取要编译的源文件
     *
     * @param projectRoot 项目根目录
     * @param currentFile 当前编辑的文件（可选）
     * @return 源文件，如果无法确定则返回 null
     */
    fun getSourceFile(projectRoot: File, currentFile: File? = null): File? = when (sourceFileMode) {
        SourceFileMode.AUTO -> null // 返回 null 表示使用默认行为

        SourceFileMode.CURRENT_FILE -> {
            currentFile?.takeIf { isSourceFile(it) }
        }

        SourceFileMode.SPECIFIED_FILE -> {
            if (sourceFilePath.isBlank()) {
                null
            } else {
                // 支持变量替换
                val context = BuildVariables.BuildContext(
                    projectDir = projectRoot,
                    projectName = projectRoot.name,
                    currentFile = currentFile
                )
                val expandedPath = BuildVariables.expand(sourceFilePath, context)
                val file = if (File(expandedPath).isAbsolute) {
                    File(expandedPath)
                } else {
                    File(projectRoot, expandedPath)
                }
                file.takeIf { it.exists() && isSourceFile(it) }
            }
        }
    }

    /**
     * 检查是否为单文件编译模式
     */
    fun isSingleFileMode(): Boolean = sourceFileMode != SourceFileMode.AUTO

    /**
     * 获取显示名称（用于配置选择器）
     */
    fun displayName(): String = name

    /**
     * 获取源文件模式的显示名称
     */
    fun sourceFileModeDisplayName(): String = when (sourceFileMode) {
        SourceFileMode.AUTO -> Strings.run_config_auto_detect.str()
        SourceFileMode.CURRENT_FILE -> Strings.run_config_current_file.str()
        SourceFileMode.SPECIFIED_FILE -> Strings.run_config_specified_file.str()
    }

    /**
     * 获取输出模式显示名称
     */
    fun outputModeDisplayName(): String = when (outputMode) {
        OutputMode.TERMINAL -> Strings.run_config_output_terminal.str()
        OutputMode.SDL -> Strings.run_config_output_sdl.str()
        OutputMode.NATIVE_ACTIVITY -> Strings.run_config_output_native_activity.str()
    }

    companion object {
        private val SOURCE_EXTENSIONS: Set<String> = CxxFileSupport.singleFileBuildSourceExtensions

        /**
         * 检查文件是否为 C/C++ 源文件
         */
        fun isSourceFile(file: File): Boolean = file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS

        /**
         * 解析单文件 C++ 标准配置（支持 `CPP_20` / `20` / `c++20`）。
         */
        fun parseSingleFileCppStandard(value: String?): CppStandard? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return null
            return CppStandard.entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) ||
                    it.flag.equals(normalized, ignoreCase = true) ||
                    it.cmakeValue == normalized
            }
        }

        /**
         * 归一化单文件 C++ 标准配置：
         * - 空值 -> null
         * - 已知标准 -> 对应 [CppStandard.name]
         * - 未知值 -> 原样保留（允许高级用户手写标准字符串）
         */
        fun normalizeSingleFileCppStandard(value: String?): String? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return null
            return parseSingleFileCppStandard(normalized)?.name ?: normalized
        }

        fun normalizeCompilerPath(value: String?): String? {
            val normalized = value?.trim().orEmpty()
            return normalized.ifBlank { null }
        }
    }
}

/**
 * 运行配置管理器 - 管理多个配置
 */
@Serializable
data class RunConfigurationManager(
    /**
     * run_configs.json schema 版本，新写入始终使用 [RUN_CONFIG_SCHEMA_CURRENT]。
     */
    val schemaVersion: Int = RUN_CONFIG_SCHEMA_CURRENT,
    val configurations: List<RunConfiguration> = listOf(RunConfiguration()),
    val selectedId: String? = null
) {
    companion object {
        private const val TAG = "RunConfigManager"
        private const val CONFIG_FILE = ".tinaide/run_configs.json"

        private val json = JsonSerializer.pretty

        internal fun configFile(projectPath: String): File = File(projectPath, CONFIG_FILE)

        /**
         * 从项目目录加载配置
         */
        fun load(projectPath: String): RunConfigurationManager {
            val configFile = configFile(projectPath)
            return try {
                if (configFile.exists()) {
                    val rawJson = configFile.readText()
                    val rawManager = json.decodeFromString<RunConfigurationManager>(rawJson)

                    val validConfigs = rawManager.configurations.filter { config ->
                        config.id.isNotBlank() && config.name.isNotBlank()
                    }
                    if (validConfigs.isEmpty()) {
                        Timber.tag(TAG).w("Run configs has no valid entries, creating default")
                        createDefault(projectPath)
                    } else {
                        val sanitizedManager = rawManager.copy(
                            schemaVersion = RUN_CONFIG_SCHEMA_CURRENT,
                            configurations = validConfigs
                        )
                        val projectMetadata = resolveProjectMetadata(projectPath)
                        val normalizedConfigManager = migrateLegacyNativeActivityMode(
                            manager = normalizeManager(sanitizedManager),
                            sourceSchemaVersion = rawManager.schemaVersion,
                            metadata = projectMetadata,
                        )
                        val targetRepair = CMakeRunTargetResolver.repairBlankTargets(
                            normalizedConfigManager,
                            projectMetadata
                        )
                        val repairedConfigManager = targetRepair.manager
                        val normalizedSelectedId = repairedConfigManager.selectedId
                            ?.takeIf { selected ->
                                repairedConfigManager.configurations.any { it.id == selected }
                            }
                            ?: repairedConfigManager.configurations.first().id
                        val normalizedManager =
                            repairedConfigManager.copy(selectedId = normalizedSelectedId)

                        val filteredInvalidConfigs =
                            validConfigs.size != rawManager.configurations.size
                        val configNormalized =
                            normalizedConfigManager.configurations != sanitizedManager.configurations
                        val targetsRepaired =
                            repairedConfigManager.configurations != normalizedConfigManager.configurations
                        val normalizedConfigCount = sanitizedManager.configurations.zip(
                            normalizedConfigManager.configurations
                        ).count { (before, after) -> before != after }
                        val repairedTargetCount = targetRepair.repairedCount
                        val selectedIdAdjusted =
                            normalizedSelectedId != repairedConfigManager.selectedId
                        val changed = filteredInvalidConfigs ||
                            rawManager.schemaVersion != RUN_CONFIG_SCHEMA_CURRENT ||
                            configNormalized ||
                            targetsRepaired ||
                            selectedIdAdjusted
                        if (changed) {
                            if (filteredInvalidConfigs) {
                                Timber.tag(TAG).w(
                                    "Filtered ${rawManager.configurations.size - validConfigs.size} invalid configs"
                                )
                            }
                            if (configNormalized || rawManager.schemaVersion != RUN_CONFIG_SCHEMA_CURRENT) {
                                Timber.tag(TAG).i(
                                    "Normalized run configs: schema=${rawManager.schemaVersion}, " +
                                        "normalized=$normalizedConfigCount"
                                )
                            }
                            if (targetsRepaired) {
                                Timber.tag(TAG).i(
                                    "Repaired missing run config targets: repaired=$repairedTargetCount"
                                )
                            }
                            if (selectedIdAdjusted) {
                                Timber.tag(TAG).w(
                                    "Selected run config id was invalid, reset to first config"
                                )
                            }
                            save(projectPath, normalizedManager)
                        }

                        normalizedManager
                    }
                } else {
                    createDefault(projectPath)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to load run configs: ${e.message}")
                createDefault(projectPath)
            }
        }

        /**
         * 保存配置到项目目录
         */
        fun save(projectPath: String, manager: RunConfigurationManager): Boolean {
            val configFile = configFile(projectPath)
            val managerToPersist = normalizeManager(
                manager.copy(schemaVersion = RUN_CONFIG_SCHEMA_CURRENT)
            )
            return try {
                configFile.parentFile?.mkdirs()
                JsonSerializer.encodePrettyToFile(configFile, managerToPersist)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e("Failed to save run configs: ${e.message}")
                false
            }
        }

        /**
         * 创建默认配置
         */
        private fun createDefault(projectPath: String? = null): RunConfigurationManager {
            val defaultConfig = createDefaultRunConfiguration(projectPath)
            return RunConfigurationManager(
                schemaVersion = RUN_CONFIG_SCHEMA_CURRENT,
                configurations = listOf(defaultConfig),
                selectedId = defaultConfig.id
            )
        }

        private fun createDefaultRunConfiguration(projectPath: String?): RunConfiguration {
            val metadata = resolveProjectMetadata(projectPath)
            return CMakeRunTargetResolver.createDefaultRunConfiguration(metadata)
                ?: RunConfiguration(name = "Debug")
        }

        private fun resolveProjectMetadata(projectPath: String?): ProjectMetadata? {
            val projectRoot = projectPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?: return null
            ProjectApkExportSupportResolver.ensureDetected(projectRoot)
            return ProjectMetadataStore.read(projectRoot)
        }

        private fun normalizeManager(manager: RunConfigurationManager): RunConfigurationManager {
            val normalizedConfigs = manager.configurations.map { it.normalized() }
            return if (normalizedConfigs == manager.configurations) {
                manager
            } else {
                manager.copy(configurations = normalizedConfigs)
            }
        }

        private fun migrateLegacyNativeActivityMode(
            manager: RunConfigurationManager,
            sourceSchemaVersion: Int,
            metadata: ProjectMetadata?,
        ): RunConfigurationManager {
            if (
                sourceSchemaVersion >= RUN_CONFIG_SCHEMA_CURRENT ||
                metadata?.apkExportType != ProjectApkExportType.NATIVE_ACTIVITY ||
                metadata?.getSdlVersionOrNull() != null
            ) {
                return manager
            }

            val migratedConfigurations = manager.configurations.map { config ->
                if (config.outputMode == OutputMode.SDL) {
                    config.copy(
                        outputMode = OutputMode.NATIVE_ACTIVITY,
                        sdlVersion = null,
                    )
                } else {
                    config
                }
            }
            return if (migratedConfigurations == manager.configurations) {
                manager
            } else {
                manager.copy(configurations = migratedConfigurations)
            }
        }
    }

    /**
     * 获取当前选中的配置
     */
    val selectedConfig: RunConfiguration
        get() = configurations.find { it.id == selectedId }
            ?: configurations.firstOrNull()
            ?: RunConfiguration()

    /**
     * 选择配置
     */
    fun selectConfig(id: String): RunConfigurationManager = copy(selectedId = id)

    /**
     * 添加新配置
     */
    fun addConfig(config: RunConfiguration): RunConfigurationManager = copy(
        configurations = configurations + config,
        selectedId = config.id
    )

    /**
     * 更新配置
     */
    fun updateConfig(config: RunConfiguration): RunConfigurationManager = copy(
        configurations = configurations.map {
            if (it.id == config.id) config else it
        }
    )

    /**
     * 删除配置
     */
    fun removeConfig(id: String): RunConfigurationManager {
        val newConfigs = configurations.filter { it.id != id }
        // 确保至少有一个配置
        val finalConfigs = if (newConfigs.isEmpty()) {
            listOf(RunConfiguration(name = "Debug"))
        } else {
            newConfigs
        }
        // 如果删除的是当前选中的，选择第一个
        val newSelectedId = if (selectedId == id) {
            finalConfigs.first().id
        } else {
            selectedId
        }
        return copy(configurations = finalConfigs, selectedId = newSelectedId)
    }

    /**
     * 复制配置
     */
    fun duplicateConfig(id: String): RunConfigurationManager {
        val original = configurations.find { it.id == id } ?: return this
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = original.name + Strings.run_config_name_copy_suffix.str()
        )
        return addConfig(copy)
    }
}

/**
 * 输出模式
 */
@Serializable
enum class OutputMode {
    /**
     * 在终端中运行
     */
    TERMINAL,

    /**
     * 在 SDL 图形运行时中运行（加载共享库）
     */
    SDL,

    /**
     * 在 Android NativeActivity 中运行共享库。
     *
     * 该模式用于 raylib 等导出 ANativeActivity_onCreate 的运行时，入口仍为普通 main，
     * 不经过 SDL_main。
     */
    NATIVE_ACTIVITY;

    fun isSdlGraphical(): Boolean = this == SDL

    fun isSharedLibraryGraphical(): Boolean = this == SDL || this == NATIVE_ACTIVITY
}

/**
 * 图形运行时屏幕方向
 */
@Serializable
enum class SdlOrientation {
    /** 跟随系统自动旋转 */
    AUTO,

    /** 强制横屏 */
    LANDSCAPE,

    /** 强制竖屏 */
    PORTRAIT
}

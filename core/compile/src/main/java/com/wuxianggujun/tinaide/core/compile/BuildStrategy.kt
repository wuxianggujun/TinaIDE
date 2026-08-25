package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import java.io.File
import kotlinx.serialization.Serializable

// 构建流水线共用模型。实际构建策略接口位于 strategy/BuildStrategy.kt。

/**
 * 构建选项
 *
 * @property buildType 构建类型（DEBUG/RELEASE）
 * @property generateDebugInfo 是否生成调试信息
 * @property parallelJobs 并行编译任务数，默认根据 CPU 核心数动态计算
 * @property compilerType 编译器类型（Clang/GCC）
 * @property onProgress 进度回调
 */
data class BuildOptions(
    val buildType: BuildType = BuildType.DEBUG,
    val generateDebugInfo: Boolean = true,
    val parallelJobs: Int = defaultParallelJobs(),
    /**
     * 单文件编译默认优化等级。
     *
     * 取值：`O0` / `O1` / `O2` / `O3`，非法值会在策略层回退到 `O2`。
     */
    val optimizationLevel: String = "O2",
    /**
     * CMake 配置阶段使用的构建类型。
     *
     * 用于解决「BuildType 只有 Debug/Release」无法表达
     * `RelWithDebInfo` / `MinSizeRel` 的问题。
     */
    val cmakeBuildType: CMakeBuildTypeOption = CMakeBuildTypeOption.DEBUG,
    /**
     * CMake 生成器选项。
     */
    val cmakeGenerator: CMakeGeneratorOption = CMakeGeneratorOption.NINJA,
    val compilerType: CompilerType = CompilerType.CLANG,
    /**
     * Android sysroot API level（已完成优先级解析）。
     *
     * 始终为合法值（21..35），默认 API 28。
     */
    val sysrootApiLevel: Int = MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL,
    /**
     * Active Android sysroot / NDK runtime profile captured at build planning time.
     *
     * It participates in artifact fingerprints so switching libc++_shared.so/sysroot
     * cannot accidentally reuse stale build outputs.
     */
    val sysrootProfileId: String? = null,
    val nativeCFlags: String = "",
    val nativeCppFlags: String = "",
    val nativeLdFlags: String = "",
    val nativeLdLibs: String = "",
    val nativeCMakeArgs: List<String> = emptyList(),
    val cppStandard: String? = null,
    val resolvedRunMode: LinuxRunModePolicy.RunMode = LinuxRunModePolicy.RunMode.NATIVE,
    /**
     * 工具链 ID（仅当 compilerType == CLANG 时生效）
     *
     * - null: 使用全局激活的工具链
     * - 非 null: 使用指定 ID 的工具链
     */
    val toolchainId: String? = null,
    /**
     * 是否为「运行」而构建。
     *
     * 用于运行场景下的链接与产物策略微调。
     */
    val buildForRun: Boolean = false,
    /**
     * 运行场景下是否优先产出共享库（.so）。
     *
     * 主要用于 SDL 图形运行模式：运行时会加载 .so，而不是 ELF 可执行文件。
     */
    val preferSharedLibraryForRun: Boolean = false,
    /**
     * 自定义 C 编译器路径（仅当 compilerType == CUSTOM 时生效）
     *
     * 路径应为 PRoot guest rootfs 内可执行文件路径。
     */
    val customCCompiler: String? = null,
    /**
     * 自定义 C++ 编译器路径（仅当 compilerType == CUSTOM 时生效）
     *
     * 路径应为 PRoot guest rootfs 内可执行文件路径。
     */
    val customCppCompiler: String? = null,
    /**
     * 单文件构建模式下指定的源文件（可选）。
     *
     * 仅 `BuildSystem.SINGLE_FILE` 的策略会使用该字段；其它策略应忽略。
     */
    val sourceFile: File? = null,
    val onProgress: ((String) -> Unit)? = null
) {
    companion object {
        /**
         * 根据 CPU 核心数计算默认并行任务数
         * 范围限制在 1-8 之间，避免过多任务导致内存不足
         */
        fun defaultParallelJobs(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    }
}

@Serializable
enum class CMakeBuildTypeOption(
    val cmakeValue: String,
    val generalBuildType: BuildType,
    val generatesDebugInfo: Boolean,
) {
    DEBUG("Debug", BuildType.DEBUG, true),
    RELEASE("Release", BuildType.RELEASE, false),
    REL_WITH_DEB_INFO("RelWithDebInfo", BuildType.DEBUG, true),
    MIN_SIZE_REL("MinSizeRel", BuildType.RELEASE, false);

    companion object {
        fun fromValue(value: String?): CMakeBuildTypeOption = when (value?.trim()) {
            RELEASE.cmakeValue -> RELEASE
            REL_WITH_DEB_INFO.cmakeValue -> REL_WITH_DEB_INFO
            MIN_SIZE_REL.cmakeValue -> MIN_SIZE_REL
            else -> DEBUG
        }
    }
}

enum class CMakeGeneratorOption(val cmakeValue: String) {
    UNIX_MAKEFILES("Unix Makefiles"),
    NINJA("Ninja");

    companion object {
        fun fromValue(value: String?): CMakeGeneratorOption = when (value?.trim()) {
            UNIX_MAKEFILES.cmakeValue -> UNIX_MAKEFILES
            else -> NINJA
        }
    }
}

enum class BuildType {
    DEBUG,
    RELEASE
}

/**
 * 配置结果
 */
sealed class ConfigureResult {
    data class Success(
        val message: String,
        val compileCommandsPath: File? = null
    ) : ConfigureResult()

    data class Error(val message: String) : ConfigureResult()
}

/**
 * 构建结果
 */
sealed class BuildResult {
    data class Success(
        val message: String,
        val buildTimeMs: Long,
        val outputPath: String?
    ) : BuildResult()

    data class Error(
        val rawOutput: String,
        val diagnostics: List<BuildDiagnostic>
    ) : BuildResult()
}

/**
 * 清理结果
 */
sealed class CleanResult {
    object Success : CleanResult()
    data class Error(val message: String) : CleanResult()
}

/**
 * 目标信息
 */
data class TargetInfo(
    val name: String,
    val type: Type,
    val sources: List<String>,
    val outputName: String? = null,
    val dependencies: List<String> = emptyList(),
) {
    enum class Type {
        EXECUTABLE,
        STATIC_LIBRARY,
        SHARED_LIBRARY,
        OTHER
    }
}

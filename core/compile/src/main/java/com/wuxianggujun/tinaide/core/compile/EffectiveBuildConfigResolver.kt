package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File

/**
 * 统一解析「运行配置 + 全局设置」得到实际生效的构建参数。
 *
 * 目标：
 * - 避免同类配置散落在 UseCase/Strategy 各处，导致优先级不透明；
 * - 让设置页面里已有配置真正进入构建链路；
 * - 提供单一事实来源，便于后续继续收敛配置模型。
 */
internal object EffectiveBuildConfigResolver {

    data class Input(
        val launch: LaunchIntent,
        val buildSystem: BuildSystem,
        val runConfig: RunConfiguration,
        val projectRoot: File,
        val linuxEnvironmentAvailable: Boolean,
        val appContext: Context? = null
    )

    data class EffectiveBuildConfig(
        val buildType: BuildType,
        val generateDebugInfo: Boolean,
        val optimizationLevel: String,
        val parallelJobs: Int,
        val cmakeBuildType: CMakeBuildTypeOption,
        val cmakeGenerator: CMakeGeneratorOption,
        val compilerType: CompilerType,
        val toolchainId: String?,
        val sysrootProfileId: String?,
        val customCCompiler: String?,
        val customCppCompiler: String?,
        val sysrootApiLevel: Int,
        val nativeCFlags: String,
        val nativeCppFlags: String,
        val nativeLdFlags: String,
        val nativeLdLibs: String,
        val nativeCMakeArgs: List<String>,
        val cppStandard: String?,
        val resolvedRunMode: LinuxRunModePolicy.RunMode
    )

    fun resolve(input: Input): EffectiveBuildConfig {
        val cmakeBuildType = resolveCMakeBuildType(
            launch = input.launch,
            buildSystem = input.buildSystem,
            configuredBuildType = input.runConfig.cmakeBuildType,
        )
        val cmakeGenerator = CMakeGeneratorOption.fromValue(Prefs.cmakeGenerator)
        val optimizationLevel = normalizeOptimizationLevel(Prefs.compilerOptimizationLevel)
        val parallelJobs = resolveParallelJobs(input.buildSystem)
        val projectMetadata = runCatching { ProjectMetadataStore.read(input.projectRoot) }.getOrNull()
        val toolchainId = input.runConfig.toolchainId?.trim()?.takeIf { it.isNotBlank() }
            ?: input.appContext
                ?.let { context ->
                    runCatching {
                        AndroidNativeToolchainManager(context).getConfigManager().getActiveToolchainId()
                    }.getOrNull()
                }
        val runConfigSysrootProfileId = input.runConfig.sysrootProfileId?.trim()?.takeIf { it.isNotBlank() }
        val sysrootProfileId = input.appContext
            ?.let { context ->
                runCatching {
                    AndroidSysrootManager(context).resolveProfileId(runConfigSysrootProfileId)
                }.getOrNull()
            }
            ?: runConfigSysrootProfileId
        val sysrootResolution = ProjectSysrootApiLevelResolver.resolve(
            projectRoot = input.projectRoot,
            runConfigApiLevel = input.runConfig.sysrootApiLevel
        )

        val (buildType, generateDebugInfo) = resolveBuildTypeAndDebugInfo(
            launch = input.launch,
            buildSystem = input.buildSystem,
            cmakeBuildType = cmakeBuildType,
            configuredBuildType = input.runConfig.buildType
        )

        return EffectiveBuildConfig(
            buildType = buildType,
            generateDebugInfo = generateDebugInfo,
            optimizationLevel = optimizationLevel,
            parallelJobs = parallelJobs,
            cmakeBuildType = cmakeBuildType,
            cmakeGenerator = cmakeGenerator,
            compilerType = input.runConfig.compilerType,
            toolchainId = toolchainId,
            sysrootProfileId = sysrootProfileId,
            customCCompiler = RunConfiguration.normalizeCompilerPath(input.runConfig.customCCompiler),
            customCppCompiler = RunConfiguration.normalizeCompilerPath(input.runConfig.customCppCompiler),
            sysrootApiLevel = sysrootResolution.apiLevel,
            nativeCFlags = projectMetadata?.normalizedNativeCFlags().orEmpty(),
            nativeCppFlags = projectMetadata?.normalizedNativeCppFlags().orEmpty(),
            nativeLdFlags = projectMetadata?.normalizedNativeLdFlags().orEmpty(),
            nativeLdLibs = projectMetadata?.normalizedNativeLdLibs().orEmpty(),
            nativeCMakeArgs = projectMetadata?.normalizedNativeCMakeArgs().orEmpty(),
            cppStandard = resolveCppStandard(input.buildSystem, input.runConfig, projectMetadata),
            resolvedRunMode = resolveRunMode(input)
        )
    }

    internal fun resolveBuildTypeAndDebugInfo(
        launch: LaunchIntent,
        buildSystem: BuildSystem,
        cmakeBuildType: CMakeBuildTypeOption,
        configuredBuildType: BuildType
    ): Pair<BuildType, Boolean> {
        if (launch is LaunchIntent.Debug) {
            return BuildType.DEBUG to true
        }

        if (buildSystem == BuildSystem.CMAKE) {
            return cmakeBuildType.generalBuildType to cmakeBuildType.generatesDebugInfo
        }

        return when (configuredBuildType) {
            BuildType.DEBUG -> BuildType.DEBUG to true
            BuildType.RELEASE -> BuildType.RELEASE to false
        }
    }

    internal fun resolveCMakeBuildType(
        launch: LaunchIntent,
        buildSystem: BuildSystem,
        configuredBuildType: CMakeBuildTypeOption,
    ): CMakeBuildTypeOption = when {
        buildSystem != BuildSystem.CMAKE -> CMakeBuildTypeOption.DEBUG
        launch is LaunchIntent.Debug -> CMakeBuildTypeOption.DEBUG
        else -> configuredBuildType
    }

    private fun resolveParallelJobs(buildSystem: BuildSystem): Int {
        val configured = when (buildSystem) {
            BuildSystem.CMAKE -> Prefs.cmakeParallelJobs
            else -> Prefs.compilerThreads
        }
        return configured.coerceIn(1, 8)
    }

    private fun normalizeOptimizationLevel(value: String?): String = when (value?.trim()?.uppercase()) {
        "O0" -> "O0"
        "O1" -> "O1"
        "O2" -> "O2"
        "O3" -> "O3"
        else -> "O2"
    }

    private fun resolveRunMode(input: Input): LinuxRunModePolicy.RunMode {
        val configuredMode = when (input.buildSystem) {
            BuildSystem.CMAKE -> Prefs.cmakeRunMode
            BuildSystem.MAKE -> Prefs.makeRunMode
            else -> LinuxRunModePolicy.MODE_NATIVE
        }
        return LinuxRunModePolicy.resolve(
            configuredMode = configuredMode,
            linuxEnvironmentAvailable = input.linuxEnvironmentAvailable
        )
    }

    private fun resolveCppStandard(
        buildSystem: BuildSystem,
        runConfig: RunConfiguration,
        metadata: ProjectMetadata?
    ): String? {
        if (buildSystem == BuildSystem.SINGLE_FILE) {
            resolveSingleFileCppStandard(runConfig)?.let { return it }
            return resolveProjectCppStandard(metadata) ?: CppStandard.DEFAULT.flag
        }
        return resolveProjectCppStandard(metadata)
    }

    private fun resolveProjectCppStandard(
        metadata: ProjectMetadata?
    ): String? {
        if (metadata == null) return null
        val configured = metadata.cppStandard?.trim().orEmpty()
        if (configured.isBlank()) return null
        return metadata.getCppStandard().flag.trim().ifBlank { "c++17" }
    }

    private fun resolveSingleFileCppStandard(runConfig: RunConfiguration): String? {
        val configured = RunConfiguration.normalizeSingleFileCppStandard(
            runConfig.singleFileCppStandard
        ) ?: return null
        return RunConfiguration.parseSingleFileCppStandard(configured)?.flag ?: configured
    }
}

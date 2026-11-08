package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VcpkgProjectInstaller(private val context: Context) {
    suspend fun install(project: File, onProgress: (String) -> Unit): ConfigureResult = withContext(Dispatchers.IO) {
        if (!File(project, "CMakeLists.txt").isFile) {
            return@withContext ConfigureResult.Error(Strings.vcpkg_cmake_required.strOr(context))
        }
        val runConfig = RunConfigurationManager.load(project.absolutePath).selectedConfig
        val effective = EffectiveBuildConfigResolver.resolve(EffectiveBuildConfigResolver.Input(
            launch = LaunchIntent.None, buildSystem = BuildSystem.CMAKE, runConfig = runConfig,
            projectRoot = project, linuxEnvironmentAvailable = true, appContext = context,
        ))
        if (effective.resolvedRunMode != LinuxRunModePolicy.RunMode.NATIVE) {
            return@withContext ConfigureResult.Error(Strings.vcpkg_native_only.strOr(context))
        }
        if (VcpkgProjectSettings.from(effective.nativeCMakeArgs).mode != VcpkgProjectSettings.MANIFEST) {
            return@withContext ConfigureResult.Error(Strings.vcpkg_manifest_mode_required.strOr(context))
        }
        NativeCMakeBuildExecutor(context).configure(
            projectDir = project,
            buildDir = File(project, "build/vcpkg-manager"),
            options = NativeCMakeBuildExecutor.Options(
                buildType = NativeCMakeBuildExecutor.CMakeBuildType.valueOf(effective.cmakeBuildType.name),
                generator = NativeCMakeBuildExecutor.CMakeGenerator.valueOf(effective.cmakeGenerator.name),
                parallelJobs = effective.parallelJobs, compilerType = effective.compilerType,
                toolchainId = effective.toolchainId, sysrootProfileId = effective.sysrootProfileId,
                sysrootApiLevel = effective.sysrootApiLevel, cCompilerPath = effective.customCCompiler,
                cxxCompilerPath = effective.customCppCompiler, cFlags = effective.nativeCFlags,
                cppFlags = effective.nativeCppFlags, ldFlags = effective.nativeLdFlags,
                ldLibs = effective.nativeLdLibs, cppStandard = effective.cppStandard,
                extraCMakeArgs = effective.nativeCMakeArgs, onProgress = onProgress,
            ),
        )
    }
}

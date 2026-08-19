package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy

/**
 * Stable CMake cache identity for build settings that must force reconfigure.
 *
 * CMake keeps compiler flags and cache variables across builds. These keys make
 * Tina's effective project settings visible in CMakeCache.txt so changing them
 * cannot silently reuse an old configured binary graph.
 */
data class CMakeConfigurationIdentity(
    val runMode: String,
    val compilerType: String,
    val toolchainId: String,
    val androidAbi: String,
    val sysrootProfileId: String,
    val sysrootApiLevel: String,
    val cppStandard: String,
    val cFlags: String,
    val cppFlags: String,
    val ldFlags: String,
    val ldLibs: String,
    val cmakeArgs: String,
) {
    fun asCMakeCacheEntries(): Map<String, String> = linkedMapOf(
        CMAKE_LINK_POLICY_VERSION_KEY to LINK_POLICY_VERSION,
        CMAKE_RUN_MODE_KEY to runMode,
        CMAKE_COMPILER_TYPE_KEY to compilerType,
        CMAKE_TOOLCHAIN_ID_KEY to toolchainId,
        CMAKE_ANDROID_ABI_KEY to androidAbi,
        NativeRuntimeIdentity.CMAKE_SYSROOT_PROFILE_ID_KEY to sysrootProfileId,
        NativeRuntimeIdentity.CMAKE_SYSROOT_API_LEVEL_KEY to sysrootApiLevel,
        CMAKE_CPP_STANDARD_KEY to cppStandard,
        CMAKE_NATIVE_C_FLAGS_KEY to cFlags,
        CMAKE_NATIVE_CPP_FLAGS_KEY to cppFlags,
        CMAKE_NATIVE_LD_FLAGS_KEY to ldFlags,
        CMAKE_NATIVE_LD_LIBS_KEY to ldLibs,
        CMAKE_NATIVE_CMAKE_ARGS_KEY to cmakeArgs,
    )

    companion object {
        /**
         * 链接策略版本。每次修改 IDE 注入的 CMake linker flags 都必须递增，
         * 强制旧构建目录重新 configure 并刷新缓存。
         */
        const val CMAKE_LINK_POLICY_VERSION_KEY = "TINA_LINK_POLICY_VERSION"
        const val LINK_POLICY_VERSION = "3"
        const val CMAKE_RUN_MODE_KEY = "TINA_RUN_MODE"
        const val CMAKE_COMPILER_TYPE_KEY = "TINA_COMPILER_TYPE"
        const val CMAKE_TOOLCHAIN_ID_KEY = "TINA_TOOLCHAIN_ID"
        const val CMAKE_ANDROID_ABI_KEY = "TINA_ANDROID_ABI"
        const val CMAKE_CPP_STANDARD_KEY = "TINA_CPP_STANDARD"
        const val CMAKE_NATIVE_C_FLAGS_KEY = "TINA_NATIVE_C_FLAGS"
        const val CMAKE_NATIVE_CPP_FLAGS_KEY = "TINA_NATIVE_CPP_FLAGS"
        const val CMAKE_NATIVE_LD_FLAGS_KEY = "TINA_NATIVE_LD_FLAGS"
        const val CMAKE_NATIVE_LD_LIBS_KEY = "TINA_NATIVE_LD_LIBS"
        const val CMAKE_NATIVE_CMAKE_ARGS_KEY = "TINA_NATIVE_CMAKE_ARGS"

        private const val ACTIVE_TOOLCHAIN_ID = "<active>"
        private const val PROOT_TOOLCHAIN_ID = "<proot>"

        fun from(options: BuildOptions, androidAbi: String = ""): CMakeConfigurationIdentity {
            val isNative = options.resolvedRunMode == LinuxRunModePolicy.RunMode.NATIVE
            return create(
                runMode = options.resolvedRunMode.name,
                compilerType = options.compilerType.name,
                toolchainId = cacheToolchainId(options.toolchainId, isNative),
                androidAbi = if (isNative) androidAbi else "",
                sysrootProfileId = NativeRuntimeIdentity.profileIdForCMake(options.sysrootProfileId),
                sysrootApiLevel = options.sysrootApiLevel.toString(),
                cppStandard = options.cppStandard,
                cFlags = options.nativeCFlags,
                cppFlags = options.nativeCppFlags,
                ldFlags = options.nativeLdFlags,
                ldLibs = options.nativeLdLibs,
                cmakeArgs = options.nativeCMakeArgs,
            )
        }

        fun create(
            runMode: String,
            compilerType: String,
            toolchainId: String?,
            androidAbi: String,
            sysrootProfileId: String?,
            sysrootApiLevel: Int,
            cppStandard: String?,
            cFlags: String,
            cppFlags: String,
            ldFlags: String,
            ldLibs: String,
            cmakeArgs: List<String>,
        ): CMakeConfigurationIdentity = create(
            runMode = runMode,
            compilerType = compilerType,
            toolchainId = toolchainId,
            androidAbi = androidAbi,
            sysrootProfileId = NativeRuntimeIdentity.profileIdForCMake(sysrootProfileId),
            sysrootApiLevel = sysrootApiLevel.toString(),
            cppStandard = cppStandard,
            cFlags = cFlags,
            cppFlags = cppFlags,
            ldFlags = ldFlags,
            ldLibs = ldLibs,
            cmakeArgs = cmakeArgs,
        )

        fun cacheToolchainId(toolchainId: String?, isNative: Boolean): String {
            if (!isNative) return PROOT_TOOLCHAIN_ID
            return normalizeScalar(toolchainId).ifBlank { ACTIVE_TOOLCHAIN_ID }
        }

        private fun create(
            runMode: String,
            compilerType: String,
            toolchainId: String?,
            androidAbi: String,
            sysrootProfileId: String,
            sysrootApiLevel: String,
            cppStandard: String?,
            cFlags: String,
            cppFlags: String,
            ldFlags: String,
            ldLibs: String,
            cmakeArgs: List<String>,
        ): CMakeConfigurationIdentity = CMakeConfigurationIdentity(
            runMode = normalizeScalar(runMode),
            compilerType = normalizeScalar(compilerType),
            toolchainId = normalizeScalar(toolchainId),
            androidAbi = normalizeScalar(androidAbi),
            sysrootProfileId = normalizeScalar(sysrootProfileId),
            sysrootApiLevel = normalizeScalar(sysrootApiLevel),
            cppStandard = normalizeScalar(cppStandard),
            cFlags = normalizeScalar(cFlags),
            cppFlags = normalizeScalar(cppFlags),
            ldFlags = normalizeScalar(ldFlags),
            ldLibs = normalizeScalar(ldLibs),
            cmakeArgs = normalizeArgs(cmakeArgs),
        )

        private fun normalizeScalar(value: String?): String = value?.trim().orEmpty()

        private fun normalizeArgs(args: List<String>): String = args
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
    }
}

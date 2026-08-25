package com.wuxianggujun.tinaide.core.compile.artifact

import kotlinx.serialization.Serializable

/**
 * 构建输入指纹，完整覆盖所有影响产物二进制结果的参数。
 */
@Serializable
data class BuildFingerprint(
    val compilerType: String,
    val compilerPath: String,
    val toolchainId: String?,
    val sysrootProfileId: String? = null,
    val sysrootApiLevel: Int,
    val buildType: String,
    val cmakeBuildType: String?,
    val cmakeGenerator: String?,
    val cFlags: String,
    val cppFlags: String,
    val ldFlags: String,
    val ldLibs: String,
    val cmakeExtraArgs: String,
    val cppStandard: String?,
    val optimizationLevel: String,
    val generateDebugInfo: Boolean,
    val preferSharedLibraryForRun: Boolean,
    val parallelJobs: Int,
    val resolvedRunMode: String,
    val artifactKind: String,
    val expectedOutputPath: String,
    val trackedInputsHash: String,
    val reconfigureInputsHash: String? = null,
    val extraEnvHash: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 7
    }
}

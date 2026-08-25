package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidSysrootManagerTest {
    private lateinit var context: Context
    private lateinit var manager: AndroidSysrootManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroot").deleteRecursively()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        manager = AndroidSysrootManager(context, testBuiltinManifest())
    }

    @Test
    fun getActiveProfile_shouldUseInstalledDefaultBundledProfileWhenNoConfig() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)

        val profile = manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profile?.id).isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(profile?.type).isEqualTo(SysrootProfileType.BUILTIN)
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(defaultProfileDir)
        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isTrue()
    }

    @Test
    fun isInstalled_shouldRejectSysrootWithoutLibcxxSharedRuntime() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(
            root = defaultProfileDir,
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            includeCxxSharedRuntime = false,
        )

        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isFalse()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)).isNull()
    }

    @Test
    fun isInstalled_shouldRejectSysrootWithoutStaticLibcxxRuntime() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(
            root = defaultProfileDir,
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            includeCxxStaticRuntime = false,
        )

        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isFalse()
        assertThat(
            manager.findMissingStaticCppRuntimeFile(
                apiLevel = 28,
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
            )
        ).isEqualTo(
            File(
                defaultProfileDir,
                "usr/lib/${AndroidSysrootManager.Companion.Arch.ARM64.triple}/libc++_static.a",
            )
        )
    }

    @Test
    fun isInstalled_shouldRejectSysrootWithoutApiLibcxxLinkerScript() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(
            root = defaultProfileDir,
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            includeApiCxxLinkerScript = false,
        )

        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isFalse()
        assertThat(
            manager.findMissingStaticCppRuntimeFile(
                apiLevel = 28,
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
            )
        ).isEqualTo(
            File(
                defaultProfileDir,
                "usr/lib/${AndroidSysrootManager.Companion.Arch.ARM64.triple}/28/libc++.a",
            )
        )
    }

    @Test
    fun isInstalled_shouldRejectSysrootWithoutApiLevelHeader() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(
            root = defaultProfileDir,
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            includeApiLevelHeader = false,
        )

        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isFalse()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)).isNull()
    }

    @Test
    fun activateProfile_shouldSwitchSysrootDirectory() {
        val profileDir = manager.getConfigManager().getProfileDir("custom-r27")
        createMinimalSysroot(profileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        val profile = profile(
            id = "custom-r27",
            path = "android-sysroots/custom-r27"
        )
        assertThat(manager.getConfigManager().registerOrReplaceProfile(profile).isSuccess).isTrue()

        val result = manager.activateProfile("custom-r27", AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)?.id)
            .isEqualTo("custom-r27")
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(profileDir)
    }

    @Test
    fun explicitProfile_shouldResolveSysrootWithoutSwitchingGlobalActiveProfile() {
        val activeProfileDir = manager.getConfigManager().getProfileDir("custom-r27")
        val explicitProfileDir = manager.getConfigManager().getProfileDir("custom-r28")
        createMinimalSysroot(activeProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        createMinimalSysroot(explicitProfileDir, AndroidSysrootManager.Companion.Arch.ARM64, apiLevel = 36)
        assertThat(
            manager.getConfigManager().registerOrReplaceProfile(
                profile(id = "custom-r27", path = "android-sysroots/custom-r27")
            ).isSuccess
        ).isTrue()
        assertThat(
            manager.getConfigManager().registerOrReplaceProfile(
                profile(id = "custom-r28", path = "android-sysroots/custom-r28", apiLevels = listOf(36))
            ).isSuccess
        ).isTrue()
        assertThat(manager.activateProfile("custom-r27", AndroidSysrootManager.Companion.Arch.ARM64).isSuccess).isTrue()

        assertThat(
            manager.getSysrootDir(
                AndroidSysrootManager.Companion.Arch.ARM64,
                profileId = "custom-r28"
            )
        ).isEqualTo(explicitProfileDir)
        assertThat(
            manager.getLibPath(
                apiLevel = 36,
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
                profileId = "custom-r28"
            )
        ).isEqualTo(File(explicitProfileDir, "usr/lib/${AndroidSysrootManager.Companion.Arch.ARM64.triple}/36").absolutePath)
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)?.id)
            .isEqualTo("custom-r27")
    }

    @Test
    fun listProfiles_shouldExposeBundledProfilesFromManifest() {
        val profiles = manager.listProfiles(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profiles.map { it.id }).contains("builtin-ndk-r27c-arm64")
        assertThat(profiles.map { it.id }).doesNotContain("builtin-current-arm64")
        assertThat(profiles.first { it.id == "builtin-ndk-r27c-arm64" }.type)
            .isEqualTo(SysrootProfileType.BUILTIN)
    }

    @Test
    fun activateOrInstallProfile_shouldSwitchAlreadyExtractedBundledProfile() = runBlocking {
        val profileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(profileDir, AndroidSysrootManager.Companion.Arch.ARM64)

        val result = manager.activateOrInstallProfile(
            profileId = "builtin-ndk-r27c-arm64",
            arch = AndroidSysrootManager.Companion.Arch.ARM64
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)?.id)
            .isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(profileDir)
    }

    @Test
    fun getActiveProfile_shouldIgnoreBrokenConfiguredProfile() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        val brokenProfile = profile(
            id = "broken",
            path = "android-sysroots/broken"
        )
        manager.getConfigManager().getProfileDir("broken").mkdirs()
        assertThat(manager.getConfigManager().registerOrReplaceProfile(brokenProfile).isSuccess).isTrue()
        assertThat(manager.getConfigManager().switchProfile("broken", AndroidSysrootManager.Companion.Arch.ARM64).isSuccess)
            .isTrue()

        val active = manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(active?.id).isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(active?.type).isEqualTo(SysrootProfileType.BUILTIN)
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(defaultProfileDir)
    }

    @Test
    fun listProfiles_shouldIgnoreStaleBuiltinCurrentProfile() {
        val staleBuiltin = profile(
            id = "builtin-current-arm64",
            path = "android-sysroots/builtin-current-arm64",
            type = SysrootProfileType.BUILTIN
        )
        assertThat(manager.getConfigManager().registerOrReplaceProfile(staleBuiltin).isSuccess).isTrue()

        val profiles = manager.listProfiles(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profiles.map { it.id }).doesNotContain("builtin-current-arm64")
        assertThat(profiles.map { it.id }).contains("builtin-ndk-r27c-arm64")
    }

    @Test
    fun explicitRemovedBuiltinProfile_shouldFallBackToDefaultBundledProfile() {
        val currentManager = AndroidSysrootManager(context, testCurrentBuiltinManifest())
        val defaultProfileDir = currentManager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        val staleProfileDir = currentManager.getConfigManager().getProfileDir("builtin-ndk-r25c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        createMinimalSysroot(staleProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        val staleBuiltin = profile(
            id = "builtin-ndk-r25c-arm64",
            path = "android-sysroots/builtin-ndk-r25c-arm64",
            type = SysrootProfileType.BUILTIN
        )
        assertThat(currentManager.getConfigManager().registerOrReplaceProfile(staleBuiltin).isSuccess).isTrue()

        assertThat(
            currentManager.resolveProfileId(
                profileId = "builtin-ndk-r25c-arm64",
                arch = AndroidSysrootManager.Companion.Arch.ARM64
            )
        ).isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(
            currentManager.getSysrootDir(
                AndroidSysrootManager.Companion.Arch.ARM64,
                profileId = "builtin-ndk-r25c-arm64"
            )
        ).isEqualTo(defaultProfileDir)
        assertThat(
            currentManager.getSysrootPath(
                AndroidSysrootManager.Companion.Arch.ARM64,
                profileId = "builtin-ndk-r25c-arm64"
            )
        ).isEqualTo(defaultProfileDir.absolutePath)
    }

    @Test
    fun resolveProfileId_shouldKeepActiveCurrentBuiltinProfileEvenWhenRepairNeeded() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        val brokenR28ProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r28c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        createMinimalSysroot(
            root = brokenR28ProfileDir,
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            includeApiLevelHeader = false,
        )
        val brokenR28Profile = profile(
            id = "builtin-ndk-r28c-arm64",
            path = "android-sysroots/builtin-ndk-r28c-arm64",
            type = SysrootProfileType.BUILTIN
        )
        assertThat(manager.getConfigManager().registerOrReplaceProfile(brokenR28Profile).isSuccess).isTrue()
        assertThat(
            manager.getConfigManager().switchProfile(
                "builtin-ndk-r28c-arm64",
                AndroidSysrootManager.Companion.Arch.ARM64
            ).isSuccess
        ).isTrue()

        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64, "builtin-ndk-r28c-arm64"))
            .isFalse()
        assertThat(manager.resolveProfileId(null, AndroidSysrootManager.Companion.Arch.ARM64))
            .isEqualTo("builtin-ndk-r28c-arm64")
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64))
            .isEqualTo(brokenR28ProfileDir)
    }

    private fun createMinimalSysroot(
        root: File,
        arch: AndroidSysrootManager.Companion.Arch,
        includeCxxSharedRuntime: Boolean = true,
        includeCxxStaticRuntime: Boolean = true,
        includeApiCxxLinkerScript: Boolean = true,
        includeApiLevelHeader: Boolean = true,
        apiLevel: Int = 28,
    ) {
        File(root, "usr/include").mkdirs()
        if (includeApiLevelHeader) {
            File(root, "usr/include/android").mkdirs()
            File(root, "usr/include/android/api-level.h").writeText("#define __ANDROID_API__ $apiLevel\n")
        }
        val runtimeDir = File(root, "usr/lib/${arch.triple}").apply { mkdirs() }
        val apiRuntimeDir = File(runtimeDir, apiLevel.toString()).apply { mkdirs() }
        if (includeCxxSharedRuntime) {
            File(runtimeDir, "libc++_shared.so").writeText("runtime")
        }
        if (includeCxxStaticRuntime) {
            File(runtimeDir, "libc++_static.a").writeText("runtime")
            File(runtimeDir, "libc++abi.a").writeText("runtime")
        }
        if (includeApiCxxLinkerScript) {
            File(apiRuntimeDir, "libc++.a").writeText("INPUT(-lc++_static -lc++abi)\n")
        }
    }

    private fun profile(
        id: String,
        path: String,
        arch: String = AndroidSysrootManager.Companion.Arch.ARM64.name,
        type: SysrootProfileType = SysrootProfileType.CUSTOM,
        apiLevels: List<Int> = listOf(28),
    ): SysrootProfileInfo = SysrootProfileInfo(
        id = id,
        name = id,
        arch = arch,
        type = type,
        path = path,
        installedAt = 100L,
        apiLevels = apiLevels,
        toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple
    )

    private fun testBuiltinManifest(): BuiltinSysrootProfileManifest = BuiltinSysrootProfileManifest(
        schemaVersion = 1,
        defaultProfileId = "builtin-ndk-r27c-arm64",
        profiles = listOf(
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r25c-arm64",
                name = "NDK r25c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r25c.tar.xz",
                ndkVersion = "r25c",
                ndkLlvmVersion = "14.0.7",
                apiLevels = listOf(21, 22, 23, 24, 26, 27, 28, 29, 30, 31, 32, 33),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = false
            ),
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r27c-arm64",
                name = "NDK r27c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r27c.tar.xz",
                ndkVersion = "r27c",
                ndkLlvmVersion = "18",
                apiLevels = listOf(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = true
            ),
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r28c-arm64",
                name = "NDK r28c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r28c.tar.xz",
                ndkVersion = "r28c",
                ndkLlvmVersion = "19",
                apiLevels = listOf(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = false
            )
        )
    )

    private fun testCurrentBuiltinManifest(): BuiltinSysrootProfileManifest = BuiltinSysrootProfileManifest(
        schemaVersion = 1,
        defaultProfileId = "builtin-ndk-r27c-arm64",
        profiles = listOf(
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r27c-arm64",
                name = "NDK r27c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r27c.tar.xz",
                ndkVersion = "r27c",
                ndkLlvmVersion = "18",
                apiLevels = listOf(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = true
            )
        )
    )
}

package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SysrootProfileConfigManagerTest {
    private lateinit var context: Context
    private lateinit var manager: SysrootProfileConfigManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        manager = SysrootProfileConfigManager(context)
    }

    @Test
    fun readConfig_shouldReturnEmptyConfigWhenFileDoesNotExist() {
        val config = manager.readConfig()

        assertThat(config.activeProfiles).isEmpty()
        assertThat(config.profiles).isEmpty()
    }

    @Test
    fun registerOrReplaceProfile_shouldReplaceSameId() {
        val first = profile(id = "custom", name = "first")
        val second = profile(id = "custom", name = "second")

        assertThat(manager.registerOrReplaceProfile(first).isSuccess).isTrue()
        assertThat(manager.registerOrReplaceProfile(second).isSuccess).isTrue()

        assertThat(manager.readConfig().profiles).containsExactly(second)
    }

    @Test
    fun switchProfile_shouldPersistActiveProfileWhenDirectoryExists() {
        val info = profile(id = "custom")
        manager.getProfileDir("custom").mkdirs()
        manager.registerOrReplaceProfile(info)

        val result = manager.switchProfile("custom", AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.readConfig().activeProfiles[AndroidSysrootManager.Companion.Arch.ARM64.name])
            .isEqualTo("custom")
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(info)
    }

    @Test
    fun listProfiles_shouldOnlyReturnMatchingArch() {
        val arm64 = profile(id = "arm64", arch = AndroidSysrootManager.Companion.Arch.ARM64.name)
        val x86 = profile(id = "x86", arch = AndroidSysrootManager.Companion.Arch.X86_64.name)
        manager.registerOrReplaceProfile(arm64)
        manager.registerOrReplaceProfile(x86)

        assertThat(manager.listProfiles(AndroidSysrootManager.Companion.Arch.ARM64)).containsExactly(arm64)
    }

    @Test
    fun readConfig_shouldMapLegacyLlvmVersionToNdkLlvmVersion() {
        File(context.filesDir, "sysroot-profile-config.json").writeText(
            """
            {
              "activeProfiles": {},
              "profiles": [
                {
                  "id": "legacy",
                  "name": "legacy",
                  "arch": "ARM64",
                  "type": "CUSTOM",
                  "path": "android-sysroots/legacy",
                  "installedAt": 100,
                  "ndkVersion": "r27c",
                  "llvmVersion": "18",
                  "apiLevels": [35],
                  "toolchainTriple": "aarch64-linux-android"
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val config = manager.readConfig()

        assertThat(config.profiles.single().ndkLlvmVersion).isEqualTo("18")
    }

    @Test
    fun registerOrReplaceProfile_shouldWriteNdkLlvmVersionField() {
        val info = profile(id = "custom", ndkLlvmVersion = "18")

        assertThat(manager.registerOrReplaceProfile(info).isSuccess).isTrue()

        val configText = File(context.filesDir, "sysroot-profile-config.json").readText(Charsets.UTF_8)
        assertThat(configText).contains("\"ndkLlvmVersion\"")
        assertThat(configText).doesNotContain("\"llvmVersion\"")
    }

    @Test
    fun removeProfile_shouldDeleteDirectoryAndConfigEntry() = runTest {
        val info = profile(id = "custom")
        val profileDir = manager.getProfileDir("custom")
        File(profileDir, "usr/include/stdio.h").apply {
            parentFile?.mkdirs()
            writeText("header", Charsets.UTF_8)
        }
        manager.registerOrReplaceProfile(info)

        val result = manager.removeProfile("custom")

        assertThat(result.isSuccess).isTrue()
        assertThat(profileDir.exists()).isFalse()
        assertThat(manager.readConfig().profiles).isEmpty()
    }

    private fun profile(
        id: String,
        name: String = id,
        arch: String = AndroidSysrootManager.Companion.Arch.ARM64.name,
        ndkLlvmVersion: String? = null,
    ): SysrootProfileInfo = SysrootProfileInfo(
        id = id,
        name = name,
        arch = arch,
        type = SysrootProfileType.CUSTOM,
        path = "android-sysroots/$id",
        installedAt = 100L,
        ndkLlvmVersion = ndkLlvmVersion,
        apiLevels = listOf(28),
        toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple
    )
}

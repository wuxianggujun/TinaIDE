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
class ToolchainConfigManagerTest {
    private lateinit var context: Context
    private lateinit var manager: ToolchainConfigManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "toolchain-config.json").delete()
        File(context.filesDir, "toolchains").deleteRecursively()
        manager = ToolchainConfigManager(context)
    }

    @Test
    fun readConfig_shouldReturnEmptyConfigWhenFileDoesNotExist() {
        val config = manager.readConfig()

        assertThat(config.activeToolchain).isNull()
        assertThat(config.toolchains).isEmpty()
    }

    @Test
    fun registerToolchain_shouldRejectDuplicateIds() {
        val info = toolchainInfo(id = "builtin")

        assertThat(manager.registerToolchain(info).isSuccess).isTrue()
        val duplicate = manager.registerToolchain(info)

        assertThat(duplicate.isFailure).isTrue()
        assertThat(duplicate.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(manager.readConfig().toolchains).containsExactly(info)
    }

    @Test
    fun switchToolchain_shouldPersistActiveToolchainWhenDirectoryExists() {
        val info = toolchainInfo(id = "custom", path = "toolchains/custom")
        manager.getToolchainDir("custom").mkdirs()
        manager.registerToolchain(info)

        val result = manager.switchToolchain("custom")

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.readConfig().activeToolchain).isEqualTo("custom")
        assertThat(manager.getActiveToolchainDir()!!.canonicalPath)
            .isEqualTo(File(context.filesDir, "toolchains/custom").canonicalPath)
    }

    @Test
    fun removeToolchain_shouldRejectActiveToolchain() = runTest {
        val info = toolchainInfo(id = "custom", path = "toolchains/custom")
        manager.getToolchainDir("custom").mkdirs()
        manager.saveConfig(
            InstalledToolchainConfig(
                activeToolchain = "custom",
                toolchains = listOf(info)
            )
        )

        val result = manager.removeToolchain("custom")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(manager.readConfig().toolchains).containsExactly(info)
    }

    @Test
    fun removeToolchain_shouldDeleteDirectoryAndConfigEntry() = runTest {
        val info = toolchainInfo(id = "custom", path = "toolchains/custom")
        val toolchainDir = manager.getToolchainDir("custom")
        File(toolchainDir, "bin/clang").apply {
            parentFile?.mkdirs()
            writeText("clang", Charsets.UTF_8)
        }
        manager.registerToolchain(info)

        val result = manager.removeToolchain("custom")

        assertThat(result.isSuccess).isTrue()
        assertThat(toolchainDir.exists()).isFalse()
        assertThat(manager.readConfig().toolchains).isEmpty()
    }

    private fun toolchainInfo(
        id: String,
        path: String = "toolchains/$id"
    ): ToolchainInfo = ToolchainInfo(
        id = id,
        name = id,
        version = "18",
        type = ToolchainType.CUSTOM,
        path = path,
        installedAt = 100L
    )
}

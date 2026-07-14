package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PRootManagerTest {

    private lateinit var context: Context
    private lateinit var nativeLibDir: File
    private lateinit var rootfsDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        nativeLibDir = File(context.filesDir, "native-libs").resetDirectory()
        rootfsDir = File(context.filesDir, "test-rootfs").resetDirectory()
        ProjectPaths.getPRootRoot(context).resetDirectory()

        context.applicationInfo.nativeLibraryDir = nativeLibDir.absolutePath
        File(nativeLibDir, "libproot.so").writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
        File(nativeLibDir, "libproot-loader.so").writeText("loader", Charsets.UTF_8)
    }

    @Test
    fun buildExecEnvironment_shouldApplyCachedLaunchConfigWithoutOuterLdPreload() {
        File(ProjectPaths.getPRootRoot(context), "proot-launch-config.txt").apply {
            parentFile?.mkdirs()
            writeText("guest-child-probe-v2|linker", Charsets.UTF_8)
        }
        val manager = PRootManager(context, rootfsDir.absolutePath)

        val env = manager.buildExecEnvironment(
            extraEnv = mapOf(
                "LANG" to "C.UTF-8",
                "CUSTOM_ENV" to "1",
            ),
        )

        assertThat(env).containsEntry("ROOTFS_PATH", rootfsDir.absolutePath)
        assertThat(env).containsEntry("PROOT_BIN", File(nativeLibDir, "libproot.so").absolutePath)
        assertThat(env).containsEntry("PROOT_LOADER", File(nativeLibDir, "libproot-loader.so").absolutePath)
        assertThat(env).containsEntry("PROOT_LAUNCH_MODE", "linker")
        assertThat(env).containsEntry("PROJECTS_HOST_DIR", ProjectPaths.getPrivateProjectsRoot(context).absolutePath)
        assertThat(env).containsEntry("WORKSPACE_HOST_DIR", ProjectPaths.getWorkspaceRoot(context).absolutePath)
        assertThat(env).containsEntry("LANG", "C.UTF-8")
        assertThat(env).containsEntry("CUSTOM_ENV", "1")
        assertThat(env).containsEntry("HOME", "/root")
        assertThat(env).containsEntry("TINA_BASE", context.filesDir.absolutePath)
        assertThat(env).doesNotContainKey("LD_PRELOAD")
    }

    @Test
    fun buildExecEnvironment_shouldIgnoreInvalidCachedLaunchConfig() {
        File(ProjectPaths.getPRootRoot(context), "proot-launch-config.txt").apply {
            parentFile?.mkdirs()
            writeText("guest-child-probe-v2|invalid", Charsets.UTF_8)
        }
        val manager = PRootManager(context, rootfsDir.absolutePath)

        val env = manager.buildExecEnvironment()

        assertThat(env).doesNotContainKey("PROOT_LAUNCH_MODE")
        assertThat(env).containsEntry("PROOT_BIN", File(nativeLibDir, "libproot.so").absolutePath)
    }

    @Test
    fun buildPRootCommandLine_shouldUseInitScriptWrapperAndPreserveCommand() {
        val manager = PRootManager(context, rootfsDir.absolutePath)
        val command = listOf("/bin/sh", "-lc", "echo ok")

        val prootCommand = manager.buildPRootCommandLine(command, workDir = "/workspace")

        assertThat(prootCommand.take(2)).containsExactly(
            "/system/bin/sh",
            File(ProjectPaths.getPRootRoot(context), "init-proot.sh").absolutePath,
        ).inOrder()
        assertThat(prootCommand.drop(2)).containsExactlyElementsIn(command).inOrder()
    }

    @Test
    fun toGuestPath_shouldMapPrivateProjectsAndWorkspaceRoots() {
        val manager = PRootManager(context, rootfsDir.absolutePath)
        val projectFile = File(ProjectPaths.getPrivateProjectsRoot(context), "demo/main.c")
        val workspaceFile = File(ProjectPaths.getWorkspaceRoot(context), "build/out.o")

        assertThat(manager.toGuestPath(projectFile.absolutePath)).isEqualTo("/projects/demo/main.c")
        assertThat(manager.toGuestPath(workspaceFile.absolutePath)).isEqualTo("/workspace/build/out.o")
        assertThat(manager.toGuestPath("/workspace/build/out.o")).isEqualTo("/workspace/build/out.o")
    }

    private fun File.resetDirectory(): File {
        deleteRecursively()
        mkdirs()
        return this
    }
}

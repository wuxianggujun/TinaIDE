package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class X11SocketLayoutTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun forRootfs_shouldPlaceTmpInsideRootfsSoHostAndGuestShareOneInode() {
        // guest 的 /tmp 在 --rootfs=<rootfsPath> 下解析到 host 的 <rootfsPath>/tmp，
        // 这是 host X server 与 guest client 唯一能对上的位置。
        val layout = X11SocketLayout.forRootfs("/data/rootfs")

        assertThat(layout.hostTmpDir).isEqualTo(File("/data/rootfs", "tmp"))
        assertThat(layout.guestTmpDir).isEqualTo("/tmp")
    }

    @Test
    fun hostSocketFile_shouldMatchTheAddressLorieHardcodes() {
        // cmdentrypoint.cpp: asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp)
        val layout = X11SocketLayout.forRootfs("/data/rootfs")

        assertThat(layout.hostSocketFile(0))
            .isEqualTo(File("/data/rootfs/tmp/.X11-unix", "X0"))
        assertThat(layout.hostSocketDir)
            .isEqualTo(File("/data/rootfs/tmp", ".X11-unix"))
    }

    @Test
    fun hostXkbConfigRoot_shouldResolveToRootfsSoGuestXkbDataIsReused() {
        // lorie 用 dirname($TMPDIR) 推导 chroot 根；<rootfs>/tmp 的 dirname 正是 <rootfs>。
        val layout = X11SocketLayout.forRootfs("/data/rootfs")

        assertThat(layout.hostXkbConfigRoot)
            .isEqualTo(File("/data/rootfs", "usr/share/X11/xkb"))
    }

    @Test
    fun guestDisplay_shouldBeRelativeToGuestNotHost() {
        assertThat(X11SocketLayout.forRootfs("/data/rootfs").guestDisplay(2)).isEqualTo(":2")
    }

    @Test
    fun prepare_shouldCreateSocketDirectoryAccessibleToTheGuest() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val layout = X11SocketLayout.forRootfs(rootfs.absolutePath)

        assertThat(layout.prepare().isSuccess).isTrue()

        assertThat(layout.hostSocketDir.isDirectory).isTrue()
    }

    @Test
    fun prepare_shouldBeIdempotent() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val layout = X11SocketLayout.forRootfs(rootfs.absolutePath)

        assertThat(layout.prepare().isSuccess).isTrue()
        assertThat(layout.prepare().isSuccess).isTrue()
    }

    @Test
    fun clearStaleSocket_shouldRemoveLeftoverFromAnAbortedServer() {
        // X server 走 _exit() 时不做清理，残留 socket 会让下次 bind 到同一 display 失败。
        val rootfs = temporaryFolder.newFolder("rootfs")
        val layout = X11SocketLayout.forRootfs(rootfs.absolutePath)
        layout.prepare().getOrThrow()
        val stale = layout.hostSocketFile(0).apply { writeText("stale") }

        layout.clearStaleSocket(0)

        assertThat(stale.exists()).isFalse()
    }

    @Test
    fun clearStaleSocket_shouldTolerateMissingSocket() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val layout = X11SocketLayout.forRootfs(rootfs.absolutePath)

        layout.clearStaleSocket(0)
    }

    @Test
    fun constructor_shouldRejectRelativeGuestPath() {
        val failure = runCatching {
            X11SocketLayout(hostTmpDir = File("/data/rootfs/tmp"), guestTmpDir = "tmp")
        }

        assertThat(failure.isFailure).isTrue()
    }
}

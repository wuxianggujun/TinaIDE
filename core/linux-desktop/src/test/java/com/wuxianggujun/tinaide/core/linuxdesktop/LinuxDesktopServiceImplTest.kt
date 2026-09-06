package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxDesktopServiceImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startX11Server_shouldFailInsteadOfReportingRunning_whenLauncherIsMissing() = runTest {
        val service = LinuxDesktopServiceImpl(
            serverLauncher = null,
            socketLayoutProvider = { layoutWithXkb() },
        )

        val result = service.startX11Server(":0", CONFIG)

        // 关键回归点：早期骨架实现会把状态直接置成 Running，于是 guest 拿到一个
        // 指向不存在的 X server 的 DISPLAY。没有 launcher 时必须失败。
        assertThat(result.isFailure).isTrue()
        assertThat(service.serverState.value).isInstanceOf(X11ServerState.Error::class.java)
        assertThat(service.getX11EnvironmentVariables()).isEmpty()
    }

    @Test
    fun startX11Server_shouldFail_whenRootfsIsNotInstalled() = runTest {
        val service = LinuxDesktopServiceImpl(
            serverLauncher = RecordingX11ServerLauncher(),
            socketLayoutProvider = { null },
        )

        assertThat(service.startX11Server(":0", CONFIG).isFailure).isTrue()
    }

    @Test
    fun startX11Server_shouldFail_whenXkbDataIsMissingFromRootfs() = runTest {
        // lorie 靠 dirname($TMPDIR) 推导 chroot 根再找 xkb，缺数据时 start() 只返回
        // false 并在 logcat 留一行，必须提前拦下来。
        val rootfs = temporaryFolder.newFolder("rootfs-without-xkb")
        val launcher = RecordingX11ServerLauncher()
        val service = LinuxDesktopServiceImpl(
            serverLauncher = launcher,
            socketLayoutProvider = { X11SocketLayout.forRootfs(rootfs.absolutePath) },
        )

        assertThat(service.startX11Server(":0", CONFIG).isFailure).isTrue()
        assertThat(launcher.launchCount).isEqualTo(0)
    }

    @Test
    fun startX11Server_shouldRejectMalformedDisplay() = runTest {
        val service = LinuxDesktopServiceImpl(
            serverLauncher = RecordingX11ServerLauncher(),
            socketLayoutProvider = { layoutWithXkb() },
        )

        assertThat(service.startX11Server("not-a-display", CONFIG).isFailure).isTrue()
    }

    @Test
    fun startX11Server_shouldExportDisplayAndPrepareSocketDirectory_onSuccess() = runTest {
        val layout = layoutWithXkb()
        val launcher = RecordingX11ServerLauncher()
        val service = LinuxDesktopServiceImpl(
            serverLauncher = launcher,
            socketLayoutProvider = { layout },
        )

        assertThat(service.startX11Server(":2", CONFIG).isSuccess).isTrue()

        assertThat(service.serverState.value).isEqualTo(X11ServerState.Running(":2"))
        assertThat(layout.hostSocketDir.isDirectory).isTrue()
        assertThat(launcher.lastArgs?.displayNumber).isEqualTo(2)
        // 不导出 XAUTHORITY：服务器以 -ac 启动，隔离由 Android 沙箱负责。
        assertThat(service.getX11EnvironmentVariables()).containsExactly("DISPLAY", ":2")
    }

    @Test
    fun stopX11Server_shouldTerminateLauncherAndClearEnvironment() = runTest {
        val launcher = RecordingX11ServerLauncher()
        val service = LinuxDesktopServiceImpl(
            serverLauncher = launcher,
            socketLayoutProvider = { layoutWithXkb() },
        )
        service.startX11Server(":1", CONFIG).getOrThrow()

        service.stopX11Server()

        assertThat(launcher.terminateCount).isEqualTo(1)
        assertThat(service.serverState.value).isEqualTo(X11ServerState.Stopped)
        assertThat(service.getX11EnvironmentVariables()).isEmpty()
    }

    @Test
    fun getX11EnvironmentVariables_shouldBeEmpty_whenServerWasNeverStarted() {
        assertThat(LinuxDesktopServiceImpl().getX11EnvironmentVariables()).isEmpty()
    }

    private fun layoutWithXkb(): X11SocketLayout {
        val rootfs = temporaryFolder.newFolder("rootfs-${counter++}")
        File(rootfs, "usr/share/X11/xkb").mkdirs()
        return X11SocketLayout.forRootfs(rootfs.absolutePath)
    }

    private var counter = 0

    private class RecordingX11ServerLauncher : X11ServerLauncher {
        var launchCount: Int = 0
            private set
        var terminateCount: Int = 0
            private set
        var lastArgs: X11ServerArgs? = null
            private set

        override suspend fun launch(
            args: X11ServerArgs,
            layout: X11SocketLayout,
        ): Result<String> {
            launchCount += 1
            lastArgs = args
            return Result.success(layout.guestDisplay(args.displayNumber))
        }

        override suspend fun terminate() {
            terminateCount += 1
        }

        override fun isAlive(): Boolean = launchCount > terminateCount
    }

    private companion object {
        private val CONFIG = X11DisplayConfig(width = 1280, height = 720, dpi = 160)
    }
}

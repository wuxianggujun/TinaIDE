package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxDesktopSessionPlanTest {

    @Test
    fun plan_shouldUseEndpointOwnedDisplayAndAudioEnvironment() {
        val plan = LinuxDesktopSessionPlanner().plan(
            endpoint = LinuxDesktopEndpoint(
                display = ":3",
                audioServer = "tcp:127.0.0.1:4713",
                environment = mapOf(
                    "GALLIUM_DRIVER" to "virpipe",
                    "DISPLAY" to ":99",
                ),
            ),
            spec = LinuxDesktopLaunchSpec(
                command = listOf("dbus-run-session", "startxfce4"),
                workingDirectory = "/root",
                environment = mapOf(
                    "LANG" to "zh_CN.UTF-8",
                    "PULSE_SERVER" to "invalid-session-value",
                ),
            ),
        )

        assertThat(plan.command).containsExactly("dbus-run-session", "startxfce4").inOrder()
        assertThat(plan.workingDirectory).isEqualTo("/root")
        // DISPLAY / PULSE_SERVER 由 endpoint 独占：调用方传的值必须被覆盖，
        // 否则一个畸形的 launch 就能让会话逃出 X11 边界。
        assertThat(plan.environment).containsExactlyEntriesIn(
            mapOf(
                "LANG" to "zh_CN.UTF-8",
                "GALLIUM_DRIVER" to "virpipe",
                "DISPLAY" to ":3",
                "PULSE_SERVER" to "tcp:127.0.0.1:4713",
            )
        )
    }

    @Test
    fun endpoint_shouldRejectInvalidDisplayAndEnvironmentKeys() {
        val invalidDisplay = runCatching { LinuxDesktopEndpoint(display = "not-a-display") }
        val invalidEnvironment = runCatching {
            LinuxDesktopEndpoint(
                display = ":0",
                environment = mapOf("INVALID-NAME" to "value"),
            )
        }

        assertThat(invalidDisplay.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(invalidEnvironment.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun ubuntuPlanner_shouldKeepX11AndRuntimeValuesManaged() {
        val plan = UbuntuDesktopSessionPlanner().plan(
            UbuntuDesktopSessionOptions(
                endpoint = LinuxDesktopEndpoint(display = ":7"),
                username = "dev",
                locale = "zh_CN.UTF-8",
                gpuBackend = UbuntuDesktopGpuBackend.VIRGL,
                environment = mapOf(
                    "DISPLAY" to ":99",
                    "XDG_RUNTIME_DIR" to "/tmp/attacker-owned",
                    "GDK_BACKEND" to "wayland",
                    "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                ),
            ),
        )

        assertThat(plan.environment["DISPLAY"]).isEqualTo(":7")
        assertThat(plan.environment["XDG_RUNTIME_DIR"]).isEqualTo("/tmp/runtime-dev")
        assertThat(plan.environment["GDK_BACKEND"]).isEqualTo("x11")
        assertThat(plan.environment["QT_QPA_PLATFORM"]).isEqualTo("xcb")
        assertThat(plan.environment["MESA_LOADER_DRIVER_OVERRIDE"]).isEqualTo("virpipe")
        assertThat(plan.environment["GALLIUM_DRIVER"]).isEqualTo("virpipe")
    }

    @Test
    fun ubuntuSessionOptions_shouldRejectUnsafeIdentityAndLocale() {
        assertThat(
            runCatching {
                UbuntuDesktopSessionOptions(
                    endpoint = LinuxDesktopEndpoint(display = ":0"),
                    username = "root;id",
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            runCatching {
                UbuntuDesktopSessionOptions(
                    endpoint = LinuxDesktopEndpoint(display = ":0"),
                    locale = "C UTF-8",
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun hostProcessPlan_shouldRejectControlCharactersInEnvironment() {
        val withNul = runCatching {
            LinuxDesktopHostProcessPlan(
                argv = listOf("/system/bin/sh", "init-proot.sh"),
                environment = mapOf("DISPLAY" to ":0" + NUL + "extra"),
                workingDirectory = "/data/data/app/files",
            )
        }
        val withNewline = runCatching {
            LinuxDesktopHostProcessPlan(
                argv = listOf("/system/bin/sh"),
                environment = mapOf("DISPLAY" to ":0\nPATH=/evil"),
                workingDirectory = "/data/data/app/files",
            )
        }
        val argvWithNul = runCatching {
            LinuxDesktopHostProcessPlan(
                argv = listOf("/system/bin/sh", "init" + NUL + "proot.sh"),
                environment = emptyMap(),
                workingDirectory = "/data/data/app/files",
            )
        }

        // 环境是以 `KEY=VALUE` 数组跨 binder 传的：换行能在解码侧伪造出额外条目。
        assertThat(withNul.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(withNewline.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(argvWithNul.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun hostProcessPlan_shouldRoundTripEnvironmentThroughTheBinderEncoding() {
        val plan = LinuxDesktopHostProcessPlan(
            argv = listOf("/system/bin/sh", "/data/init-proot.sh", "startxfce4"),
            environment = mapOf(
                "DISPLAY" to ":0",
                // 值里带 `=` 必须原样保留：解码只能按第一个 `=` 切分。
                "PS1" to "\${PWD#/}# ",
                "ROOTFS_PATH" to "/data/data/app/rootfs",
            ),
            workingDirectory = "/data/data/app/files",
        )

        val decoded = LinuxDesktopHostProcessPlan.decodeEnvironment(plan.toEnvironmentArray())

        assertThat(decoded).isEqualTo(plan.environment)
    }

    @Test
    fun hostProcessPlan_shouldIgnoreMalformedEnvironmentEntriesWhenDecoding() {
        val decoded = LinuxDesktopHostProcessPlan.decodeEnvironment(
            arrayOf("DISPLAY=:0", "no-separator", "=missing-key"),
        )

        assertThat(decoded).containsExactly("DISPLAY", ":0")
    }

    private companion object {
        /** 直接写字面量 NUL 会让源文件变成二进制，这里用码点构造。 */
        val NUL: Char = 0.toChar()
    }
}

package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PRootBootstrapModelsTest {

    @Test
    fun packageInfo_shouldMatchOnlyExactPackageName() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "clang-format",
            displayName = "Clang Format",
            status = PRootBootstrap.PackageStatus.INSTALLING
        )

        assertThat(packageInfo.matchesPackageName("clang-format")).isTrue()
        assertThat(packageInfo.matchesPackageName("clang")).isFalse()
        assertThat(packageInfo.matchesPackageName("Clang-Format")).isFalse()
        assertThat(packageInfo.matchesPackageName(" clang-format ")).isFalse()
    }

    @Test
    fun packageInfo_shouldRejectBlankPackageName() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "cmake",
            displayName = "CMake"
        )

        assertThat(packageInfo.matchesPackageName(null)).isFalse()
        assertThat(packageInfo.matchesPackageName("")).isFalse()
        assertThat(packageInfo.matchesPackageName("   ")).isFalse()
    }

    @Test
    fun packageInfo_shouldDefaultToPendingStatus() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "ninja-build",
            displayName = "Ninja"
        )

        assertThat(packageInfo.status).isEqualTo(PRootBootstrap.PackageStatus.PENDING)
    }

    @Test
    fun finishBootstrapInstall_shouldClearLifecycleBeforePublishingInstalled() {
        val events = mutableListOf<String>()

        finishBootstrapInstall(
            terminalState = PRootBootstrap.BootstrapState.Installed,
            clearInstalling = { events += "clearInstalling" },
            clearCurrentJob = { events += "clearCurrentJob" },
            publishTerminalState = { events += "publishInstalled" },
        )

        assertThat(events).containsExactly(
            "clearInstalling",
            "clearCurrentJob",
            "publishInstalled",
        ).inOrder()
    }

    @Test
    fun finishBootstrapInstall_shouldPublishFailedOnlyAfterLifecycleCleanup() {
        assertThat(finishEvents(PRootBootstrap.BootstrapState.Failed("failed"))).containsExactly(
            "clearInstalling",
            "clearCurrentJob",
            "publishFailed",
        ).inOrder()
    }

    @Test
    fun finishBootstrapInstall_shouldPublishIdleOnlyAfterLifecycleCleanup() {
        assertThat(finishEvents(PRootBootstrap.BootstrapState.Idle)).containsExactly(
            "clearInstalling",
            "clearCurrentJob",
            "publishIdle",
        ).inOrder()
    }

    private fun finishEvents(terminalState: PRootBootstrap.BootstrapState): List<String> = buildList {
        finishBootstrapInstall(
            terminalState = terminalState,
            clearInstalling = { add("clearInstalling") },
            clearCurrentJob = { add("clearCurrentJob") },
            publishTerminalState = { state ->
                add(
                    when (state) {
                        PRootBootstrap.BootstrapState.Idle -> "publishIdle"
                        PRootBootstrap.BootstrapState.Installed -> "publishInstalled"
                        is PRootBootstrap.BootstrapState.Failed -> "publishFailed"
                        is PRootBootstrap.BootstrapState.Installing -> "publishInstalling"
                        PRootBootstrap.BootstrapState.NeedsToolchainRepair -> "publishNeedsToolchainRepair"
                    }
                )
            },
        )
    }
}

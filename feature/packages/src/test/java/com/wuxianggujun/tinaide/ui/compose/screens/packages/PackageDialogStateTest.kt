package com.wuxianggujun.tinaide.ui.compose.screens.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.UpdateInfo
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.Platform
import org.junit.Test

class PackageDialogStateTest {

    @Test
    fun batchInstalling_shouldCarrySelectionAndProgressContext() {
        val state = PackageDialogState.BatchInstalling(
            packageIds = listOf("cmake", "ninja"),
            platform = Platform.ANDROID,
            currentIndex = 1,
            totalCount = 2,
            currentPackageName = "Ninja",
            event = InstallProgressEvent.Downloading(downloaded = 50L, total = 100L, speed = 10L)
        )

        assertThat(state.packageIds).containsExactly("cmake", "ninja").inOrder()
        assertThat(state.platform).isEqualTo(Platform.ANDROID)
        assertThat(state.currentIndex).isEqualTo(1)
        assertThat(state.totalCount).isEqualTo(2)
        assertThat(state.currentPackageName).isEqualTo("Ninja")
    }

    @Test
    fun batchInstallConfirm_shouldCarryInstallPlans() {
        val plan = packageInstallPlan("cmake")
        val state = PackageDialogState.BatchInstallConfirm(
            packageIds = listOf("cmake"),
            platform = Platform.ANDROID,
            plans = listOf(plan)
        )

        assertThat(state.packageIds).containsExactly("cmake")
        assertThat(state.platform).isEqualTo(Platform.ANDROID)
        assertThat(state.plans).containsExactly(plan)
    }

    @Test
    fun batchUpdateStates_shouldCarryUpdateSummary() {
        val update = UpdateInfo(
            packageId = "cmake",
            packageName = "CMake",
            currentVersion = "3.27",
            newVersion = "3.29",
            platform = Platform.LINUX
        )
        val updating = PackageDialogState.BatchUpdating(
            currentIndex = 0,
            totalCount = 1,
            currentPackageName = "CMake",
            event = InstallProgressEvent.Preparing("start")
        )
        val confirm = PackageDialogState.BatchUpdateConfirm(
            updates = listOf(update),
            plans = listOf(packageInstallPlan("cmake"))
        )
        val complete = PackageDialogState.BatchComplete(
            operation = PackageBatchOperation.UPDATE,
            result = PackageBatchResult(total = 1, succeeded = 1, failures = emptyList(), cancelled = false),
        )

        assertThat(confirm.updates).containsExactly(update)
        assertThat(confirm.plans.single().packageId).isEqualTo("cmake")
        assertThat(updating.currentPackageName).isEqualTo("CMake")
        assertThat(complete.result.succeeded).isEqualTo(1)
        assertThat(complete.result.unfinished).isEqualTo(0)
    }

    private fun packageInstallPlan(packageId: String): PackageInstallPlan = PackageInstallPlan(
        packageId = packageId,
        packageName = packageId,
        platform = Platform.ANDROID,
        packages = listOf(
            PackageInstallPlanItem(
                packageId = "sdl3",
                packageName = "SDL3",
                version = "3.2.0",
                isRoot = false,
                isAlreadyInstalled = false
            ),
            PackageInstallPlanItem(
                packageId = packageId,
                packageName = packageId,
                version = "1.0.0",
                isRoot = true,
                isAlreadyInstalled = false
            )
        )
    )
}

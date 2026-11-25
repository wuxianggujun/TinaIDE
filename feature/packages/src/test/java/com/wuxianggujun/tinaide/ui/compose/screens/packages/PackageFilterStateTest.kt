package com.wuxianggujun.tinaide.ui.compose.screens.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.model.PlatformInstallState
import org.junit.Test

class PackageFilterStateTest {
    @Test
    fun installedOnly_shouldKeepInstalledAndUpdatablePackagesButExcludeCatalogEntries() {
        val pkg = GUIPackage(id = "raylib", name = "raylib")
        val filter = PackageFilterState(installedOnly = true)
        assertThat(filter.matches(pkg, PackageInstallState())).isFalse()
        assertThat(filter.matches(pkg, PackageInstallState(android = PlatformInstallState.Installed("6.0")))).isTrue()
        assertThat(filter.matches(pkg, PackageInstallState(linux = PlatformInstallState.UpdateAvailable("5.0", "6.0")))).isTrue()
        assertThat(filter.copy(searchQuery = "fmt").matches(pkg, PackageInstallState(android = PlatformInstallState.Installed("6.0")))).isFalse()
        assertThat(PackageFilterState().matches(pkg, PackageInstallState())).isTrue()
    }

    @Test
    fun filterState_shouldDefaultToBlankSearchQueryAndSupportCopy() {
        val defaultState = PackageFilterState()
        val queriedState = defaultState.copy(searchQuery = "cmake")

        assertThat(defaultState.searchQuery).isEmpty()
        assertThat(queriedState.searchQuery).isEqualTo("cmake")
    }

    @Test
    fun uiStateCopy_shouldCarryFilteredSelectionAndDetailPackage() {
        val cmake = GUIPackage(id = "cmake", name = "CMake")
        val ninja = GUIPackage(id = "ninja", name = "Ninja")

        val state = PackageManagerUiState().copy(
            packages = listOf(cmake, ninja),
            filteredPackages = listOf(cmake),
            selectedPackageIds = setOf("cmake"),
            isSelectionMode = true,
            currentDetailPackage = cmake,
        )

        assertThat(state.packages).containsExactly(cmake, ninja).inOrder()
        assertThat(state.filteredPackages).containsExactly(cmake)
        assertThat(state.selectedPackageIds).containsExactly("cmake")
        assertThat(state.isSelectionMode).isTrue()
        assertThat(state.currentDetailPackage).isEqualTo(cmake)
    }
}

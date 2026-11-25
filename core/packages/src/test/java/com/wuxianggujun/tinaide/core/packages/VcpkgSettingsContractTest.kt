package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import org.junit.Test

class VcpkgSettingsContractTest {
    @Test fun obsoletePrebuiltModeAndPathsAreNotLoaded() {
        val arguments = listOf("-DTINA_VCPKG_MODE=prebuilt", "-DTINA_VCPKG_ROOT=/old/tools", "-DTINA_VCPKG_INSTALLED_DIR=/old/libs")
        assertThat(VcpkgProjectSettings.from(arguments)).isEqualTo(VcpkgProjectSettings())
        assertThat(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).applyTo(arguments + "-DKEEP=1"))
            .containsExactly("-DKEEP=1", "-DTINA_VCPKG_MODE=manifest").inOrder()
    }

    @Test fun sharedModeStoresNoDeviceSpecificDirectories() {
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        val arguments = settings.applyTo(emptyList())
        assertThat(arguments).containsExactly("-DTINA_VCPKG_MODE=shared")
        assertThat(VcpkgProjectSettings.from(arguments)).isEqualTo(settings)
        assertThat(settings.targetTriplet("x86_64")).isEqualTo("x64-android")
        assertThat(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).targetTriplet("arm64-v8a"))
            .isEqualTo("arm64-tina-android")
    }
}

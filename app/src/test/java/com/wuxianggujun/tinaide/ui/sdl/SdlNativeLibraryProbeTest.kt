package com.wuxianggujun.tinaide.ui.sdl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.libsdl.app.SdlNativeLibraryProbe

class SdlNativeLibraryProbeTest {
    @Test
    fun supportedVersions_shouldExcludeIncompatibleAndUnknownJniGenerations() {
        listOf(3_004_000, 3_004_010, 3_005_000).forEach {
            assertThat(SdlNativeLibraryProbe.isSupported(it)).isTrue()
        }
        listOf(0, 2_032_010, 3_002_010, 3_006_000, 4_000_000).forEach {
            assertThat(SdlNativeLibraryProbe.isSupported(it)).isFalse()
        }
    }
}

package com.wuxianggujun.tinaide.core.compile.artifact

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcpkgBuildInputsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun replacingLibraryAtSamePathWithSameSizeAndTimestampChangesFingerprint() {
        val project = temporary.newFolder()
        val root = temporary.newFolder()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.PREBUILT, root.path)
        val library = File(root, "installed/arm64-android/lib/libfmt.a").apply {
            parentFile.mkdirs(); writeText("old library")
        }
        fun hash() = TrackedInputHasher.hashFiles(VcpkgBuildInputs.collect(project, config.applyTo(emptyList()), "arm64-v8a", false), project)
        val before = hash()
        val timestamp = library.lastModified()
        library.writeText("new library")
        library.setLastModified(timestamp)
        assertThat(hash()).isNotEqualTo(before)
        val replaced = hash()
        library.delete()
        assertThat(hash()).isNotEqualTo(replaced)
    }

    @Test
    fun manifestTracksOnlySelectedAbiAndConfigurationAlongWithStatusAndHeaders() {
        val project = temporary.newFolder()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, "tools/vcpkg")
        fun file(path: String) = File(project, path).apply { parentFile.mkdirs(); writeText(path) }
        val header = file("vcpkg_installed/arm64-tina-android/include/fmt/core.h")
        val debug = file("vcpkg_installed/arm64-tina-android/debug/lib/libfmt.a")
        val release = file("vcpkg_installed/arm64-tina-android/lib/libfmt.a")
        val status = file("vcpkg_installed/vcpkg/status")
        file("vcpkg_installed/x64-tina-android/lib/libother.a")
        file("tools/vcpkg/buildtrees/fmt/unrelated.cpp")
        assertThat(VcpkgBuildInputs.collect(project, config.applyTo(emptyList()), "arm64-v8a", true))
            .containsExactly(header, debug, release, status)
        assertThat(VcpkgBuildInputs.collect(project, config.applyTo(emptyList()), "arm64-v8a", false))
            .containsExactly(header, release, status)
        assertThat(VcpkgBuildInputs.collect(project, emptyList(), "arm64-v8a", true)).isEmpty()
    }
}

package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArchitecture
import com.wuxianggujun.tinaide.core.linuxdistro.DistroPackageManager
import com.wuxianggujun.tinaide.core.linuxdistro.InstalledLinuxDistro
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class LinuxDistroRootfsProfileMapperTest {

    @Test
    fun toRootfsProfile_shouldMapSelfHostedInstallationToLinuxProfile() {
        val rootfsDir = createRootfs(
            osRelease = """
                ID=debian
                NAME="Debian GNU/Linux"
                PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
            """.trimIndent(),
        )
        val installation = installedLinuxDistro(rootfsDir)

        val profile = LinuxDistroRootfsProfileMapper.toRootfsProfile(
            installation = installation,
            now = UPDATED_AT,
        )

        assertThat(profile.id).isEqualTo("linux-distro:debian")
        assertThat(profile.sourceType).isEqualTo(RootfsSourceType.LINUX_DISTRO)
        assertThat(profile.displayName).isEqualTo("Debian GNU/Linux")
        assertThat(profile.distroId).isEqualTo("debian")
        assertThat(profile.packageManager).isEqualTo(RootfsPackageManager.APT)
        assertThat(profile.shellPath).isEqualTo("/bin/sh")
        assertThat(profile.createdAt).isEqualTo(INSTALLED_AT)
        assertThat(profile.updatedAt).isEqualTo(UPDATED_AT)
    }

    @Test
    fun toRootfsPackageManager_shouldPreserveSupportedManagers() {
        val mapped = with(LinuxDistroRootfsProfileMapper) {
            listOf(
                DistroPackageManager.APK.toRootfsPackageManager(),
                DistroPackageManager.APT.toRootfsPackageManager(),
                DistroPackageManager.PACMAN.toRootfsPackageManager(),
                DistroPackageManager.DNF.toRootfsPackageManager(),
            )
        }

        assertThat(mapped).containsExactly(
            RootfsPackageManager.APK,
            RootfsPackageManager.APT,
            RootfsPackageManager.PACMAN,
            RootfsPackageManager.DNF,
        ).inOrder()
    }

    @Test
    fun toRootfsPackageManager_shouldMapUnsupportedManagersToUnknownForNow() {
        val mapped = with(LinuxDistroRootfsProfileMapper) {
            listOf(
                DistroPackageManager.ZYPPER.toRootfsPackageManager(),
                DistroPackageManager.XBPS.toRootfsPackageManager(),
                DistroPackageManager.UNKNOWN.toRootfsPackageManager(),
            )
        }

        assertThat(mapped).containsExactly(
            RootfsPackageManager.UNKNOWN,
            RootfsPackageManager.UNKNOWN,
            RootfsPackageManager.UNKNOWN,
        ).inOrder()
    }

    private fun createRootfs(osRelease: String): File {
        val rootfsDir = createTempDirectory("linux-distro-profile").toFile()
        File(rootfsDir, "bin/sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }
        File(rootfsDir, "etc/os-release").apply {
            parentFile?.mkdirs()
            writeText(osRelease)
        }
        return rootfsDir
    }

    private fun installedLinuxDistro(rootfsDir: File): InstalledLinuxDistro = InstalledLinuxDistro(
        distroId = "debian",
        releaseId = "12",
        architecture = DistroArchitecture.AARCH64,
        displayName = "Debian GNU/Linux",
        packageManager = DistroPackageManager.APT,
        rootfsPath = rootfsDir.absolutePath,
        archivePath = File(rootfsDir.parentFile, "debian.tar.gz").absolutePath,
        checksum = null,
        installedAtEpochMillis = INSTALLED_AT,
        updatedAtEpochMillis = UPDATED_AT,
    )

    private companion object {
        private const val INSTALLED_AT = 1_800_000_000_000L
        private const val UPDATED_AT = 1_800_000_001_000L
    }
}

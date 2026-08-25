package com.wuxianggujun.tinaide.core.linuxdistro

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxDistroModelsTest {

    @Test
    fun architecture_shouldResolveFromAndroidAbi() {
        assertThat(DistroArchitecture.fromAndroidAbi("arm64-v8a")).isEqualTo(DistroArchitecture.AARCH64)
        assertThat(DistroArchitecture.fromAndroidAbi("armeabi-v7a")).isEqualTo(DistroArchitecture.ARM)
        assertThat(DistroArchitecture.fromAndroidAbi("x86_64")).isEqualTo(DistroArchitecture.X86_64)
        assertThat(DistroArchitecture.fromAndroidAbi("x86")).isEqualTo(DistroArchitecture.I686)
        assertThat(DistroArchitecture.fromAndroidAbi("mips")).isNull()
    }

    @Test
    fun archiveFormat_shouldMapToTarCompressionType() {
        assertThat(DistroArchiveFormat.TAR.compressionType()).isEqualTo(TarExtractor.CompressionType.NONE)
        assertThat(DistroArchiveFormat.TAR_GZ.compressionType()).isEqualTo(TarExtractor.CompressionType.GZIP)
        assertThat(DistroArchiveFormat.TAR_XZ.compressionType()).isEqualTo(TarExtractor.CompressionType.XZ)
        assertThat(DistroArchiveFormat.TAR_ZST.compressionType()).isEqualTo(TarExtractor.CompressionType.ZSTD)
    }

    @Test
    fun checksum_shouldNormalizeValueAndRejectBlankInput() {
        val uppercaseChecksum = "AB".repeat(32)
        val checksum = DistroChecksum(
            algorithm = DistroChecksumAlgorithm.SHA256,
            value = uppercaseChecksum
        )

        assertThat(checksum.normalizedValue).isEqualTo(uppercaseChecksum.lowercase())
        assertThrows(IllegalArgumentException::class.java) {
            DistroChecksum(DistroChecksumAlgorithm.SHA256, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DistroChecksum(DistroChecksumAlgorithm.SHA256, "ab".repeat(31))
        }
    }

    @Test
    fun artifact_shouldRejectUnsupportedUrlAndNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) {
            artifact(url = "file:///tmp/rootfs.tar.gz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            artifact(url = "https:///rootfs.tar.gz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            artifact(url = "https://user@example.com/rootfs.tar.gz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            artifact(sizeBytes = 0L)
        }
    }

    @Test
    fun urlValidationErrors_shouldNotExposeOriginalUrl() {
        val secret = "private-query-token"

        val artifactError = assertThrows(IllegalArgumentException::class.java) {
            artifact(url = "http://example.com/rootfs.tar.gz?token=$secret")
        }
        val mirrorError = assertThrows(IllegalArgumentException::class.java) {
            DistroMirrorRule(
                matchPrefix = "http://example.com/?token=$secret",
                replaceWith = "https://mirror.example.com/",
            )
        }

        assertThat(artifactError).hasMessageThat().doesNotContain(secret)
        assertThat(mirrorError).hasMessageThat().doesNotContain(secret)
    }

    @Test
    fun definition_shouldResolveDefaultAndRequestedRelease() {
        val stable = release(id = "stable")
        val edge = release(id = "edge")
        val definition = DistroDefinition(
            id = "alpine",
            family = DistroFamily.ALPINE,
            displayName = "Alpine",
            packageManager = DistroPackageManager.APK,
            defaultReleaseId = "stable",
            releases = listOf(stable, edge)
        )

        assertThat(definition.defaultRelease()).isEqualTo(stable)
        assertThat(definition.release(null)).isEqualTo(stable)
        assertThat(definition.release("edge")).isEqualTo(edge)
        assertThat(definition.release("missing")).isNull()
    }

    @Test
    fun definition_shouldRejectUnsafeIdsAndMissingDefaultRelease() {
        assertThrows(IllegalArgumentException::class.java) {
            DistroDefinition(
                id = "bad/id",
                family = DistroFamily.CUSTOM,
                displayName = "Bad",
                packageManager = DistroPackageManager.UNKNOWN,
                defaultReleaseId = "stable",
                releases = listOf(release(id = "stable"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DistroDefinition(
                id = "custom",
                family = DistroFamily.CUSTOM,
                displayName = "Custom",
                packageManager = DistroPackageManager.UNKNOWN,
                defaultReleaseId = "missing",
                releases = listOf(release(id = "stable"))
            )
        }
    }

    private fun release(id: String): DistroRelease = DistroRelease(
        id = id,
        version = id,
        displayName = id,
        artifacts = listOf(artifact())
    )

    private fun artifact(
        url: String = "https://example.com/rootfs.tar.gz",
        sizeBytes: Long? = 1024L,
    ): DistroArtifact = DistroArtifact(
        architecture = DistroArchitecture.AARCH64,
        url = url,
        format = DistroArchiveFormat.TAR_GZ,
        checksum = DistroChecksum(DistroChecksumAlgorithm.SHA256, "ab".repeat(32)),
        sizeBytes = sizeBytes
    )
}

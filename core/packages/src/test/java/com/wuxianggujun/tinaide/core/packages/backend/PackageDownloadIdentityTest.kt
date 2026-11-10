package com.wuxianggujun.tinaide.core.packages.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageDownloadIdentityTest {
    @Test fun perAbiSourceChecksumDoesNotRequireAVersionWideChecksum() {
        val digest = "sha256:" + "a".repeat(64)
        assertThat(resolvePackageDownloadChecksum(digest, null)).isEqualTo(digest)
        assertThat(resolvePackageDownloadChecksum(null, digest)).isEqualTo(digest)
        assertThat(resolvePackageDownloadChecksum(digest, "malformed fallback")).isEqualTo(digest)
        assertThat(resolvePackageDownloadChecksum("malformed source", digest)).isNull()
        assertThat(resolvePackageDownloadChecksum(null, null)).isNull()
    }
    @Test fun retriesAndEquivalentMirrorsShareAnIdentityButDifferentContentDoesNot() {
        val checksum = "sha256:" + "a".repeat(64)
        val original = packageDownloadIdentity("sdl3", "3.5.0", "x86_64", checksum)
        assertThat(packageDownloadIdentity("sdl3", "3.5.0", "x86_64", checksum.uppercase())).isEqualTo(original)
        assertThat(packageDownloadIdentity("sdl3", "3.5.0", "arm64-v8a", checksum)).isNotEqualTo(original)
        assertThat(packageDownloadIdentity("sdl3", "3.5.0", "x86_64", "sha256:" + "b".repeat(64))).isNotEqualTo(original)
    }
}

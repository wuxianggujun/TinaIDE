package com.wuxianggujun.tinaide.core.linuxdistro

import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class DistroFamily {
    ALPINE,
    DEBIAN,
    UBUNTU,
    ARCH,
    FEDORA,
    OPENSUSE,
    VOID,
    CUSTOM,
}

@Serializable
enum class DistroPackageManager {
    APK,
    APT,
    PACMAN,
    DNF,
    ZYPPER,
    XBPS,
    UNKNOWN,
}

@Serializable
enum class DistroArchitecture(val androidAbis: Set<String>) {
    AARCH64(setOf("arm64-v8a")),
    ARM(setOf("armeabi-v7a")),
    X86_64(setOf("x86_64")),
    I686(setOf("x86"));

    companion object {
        fun fromAndroidAbi(abi: String): DistroArchitecture? = entries.firstOrNull { architecture -> abi in architecture.androidAbis }
    }
}

@Serializable
enum class DistroArchiveFormat {
    TAR,
    TAR_GZ,
    TAR_XZ,
    TAR_ZST;

    fun compressionType(): TarExtractor.CompressionType = when (this) {
        TAR -> TarExtractor.CompressionType.NONE
        TAR_GZ -> TarExtractor.CompressionType.GZIP
        TAR_XZ -> TarExtractor.CompressionType.XZ
        TAR_ZST -> TarExtractor.CompressionType.ZSTD
    }
}

@Serializable
enum class DistroChecksumAlgorithm {
    SHA256,
}

@Serializable
data class DistroChecksum(
    val algorithm: DistroChecksumAlgorithm,
    val value: String,
) {
    init {
        when (algorithm) {
            DistroChecksumAlgorithm.SHA256 -> require(value.matches(Regex("(?i)^[0-9a-f]{64}$"))) {
                "SHA-256 checksum must contain exactly 64 hexadecimal characters."
            }
        }
    }

    @Transient
    val normalizedValue: String = value.lowercase()
}

/**
 * 镜像替换规则（清单级）。
 *
 * 下载时若 artifact 的 url 以 [matchPrefix] 开头，则可派生出
 * 把该前缀替换为 [replaceWith] 后的镜像候选地址，用于在官方源不可达时回落。
 * 一条规则覆盖某发行版全部架构，避免逐 artifact 重复列镜像 URL。
 */
@Serializable
data class DistroMirrorRule(
    val matchPrefix: String,
    val replaceWith: String,
) {
    init {
        require(matchPrefix.isStrictHttpsUrl()) {
            "Mirror matchPrefix must use a valid HTTPS URL"
        }
        require(replaceWith.isStrictHttpsUrl()) {
            "Mirror replaceWith must use a valid HTTPS URL"
        }
    }

    /** 若 [url] 命中本规则前缀则返回派生后的镜像地址，否则返回 null。 */
    fun deriveOrNull(url: String): String? =
        if (url.startsWith(matchPrefix)) replaceWith + url.removePrefix(matchPrefix) else null
}

@Serializable
data class DistroArtifact(
    val architecture: DistroArchitecture,
    val url: String,
    val format: DistroArchiveFormat,
    val checksum: DistroChecksum,
    val sizeBytes: Long? = null,
    val signatureUrl: String? = null,
) {
    init {
        require(url.isStrictHttpsUrl()) {
            "Artifact URL must use a valid HTTPS URL"
        }
        require(sizeBytes == null || sizeBytes > 0L) { "Artifact size must be positive when provided." }
        require(signatureUrl == null || signatureUrl.isStrictHttpsUrl()) {
            "Artifact signature URL must use a valid HTTPS URL"
        }
    }
}

@Serializable
data class DistroRelease(
    val id: String,
    val version: String,
    val displayName: String,
    val channel: String = "stable",
    val artifacts: List<DistroArtifact>,
) {
    init {
        require(id.isSafeId()) { "Unsafe release id: $id" }
        require(displayName.isNotBlank()) { "Release display name must not be blank." }
    }

    fun artifactFor(architecture: DistroArchitecture): DistroArtifact? = artifacts.firstOrNull { artifact -> artifact.architecture == architecture }
}

@Serializable
data class DistroDefinition(
    val id: String,
    val family: DistroFamily,
    val displayName: String,
    val packageManager: DistroPackageManager,
    val defaultReleaseId: String,
    val homepageUrl: String? = null,
    val releases: List<DistroRelease>,
) {
    init {
        require(id.isSafeId()) { "Unsafe distro id: $id" }
        require(displayName.isNotBlank()) { "Distro display name must not be blank." }
        require(defaultReleaseId.isSafeId()) { "Unsafe default release id: $defaultReleaseId" }
        require(releases.isNotEmpty()) { "Distro must define at least one release: $id" }
        require(releases.map { it.id }.distinct().size == releases.size) {
            "Distro releases must be unique: $id"
        }
        require(releases.any { it.id == defaultReleaseId }) {
            "Distro default release must exist: $id/$defaultReleaseId"
        }
    }

    fun defaultRelease(): DistroRelease? = releases.firstOrNull { release -> release.id == defaultReleaseId }

    fun release(releaseId: String?): DistroRelease? = if (releaseId.isNullOrBlank()) defaultRelease() else releases.firstOrNull { it.id == releaseId }
}

data class ResolvedDistroArtifact(
    val distro: DistroDefinition,
    val release: DistroRelease,
    val artifact: DistroArtifact,
)

internal fun String.isSafeId(): Boolean = isNotBlank() && all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }

internal fun String.isStrictHttpsUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

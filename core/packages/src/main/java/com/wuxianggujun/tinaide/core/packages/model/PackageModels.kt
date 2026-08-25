package com.wuxianggujun.tinaide.core.packages.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    @SerialName("linux")
    LINUX,

    @SerialName("android")
    ANDROID
}

@Serializable
enum class InstallType {
    @SerialName("apt")
    APT,

    // 历史协议值，当前 Linux guest 会按发行版包管理器安装系统包
    @SerialName("download")
    DOWNLOAD,

    @SerialName("script")
    SCRIPT
}

@Serializable
enum class PackageArtifactType {
    @SerialName("source")
    SOURCE,

    @SerialName("header")
    HEADER,

    @SerialName("static")
    STATIC,

    @SerialName("shared")
    SHARED,

    @SerialName("executable")
    EXECUTABLE,

    @SerialName("mixed")
    MIXED
}

@Serializable
data class GUIPackage(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val homepage: String? = null,
    val linux: PlatformPackage? = null,
    val android: PlatformPackage? = null,
    @SerialName("is_bundled") val isBundled: Boolean = false
)

@Serializable
data class PlatformPackage(
    val version: String,
    @SerialName("artifact_type") val artifactType: PackageArtifactType = PackageArtifactType.MIXED,
    @SerialName("install_type") val installType: InstallType,
    @SerialName("apt_package") val aptPackage: String? = null,
    val size: Long? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("download_sources") val downloadSources: List<DownloadSource>? = null,
    val checksum: String? = null,
    val abi: List<String>? = null,
    @SerialName("is_latest") val isLatest: Boolean = false,
    val dependencies: List<String>? = null,
    @SerialName("release_notes") val releaseNotes: String? = null
)

@Serializable
data class PackageVersion(
    val id: Int,
    @SerialName("package_id") val packageId: String,
    val platform: Platform,
    val version: String,
    @SerialName("artifact_type") val artifactType: PackageArtifactType = PackageArtifactType.MIXED,
    @SerialName("install_type") val installType: InstallType,
    @SerialName("apt_package") val aptPackage: String? = null,
    @SerialName("apt_repository") val aptRepository: String? = null,
    @SerialName("download_size") val downloadSize: Long? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("download_sources") val downloadSources: List<DownloadSource>? = null,
    val checksum: String? = null,
    val abi: List<String>? = null,
    @SerialName("install_script") val installScript: String? = null,
    @SerialName("uninstall_script") val uninstallScript: String? = null,
    val dependencies: List<String>? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("is_latest") val isLatest: Boolean = false
)

@Serializable
data class DownloadSource(
    val id: Int,
    val name: String,
    val url: String,
    val region: String? = null,
    val priority: Int,
    @SerialName("supports_range") val supportsRange: Boolean = true,
    val abi: String? = null,
    val size: Long? = null,
    val checksum: String? = null
)

@Serializable
data class DownloadInfo(
    @SerialName("package_id") val packageId: String,
    val version: String,
    val platform: Platform,
    @SerialName("install_type") val installType: InstallType,
    val size: Long? = null,
    val checksum: String? = null,
    val sources: List<DownloadSource>
)

@Serializable
data class PackageCategory(
    val id: String,
    val name: String,
    @SerialName("name_en") val nameEn: String? = null,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)

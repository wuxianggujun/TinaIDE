package com.wuxianggujun.tinaide.plugin.marketplace

import com.wuxianggujun.tinaide.plugin.PluginCompatibility
import com.wuxianggujun.tinaide.plugin.PluginManifestValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginPublisher(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null
)

@Serializable
data class PluginSummary(
    val id: String,
    @SerialName("plugin_id")
    val pluginId: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("icon_url")
    val iconUrl: String? = null,
    val publisher: PluginPublisher,
    @SerialName("latest_version")
    val latestVersion: String? = null,
    @SerialName("updated_at")
    val updatedAt: String
)

@Serializable
data class PluginVersion(
    val version: String,
    @SerialName("version_code")
    val versionCode: Int,
    @SerialName("file_size")
    val fileSize: Long,
    @SerialName("file_hash")
    val fileHash: String? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("api_version")
    val apiVersion: Int = 1,
    @SerialName("min_app_version")
    val minAppVersion: String? = null,
    val changelog: String? = null,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class PluginDetail(
    val id: String,
    @SerialName("plugin_id")
    val pluginId: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("icon_url")
    val iconUrl: String? = null,
    @SerialName("repository_url")
    val repositoryUrl: String? = null,
    @SerialName("homepage_url")
    val homepageUrl: String? = null,
    val license: String? = null,
    val publisher: PluginPublisher,
    val versions: List<PluginVersion> = emptyList(),
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
) {
    fun latestVersionEntry(): PluginVersion? = versions.maxWithOrNull(
        compareBy<PluginVersion> { it.versionCode }.thenBy { it.version }
    )

    internal fun latestCompatibleVersionEntry(hostVersion: String?): PluginVersion? = versions
        .asSequence()
        .filter { version ->
            version.apiVersion == PluginManifestValidator.SUPPORTED_API_VERSION &&
                PluginCompatibility.evaluate(hostVersion, version.minAppVersion).isCompatible
        }
        .maxWithOrNull(compareBy<PluginVersion> { it.versionCode }.thenBy { it.version })

    internal fun compatibleWith(hostVersion: String?): PluginDetail = copy(
        versions = versions
            .filter { version ->
                version.apiVersion == PluginManifestValidator.SUPPORTED_API_VERSION &&
                    PluginCompatibility.evaluate(hostVersion, version.minAppVersion).isCompatible
            }
            .sortedWith(compareByDescending<PluginVersion> { it.versionCode }.thenByDescending { it.version }),
    )
}

@Serializable
data class Pagination(
    val page: Int,
    val limit: Int,
    val total: Long,
    @SerialName("total_pages")
    val totalPages: Int
)

@Serializable
data class PluginListData(
    val plugins: List<PluginSummary>,
    val pagination: Pagination
)

@Serializable
data class CheckUpdateItem(
    @SerialName("plugin_id")
    val pluginId: String,
    val version: String
)

@Serializable
data class PluginUpdateInfo(
    @SerialName("plugin_id")
    val pluginId: String,
    @SerialName("current_version")
    val currentVersion: String,
    @SerialName("latest_version")
    val latestVersion: String,
    @SerialName("download_url")
    val downloadUrl: String,
    val changelog: String? = null,
    @SerialName("file_size")
    val fileSize: Long? = null
)

@Serializable
data class CheckUpdateData(
    val updates: List<PluginUpdateInfo>
)

@Serializable
enum class PluginSortType(val value: String) {
    NEWEST("newest"),
    UPDATED("updated")
}

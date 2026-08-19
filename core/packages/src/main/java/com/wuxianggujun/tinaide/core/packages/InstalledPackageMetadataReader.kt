package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import kotlinx.serialization.Serializable

/**
 * 读取已安装包目录中的 package.json，供 UI 或业务层查询库自身版本。
 */
object InstalledPackageMetadataReader {

    private const val INSTALL_DIR_NAME = "installed-packages"
    private const val PACKAGE_METADATA_FILE_NAME = "package.json"

    fun read(context: Context, packageId: String): InstalledPackageMetadata? {
        if (!RegistryPackageId.isValid(packageId)) return null
        val metadataFile = resolveMetadataFile(context, packageId)
        if (!metadataFile.isFile) return null
        return JsonSerializer.decodeFromFileOrNull<InstalledPackageMetadata>(metadataFile)
            ?.takeIf { metadata ->
                metadata.id == packageId && metadata.name.isNotBlank() && metadata.version.isNotBlank()
            }
    }

    fun readVersion(context: Context, packageId: String): String? = read(context, packageId)?.version

    fun readUpstreamVersion(context: Context, packageId: String): String? = read(context, packageId)?.upstreamVersion

    private fun resolveMetadataFile(context: Context, packageId: String): File {
        RegistryPackageId.requireValid(packageId)
        return File(context.filesDir, INSTALL_DIR_NAME)
            .resolve(packageId)
            .resolve(PACKAGE_METADATA_FILE_NAME)
    }
}

@Serializable
data class InstalledPackageMetadata(
    val id: String,
    val name: String,
    val version: String,
    val packageRevision: Int? = null,
    val upstreamName: String? = null,
    val upstreamVersion: String? = null,
    val upstreamTag: String? = null,
    val upstreamCommit: String? = null,
    val description: String? = null,
    val platform: String? = null,
    val artifactType: String? = null,
    val installType: String? = null,
    val category: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val installedAt: Long? = null,
    val files: InstalledPackageFiles? = null,
    val abis: List<String>? = null
)

@Serializable
data class InstalledPackageFiles(
    val include: String? = null,
    val source: String? = null,
    val lib: String? = null,
    val pkgconfig: String? = null
)

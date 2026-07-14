package com.wuxianggujun.tinaide.plugin.marketplace

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginPackageExpectation
import com.wuxianggujun.tinaide.plugin.PluginStateSnapshot
import com.wuxianggujun.tinaide.plugin.PluginTypes
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

data class MarketplacePendingPluginInstall(
    val requestedPluginId: String,
    val requestedVersion: String?,
    val packageFile: File,
    val manifest: PluginManifest,
    val permissions: Set<PluginPermission>,
    val needsUserConfirmation: Boolean,
)

class PluginMarketplaceRepository(
    private val context: Context,
    private val api: PluginMarketplaceApi = PluginMarketplaceApi.create(context.applicationContext),
    private val pluginManager: PluginManager = PluginManager.getInstance(context)
) {
    companion object {
        private const val TAG = "PluginMarketRepo"
    }

    private val pluginLogManager = PluginLogManager.getInstance(context.applicationContext)
    private val permissionManager = PluginPermissionManager.getInstance(context.applicationContext)
    private val downloadDir: File by lazy {
        File(context.cacheDir, "plugin_downloads").also { it.mkdirs() }
    }

    init {
        Timber.tag(TAG).i(
            "PluginMarketplaceRepository using PluginManager instance=%s",
            pluginManager.instanceId
        )
        pluginLogManager.info(
            PluginHostLogSources.Marketplace,
            "Marketplace repository using PluginManager instance=${pluginManager.instanceId}"
        )
    }

    val pluginStateFlow: StateFlow<PluginStateSnapshot>
        get() = pluginManager.pluginStateFlow

    suspend fun listPlugins(
        page: Int = 1,
        limit: Int = 20,
        category: String? = null,
        search: String? = null,
        sort: String? = null
    ): ApiResult<PluginListData> = api.listPlugins(page, limit, category, search, sort)

    suspend fun getPluginDetail(pluginId: String): ApiResult<PluginDetail> = api.getPluginDetail(pluginId)

    suspend fun checkUpdates(): ApiResult<CheckUpdateData> {
        val installed = pluginManager.listInstalledPlugins()
        if (installed.isEmpty()) {
            return ApiResult.Success(CheckUpdateData(emptyList()))
        }
        val items = installed.map { plugin ->
            CheckUpdateItem(plugin.manifest.id, plugin.manifest.version)
        }
        return api.checkUpdates(items)
    }

    suspend fun preparePluginInstall(
        pluginId: String,
        version: String? = null,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<MarketplacePendingPluginInstall> = withContext(Dispatchers.IO) {
        var pendingFile: File? = null
        var retainPendingFile = false
        try {
            pluginManager.validatePluginId(pluginId)

            val targetFile = File(downloadDir, "$pluginId-${UUID.randomUUID()}.tinaplug")
            pendingFile = targetFile
            Timber.tag(TAG).i(
                "Download and inspect plugin started pluginId=%s version=%s manager=%s",
                pluginId,
                version ?: "",
                pluginManager.instanceId
            )
            pluginLogManager.info(
                PluginHostLogSources.Marketplace,
                "Download and inspect started pluginId=$pluginId version=${version.orEmpty()} manager=${pluginManager.instanceId}"
            )

            val downloadResult = api.downloadPlugin(
                pluginId = pluginId,
                version = version,
                targetFile = targetFile,
                onProgress = onProgress
            )

            when (downloadResult) {
                is ApiResult.Success -> {
                    val manifest = pluginManager.inspectPluginPackage(downloadResult.data).getOrElse { error ->
                        targetFile.delete()
                        throw error
                    }
                    require(manifest.id == pluginId) {
                        Strings.plugin_error_marketplace_id_mismatch.strOr(context, pluginId, manifest.id)
                    }
                    version?.let { expectedVersion ->
                        require(manifest.version == expectedVersion) {
                            Strings.plugin_error_marketplace_version_mismatch.strOr(
                                context,
                                expectedVersion,
                                manifest.version,
                            )
                        }
                    }
                    val permissions = PluginPermission.parseList(manifest.permissions)
                    val isExecutablePlugin = manifest.type.equals(PluginTypes.SCRIPT, ignoreCase = true) ||
                        manifest.type.equals(PluginTypes.HYBRID, ignoreCase = true)
                    val installPermissions = if (isExecutablePlugin) permissions else emptySet()
                    retainPendingFile = true
                    Result.success(
                        MarketplacePendingPluginInstall(
                            requestedPluginId = pluginId,
                            requestedVersion = version,
                            packageFile = targetFile,
                            manifest = manifest,
                            permissions = installPermissions,
                            needsUserConfirmation = isExecutablePlugin &&
                                permissionManager.getRequiredPermissionsForInstall(installPermissions).needsUserConfirmation,
                        )
                    )
                }
                is ApiResult.Error -> {
                    Timber.tag(TAG).w(
                        "Download plugin failed: pluginId=%s, version=%s, code=%d, message=%s",
                        pluginId,
                        version ?: "",
                        downloadResult.code,
                        downloadResult.message
                    )
                    pluginLogManager.warn(
                        PluginHostLogSources.Marketplace,
                        "Download failed pluginId=$pluginId version=${version.orEmpty()} code=${downloadResult.code} message=${downloadResult.message}"
                    )
                    Result.failure(Exception(downloadResult.message))
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w(
                        "Download plugin failed (network): pluginId=%s, version=%s, message=%s",
                        pluginId,
                        version ?: "",
                        downloadResult.message
                    )
                    pluginLogManager.warn(
                        PluginHostLogSources.Marketplace,
                        "Download failed by network pluginId=$pluginId version=${version.orEmpty()} message=${downloadResult.message}"
                    )
                    Result.failure(Exception(downloadResult.message))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download and inspect failed")
            pluginLogManager.error(
                source = PluginHostLogSources.Marketplace,
                message = "Download and inspect crashed pluginId=$pluginId version=${version.orEmpty()} manager=${pluginManager.instanceId} reason=${e.message.orEmpty()}",
                stackTrace = e.stackTraceToString()
            )
            Result.failure(e)
        } finally {
            if (!retainPendingFile) pendingFile?.delete()
        }
    }

    suspend fun confirmPluginInstall(
        pending: MarketplacePendingPluginInstall,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        withContext(NonCancellable) {
            val previousGrants = permissionManager.getGrantedPermissions(pending.requestedPluginId)
            val result = runCatching {
                if (pending.permissions.isNotEmpty()) {
                    permissionManager.grantPermissions(pending.requestedPluginId, pending.permissions)
                }
                pluginManager.install(
                    zipFile = pending.packageFile,
                    expectedPackage = PluginPackageExpectation(
                        pluginId = pending.requestedPluginId,
                        version = pending.requestedVersion,
                    ),
                ).getOrThrow()
            }.onFailure { error ->
                runCatching {
                    permissionManager.replacePermissions(pending.requestedPluginId, previousGrants)
                }.onFailure { restoreError ->
                    error.addSuppressed(restoreError)
                }
                pluginLogManager.error(
                    source = PluginHostLogSources.Marketplace,
                    message = "Install failed after confirmation pluginId=${pending.requestedPluginId} version=${pending.requestedVersion.orEmpty()} reason=${error.message.orEmpty()}",
                    stackTrace = error.stackTraceToString(),
                )
            }
            pending.packageFile.delete()
            val cancellation = result.exceptionOrNull() as? CancellationException
            if (cancellation != null) throw cancellation
            result
        }
    }

    fun discardPendingInstall(pending: MarketplacePendingPluginInstall) {
        pending.packageFile.delete()
    }

    fun getInstalledVersion(pluginId: String): String? = pluginManager.getInstalledVersion(pluginId)

    fun resolveInstallState(plugins: List<PluginSummary>): PluginMarketplaceInstallState = PluginMarketplaceInstallStateResolver.resolve(
        plugins = plugins,
        installedVersions = pluginManager.pluginStateFlow.value.installedVersions,
    )
}

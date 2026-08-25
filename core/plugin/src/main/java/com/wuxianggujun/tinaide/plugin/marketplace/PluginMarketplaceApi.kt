package com.wuxianggujun.tinaide.plugin.marketplace

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.ResponseBodyTooLargeException
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.network.readUtf8Limited
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.core.network.registry.RegistryEndpoint
import com.wuxianggujun.tinaide.core.network.registry.RegistryUrl
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.PluginCompatibility
import com.wuxianggujun.tinaide.plugin.PluginManifestValidator
import com.wuxianggujun.tinaide.plugin.ZipUtils
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class PluginMarketplaceApi private constructor(
    private val indexUrls: List<RegistryUrl>,
    private val indexClient: OkHttpClient,
    private val downloadClient: OkHttpClient,
    private val customGitHubProxyPrefix: String? = null,
) {
    private val json = JsonSerializer.default
    private val indexMutex = Mutex()
    private val detailMutexes = ConcurrentHashMap<String, Mutex>()
    private val detailFetchSemaphore = Semaphore(MAX_CONCURRENT_DETAIL_REQUESTS)
    @Volatile
    private var cachedIndex: LoadedPluginRegistryCatalog? = null
    private val cachedDetails = ConcurrentHashMap<String, PluginDetail>()
    private var currentHostVersion: String? = null

    companion object {
        private const val TAG = "PluginMarketplaceApi"
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_REGISTRY_JSON_BYTES = 8 * 1024 * 1024
        private const val MAX_CONCURRENT_DETAIL_REQUESTS = 4
        private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

        fun create(context: Context): PluginMarketplaceApi {
            val appContext = context.applicationContext
            val settings = GitHubRegistryProxyConfig.load(appContext)
            return PluginMarketplaceApi(
                indexUrls = GitHubRegistryConfig.pluginIndexV3Urls(settings.customMirrorPrefix),
                indexClient = GitHubRegistryHttpClientFactory.probe(appContext),
                downloadClient = GitHubRegistryHttpClientFactory.download(appContext),
                customGitHubProxyPrefix = settings.customMirrorPrefix,
            ).also { api ->
                api.currentHostVersion = PluginCompatibility.resolveCurrentAppVersion(appContext)
            }
        }
    }

    suspend fun listPlugins(
        page: Int = 1,
        limit: Int = 20,
        category: String? = null,
        search: String? = null,
        sort: String? = null,
    ): ApiResult<PluginListData> = withContext(Dispatchers.IO) {
        val index = when (val indexResult = loadIndex()) {
            is ApiResult.Success -> indexResult.data
            is ApiResult.Error -> return@withContext indexResult
            is ApiResult.NetworkError -> return@withContext indexResult
        }
        val filteredEntries = index.plugins
            .asSequence()
            .filter { plugin -> category.isNullOrBlank() || plugin.category == category }
            .filter { plugin ->
                val query = search?.trim().orEmpty()
                query.isBlank() ||
                    plugin.name.contains(query, ignoreCase = true) ||
                    plugin.pluginId.contains(query, ignoreCase = true) ||
                    plugin.description?.contains(query, ignoreCase = true) == true ||
                    plugin.tags.any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(pluginSortComparator(sort))
            .toList()
        val detailResults = coroutineScope {
            filteredEntries.map { entry ->
                async {
                    detailFetchSemaphore.withPermit {
                        entry to resolvePluginDetail(index, entry.pluginId)
                    }
                }
            }.awaitAll()
        }
        val filtered = buildList {
            for ((entry, detailResult) in detailResults) {
                val detail = when (detailResult) {
                    is ApiResult.Success -> detailResult.data
                    is ApiResult.Error -> return@withContext detailResult
                    is ApiResult.NetworkError -> return@withContext detailResult
                }
                val latest = detail.latestCompatibleVersionEntry(currentHostVersion)
                    ?: continue
                add(entry to latest.version)
            }
        }

        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(1)
        val total = filtered.size
        val totalPages = if (total == 0) 1 else ((total + safeLimit - 1) / safeLimit)
        val offset = ((safePage - 1L) * safeLimit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pageItems = filtered
            .drop(offset)
            .take(safeLimit)
            .map { (entry, latestVersion) -> entry.toSummary(latestVersion) }

        ApiResult.Success(
            PluginListData(
                plugins = pageItems,
                pagination = Pagination(
                    page = safePage,
                    limit = safeLimit,
                    total = total.toLong(),
                    totalPages = totalPages,
                ),
            ),
        )
    }

    suspend fun getPluginDetail(pluginId: String): ApiResult<PluginDetail> = withContext(Dispatchers.IO) {
        when (val indexResult = loadIndex()) {
            is ApiResult.Success -> when (val detailResult = resolvePluginDetail(indexResult.data, pluginId)) {
                is ApiResult.Success -> ApiResult.Success(detailResult.data.compatibleWith(currentHostVersion))
                is ApiResult.Error -> detailResult
                is ApiResult.NetworkError -> detailResult
            }
            is ApiResult.Error -> indexResult
            is ApiResult.NetworkError -> indexResult
        }
    }

    suspend fun checkUpdates(
        plugins: List<CheckUpdateItem>,
    ): ApiResult<CheckUpdateData> = withContext(Dispatchers.IO) {
        val index = when (val indexResult = loadIndex()) {
            is ApiResult.Success -> indexResult.data
            is ApiResult.Error -> return@withContext indexResult
            is ApiResult.NetworkError -> return@withContext indexResult
        }
        val updates = plugins.mapNotNull { installed ->
            val remote = when (val detailResult = resolvePluginDetail(index, installed.pluginId)) {
                is ApiResult.Success -> detailResult.data
                else -> return@mapNotNull null
            }
            val latest = remote.latestCompatibleVersionEntry(currentHostVersion) ?: return@mapNotNull null
            if (!isNewerVersion(latest.version, installed.version)) return@mapNotNull null
            PluginUpdateInfo(
                pluginId = installed.pluginId,
                currentVersion = installed.version,
                latestVersion = latest.version,
                downloadUrl = latest.downloadUrl
                    ?.let {
                        GitHubRegistryConfig.registryResourceUrlCandidates(
                            urlOrPath = it,
                            endpoint = index.endpoint,
                            customProxyPrefix = customGitHubProxyPrefix,
                        ).firstOrNull()
                    }
                    .orEmpty(),
                changelog = latest.changelog,
                fileSize = latest.fileSize,
            )
        }
        ApiResult.Success(CheckUpdateData(updates))
    }

    suspend fun downloadPlugin(
        pluginId: String,
        version: String? = null,
        targetFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): ApiResult<File> = withContext(Dispatchers.IO) {
        try {
            val index = when (val indexResult = loadIndex()) {
                is ApiResult.Success -> indexResult.data
                is ApiResult.Error -> return@withContext indexResult
                is ApiResult.NetworkError -> return@withContext indexResult
            }
            val plugin = when (val detailResult = resolvePluginDetail(index, pluginId)) {
                is ApiResult.Success -> detailResult.data
                is ApiResult.Error -> return@withContext detailResult
                is ApiResult.NetworkError -> return@withContext detailResult
            }
            val pluginVersion = plugin.resolveVersion(version, currentHostVersion)
                ?: return@withContext ApiResult.Error(
                    404,
                    Strings.plugin_marketplace_error_plugin_version_not_found.str(version ?: "latest"),
                )
            val downloadUrls = pluginVersion.downloadUrl
                ?.let {
                    GitHubRegistryConfig.registryResourceUrlCandidates(
                        urlOrPath = it,
                        endpoint = index.endpoint,
                        customProxyPrefix = customGitHubProxyPrefix,
                    )
                }
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext ApiResult.Error(
                    -1,
                    Strings.plugin_marketplace_error_download_url_missing.str(pluginId),
                )
            val expectedHash = pluginVersion.fileHash
                ?.takeIf { it.matches(Regex("(?i)^sha256:[0-9a-f]{64}$")) }
                ?: return@withContext ApiResult.Error(
                    -1,
                    Strings.plugin_marketplace_error_integrity_metadata_invalid.str(),
                )
            val expectedSize = pluginVersion.fileSize.takeIf { it in 1L..ZipUtils.MAX_PACKAGE_BYTES }
                ?: return@withContext ApiResult.Error(
                    -1,
                    Strings.plugin_marketplace_error_integrity_metadata_invalid.str(),
                )

            downloadFile(
                urls = downloadUrls,
                targetFile = targetFile,
                expectedHash = expectedHash,
                expectedSize = expectedSize,
                onProgress = onProgress,
            )
        } catch (e: CancellationException) {
            // 用户取消：向上抛出由协程框架处理，不当作下载错误。
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Download plugin failed")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download plugin unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend fun loadIndex(): ApiResult<LoadedPluginRegistryCatalog> = withContext(Dispatchers.IO) {
        cachedIndex?.let { return@withContext ApiResult.Success(it) }
        indexMutex.withLock {
            cachedIndex?.let { return@withLock ApiResult.Success(it) }
            val result = loadIndexFromUrls(indexUrls, "v3") { body, registryUrl ->
                val catalog = json.decodeFromString<PluginRegistryCatalog>(body)
                require(catalog.schemaVersion == GitHubRegistryConfig.REGISTRY_SCHEMA_VERSION) {
                    Strings.error_response_parse_failed.str()
                }
                LoadedPluginRegistryCatalog(
                    catalog = catalog,
                    baseUrl = registryUrl.endpoint.baseUrl,
                    endpoint = registryUrl.endpoint,
                )
            }
            if (result is ApiResult.Success) {
                cachedIndex = result.data
            }
            result
        }
    }

    private fun loadIndexFromUrls(
        urls: List<RegistryUrl>,
        schemaLabel: String,
        decode: (body: String, registryUrl: RegistryUrl) -> LoadedPluginRegistryCatalog,
    ): ApiResult<LoadedPluginRegistryCatalog> {
        var lastError: ApiResult<LoadedPluginRegistryCatalog>? = null
        for (registryUrl in urls) {
            try {
                val response = indexClient.newCall(
                    Request.Builder()
                        .url(registryUrl.url)
                        .get()
                        .build()
                ).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = ApiResult.Error(
                            resp.code,
                            "Registry $schemaLabel request failed via ${registryUrl.endpoint.name}: HTTP ${resp.code}",
                        )
                        return@use
                    }
                    val body = resp.body?.readUtf8Limited(MAX_REGISTRY_JSON_BYTES)
                    if (body.isNullOrBlank()) {
                        lastError = ApiResult.Error(-1, Strings.error_response_empty.str())
                        return@use
                    }
                    val index = decode(body, registryUrl)
                    Timber.tag(TAG).i(
                        "Loaded plugin registry %s via %s",
                        schemaLabel,
                        registryUrl.endpoint.name,
                    )
                    return ApiResult.Success(index)
                }
            } catch (e: ResponseBodyTooLargeException) {
                Timber.tag(TAG).w("Plugin registry %s response exceeded size limit via %s", schemaLabel, registryUrl.endpoint.name)
                lastError = ApiResult.Error(-1, Strings.error_response_parse_failed.str())
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "Load plugin registry %s failed via %s", schemaLabel, registryUrl.endpoint.name)
                lastError = ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Parse plugin registry %s failed via %s", schemaLabel, registryUrl.endpoint.name)
                lastError = ApiResult.Error(-1, e.message ?: Strings.error_response_parse_failed.str())
            }
        }
        return lastError ?: ApiResult.NetworkError(Strings.error_network_connection_failed.str())
    }

    private suspend fun resolvePluginDetail(
        index: LoadedPluginRegistryCatalog,
        pluginId: String,
    ): ApiResult<PluginDetail> {
        val entry = index.catalog.plugins
            .firstOrNull { it.pluginId == pluginId || it.id == pluginId }
            ?: return ApiResult.Error(404, Strings.plugin_marketplace_error_plugin_not_found.str(pluginId))

        val detailUrl = entry.detailUrl
            ?: return ApiResult.Error(-1, Strings.plugin_marketplace_error_download_url_missing.str(pluginId))

        cachedDetails[entry.pluginId]?.let { return ApiResult.Success(it) }
        cachedDetails[entry.id]?.let { return ApiResult.Success(it) }

        val detailMutex = detailMutexes.computeIfAbsent(entry.pluginId) { Mutex() }
        return detailMutex.withLock {
            cachedDetails[entry.pluginId]?.let { return@withLock ApiResult.Success(it) }
            cachedDetails[entry.id]?.let { return@withLock ApiResult.Success(it) }
            var lastError: ApiResult<PluginDetail>? = null
            for (candidateUrl in GitHubRegistryConfig.registryResourceUrlCandidates(
                urlOrPath = detailUrl,
                endpoint = index.endpoint,
                customProxyPrefix = customGitHubProxyPrefix,
            )) {
                try {
                    val response = indexClient.newCall(
                        Request.Builder()
                            .url(candidateUrl)
                            .get()
                            .build()
                    ).execute()
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            lastError = ApiResult.Error(
                                resp.code,
                                "Plugin detail request failed: HTTP ${resp.code}",
                            )
                            return@use
                        }
                        val body = resp.body?.readUtf8Limited(MAX_REGISTRY_JSON_BYTES)
                        if (body.isNullOrBlank()) {
                            lastError = ApiResult.Error(-1, Strings.error_response_empty.str())
                            return@use
                        }
                        val detail = json.decodeFromString<PluginDetail>(body)
                        if (detail.pluginId != entry.pluginId || detail.id != entry.id) {
                            lastError = ApiResult.Error(-1, "Plugin detail id does not match registry entry")
                            return@use
                        }
                        cachedDetails[detail.pluginId] = detail
                        cachedDetails[detail.id] = detail
                        return@withLock ApiResult.Success(detail)
                    }
                } catch (e: ResponseBodyTooLargeException) {
                    Timber.tag(TAG).w("Plugin detail response exceeded size limit: %s", pluginId)
                    lastError = ApiResult.Error(-1, Strings.error_response_parse_failed.str())
                } catch (e: IOException) {
                    Timber.tag(TAG).w(e, "Load plugin detail failed: %s", pluginId)
                    lastError = ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Parse plugin detail failed: %s", pluginId)
                    lastError = ApiResult.Error(-1, e.message ?: Strings.error_response_parse_failed.str())
                }
            }
            lastError ?: ApiResult.NetworkError(Strings.error_network_connection_failed.str())
        }
    }

    private suspend fun downloadFile(
        urls: List<String>,
        targetFile: File,
        expectedHash: String,
        expectedSize: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?,
    ): ApiResult<File> {
        var lastResult: ApiResult<File>? = null
        for (candidateUrl in urls) {
            val result = try {
                downloadSingleFile(
                    url = candidateUrl,
                    targetFile = targetFile,
                    expectedHash = expectedHash,
                    expectedSize = expectedSize,
                    onProgress = onProgress,
                )
            } catch (e: CancellationException) {
                // 用户取消：不再尝试下一个镜像，直接向上抛出。
                throw e
            } catch (error: Exception) {
                ApiResult.NetworkError(error.message ?: Strings.error_network_connection_failed.str())
            }
            if (result is ApiResult.Success) return result
            lastResult = result
        }
        return lastResult ?: ApiResult.NetworkError(Strings.error_network_connection_failed.str())
    }

    private suspend fun downloadSingleFile(
        url: String,
        targetFile: File,
        expectedHash: String,
        expectedSize: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?,
    ): ApiResult<File> {
        if (!url.isStrictHttpsUrl()) {
            return ApiResult.Error(-1, Strings.error_download_failed.str())
        }
        var startByte = 0L
        if (targetFile.exists()) {
            startByte = targetFile.length()
            if (startByte > ZipUtils.MAX_PACKAGE_BYTES) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.plugin_error_package_too_large.str())
            }
            if (startByte == expectedSize) {
                val expectedHashValue = expectedHash.substringAfter("sha256:")
                if (calculateSha256(targetFile).equals(expectedHashValue, ignoreCase = true)) {
                    return ApiResult.Success(targetFile)
                }
                targetFile.delete()
                startByte = 0L
            } else if (startByte > expectedSize) {
                targetFile.delete()
                startByte = 0L
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (startByte > 0) {
            requestBuilder.addHeader("Range", "bytes=$startByte-")
        }

        val response = downloadClient.newCall(requestBuilder.build()).executeCancellable()
        response.use { resp ->
            if (!resp.request.url.toString().isStrictHttpsUrl()) {
                return ApiResult.Error(-1, Strings.error_download_failed.str())
            }
            if (!resp.isSuccessful && resp.code != 206) {
                return ApiResult.Error(resp.code, "${Strings.error_download_failed.str()} (HTTP ${resp.code})")
            }

            val body = resp.body ?: return ApiResult.Error(-1, Strings.error_download_failed.str())
            val contentLength = body.contentLength()
            val contentRange = if (resp.code == 206) {
                parseContentRange(resp.header("Content-Range"))
                    ?.takeIf { range ->
                        range.start == startByte &&
                            (contentLength < 0L || range.end - range.start + 1L == contentLength)
                    }
                    ?: run {
                        targetFile.delete()
                        return ApiResult.Error(-1, Strings.error_download_failed.str())
                    }
            } else {
                null
            }
            val total = when {
                contentRange?.total != null -> contentRange.total
                resp.code == 206 && contentLength >= 0L -> startByte + contentLength
                else -> contentLength
            }
            if (total > ZipUtils.MAX_PACKAGE_BYTES) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.plugin_error_package_too_large.str())
            }
            if (total > 0L && total != expectedSize) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.error_download_failed.str())
            }

            val isResume = resp.code == 206
            var exceededPackageLimit = false
            RandomAccessFile(targetFile, "rw").use { raf ->
                if (isResume) {
                    raf.seek(startByte)
                } else {
                    raf.setLength(0)
                }

                val buffer = ByteArray(8192)
                var downloaded = if (isResume) startByte else 0L
                body.byteStream().use { inputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        if (downloaded + bytesRead > ZipUtils.MAX_PACKAGE_BYTES) {
                            exceededPackageLimit = true
                            break
                        }
                        raf.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress?.invoke(downloaded, total)
                    }
                }
            }
            if (exceededPackageLimit) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.plugin_error_package_too_large.str())
            }

            if (targetFile.length() != expectedSize) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.error_download_failed.str())
            }

            val actualHash = calculateSha256(targetFile)
            val expectedHashValue = expectedHash.substringAfter("sha256:")
            if (!actualHash.equals(expectedHashValue, ignoreCase = true)) {
                targetFile.delete()
                return ApiResult.Error(-1, Strings.error_file_hash_mismatch.str())
            }

            return ApiResult.Success(targetFile)
        }
    }

    private fun String.isStrictHttpsUrl(): Boolean = runCatching {
        val uri = URI(this)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start || total != null && (total <= end || total <= 0L)) return null
        return ContentRange(start, end, total)
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private fun pluginSortComparator(sort: String?): Comparator<PluginRegistryCatalogEntry> = when (sort) {
        PluginSortType.NEWEST.value -> compareByDescending { it.createdAt }
        PluginSortType.UPDATED.value -> compareByDescending { it.updatedAt }
        else -> compareByDescending { it.updatedAt }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
            val left = remoteParts.getOrElse(index) { 0 }
            val right = localParts.getOrElse(index) { 0 }
            if (left > right) return true
            if (left < right) return false
        }
        return remote != local
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class PluginRegistryCatalog(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("generated_at")
    val generatedAt: String? = null,
    val plugins: List<PluginRegistryCatalogEntry> = emptyList(),
)

data class LoadedPluginRegistryCatalog(
    val catalog: PluginRegistryCatalog,
    val baseUrl: String,
    val endpoint: RegistryEndpoint = RegistryEndpoint(name = "Registry", baseUrl = baseUrl),
) {
    val plugins: List<PluginRegistryCatalogEntry>
        get() = catalog.plugins
}

@Serializable
data class PluginRegistryCatalogEntry(
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
    @SerialName("detail_url")
    val detailUrl: String? = null,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
) {
    fun toSummary(compatibleLatestVersion: String? = latestVersion): PluginSummary = PluginSummary(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        category = category,
        tags = tags,
        iconUrl = iconUrl,
        publisher = publisher,
        latestVersion = compatibleLatestVersion,
        updatedAt = updatedAt,
    )
}

private fun PluginDetail.resolveVersion(
    version: String?,
    hostVersion: String?,
): PluginVersion? = if (version.isNullOrBlank()) {
    latestCompatibleVersionEntry(hostVersion)
} else {
    versions.firstOrNull { candidate ->
        candidate.version == version &&
            candidate.apiVersion == PluginManifestValidator.SUPPORTED_API_VERSION &&
            PluginCompatibility.evaluate(hostVersion, candidate.minAppVersion).isCompatible
    }
}

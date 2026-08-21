package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifest
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestParser
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestSource
import com.wuxianggujun.tinaide.core.network.readUtf8Limited
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Linux 发行版清单的「远程增强」加载源。
 *
 * 加载优先级（任一失败都向下回落，绝不抛出，保证安装链路永远拿得到清单）：
 *   1. 新鲜缓存（< [cacheTtlMillis]）：直接用，不打网络。
 *   2. 远程拉取：走 [GitHubRegistryConfig] 多端点候选（GitHub Raw + jsDelivr + 国内代理），
 *      成功则写缓存并返回。
 *   3. 过期缓存：远程失败时退回上次成功缓存。
 *   4. [fallback]：通常是缓存优先、本地 asset 兜底的无网络源。
 *
 * 设计取舍：远程清单只用于「更新数据不发版」，因此任何远程异常（网络、坏 JSON、
 * 比 App 新的 schema）都静默回落，不打断安装、不崩溃。
 */
class RemoteLinuxDistroManifestSource(
    context: Context,
    private val fallback: LinuxDistroManifestSource,
    private val client: OkHttpClient = GitHubRegistryHttpClientFactory.probe(context.applicationContext),
    private val cacheFile: File = defaultCacheFile(context),
    private val manifestUrls: List<String> = GitHubRegistryConfig.linuxDistroManifestUrls().map { it.url },
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : LinuxDistroManifestSource {

    override fun loadManifest(): LinuxDistroManifest {
        readFreshCache()?.let { return it }

        fetchRemote()?.let { manifest ->
            writeCache(manifest)
            return manifest
        }

        readStaleCache()?.let { return it }

        return fallback.loadManifest()
    }

    private fun readFreshCache(): LinuxDistroManifest? {
        if (!cacheFile.isFile) return null
        val age = clock() - cacheFile.lastModified()
        if (age !in 0..cacheTtlMillis) return null
        return decodeCacheOrNull()
    }

    private fun readStaleCache(): LinuxDistroManifest? {
        if (!cacheFile.isFile) return null
        return decodeCacheOrNull()
    }

    private fun decodeCacheOrNull(): LinuxDistroManifest? = runCatching {
        require(cacheFile.length() <= MAX_MANIFEST_BYTES) { "Cached linux distro manifest exceeds size limit" }
        LinuxDistroManifestParser.decode(cacheFile.readText(Charsets.UTF_8))
    }.onFailure { error ->
        Timber.tag(TAG).w(error, "Decode cached linux distro manifest failed")
    }.getOrNull()

    private fun fetchRemote(): LinuxDistroManifest? {
        for (url in manifestUrls) {
            val manifest = runCatching { fetchFrom(url) }
                .onFailure { error ->
                    when (error) {
                        is IOException -> Timber.tag(TAG).w(
                            "Fetch linux distro manifest failed via %s (%s)",
                            endpointName(url),
                            error::class.java.simpleName,
                        )
                        else -> Timber.tag(TAG).w(
                            "Parse linux distro manifest failed via %s (%s)",
                            endpointName(url),
                            error::class.java.simpleName,
                        )
                    }
                }
                .getOrNull()
            if (manifest != null) {
                Timber.tag(TAG).i("Loaded remote linux distro manifest via %s", endpointName(url))
                return manifest
            }
        }
        return null
    }

    private fun fetchFrom(url: String): LinuxDistroManifest? {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.readUtf8Limited(MAX_MANIFEST_BYTES)
            if (body.isNullOrBlank()) return null
            return LinuxDistroManifestParser.decode(body)
        }
    }

    private fun writeCache(manifest: LinuxDistroManifest) {
        runCatching {
            val parent = requireNotNull(cacheFile.parentFile) { "Linux distro cache must have a parent directory" }
            check(parent.mkdirs() || parent.isDirectory) { "Failed to create linux distro cache directory" }
            // 回写解析后的标准 JSON，确保缓存内容一定可被本 App 重新解析。
            val pending = Files.createTempFile(parent.toPath(), ".${cacheFile.name}.", ".tmp")
            try {
                pending.toFile().writeText(LinuxDistroManifestParser.encode(manifest), Charsets.UTF_8)
                moveReplacing(pending, cacheFile.toPath())
            } finally {
                Files.deleteIfExists(pending)
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Write linux distro manifest cache failed")
        }
    }

    private fun moveReplacing(source: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (atomicError: IOException) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (fallbackError: IOException) {
                fallbackError.addSuppressed(atomicError)
                throw fallbackError
            }
        }
    }

    private fun endpointName(url: String): String = runCatching { URI(url).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "unknown-host"

    companion object {
        private const val TAG = "RemoteLinuxManifest"
        private const val DEFAULT_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L // 6h
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

        fun defaultCacheFile(context: Context): File = File(
            SelfHostedLinuxDistroRuntime.defaultRuntimeDir(context),
            "manifest.cache.json",
        )
    }
}

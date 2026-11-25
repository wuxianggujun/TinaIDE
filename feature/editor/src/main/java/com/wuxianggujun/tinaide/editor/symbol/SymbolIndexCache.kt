package com.wuxianggujun.tinaide.editor.symbol

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 符号索引持久化缓存
 *
 * 功能：
 * - 将项目符号索引序列化到本地文件
 * - 启动时快速加载缓存，避免重建索引
 * - 基于文件修改时间判断缓存有效性
 *
 * 缓存策略：
 * - 每个项目一个缓存文件，以项目路径 hash 命名
 * - 缓存包含：符号数据 + 文件修改时间戳
 * - 加载时校验文件时间戳，过期则重建
 */
class SymbolIndexCache(private val context: Context) {

    companion object {
        private const val TAG = "SymbolIndexCache"
        private const val CACHE_DIR = "symbol_cache"

        // v5: scoped C++ symbols and lossless signatures/documentation.
        private const val CACHE_VERSION = 5
        private const val MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 天
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * 缓存数据结构
     */
    data class CachedFileSnapshot(
        val filePath: String,
        val globals: List<ProjectSymbol>,
    )

    data class CachedIndex(
        val projectRoot: String,
        val fileSnapshots: List<CachedFileSnapshot>,
        val fileTimestamps: Map<String, Long>, // 文件路径 -> 最后修改时间
        val cachedAt: Long,
    )

    /**
     * 保存索引到缓存
     */
    suspend fun saveIndex(
        projectRoot: String,
        fileSnapshots: List<CachedFileSnapshot>,
        fileTimestamps: Map<String, Long>,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(projectRoot)
            val symbolCount = fileSnapshots.sumOf { it.globals.size }
            val json = JSONObject().apply {
                put("version", CACHE_VERSION)
                put("projectRoot", projectRoot)
                put("cachedAt", System.currentTimeMillis())
                put("fileSnapshots", serializeFileSnapshots(fileSnapshots))
                put("fileTimestamps", serializeFileTimestamps(fileTimestamps))
            }
            val atomicFile = AtomicFile(cacheFile)
            val output = atomicFile.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
            Timber.tag(TAG).i("Saved index cache: $symbolCount symbols, ${fileTimestamps.size} files")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w("Failed to save index cache: ${e.message}")
            false
        }
    }

    /**
     * 加载缓存的索引
     *
     * @return 缓存数据，如果缓存无效或不存在则返回 null
     */
    suspend fun loadIndex(projectRoot: String): CachedIndex? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(projectRoot)
            if (!cacheFile.exists()) {
                Timber.tag(TAG).d("No cache file found for project")
                return@withContext null
            }

            val json = JSONObject(AtomicFile(cacheFile).readFully().toString(Charsets.UTF_8))
            val version = json.optInt("version", 0)
            if (version != CACHE_VERSION) {
                Timber.tag(TAG).i("Cache version mismatch: $version != $CACHE_VERSION")
                cacheFile.delete()
                return@withContext null
            }

            val cachedAt = json.optLong("cachedAt", 0)
            if (System.currentTimeMillis() - cachedAt > MAX_CACHE_AGE_MS) {
                Timber.tag(TAG).i("Cache expired")
                cacheFile.delete()
                return@withContext null
            }

            val cached = CachedIndex(
                projectRoot = json.getString("projectRoot"),
                fileSnapshots = deserializeFileSnapshots(json.getJSONArray("fileSnapshots")),
                fileTimestamps = deserializeFileTimestamps(json.getJSONObject("fileTimestamps")),
                cachedAt = cachedAt,
            )

            val symbolCount = cached.fileSnapshots.sumOf { it.globals.size }
            Timber.tag(TAG).i("Loaded index cache: $symbolCount symbols, ${cached.fileTimestamps.size} files")
            cached
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w("Failed to load index cache: ${e.message}")
            null
        }
    }

    /**
     * 验证缓存是否仍然有效
     *
     * @return 需要重新索引的文件列表，如果缓存完全有效则返回空列表
     */
    fun validateCache(cached: CachedIndex, currentFiles: List<File>): List<File> {
        val invalidFiles = mutableListOf<File>()
        val cachedPaths = cached.fileTimestamps.keys.toMutableSet()

        for (file in currentFiles) {
            val path = file.absolutePath
            val cachedTimestamp = cached.fileTimestamps[path]

            if (cachedTimestamp == null) {
                // 新文件
                invalidFiles.add(file)
            } else {
                cachedPaths.remove(path)
                val currentTimestamp = file.lastModified()
                if (currentTimestamp != cachedTimestamp) {
                    // 文件已修改
                    invalidFiles.add(file)
                }
            }
        }

        // cachedPaths 中剩余的是已删除的文件
        if (cachedPaths.isNotEmpty()) {
            Timber.tag(TAG).d("Detected ${cachedPaths.size} deleted files")
        }

        return invalidFiles
    }

    /**
     * 清除项目缓存
     */
    fun clearCache(projectRoot: String) {
        try {
            AtomicFile(getCacheFile(projectRoot)).delete()
            Timber.tag(TAG).i("Cleared cache for project: $projectRoot")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to clear cache: ${e.message}")
        }
    }

    private fun getCacheFile(projectRoot: String): File {
        val hash = md5(projectRoot)
        return File(cacheDir, "index_$hash.json")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ========== 序列化方法 ==========

    private fun serializeFileSnapshots(fileSnapshots: List<CachedFileSnapshot>): JSONArray = JSONArray().apply {
        for (snapshot in fileSnapshots) {
            put(
                JSONObject().apply {
                    put("filePath", snapshot.filePath)
                    put("globals", serializeGlobals(snapshot.globals))
                }
            )
        }
    }

    private fun serializeGlobals(globals: List<ProjectSymbol>): JSONArray = JSONArray().apply {
        for (symbol in globals) {
            put(
                JSONObject().apply {
                    put("name", symbol.name)
                    put("kind", symbol.kind.name)
                    put("detail", symbol.detail)
                    put("filePath", symbol.filePath)
                    symbol.signature?.let { put("signature", it) }
                    symbol.documentation?.let { put("documentation", it) }
                    symbol.containerName?.let { put("containerName", it) }
                    symbol.location?.let { loc ->
                        put("line", loc.line)
                        put("column", loc.column)
                    }
                }
            )
        }
    }

    private fun serializeFileTimestamps(timestamps: Map<String, Long>): JSONObject = JSONObject().apply {
        for ((path, timestamp) in timestamps) {
            put(path, timestamp)
        }
    }

    // ========== 反序列化方法 ==========

    private fun deserializeFileSnapshots(array: JSONArray): List<CachedFileSnapshot> {
        val result = mutableListOf<CachedFileSnapshot>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                CachedFileSnapshot(
                    filePath = obj.getString("filePath"),
                    globals = deserializeGlobals(obj.getJSONArray("globals")),
                )
            )
        }
        return result
    }

    private fun deserializeGlobals(array: JSONArray): List<ProjectSymbol> {
        val result = mutableListOf<ProjectSymbol>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val location = if (obj.has("line")) {
                SymbolLocation(obj.getInt("line"), obj.getInt("column"))
            } else {
                null
            }
            result.add(
                ProjectSymbol(
                    name = obj.getString("name"),
                    kind = SymbolKind.valueOf(obj.getString("kind")),
                    detail = obj.getString("detail"),
                    filePath = obj.getString("filePath"),
                    location = location,
                    signature = obj.optString("signature").takeIf { it.isNotEmpty() },
                    documentation = obj.optString("documentation").takeIf { it.isNotEmpty() },
                    containerName = obj.optString("containerName").takeIf { it.isNotEmpty() },
                )
            )
        }
        return result
    }

    private fun deserializeFileTimestamps(obj: JSONObject): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (key in obj.keys()) {
            result[key] = obj.getLong(key)
        }
        return result
    }
}

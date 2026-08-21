package com.wuxianggujun.tinaide.editor.symbol

import android.content.Context
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSTree
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.symbol.FuzzySymbolMatch
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.core.symbol.SymbolIndexStatus
import com.wuxianggujun.tinaide.core.symbol.SymbolInfo
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber

/**
 * 项目级符号索引（基于 Tree-sitter 的语法级索引）
 *
 * 目标：
 * - 全局符号搜索/跳转（例如 Outline、Symbols 面板）
 * - 后台构建 + 增量更新（保存文件时刷新该文件索引）
 * - 持久化缓存：启动时快速加载，避免重建索引
 *
 * 非目标（请走 LSP/clangd）：
 * - 语义级补全/类型推断/继承链遍历/宏展开/重命名
 */
class ProjectSymbolIndexService(
    private val context: Context? = null,
    providers: List<LanguageSymbolProvider> = listOf(CxxSymbolProvider()),
) : ServiceLifecycle,
    Closeable,
    IProjectSymbolIndexService {

    companion object {
        private const val TAG = "ProjectSymbolIndex"

        private val IGNORED_DIR_NAMES = setOf(
            ".git", ".gradle", ".idea", ".vscode", ".tinaide",
            "build", "out", "dist", "node_modules",
            "cmake-build-debug", "cmake-build-release",
        )

        /** 单个文件最大字节数 - 避免解析超大文件导致 OOM */
        private const val MAX_FILE_BYTES_DEFAULT = 1 * 1024 * 1024 // 1MB

        /** 解析超时时间（毫秒）- 防止恶意代码导致解析挂起 */
        private const val PARSE_TIMEOUT_MS = 5000L // 5 秒

        private const val MAX_INDEX_FILES = 50_000
        private const val MAX_SCAN_ENTRIES = 100_000
        private const val MAX_SCAN_DEPTH = 64
    }

    data class IndexStatus(
        val projectRoot: String? = null,
        val isIndexing: Boolean = false,
        val indexedFiles: Int = 0,
        val totalFiles: Int = 0,
        val lastIndexedAt: Long? = null,
        val lastError: String? = null,
        val revision: Long = 0L,
        val cacheLoaded: Boolean = false, // 是否从缓存加载
        val cacheHitFiles: Int = 0, // 缓存命中的文件数
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = ReentrantReadWriteLock()
    private val revision = AtomicLong(0L)
    private val projectGeneration = AtomicLong(0L)
    private val fileUpdateSequence = AtomicLong(0L)
    private val latestFileUpdates = ConcurrentHashMap<String, FileUpdateToken>()
    private val cacheSaveMutex = Mutex()
    private var indexingJob: Job? = null

    private val mutableStatus = MutableStateFlow(IndexStatus())
    private val statusInternal: StateFlow<IndexStatus> = mutableStatus.asStateFlow()

    override val status: StateFlow<SymbolIndexStatus> = statusInternal.map { it.toSymbolIndexStatus() }
        .stateIn(scope, SharingStarted.Eagerly, SymbolIndexStatus())

    @Volatile
    private var activeProjectRoot: File? = null

    // Per-file extracted snapshot (for removal on update)
    private val fileSnapshots = HashMap<String, FileSnapshot>()

    // Global indexes (case-sensitive keys, but queried by lowercase prefix)
    private val globalSymbolsByLower = TreeMap<String, MutableList<ProjectSymbol>>()

    private val providers = providers.ifEmpty { listOf(CxxSymbolProvider()) }

    private data class ProviderParserState(
        val provider: LanguageSymbolProvider,
        val parser: TSParser,
        val lock: Any = Any(),
    )

    private data class FileUpdateToken(
        val projectGeneration: Long,
        val sequence: Long,
    )

    private data class CacheSaveSnapshot(
        val revision: Long,
        val fileSnapshots: List<SymbolIndexCache.CachedFileSnapshot>,
        val fileTimestamps: Map<String, Long>,
    )

    private val parserStates: List<ProviderParserState> = this.providers.map { provider ->
        ProviderParserState(
            provider = provider,
            parser = TSParser.create().apply {
                setLanguage(provider.createLanguage())
                setTimeout(PARSE_TIMEOUT_MS * 1_000L)
            },
        )
    }
    private val parserStateByExt: Map<String, ProviderParserState> = buildMap {
        for (state in parserStates) {
            for (ext in state.provider.supportedExtensions) {
                val normalizedExt = ext.lowercase(Locale.ROOT)
                val previous = putIfAbsent(normalizedExt, state)
                if (previous != null && previous.provider::class != state.provider::class) {
                    Timber.tag(TAG).w(
                        "Duplicate symbol provider extension '%s': keep %s, ignore %s",
                        normalizedExt,
                        previous.provider::class.java.simpleName,
                        state.provider::class.java.simpleName,
                    )
                }
            }
        }
    }

    // 持久化缓存
    private val indexCache: SymbolIndexCache? = context?.let { SymbolIndexCache(it) }

    // 文件时间戳（用于缓存验证）
    private val fileTimestamps = HashMap<String, Long>()

    override fun onCreate() {
        Timber.tag(TAG).i("ProjectSymbolIndexService created")
    }

    override fun onDestroy() {
        close()
    }

    override fun onProjectOpened(projectRoot: File) {
        val normalizedRoot = runCatching { projectRoot.canonicalFile }.getOrElse { projectRoot.absoluteFile }
        if (!normalizedRoot.isDirectory) return
        if (activeProjectRoot?.absolutePath == normalizedRoot.absolutePath) return
        indexingJob?.cancel()
        cancelInFlightParses()
        activeProjectRoot = normalizedRoot
        val generation = projectGeneration.incrementAndGet()
        clearIndex("project switched")
        startIndexWithCache(normalizedRoot, generation)
    }

    override fun onProjectClosed() {
        indexingJob?.cancel()
        indexingJob = null
        cancelInFlightParses()
        projectGeneration.incrementAndGet()
        activeProjectRoot = null
        clearIndex("project closed")
    }

    override fun onFileSaved(file: File, content: String) {
        val root = activeProjectRoot ?: return
        val normalizedFile = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!isUnderOrEqualRoot(normalizedFile, root) || !normalizedFile.isFile) return
        if (runCatching { Files.isSymbolicLink(normalizedFile.toPath()) }.getOrDefault(true)) return
        if (!isSupportedFile(normalizedFile)) return
        val generation = projectGeneration.get()
        if (!isCurrentProject(root, generation)) return
        val path = normalizedFile.absolutePath
        val updateToken = beginFileUpdate(path, generation)
        scope.launch {
            try {
                val errorMessage = updateSingleFile(normalizedFile, content, updateToken)
                if (!isCurrentFileUpdate(path, updateToken)) return@launch
                if (errorMessage != null) {
                    mutableStatus.value = mutableStatus.value.copy(lastError = errorMessage)
                } else if (mutableStatus.value.lastError != null) {
                    mutableStatus.value = mutableStatus.value.copy(lastError = null)
                }
            } finally {
                completeFileUpdate(path, updateToken)
            }
        }
    }

    /**
     * 查询全局符号（前缀匹配）
     *
     * @param prefix 搜索前缀
     * @param limit 返回数量限制
     * @return 匹配的符号列表
     */
    override fun queryGlobals(prefix: String, limit: Int): List<SymbolInfo> = queryGlobalsInternal(prefix, limit).map { it.toSymbolInfo() }

    /**
     * 内部查询方法（返回 ProjectSymbol）
     */
    private fun queryGlobalsInternal(prefix: String, limit: Int): List<ProjectSymbol> {
        val max = limit.coerceIn(10, 500)
        val p = prefix.trim()
        val needle = p.lowercase(Locale.ROOT)
        return lock.read {
            val view = if (needle.isEmpty()) {
                globalSymbolsByLower
            } else {
                prefixView(globalSymbolsByLower, needle)
            }
            val out = ArrayList<ProjectSymbol>(minOf(max, 64))
            val seenKeys = HashSet<String>(minOf(max * 2, 512))
            for ((_, symbols) in view) {
                for (symbol in symbols) {
                    val key = symbol.composeStableKey()
                    if (!seenKeys.add(key)) continue
                    out.add(symbol)
                    if (out.size >= max) return@read out
                }
            }
            out
        }
    }

    /**
     * 模糊查询全局符号
     *
     * 支持多种匹配模式：
     * - 前缀匹配（最高优先级）
     * - 驼峰匹配（如 "gAL" 匹配 "getArrayLength"）
     * - 子序列匹配（如 "gal" 匹配 "getArrayLength"）
     * - 包含匹配（如 "array" 匹配 "getArrayLength"）
     *
     * @param pattern 搜索模式
     * @param limit 返回数量限制
     * @return 匹配的符号列表（按匹配分数降序排列）
     */
    override fun queryGlobalsFuzzy(pattern: String, limit: Int): List<FuzzySymbolMatch> = queryGlobalsFuzzyInternal(pattern, limit).map { it.toFuzzySymbolMatch() }

    /**
     * 内部模糊查询方法（返回 FuzzySymbolResult）
     */
    private fun queryGlobalsFuzzyInternal(pattern: String, limit: Int): List<FuzzySymbolResult> {
        val max = limit.coerceIn(10, 500)
        val p = pattern.trim()

        if (p.isEmpty()) {
            return lock.read {
                val out = ArrayList<FuzzySymbolResult>(minOf(max, 64))
                val seenKeys = HashSet<String>(minOf(max * 2, 512))
                for (symbol in globalSymbolsByLower.values.flatten()) {
                    val key = symbol.composeStableKey()
                    if (!seenKeys.add(key)) continue
                    out.add(FuzzySymbolResult(symbol, FuzzyMatcher.MatchResult(matched = true, score = 0)))
                    if (out.size >= max) break
                }
                out
            }
        }

        return lock.read {
            val allSymbols = globalSymbolsByLower.values.flatten()
            val matched = FuzzyMatcher.matchAndSort(p, allSymbols, { it.name }, max)
            val out = ArrayList<FuzzySymbolResult>(minOf(matched.size, max))
            val seenKeys = HashSet<String>(minOf(max * 2, 512))
            for ((symbol, matchResult) in matched) {
                val key = symbol.composeStableKey()
                if (!seenKeys.add(key)) continue
                out.add(FuzzySymbolResult(symbol, matchResult))
                if (out.size >= max) break
            }
            out
        }
    }

    /**
     * 启动索引（优先从缓存加载）
     */
    private fun startIndexWithCache(projectRoot: File, generation: Long) {
        indexingJob = scope.launch {
            runCatching {
                val files = collectProjectFiles(projectRoot)
                if (!isCurrentProject(projectRoot, generation)) return@runCatching
                mutableStatus.value = mutableStatus.value.copy(
                    projectRoot = projectRoot.absolutePath,
                    isIndexing = true,
                    indexedFiles = 0,
                    totalFiles = files.size,
                    lastError = null,
                    cacheLoaded = false,
                    cacheHitFiles = 0,
                )

                // 尝试加载缓存
                val cached = indexCache?.loadIndex(projectRoot.absolutePath)
                var filesToIndex = files
                var cacheHitCount = 0

                if (cached != null) {
                    if (!isCurrentProject(projectRoot, generation)) return@runCatching
                    // 验证缓存有效性
                    val invalidFiles = indexCache.validateCache(cached, files)
                    val invalidPathSet = invalidFiles.asSequence()
                        .map { it.absolutePath }
                        .toHashSet()
                    val validPathSet = files.asSequence()
                        .map { it.absolutePath }
                        .filter { it !in invalidPathSet }
                        .toHashSet()

                    if (validPathSet.isNotEmpty()) {
                        val symbolCount = cached.fileSnapshots
                            .asSequence()
                            .filter { it.filePath in validPathSet }
                            .sumOf { it.globals.size }
                        Timber.tag(TAG).i(
                            "Loading partial cache: %d symbols, %d cache hits, %d files need update",
                            symbolCount,
                            validPathSet.size,
                            invalidFiles.size
                        )

                        // 应用有效缓存，剩余文件增量更新（不再按 50% 阈值放弃缓存）
                        val appliedPaths = applyCachedIndex(cached, validPathSet, generation)
                        if (!isCurrentProject(projectRoot, generation)) return@runCatching
                        cacheHitCount = appliedPaths.size
                        filesToIndex = files.filter { it.absolutePath !in appliedPaths }

                        mutableStatus.value = mutableStatus.value.copy(
                            cacheLoaded = true,
                            cacheHitFiles = cacheHitCount,
                            indexedFiles = cacheHitCount,
                        )
                    } else {
                        Timber.tag(TAG).i("Cache invalid for all files, rebuilding index")
                    }
                }

                // 索引需要更新的文件
                var done = cacheHitCount
                for (file in filesToIndex) {
                    if (!isCurrentProject(projectRoot, generation)) return@runCatching
                    val path = file.absolutePath
                    val updateToken = beginFileUpdate(path, generation)
                    try {
                        val readResult = runCatching { readFileForIndex(file) }
                        if (readResult.isFailure) {
                            removeSnapshotForCurrentUpdate(path, updateToken)
                            if (!isCurrentProject(projectRoot, generation)) return@runCatching
                            val error = readResult.exceptionOrNull()
                            mutableStatus.value = mutableStatus.value.copy(
                                lastError = "Read file failed: ${file.name} (${error?.messageOrClass() ?: "unknown"})"
                            )
                            done++
                            if (done % 20 == 0) {
                                mutableStatus.value = mutableStatus.value.copy(indexedFiles = done)
                            }
                            continue
                        }
                        val text = readResult.getOrThrow()
                        val fileError = updateSingleFile(file, text, updateToken)
                        if (!isCurrentProject(projectRoot, generation)) return@runCatching
                        if (fileError != null) {
                            mutableStatus.value = mutableStatus.value.copy(lastError = fileError)
                        }
                        done++
                        if (done % 20 == 0) {
                            mutableStatus.value = mutableStatus.value.copy(indexedFiles = done)
                        }
                    } finally {
                        completeFileUpdate(path, updateToken)
                    }
                }

                if (!isCurrentProject(projectRoot, generation)) return@runCatching
                val now = System.currentTimeMillis()
                mutableStatus.value = mutableStatus.value.copy(
                    isIndexing = false,
                    indexedFiles = done,
                    lastIndexedAt = now,
                    revision = revision.get(),
                )

                // 保存缓存
                saveIndexCache(projectRoot.absolutePath, generation)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (!isCurrentProject(projectRoot, generation)) return@onFailure
                Timber.tag(TAG).w(e, "Index failed: ${e.message}")
                mutableStatus.value = mutableStatus.value.copy(
                    isIndexing = false,
                    lastError = e.messageOrClass(),
                )
            }
        }
    }

    /**
     * 应用缓存的索引数据
     */
    private fun applyCachedIndex(
        cached: SymbolIndexCache.CachedIndex,
        allowedPaths: Set<String>,
        generation: Long,
    ): Set<String> = lock.write {
            if (!isCurrentProjectGeneration(generation)) return@write emptySet()
            // 只应用允许复用的缓存文件，避免陈旧索引污染。
            var applied = 0
            var skipped = 0
            val appliedPaths = HashSet<String>()
            for (cachedSnapshot in cached.fileSnapshots) {
                val hasCurrentUpdate = latestFileUpdates[cachedSnapshot.filePath]
                    ?.projectGeneration == generation
                if (cachedSnapshot.filePath !in allowedPaths ||
                    hasCurrentUpdate ||
                    !appliedPaths.add(cachedSnapshot.filePath)
                ) {
                    skipped++
                    continue
                }
                val snapshot = FileSnapshot.fromCached(cachedSnapshot)
                applyFileSnapshotLocked(snapshot)
                fileSnapshots[snapshot.filePath] = snapshot
                cached.fileTimestamps[snapshot.filePath]?.let { fileTimestamps[snapshot.filePath] = it }
                applied++
            }

            Timber.tag(TAG).i("Cache applied: $applied files, skipped: $skipped")
            if (applied > 0) bumpRevisionLocked()
            appliedPaths
        }

    /**
     * 保存索引到缓存
     */
    private suspend fun saveIndexCache(projectRoot: String, generation: Long = projectGeneration.get()) {
        val cache = indexCache ?: return
        cacheSaveMutex.lock()
        try {
            if (!isCurrentProjectGeneration(generation)) return
            if (hasInFlightFileUpdates(generation)) {
                cache.clearCache(projectRoot)
                return
            }

            val snapshot = lock.read {
                CacheSaveSnapshot(
                    revision = revision.get(),
                    fileSnapshots = fileSnapshots.values.map { fileSnapshot ->
                        SymbolIndexCache.CachedFileSnapshot(
                            filePath = fileSnapshot.filePath,
                            globals = fileSnapshot.globals,
                        )
                    },
                    fileTimestamps = fileTimestamps.toMap(),
                )
            }

            if (!isCurrentProjectGeneration(generation)) return
            var cacheSaved = false
            try {
                cacheSaved = cache.saveIndex(
                    projectRoot = projectRoot,
                    fileSnapshots = snapshot.fileSnapshots,
                    fileTimestamps = snapshot.fileTimestamps,
                )
            } finally {
                if (!cacheSaved ||
                    !isCurrentProjectGeneration(generation) ||
                    revision.get() != snapshot.revision ||
                    hasInFlightFileUpdates(generation)
                ) {
                    cache.clearCache(projectRoot)
                }
            }
        } finally {
            cacheSaveMutex.unlock()
        }
    }

    /**
     * 带超时的解析方法
     * 防止恶意代码导致解析挂起
     */
    private sealed interface ParseResult {
        data class Success(val tree: TSTree) : ParseResult
        data object Timeout : ParseResult
        data class Failure(val cause: Throwable) : ParseResult
    }

    private fun parseWithTimeout(content: String, parserState: ProviderParserState): ParseResult = try {
        val tree = synchronized(parserState.lock) {
            parserState.parser.reset()
            parserState.parser.parseString(content)
        }
        if (tree == null) {
            Timber.tag(TAG).w("Parse timeout after %d ms", PARSE_TIMEOUT_MS)
            ParseResult.Timeout
        } else {
            ParseResult.Success(tree)
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Timber.tag(TAG).w(t, "Parse failed with exception")
        ParseResult.Failure(t)
    }

    private fun updateSingleFile(file: File, content: String, updateToken: FileUpdateToken): String? {
        val path = file.absolutePath
        if (!isCurrentFileUpdate(path, updateToken)) return null
        val parserState = parserStateByExt[file.extension.lowercase(Locale.ROOT)]
        if (parserState == null) {
            removeSnapshotForCurrentUpdate(path, updateToken)
            return null
        }

        if (!file.exists() || !file.isFile) {
            removeSnapshotForCurrentUpdate(path, updateToken)
            return null
        }

        val size = runCatching { file.length() }.getOrDefault(0L)
        if (size > MAX_FILE_BYTES_DEFAULT ||
            content.length > MAX_FILE_BYTES_DEFAULT ||
            content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES_DEFAULT
        ) {
            Timber.tag(TAG).d("Skipping large file: %s (%d bytes)", path, size)
            removeSnapshotForCurrentUpdate(path, updateToken)
            return null
        }

        val parseResult = parseWithTimeout(content, parserState)
        val tree = when (parseResult) {
            is ParseResult.Success -> parseResult.tree
            ParseResult.Timeout -> {
                val message = "Parse timeout: ${file.name}"
                Timber.tag(TAG).w("Failed to parse file (timeout): %s", path)
                removeSnapshotForCurrentUpdate(path, updateToken)
                return message
            }
            is ParseResult.Failure -> {
                val message = "Parse failed: ${file.name} (${parseResult.cause.messageOrClass()})"
                Timber.tag(TAG).w(parseResult.cause, "Failed to parse file: %s", path)
                removeSnapshotForCurrentUpdate(path, updateToken)
                return message
            }
        }

        var warningMessage: String? = null
        try {
            if (!isCurrentFileUpdate(path, updateToken)) return null
            val globals = runCatching {
                parserState.provider.extractSymbols(tree.rootNode, content)
            }.getOrElse { e ->
                warningMessage = "Extract symbols failed: ${file.name} (${e.messageOrClass()})"
                Timber.tag(TAG).w(
                    e,
                    "Failed to extract symbols: provider=%s file=%s",
                    parserState.provider::class.java.simpleName,
                    path,
                )
                emptyList()
            }
            val snapshot = FileSnapshot.from(file, globals)
            lock.write {
                if (!isCurrentFileUpdate(path, updateToken)) return@write
                removeFileSnapshotLocked(path)
                applyFileSnapshotLocked(snapshot)
                fileSnapshots[path] = snapshot
                fileTimestamps[path] = file.lastModified()
                bumpRevisionLocked()
            }
        } finally {
            tree.close()
        }
        return warningMessage
    }

    private fun isCurrentProject(projectRoot: File, generation: Long): Boolean =
        isCurrentProjectGeneration(generation) && activeProjectRoot?.absolutePath == projectRoot.absolutePath

    private fun isCurrentProjectGeneration(generation: Long): Boolean = projectGeneration.get() == generation

    private fun beginFileUpdate(path: String, generation: Long): FileUpdateToken {
        val updateToken = FileUpdateToken(generation, fileUpdateSequence.incrementAndGet())
        while (true) {
            val current = latestFileUpdates[path]
            if (current != null && current.sequence >= updateToken.sequence) return updateToken
            if (current == null) {
                if (latestFileUpdates.putIfAbsent(path, updateToken) == null) return updateToken
            } else if (latestFileUpdates.replace(path, current, updateToken)) {
                return updateToken
            }
        }
    }

    private fun isCurrentFileUpdate(path: String, updateToken: FileUpdateToken): Boolean =
        isCurrentProjectGeneration(updateToken.projectGeneration) && latestFileUpdates[path] == updateToken

    private fun completeFileUpdate(path: String, updateToken: FileUpdateToken) {
        latestFileUpdates.remove(path, updateToken)
    }

    private fun hasInFlightFileUpdates(generation: Long): Boolean =
        latestFileUpdates.values.any { it.projectGeneration == generation }

    private fun removeSnapshotForCurrentUpdate(path: String, updateToken: FileUpdateToken) {
        lock.write {
            if (!isCurrentFileUpdate(path, updateToken)) return@write
            val removedSnapshot = removeFileSnapshotLocked(path)
            val removedTimestamp = fileTimestamps.remove(path) != null
            if (removedSnapshot || removedTimestamp) bumpRevisionLocked()
        }
    }

    private fun readFileForIndex(file: File): String {
        require(file.length() <= MAX_FILE_BYTES_DEFAULT) { "File exceeds symbol index size limit" }
        val bytes = file.inputStream().use { input ->
            ByteArrayOutputStream(minOf(file.length(), MAX_FILE_BYTES_DEFAULT.toLong()).toInt()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_FILE_BYTES_DEFAULT) { "File exceeds symbol index size limit" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun cancelInFlightParses() {
        parserStates.forEach { state ->
            runCatching { state.parser.requestCancellationAsync() }
        }
    }

    private fun bumpRevisionLocked() {
        val newRev = revision.incrementAndGet()
        mutableStatus.value = mutableStatus.value.copy(revision = newRev)
    }

    private fun applyFileSnapshotLocked(snapshot: FileSnapshot) {
        for (s in snapshot.globals) {
            val key = s.name.lowercase(Locale.ROOT)
            globalSymbolsByLower.getOrPut(key) { mutableListOf() }.add(s)
        }
    }

    private fun removeFileSnapshotLocked(path: String): Boolean {
        val old = fileSnapshots.remove(path) ?: return false

        for (s in old.globals) {
            val key = s.name.lowercase(Locale.ROOT)
            val list = globalSymbolsByLower[key] ?: continue
            list.removeAll { it.filePath == old.filePath && it.name == s.name && it.kind == s.kind }
            if (list.isEmpty()) globalSymbolsByLower.remove(key)
        }
        return true
    }

    private fun clearIndex(reason: String) {
        lock.write {
            fileSnapshots.clear()
            globalSymbolsByLower.clear()
            fileTimestamps.clear()
            latestFileUpdates.clear()
            val newRev = revision.incrementAndGet()
            mutableStatus.value = IndexStatus(
                projectRoot = activeProjectRoot?.absolutePath,
                isIndexing = false,
                indexedFiles = 0,
                totalFiles = 0,
                lastIndexedAt = null,
                lastError = null,
                revision = newRev,
                cacheLoaded = false,
                cacheHitFiles = 0,
            )
        }
        Timber.tag(TAG).i("Index cleared: $reason")
    }

    /**
     * 清除项目缓存
     */
    override fun clearCache() {
        val projectRoot = activeProjectRoot?.absolutePath ?: return
        indexCache?.clearCache(projectRoot)
    }

    private suspend fun collectProjectFiles(projectRoot: File): List<File> {
        val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrElse { projectRoot.absoluteFile }
        val out = ArrayList<File>(1024)
        val pendingDirectories = ArrayDeque<ScanDirectory>()
        val visitedDirectories = hashSetOf(canonicalRoot.path)
        pendingDirectories.add(ScanDirectory(canonicalRoot, depth = 0))
        var scannedEntries = 0

        while (pendingDirectories.isNotEmpty() &&
            out.size < MAX_INDEX_FILES &&
            scannedEntries < MAX_SCAN_ENTRIES
        ) {
            currentCoroutineContext().ensureActive()
            val current = pendingDirectories.removeLast()
            try {
                Files.newDirectoryStream(current.directory.toPath()).use { children ->
                    for (childPath in children) {
                        currentCoroutineContext().ensureActive()
                        scannedEntries++
                        if (scannedEntries > MAX_SCAN_ENTRIES) break
                        if (runCatching { Files.isSymbolicLink(childPath) }.getOrDefault(true)) continue

                        val canonicalChild = runCatching { childPath.toFile().canonicalFile }.getOrNull() ?: continue
                        if (!isUnderOrEqualRoot(canonicalChild, canonicalRoot)) continue
                        when {
                            canonicalChild.isDirectory -> {
                                if (current.depth >= MAX_SCAN_DEPTH || canonicalChild.name in IGNORED_DIR_NAMES) continue
                                if (visitedDirectories.add(canonicalChild.path)) {
                                    pendingDirectories.add(
                                        ScanDirectory(canonicalChild, depth = current.depth + 1)
                                    )
                                }
                            }
                            canonicalChild.isFile && isSupportedFile(canonicalChild) -> {
                                val size = runCatching { canonicalChild.length() }.getOrDefault(Long.MAX_VALUE)
                                if (size <= MAX_FILE_BYTES_DEFAULT) {
                                    out.add(canonicalChild)
                                    if (out.size >= MAX_INDEX_FILES) break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).d(e, "Skipping unreadable directory: %s", current.directory.path)
            }
        }
        if (out.size >= MAX_INDEX_FILES || scannedEntries >= MAX_SCAN_ENTRIES) {
            Timber.tag(TAG).w(
                "Project symbol scan reached its resource limit: files=%d entries=%d",
                out.size,
                scannedEntries,
            )
        }
        return out
    }

    private fun isSupportedFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in parserStateByExt

    private fun isUnderOrEqualRoot(file: File, root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        return file.path == rootPath || file.path.startsWith(rootPath + File.separator)
    }

    private fun <V> prefixView(map: TreeMap<String, V>, prefix: String): Map<String, V> {
        val fromKey = prefix
        val toKey = prefix + '\uFFFF'
        return map.subMap(fromKey, true, toKey, true)
    }

    override fun close() {
        indexingJob?.cancel()
        indexingJob = null
        cancelInFlightParses()
        projectGeneration.incrementAndGet()
        scope.cancel()
        parserStates.forEach { state ->
            runCatching { synchronized(state.lock) { state.parser.close() } }
        }
        Timber.tag(TAG).i("ProjectSymbolIndexService closed")
    }

    private data class ScanDirectory(
        val directory: File,
        val depth: Int,
    )
}

data class ProjectSymbol(
    val name: String,
    val kind: SymbolKind,
    val detail: String,
    val filePath: String,
    val location: SymbolLocation? = null,
    val signature: String? = null,
    val documentation: String? = null,
) {
    val displayDetail: String
        get() {
            val fileName = File(filePath).name
            return when {
                signature != null -> "$signature ($fileName)"
                detail.isNotBlank() -> "$detail ($fileName)"
                else -> fileName
            }
        }

    val displayDocumentation: String?
        get() = documentation?.takeIf { it.isNotBlank() }
}

private fun ProjectSymbol.composeStableKey(): String {
    val location = this.location
    return buildString {
        append(filePath)
        append("|")
        append(kind.name)
        append("|")
        append(name)
        append("|")
        append(location?.line ?: -1)
        append("|")
        append(location?.column ?: -1)
    }
}

/**
 * 模糊匹配结果：全局符号
 */
data class FuzzySymbolResult(
    val symbol: ProjectSymbol,
    val matchResult: FuzzyMatcher.MatchResult,
) {
    val score: Int get() = matchResult.score
    val matchedIndices: List<Int> get() = matchResult.matchedIndices
}

private data class FileSnapshot(
    val filePath: String,
    val globals: List<ProjectSymbol>,
) {
    companion object {
        fun fromCached(snapshot: SymbolIndexCache.CachedFileSnapshot): FileSnapshot = FileSnapshot(
            filePath = snapshot.filePath,
            globals = snapshot.globals,
        )

        fun from(file: File, globals: List<GlobalSymbol>): FileSnapshot {
            val filePath = file.absolutePath
            val symbols = globals.map {
                ProjectSymbol(
                    name = it.name,
                    kind = it.kind,
                    detail = it.detail,
                    filePath = filePath,
                    location = it.location,
                    signature = it.signature,
                    documentation = it.documentation,
                )
            }
            return FileSnapshot(
                filePath = filePath,
                globals = symbols,
            )
        }
    }
}

// ========== 类型转换扩展函数 ==========

/**
 * 将内部 IndexStatus 转换为接口 SymbolIndexStatus
 */
private fun ProjectSymbolIndexService.IndexStatus.toSymbolIndexStatus(): SymbolIndexStatus = SymbolIndexStatus(
    projectRoot = projectRoot,
    isIndexing = isIndexing,
    indexedFiles = indexedFiles,
    totalFiles = totalFiles,
    lastIndexedAt = lastIndexedAt,
    lastError = lastError,
    revision = revision,
    cacheLoaded = cacheLoaded,
    cacheHitFiles = cacheHitFiles,
)

/**
 * 将内部 ProjectSymbol 转换为接口 SymbolInfo
 */
private fun ProjectSymbol.toSymbolInfo(): SymbolInfo = SymbolInfo(
    name = name,
    kind = kind.toCoreSymbolKind(),
    detail = detail,
    filePath = filePath,
    location = location?.let {
        com.wuxianggujun.tinaide.core.symbol.SymbolLocation(
            startLine = it.line,
            startColumn = it.column,
            endLine = it.line,
            endColumn = it.column,
        )
    },
    signature = signature,
    documentation = documentation,
)

/**
 * 将 feature:editor 层的 SymbolKind 转换为 core:common 层的 SymbolKind
 */
private fun com.wuxianggujun.tinaide.editor.symbol.SymbolKind.toCoreSymbolKind(): com.wuxianggujun.tinaide.core.symbol.SymbolKind = when (this) {
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Class -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.CLASS
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Struct -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.STRUCT
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Enum -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.ENUM
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Namespace -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.NAMESPACE
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Function -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.FUNCTION
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Method -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.METHOD
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Field -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.FIELD
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Variable -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.VARIABLE
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Constant -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.CONSTANT
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Interface -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.INTERFACE
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Module -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.MODULE
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Property -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.PROPERTY
    com.wuxianggujun.tinaide.editor.symbol.SymbolKind.Trait -> com.wuxianggujun.tinaide.core.symbol.SymbolKind.INTERFACE // Trait 映射到 INTERFACE
}

/**
 * 将内部 FuzzySymbolResult 转换为接口 FuzzySymbolMatch
 */
private fun FuzzySymbolResult.toFuzzySymbolMatch(): FuzzySymbolMatch = FuzzySymbolMatch(
    symbol = symbol.toSymbolInfo(),
    score = score,
    matchedIndices = matchedIndices,
)

private fun Throwable.messageOrClass(): String = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

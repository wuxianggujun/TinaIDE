package com.wuxianggujun.tinaide.core.lsp

import android.util.Base64
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lang.ProjectPathFilters
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 项目同步状态
 */
enum class ProjectSyncState {
    IDLE, // 空闲
    SCANNING, // 扫描项目
    COMPRESSING, // 压缩文件
    UPLOADING, // 上传中
    SYNCED, // 已同步
    ERROR // 错误
}

/**
 * 项目同步进度
 */
data class ProjectSyncProgress(
    val state: ProjectSyncState = ProjectSyncState.IDLE,
    val currentFile: String = "",
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 0f

    val bytesProgressPercent: Float
        get() = if (totalBytes > 0) processedBytes.toFloat() / totalBytes else 0f

    val chunkProgressPercent: Float
        get() = if (totalChunks > 0) currentChunk.toFloat() / totalChunks else 0f
}

/**
 * 项目文件信息
 */
data class ProjectFileInfo(
    val relativePath: String,
    val content: String,
    val size: Long
)

/**
 * 分块同步配置
 */
data class ChunkConfig(
    val maxChunkSize: Long = 512 * 1024,
    val maxFilesPerChunk: Int = 50,
    val enabled: Boolean = true
) {
    init {
        require(maxChunkSize > 0L)
        require(maxFilesPerChunk > 0)
    }
}

/**
 * 同步块信息
 */
data class SyncChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val files: List<ProjectFileInfo>,
    val isLast: Boolean
)

/**
 * 项目同步管理器
 *
 * 负责：
 * 1. 扫描项目目录
 * 2. 过滤不需要同步的文件
 * 3. 压缩项目文件
 * 4. 生成同步消息
 * 5. 跟踪文件变更
 */
object ProjectSyncManager {

    private const val TAG = "ProjectSyncManager"
    const val MAX_SYNC_FILE_COUNT = 10_000
    const val MAX_SYNC_FILE_BYTES = 2L * 1024L * 1024L
    const val MAX_SYNC_TOTAL_BYTES = 64L * 1024L * 1024L
    const val MAX_SYNC_DEPTH = 64
    const val MAX_SYNC_PATH_BYTES = 1024

    // 同步进度状态流
    private val _progressFlow = MutableStateFlow(ProjectSyncProgress())
    val progressFlow: StateFlow<ProjectSyncProgress> = _progressFlow.asStateFlow()

    // 已同步的项目根目录
    private var syncedProjectRoot: String? = null

    // 已同步的文件列表（用于增量同步）
    private val syncedFiles = mutableMapOf<String, Long>() // path -> lastModified

    // 默认忽略的目录和文件
    private val defaultIgnorePatterns = ProjectPathFilters.SYNC_IGNORE_PATTERNS
    private val sensitiveFileNames = setOf(
        ".env",
        ".npmrc",
        ".pypirc",
        "credentials",
        "credentials.json",
        "client_secret.json",
        "service_account.json",
        ".git-credentials",
        ".netrc",
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519",
    )
    private val sensitiveConfigExtensions = setOf(
        "conf",
        "ini",
        "json",
        "properties",
        "toml",
        "txt",
        "yaml",
        "yml",
    )
    private val sensitiveConfigStems = setOf("credential", "credentials", "secret", "secrets")

    // 源代码文件扩展名（优先同步）
    private val sourceExtensions: Set<String> =
        CxxFileSupport.editorRelatedExtensions + setOf(
            // 一些项目会把头文件/片段写成 .inc
            "inc",
            // Java/Kotlin
            "java", "kt", "kts",
            // Python
            "py", "pyi",
            // JavaScript/TypeScript
            "js", "jsx", "ts", "tsx",
            // Rust
            "rs",
            // Go
            "go",
            // 配置文件
            "json", "xml", "yaml", "yml", "toml",
            // CMake
            "cmake", "txt" // CMakeLists.txt
        )

    /**
     * 扫描项目目录
     *
     * @param projectRoot 项目根目录
     * @param customIgnorePatterns 自定义忽略模式
     * @return 项目文件列表
     */
    suspend fun scanProject(
        projectRoot: File,
        customIgnorePatterns: List<String> = emptyList()
    ): List<ProjectFileInfo> = withContext(Dispatchers.IO) {
        val files = mutableListOf<ProjectFileInfo>()
        val ignorePatterns = defaultIgnorePatterns + customIgnorePatterns

        updateProgress(ProjectSyncState.SCANNING, totalFiles = 0)

        try {
            val canonicalRoot = projectRoot.canonicalFile
            require(canonicalRoot.isDirectory) { "Project root is not a directory" }
            scanDirectory(
                root = canonicalRoot,
                current = canonicalRoot,
                files = files,
                ignorePatterns = ignorePatterns,
                visitedDirectories = hashSetOf(),
                totalBytes = longArrayOf(0L),
                depth = 0,
            )
            Timber.tag(TAG).d("Project sync scan completed: files=%d", files.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to scan project: %s", e::class.java.simpleName)
            updateProgress(ProjectSyncState.ERROR, errorMessage = e.message)
            throw e
        }

        files
    }

    /**
     * 递归扫描目录
     */
    private fun scanDirectory(
        root: File,
        current: File,
        files: MutableList<ProjectFileInfo>,
        ignorePatterns: List<String>,
        visitedDirectories: MutableSet<String>,
        totalBytes: LongArray,
        depth: Int,
    ) {
        require(depth <= MAX_SYNC_DEPTH) { "Project directory depth exceeds the sync limit" }
        val canonicalCurrent = current.canonicalFile
        val rootPath = root.toPath()
        require(canonicalCurrent.toPath().startsWith(rootPath)) { "Project path escapes the project root" }
        if (!visitedDirectories.add(canonicalCurrent.path)) return
        val children = current.listFiles() ?: return

        for (child in children) {
            val lexicalRelativePath = child.relativeTo(root).path.replace('\\', '/')
            if (shouldIgnore(lexicalRelativePath, child.isDirectory, ignorePatterns)) {
                continue
            }
            if (Files.isSymbolicLink(child.toPath())) {
                Timber.tag(TAG).w("Skipping project symbolic link: %s", lexicalRelativePath)
                continue
            }
            val canonicalChild = child.canonicalFile
            require(canonicalChild.toPath().startsWith(rootPath)) {
                "Project symbolic link escapes the project root: ${child.path}"
            }
            val relativePath = canonicalChild.relativeTo(root).path.replace('\\', '/')

            if (child.isDirectory) {
                scanDirectory(root, canonicalChild, files, ignorePatterns, visitedDirectories, totalBytes, depth + 1)
            } else if (canonicalChild.isFile && isSourceFile(canonicalChild) && !isSensitiveFile(relativePath)) {
                try {
                    val fileSize = canonicalChild.length()
                    require(fileSize <= MAX_SYNC_FILE_BYTES) { "Project file exceeds the sync limit: $relativePath" }
                    require(files.size < MAX_SYNC_FILE_COUNT) { "Project has too many files to sync" }
                    require(fileSize <= MAX_SYNC_TOTAL_BYTES - totalBytes[0]) {
                        "Project exceeds the total sync size limit"
                    }
                    val (content, actualSize) = readUtf8TextWithLimit(canonicalChild, relativePath)
                    require(actualSize <= MAX_SYNC_TOTAL_BYTES - totalBytes[0]) {
                        "Project exceeds the total sync size limit"
                    }
                    files.add(
                        ProjectFileInfo(
                            relativePath = relativePath,
                            content = content,
                            size = actualSize
                        )
                    )
                    totalBytes[0] += actualSize
                    updateProgress(
                        ProjectSyncState.SCANNING,
                        currentFile = relativePath,
                        processedFiles = files.size
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to read project sync file: error=%s", e::class.java.simpleName)
                    throw e
                }
            }
        }
    }

    private fun readUtf8TextWithLimit(file: File, relativePath: String): Pair<String, Long> {
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read.toLong()
                require(totalBytes <= MAX_SYNC_FILE_BYTES) {
                    "Project file exceeds the sync limit: $relativePath"
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name()) to totalBytes
        }
    }

    fun validateSyncFiles(files: List<ProjectFileInfo>): Long {
        require(files.size <= MAX_SYNC_FILE_COUNT) { "Project has too many files to sync" }
        var totalSize = 0L
        val uniquePaths = HashSet<String>(files.size)
        files.forEach { file ->
            require(isSafeRelativeSyncPath(file.relativePath)) { "Project contains an unsafe sync path" }
            val normalizedPath = normalizeSyncPath(file.relativePath)
            require(uniquePaths.add(normalizedPath)) { "Project contains duplicate sync paths" }
            val actualSize = file.content.toByteArray(Charsets.UTF_8).size.toLong()
            require(actualSize <= MAX_SYNC_FILE_BYTES) { "Project contains an oversized sync file" }
            require(actualSize <= MAX_SYNC_TOTAL_BYTES - totalSize) {
                "Project exceeds the total sync size limit"
            }
            totalSize += actualSize
        }
        return totalSize
    }

    private fun isSensitiveFile(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/').lowercase()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        val stem = name.substringBeforeLast('.', missingDelimiterValue = name)
        return name in sensitiveFileNames ||
            name.startsWith(".env.") ||
            name.endsWith(".pem") ||
            name.endsWith(".key") ||
            (extension in sensitiveConfigExtensions && stem in sensitiveConfigStems)
    }

    /**
     * 检查是否应该忽略该路径
     */
    private fun shouldIgnore(path: String, isDirectory: Boolean, patterns: List<String>): Boolean {
        val pathToCheck = if (isDirectory) "$path/" else path

        for (pattern in patterns) {
            when {
                // 目录前缀模式（例如 cmake-build-*/）
                pattern.endsWith("*/") -> {
                    val prefix = pattern.removeSuffix("*/")
                    if (isDirectory && path.split('/').any { segment -> segment.startsWith(prefix) }) {
                        return true
                    }
                }
                // 目录模式（以 / 结尾）
                pattern.endsWith("/") -> {
                    if (isDirectory && (pathToCheck.startsWith(pattern) || pathToCheck.contains("/$pattern"))) {
                        return true
                    }
                }
                // 通配符模式
                pattern.startsWith("*") -> {
                    val suffix = pattern.substring(1)
                    if (path.endsWith(suffix)) {
                        return true
                    }
                }
                // 精确匹配
                else -> {
                    if (path == pattern || path.endsWith("/$pattern")) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 检查是否是源代码文件
     */
    private fun isSourceFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        // CMakeLists.txt 特殊处理
        if (file.name == "CMakeLists.txt" || file.name == "compile_commands.json") {
            return true
        }
        return extension in sourceExtensions
    }

    /**
     * 生成项目同步消息（JSON-RPC 格式）
     *
     * @param projectName 项目名称
     * @param files 项目文件列表
     * @param compress 是否压缩内容
     * @return JSON-RPC 消息字符串
     */
    suspend fun generateSyncMessage(
        projectName: String,
        files: List<ProjectFileInfo>,
        compress: Boolean = true
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        val totalSize = validateSyncFiles(files)
        updateProgress(ProjectSyncState.COMPRESSING, totalFiles = files.size)

        val filesArray = JSONArray()

        files.forEachIndexed { index, file ->
            val fileObj = JSONObject().apply {
                put("path", file.relativePath)
                put("content", if (compress) compressContent(file.content) else file.content)
                if (compress) {
                    put("compressed", true)
                }
            }
            filesArray.put(fileObj)

            updateProgress(
                ProjectSyncState.COMPRESSING,
                currentFile = file.relativePath,
                processedFiles = index + 1,
                totalFiles = files.size
            )
        }

        val params = JSONObject().apply {
            put("projectName", projectName)
            put("files", filesArray)
            put("totalSize", totalSize)
            put("fileCount", files.size)
            put("compressed", compress)
        }

        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProject")
            put("params", params)
        }

        val messageStr = message.toString()
        Pair(messageStr, messageStr.length.toLong())
    }

    /**
     * 压缩内容（gzip + base64）
     */
    private fun compressContent(content: String): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzip ->
            gzip.write(content.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 将文件列表分割成多个块
     *
     * @param files 项目文件列表
     * @param config 分块配置
     * @return 分块列表
     */
    fun splitIntoChunks(
        files: List<ProjectFileInfo>,
        config: ChunkConfig = ChunkConfig()
    ): List<SyncChunk> {
        validateSyncFiles(files)
        if (!config.enabled || files.isEmpty()) {
            return listOf(SyncChunk(0, 1, files, true))
        }

        val chunks = mutableListOf<SyncChunk>()
        var currentChunkFiles = mutableListOf<ProjectFileInfo>()
        var currentChunkSize = 0L

        for (file in files) {
            val fileSize = file.content.toByteArray(Charsets.UTF_8).size.toLong()
            // 检查是否需要开始新的块
            val shouldStartNewChunk = currentChunkFiles.isNotEmpty() &&
                (
                    currentChunkSize + fileSize > config.maxChunkSize ||
                        currentChunkFiles.size >= config.maxFilesPerChunk
                    )

            if (shouldStartNewChunk) {
                chunks.add(
                    SyncChunk(
                        chunkIndex = chunks.size,
                        totalChunks = 0, // 稍后更新
                        files = currentChunkFiles.toList(),
                        isLast = false
                    )
                )
                currentChunkFiles = mutableListOf()
                currentChunkSize = 0L
            }

            currentChunkFiles.add(file)
            currentChunkSize += fileSize
        }

        // 添加最后一个块
        if (currentChunkFiles.isNotEmpty()) {
            chunks.add(
                SyncChunk(
                    chunkIndex = chunks.size,
                    totalChunks = 0,
                    files = currentChunkFiles.toList(),
                    isLast = true
                )
            )
        }

        // 更新 totalChunks
        val totalChunks = chunks.size
        return chunks.mapIndexed { index, chunk ->
            chunk.copy(
                totalChunks = totalChunks,
                isLast = index == totalChunks - 1
            )
        }
    }

    /**
     * 生成分块同步消息
     *
     * @param projectName 项目名称
     * @param chunk 同步块
     * @param sessionId 同步会话 ID（用于服务器端组装）
     * @param compress 是否压缩
     * @return JSON-RPC 消息字符串
     */
    suspend fun generateChunkSyncMessage(
        projectName: String,
        chunk: SyncChunk,
        sessionId: String,
        compress: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        validateSyncFiles(chunk.files)
        val filesArray = JSONArray()

        chunk.files.forEach { file ->
            val fileObj = JSONObject().apply {
                put("path", file.relativePath)
                put("content", if (compress) compressContent(file.content) else file.content)
                if (compress) {
                    put("compressed", true)
                }
            }
            filesArray.put(fileObj)
        }

        val params = JSONObject().apply {
            put("projectName", projectName)
            put("sessionId", sessionId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("files", filesArray)
            put("fileCount", chunk.files.size)
            put("isLast", chunk.isLast)
            put("compressed", compress)
        }

        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProjectChunk")
            put("params", params)
        }.toString()
    }

    /**
     * 生成分块同步开始消息
     *
     * @param projectName 项目名称
     * @param sessionId 同步会话 ID
     * @param totalFiles 总文件数
     * @param totalSize 总大小
     * @param totalChunks 总块数
     * @return JSON-RPC 消息字符串
     */
    fun generateChunkSyncStartMessage(
        projectName: String,
        sessionId: String,
        totalFiles: Int,
        totalSize: Long,
        totalChunks: Int
    ): String {
        val params = JSONObject().apply {
            put("projectName", projectName)
            put("sessionId", sessionId)
            put("totalFiles", totalFiles)
            put("totalSize", totalSize)
            put("totalChunks", totalChunks)
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProjectStart")
            put("params", params)
        }.toString()
    }

    /**
     * 生成唯一的同步会话 ID
     */
    fun generateSessionId(): String = "sync-${UUID.randomUUID()}"

    /**
     * 判断是否应该使用分块传输
     *
     * @param files 文件列表
     * @param config 分块配置
     * @return 是否应该使用分块传输
     */
    fun shouldUseChunkedTransfer(
        files: List<ProjectFileInfo>,
        config: ChunkConfig = ChunkConfig()
    ): Boolean {
        if (!config.enabled) return false

        val totalSize = validateSyncFiles(files)
        val fileCount = files.size

        // 如果总大小超过 1MB 或文件数超过 100，使用分块传输
        return totalSize > 1024 * 1024 || fileCount > 100
    }

    /**
     * 生成文件变更消息
     *
     * @param type 变更类型：created, deleted, renamed, modified
     * @param path 文件路径
     * @param content 文件内容（仅 created 和 modified 需要）
     * @param oldPath 旧路径（仅 renamed 需要）
     * @return JSON-RPC 消息字符串
     */
    fun generateFileChangedMessage(
        type: String,
        path: String,
        content: String? = null,
        oldPath: String? = null
    ): String {
        require(type in FILE_CHANGE_TYPES) { "Unsupported file change type" }
        require(isSafeRelativeSyncPath(path)) { "File change path must stay inside the synchronized project" }
        require(oldPath == null || isSafeRelativeSyncPath(oldPath)) {
            "Previous file path must stay inside the synchronized project"
        }
        require(content == null || content.toByteArray(Charsets.UTF_8).size <= MAX_SYNC_FILE_BYTES) {
            "Changed file exceeds the sync size limit"
        }
        require(type !in CONTENT_REQUIRED_CHANGE_TYPES || content != null) {
            "Created and modified file changes require content"
        }
        require(type != "renamed" || oldPath != null) { "Renamed file changes require the previous path" }
        val params = JSONObject().apply {
            put("type", type)
            put("path", path)
            content?.let { put("content", it) }
            oldPath?.let { put("oldPath", it) }
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/fileChanged")
            put("params", params)
        }.toString()
    }

    fun isSafeRelativeSyncPath(path: String): Boolean {
        val normalized = normalizeSyncPath(path)
        if (normalized.isBlank() || normalized.startsWith('/') ||
            normalized.toByteArray(Charsets.UTF_8).size > MAX_SYNC_PATH_BYTES ||
            normalized.any(Char::isISOControl) ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized)
        ) {
            return false
        }
        return normalized.split('/').none { it.isBlank() || it == "." || it == ".." } &&
            !isSensitiveFile(normalized)
    }

    private fun normalizeSyncPath(path: String): String = path.replace('\\', '/').trim()

    /**
     * 检测项目特征，判断应该使用哪种同步模式
     *
     * @param projectRoot 项目根目录
     * @return 推荐的同步模式和原因
     */
    suspend fun detectSyncMode(projectRoot: File): Pair<RemoteLspSyncMode, String> = withContext(Dispatchers.IO) {
        // 检查是否存在 CMakeLists.txt 或 compile_commands.json
        val hasCMake = File(projectRoot, "CMakeLists.txt").exists()
        val hasCompileCommands = hasProjectCompileCommands(projectRoot)

        if (hasCMake || hasCompileCommands) {
            return@withContext Pair(
                RemoteLspSyncMode.PROJECT,
                if (hasCMake) {
                    Strings.lsp_sync_reason_has_cmake.str()
                } else {
                    Strings.lsp_sync_reason_has_compile_commands.str()
                }
            )
        }

        val (fileCount, totalSize) = summarizeProjectForMode(projectRoot)

        // 判断规则
        return@withContext when {
            fileCount > PROJECT_MODE_FILE_THRESHOLD || totalSize > PROJECT_MODE_SIZE_THRESHOLD -> {
                Pair(
                    RemoteLspSyncMode.PROJECT,
                    Strings.lsp_sync_reason_project_recommended.str(fileCount, formatSize(totalSize))
                )
            }
            else -> {
                Pair(
                    RemoteLspSyncMode.LIGHTWEIGHT,
                    Strings.lsp_sync_reason_lightweight.str(fileCount, formatSize(totalSize))
                )
            }
        }
    }

    private fun summarizeProjectForMode(projectRoot: File): Pair<Int, Long> {
        val canonicalRoot = projectRoot.canonicalFile
        if (!canonicalRoot.isDirectory) return 0 to 0L

        var fileCount = 0
        var totalSize = 0L
        var scannedEntries = 0
        val rootPath = canonicalRoot.toPath()
        val visitedDirectories = hashSetOf<String>()
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(canonicalRoot to 0)

        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeLast()
            if (depth > MAX_SYNC_DEPTH) return PROJECT_MODE_FILE_THRESHOLD + 1 to totalSize
            val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: continue
            if (!canonicalDirectory.toPath().startsWith(rootPath) ||
                !visitedDirectories.add(canonicalDirectory.path)
            ) {
                continue
            }

            canonicalDirectory.listFiles()?.forEach { child ->
                scannedEntries++
                if (scannedEntries > MAX_MODE_SCAN_ENTRY_COUNT) {
                    return PROJECT_MODE_FILE_THRESHOLD + 1 to totalSize
                }
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                if (!canonicalChild.toPath().startsWith(rootPath)) return@forEach
                val relativePath = canonicalChild.relativeTo(canonicalRoot).path.replace('\\', '/')
                if (shouldIgnore(relativePath, child.isDirectory, defaultIgnorePatterns)) return@forEach

                if (child.isDirectory) {
                    pending.add(canonicalChild to depth + 1)
                } else if (canonicalChild.isFile &&
                    isSourceFile(canonicalChild) &&
                    !isSensitiveFile(relativePath)
                ) {
                    fileCount++
                    totalSize = (totalSize + canonicalChild.length()).coerceAtMost(PROJECT_MODE_SIZE_THRESHOLD + 1)
                    if (fileCount > PROJECT_MODE_FILE_THRESHOLD || totalSize > PROJECT_MODE_SIZE_THRESHOLD) {
                        return fileCount to totalSize
                    }
                }
            }
        }
        return fileCount to totalSize
    }

    private fun hasProjectCompileCommands(projectRoot: File): Boolean {
        val candidates = listOf(
            "compile_commands.json",
            "build/compile_commands.json",
            "build/debug/compile_commands.json",
            "build/release/compile_commands.json",
            "cmake-build-debug/compile_commands.json",
            "cmake-build-release/compile_commands.json",
            "out/build/compile_commands.json"
        )
        return candidates.any { relative ->
            File(projectRoot, relative).let { it.isFile && it.length() > 0L }
        }
    }

    private const val PROJECT_MODE_FILE_THRESHOLD = 20
    private const val PROJECT_MODE_SIZE_THRESHOLD = 1024L * 1024L
    private const val MAX_MODE_SCAN_ENTRY_COUNT = 20_000
    private val FILE_CHANGE_TYPES = setOf("created", "deleted", "renamed", "modified")
    private val CONTENT_REQUIRED_CHANGE_TYPES = setOf("created", "modified")

    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    /**
     * 更新同步进度
     */
    private fun updateProgress(
        state: ProjectSyncState,
        currentFile: String = _progressFlow.value.currentFile,
        processedFiles: Int = _progressFlow.value.processedFiles,
        totalFiles: Int = _progressFlow.value.totalFiles,
        processedBytes: Long = _progressFlow.value.processedBytes,
        totalBytes: Long = _progressFlow.value.totalBytes,
        currentChunk: Int = _progressFlow.value.currentChunk,
        totalChunks: Int = _progressFlow.value.totalChunks,
        errorMessage: String? = null
    ) {
        _progressFlow.value = ProjectSyncProgress(
            state = state,
            currentFile = currentFile,
            processedFiles = processedFiles,
            totalFiles = totalFiles,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            currentChunk = currentChunk,
            totalChunks = totalChunks,
            errorMessage = errorMessage
        )
    }

    /**
     * 更新分块上传进度（公开方法，供外部调用）
     */
    fun updateChunkProgress(currentChunk: Int, totalChunks: Int) {
        updateProgress(
            state = ProjectSyncState.UPLOADING,
            currentChunk = currentChunk,
            totalChunks = totalChunks
        )
    }

    /**
     * 重置同步状态
     */
    fun reset() {
        _progressFlow.value = ProjectSyncProgress()
        syncedProjectRoot = null
        syncedFiles.clear()
    }

    /**
     * 标记项目已同步
     */
    fun markSynced(projectRoot: String, files: List<ProjectFileInfo>) {
        syncedProjectRoot = projectRoot
        syncedFiles.clear()
        files.forEach { file ->
            syncedFiles[file.relativePath] = System.currentTimeMillis()
        }
        updateProgress(ProjectSyncState.SYNCED, processedFiles = files.size, totalFiles = files.size)
    }

    /**
     * 检查文件是否已同步
     */
    fun isFileSynced(relativePath: String): Boolean = syncedFiles.containsKey(relativePath)

    /**
     * 获取已同步的项目根目录
     */
    fun getSyncedProjectRoot(): String? = syncedProjectRoot
}

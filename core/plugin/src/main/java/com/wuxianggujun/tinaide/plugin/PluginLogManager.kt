package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.script.RateLimiter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import timber.log.Timber

/**
 * 插件日志级别
 */
enum class PluginLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * 插件日志条目
 */
data class PluginLogEntry(
    val id: Long,
    val timestamp: Long,
    val pluginId: String,
    val pluginName: String,
    val level: PluginLogLevel,
    val message: String,
    val stackTrace: String? = null,
    val eventCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 转换为可复制的文本格式
     */
    fun toClipboardText(): String = buildString {
        appendLine(Strings.plugin_log_time.str(getFormattedDate()))
        appendLine(Strings.plugin_log_level_line.str(level.name))
        appendLine(Strings.plugin_log_plugin.str(pluginName, pluginId))
        appendLine(Strings.plugin_log_message_line.str(message))
        stackTrace?.let {
            appendLine(Strings.plugin_log_stacktrace.str())
            appendLine(it)
        }
    }
}

/**
 * 用于序列化的日志条目（上传到服务器）
 */
@Serializable
data class PluginLogEntryDto(
    val id: Long,
    val timestamp: Long,
    val pluginId: String,
    val pluginName: String,
    val level: String,
    val message: String,
    val stackTrace: String? = null,
    val eventCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * 插件日志管理器
 *
 * 负责收集和管理所有插件的日志输出，支持持久化和上传
 */
class PluginLogManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PluginLogManager"
        private const val MAX_LOG_ENTRIES = 5000 // 内存中最多保存5000条日志
        private const val MAX_PERSISTED_ENTRIES = 5000
        private const val MAX_LOGS_PER_PLUGIN = 1000
        private const val MAX_LOG_BYTES = 8 * 1024
        private const val MAX_LOGS_PER_MINUTE = 120
        private const val LOG_FILE_NAME = "plugin_logs.json"

        @Volatile
        private var instance: PluginLogManager? = null

        fun getInstance(context: Context): PluginLogManager = instance ?: synchronized(this) {
            instance ?: PluginLogManager(context.applicationContext).also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = JsonSerializer.default

    private val idGenerator = AtomicLong(0)
    private val logs = CopyOnWriteArrayList<PluginLogEntry>()
    private val logRateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val _logsFlow = MutableStateFlow<List<PluginLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<PluginLogEntry>> = _logsFlow.asStateFlow()

    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME)

    init {
        // 启动时加载持久化的日志
        loadPersistedLogs()
    }

    /**
     * 添加日志条目
     */
    fun log(
        pluginId: String,
        pluginName: String,
        level: PluginLogLevel,
        message: String,
        stackTrace: String? = null,
        eventCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val limiter = logRateLimiters.computeIfAbsent(pluginId) {
            RateLimiter(MAX_LOGS_PER_MINUTE, 60_000L)
        }
        if (!limiter.tryAcquire()) return
        val entry = PluginLogEntry(
            id = idGenerator.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            pluginId = pluginId,
            pluginName = pluginName,
            level = level,
            message = truncateUtf8(sanitizeDiagnosticText(message), MAX_LOG_BYTES),
            stackTrace = stackTrace?.let(::sanitizeDiagnosticText)?.let { truncateUtf8(it, MAX_LOG_BYTES) },
            eventCode = eventCode,
            attributes = attributes.mapValues { (_, value) -> truncateUtf8(sanitizeDiagnosticText(value), 1024) },
        )

        logs.add(entry)

        while (logs.count { it.pluginId == pluginId } > MAX_LOGS_PER_PLUGIN) {
            logs.indexOfFirst { it.pluginId == pluginId }
                .takeIf { it >= 0 }
                ?.let(logs::removeAt)
                ?: break
        }

        // 限制内存中日志数量
        while (logs.size > MAX_LOG_ENTRIES) {
            logs.removeAt(0)
        }

        _logsFlow.value = logs.toList()

        // 异步持久化
        schedulePersistedSave()
    }

    /**
     * 便捷方法：记录 DEBUG 级别日志
     */
    fun debug(
        pluginId: String,
        pluginName: String,
        message: String,
        eventCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(
            pluginId = pluginId,
            pluginName = pluginName,
            level = PluginLogLevel.DEBUG,
            message = message,
            eventCode = eventCode,
            attributes = attributes,
        )
    }

    fun debug(source: PluginLogSource, message: String) {
        debug(source.id, source.name, message)
    }

    /**
     * 便捷方法：记录 INFO 级别日志
     */
    fun info(
        pluginId: String,
        pluginName: String,
        message: String,
        eventCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(
            pluginId = pluginId,
            pluginName = pluginName,
            level = PluginLogLevel.INFO,
            message = message,
            eventCode = eventCode,
            attributes = attributes,
        )
    }

    fun info(source: PluginLogSource, message: String) {
        info(source.id, source.name, message)
    }

    /**
     * 便捷方法：记录 WARN 级别日志
     */
    fun warn(
        pluginId: String,
        pluginName: String,
        message: String,
        eventCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(
            pluginId = pluginId,
            pluginName = pluginName,
            level = PluginLogLevel.WARN,
            message = message,
            eventCode = eventCode,
            attributes = attributes,
        )
    }

    fun warn(source: PluginLogSource, message: String) {
        warn(source.id, source.name, message)
    }

    /**
     * 便捷方法：记录 ERROR 级别日志
     */
    fun error(
        pluginId: String,
        pluginName: String,
        message: String,
        stackTrace: String? = null,
        eventCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(
            pluginId = pluginId,
            pluginName = pluginName,
            level = PluginLogLevel.ERROR,
            message = message,
            stackTrace = stackTrace,
            eventCode = eventCode,
            attributes = attributes,
        )
    }

    fun error(source: PluginLogSource, message: String, stackTrace: String? = null) {
        error(source.id, source.name, message, stackTrace)
    }

    /**
     * 获取所有日志
     */
    fun getAllLogs(): List<PluginLogEntry> = logs.toList()

    /**
     * 获取日志数量
     */
    fun getLogCount(): Int = logs.size

    /**
     * 获取指定插件的日志
     */
    fun getLogsForPlugin(pluginId: String): List<PluginLogEntry> = logs.filter { it.pluginId == pluginId }

    /**
     * 获取指定级别的日志
     */
    fun getLogsByLevel(level: PluginLogLevel): List<PluginLogEntry> = logs.filter { it.level == level }

    /**
     * 清空所有日志
     */
    fun clearAll() {
        logs.clear()
        _logsFlow.value = emptyList()
        // 删除持久化文件
        scope.launch {
            runCatching { logFile.delete() }
        }
    }

    /**
     * 清空指定插件的日志
     */
    fun clearForPlugin(pluginId: String) {
        logs.removeAll { it.pluginId == pluginId }
        logRateLimiters.remove(pluginId)
        _logsFlow.value = logs.toList()
        schedulePersistedSave()
    }

    /**
     * 导出日志为文本格式
     */
    fun exportToText(): String = buildString {
        logs.forEach { entry ->
            appendLine("${entry.getFormattedDate()} [${entry.level}] [${entry.pluginName}] ${entry.message}")
            entry.stackTrace?.let {
                appendLine("Stack trace:")
                appendLine(it)
                appendLine()
            }
        }
    }

    /**
     * 导出日志为 JSON 格式（用于上传到服务器）
     */
    fun exportToJson(): String {
        val dtos = logs.map { entry ->
            PluginLogEntryDto(
                id = entry.id,
                timestamp = entry.timestamp,
                pluginId = entry.pluginId,
                pluginName = entry.pluginName,
                level = entry.level.name,
                message = entry.message,
                stackTrace = entry.stackTrace,
                eventCode = entry.eventCode,
                attributes = entry.attributes,
            )
        }
        return json.encodeToString(dtos)
    }

    /**
     * 获取用于上传的日志数据
     *
     * @param maxEntries 最大条目数，默认返回所有
     * @param minLevel 最小日志级别，默认返回所有级别
     * @param sinceTimestamp 只返回此时间戳之后的日志，默认返回所有
     */
    fun getLogsForUpload(
        maxEntries: Int = Int.MAX_VALUE,
        minLevel: PluginLogLevel? = null,
        sinceTimestamp: Long? = null
    ): List<PluginLogEntryDto> = logs
        .filter { entry ->
            (minLevel == null || entry.level.ordinal >= minLevel.ordinal) &&
                (sinceTimestamp == null || entry.timestamp >= sinceTimestamp)
        }
        .takeLast(maxEntries)
        .map { entry ->
            PluginLogEntryDto(
                id = entry.id,
                timestamp = entry.timestamp,
                pluginId = entry.pluginId,
                pluginName = entry.pluginName,
                level = entry.level.name,
                message = entry.message,
                stackTrace = entry.stackTrace,
                eventCode = entry.eventCode,
                attributes = entry.attributes,
            )
        }

    /**
     * 获取最近的错误日志（用于崩溃报告）
     */
    fun getRecentErrors(maxEntries: Int = 50): List<PluginLogEntryDto> = getLogsForUpload(
        maxEntries = maxEntries,
        minLevel = PluginLogLevel.ERROR
    )

    /**
     * 获取最近的警告和错误日志
     */
    fun getRecentWarningsAndErrors(maxEntries: Int = 100): List<PluginLogEntryDto> = getLogsForUpload(
        maxEntries = maxEntries,
        minLevel = PluginLogLevel.WARN
    )

    // ==================== 持久化相关 ====================

    private var pendingSave = false

    private fun schedulePersistedSave() {
        if (pendingSave) return
        pendingSave = true
        scope.launch {
            kotlinx.coroutines.delay(1000) // 延迟1秒批量保存
            saveToFile()
            pendingSave = false
        }
    }

    private fun saveToFile() {
        runCatching {
            val entriesToSave = logs.takeLast(MAX_PERSISTED_ENTRIES)
            val dtos = entriesToSave.map { entry ->
                PluginLogEntryDto(
                    id = entry.id,
                    timestamp = entry.timestamp,
                    pluginId = entry.pluginId,
                    pluginName = entry.pluginName,
                    level = entry.level.name,
                    message = entry.message,
                    stackTrace = entry.stackTrace,
                    eventCode = entry.eventCode,
                    attributes = entry.attributes,
                )
            }
            JsonSerializer.encodeToFile(logFile, dtos)
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to save plugin logs")
        }
    }

    private fun loadPersistedLogs() {
        scope.launch {
            runCatching {
                if (!logFile.exists()) return@launch

                val content = logFile.readText()
                if (content.isBlank()) return@launch

                val dtos: List<PluginLogEntryDto> = json.decodeFromString(content)

                // 找到最大 ID 以继续生成
                val maxId = dtos.maxOfOrNull { it.id } ?: 0
                idGenerator.set(maxId)

                val entries = dtos.map { dto ->
                    PluginLogEntry(
                        id = dto.id,
                        timestamp = dto.timestamp,
                        pluginId = dto.pluginId,
                        pluginName = dto.pluginName,
                        level = PluginLogLevel.valueOf(dto.level),
                        message = dto.message,
                        stackTrace = dto.stackTrace,
                        eventCode = dto.eventCode,
                        attributes = dto.attributes,
                    )
                }

                logs.clear()
                logs.addAll(
                    entries
                        .groupBy { it.pluginId }
                        .values
                        .flatMap { pluginEntries -> pluginEntries.takeLast(MAX_LOGS_PER_PLUGIN) }
                        .sortedBy { it.timestamp }
                        .takeLast(MAX_LOG_ENTRIES)
                )
                _logsFlow.value = logs.toList()

                Timber.tag(TAG).i("Loaded ${logs.size} plugin logs from file")
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to load plugin logs")
                // 加载失败时删除损坏的文件
                runCatching { logFile.delete() }
            }
        }
    }

    private fun sanitizeDiagnosticText(value: String): String = value
        .replace(Regex("/data/(?:data|user/\\d+)/[^/\\s]+(?:/[^\\s]*)?"), "<private-path>")
        .replace(Regex("(?i)\\b[A-Z]:[\\\\/](?:[^\\s,;]+[\\\\/])*[^\\s,;]*"), "<private-path>")
        .replace(Regex("(?<![:/A-Za-z0-9])/(?:[^\\s,;:()\\[\\]]+/)*[^\\s,;:()\\[\\]]+"), "<private-path>")
        .replace(Regex("(?i)(token|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var index = 0
        var usedBytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (usedBytes + bytes > maxBytes) break
            result.append(text)
            usedBytes += bytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}

package com.wuxianggujun.tinaide.core.crash

import com.wuxianggujun.tinaide.core.network.aegis.AegisBreadcrumb
import com.wuxianggujun.tinaide.core.network.aegis.AegisException
import com.wuxianggujun.tinaide.core.network.aegis.AegisFrame
import com.wuxianggujun.tinaide.core.network.aegis.AegisIngestReport
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 把 xCrash 墓碑转换成 AegisHub 的 `crash` 报告。
 *
 * 设计要点：
 * - 事件 ID 由“包名 + 墓碑文件名 + mtime”推导，重试时保持幂等；
 * - AegisHub 要求 crash 类型必须带结构化异常和至少一帧，解析失败时用兜底帧；
 * - `attributes` 必须为空（标准 crash schema 没有自定义字段），设备信息放进 breadcrumb；
 * - 只解析上传前已脱敏的文本，避免把用户路径/凭据带进结构化字段。
 */
internal object AegisCrashReportMapper {

    private const val MAX_TYPE_BYTES = 128
    private const val MAX_MESSAGE_BYTES = 8192
    private const val MAX_FRAME_FUNCTION_BYTES = 512
    private const val MAX_FRAME_FILE_BYTES = 1024
    private const val MAX_FRAMES = 64

    // xCrash also wrote plain version names before TinaIDE added build IDs to its label.
    private val appVersionLabel = Regex(
        """([A-Za-z0-9][A-Za-z0-9.+_-]{0,63})(?: \(code [0-9]+(?:, build ([A-Za-z0-9._+-]{1,128}))?\))?"""
    )

    private val crashTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val javaFrameRegex = Regex("""^\s+at\s+([^\s(]+)\(([^)]*)\)\s*$""")
    private val nativeFrameRegex = Regex("""^\s*#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+(.*\S)\s*$""")
    private val signalRegex = Regex(
        """^signal\s+-?\d+\s+\((SIG[A-Z0-9]+)\)(?:,\s*code\s+-?\d+\s+\([A-Z0-9_]+\))?(?:,\s*fault addr\s+0x[0-9a-fA-F]+)?"""
    )
    private val abortMessageRegex = Regex("""^Abort message:\s*'(.*)'\s*$""")
    private val anrReasonRegex = Regex("""^Reason:\s*(.+?)\s*$""")
    private val javaExceptionRegex = Regex("""^([\w.$]+(?:Exception|Error|Throwable))(?::\s*(.*))?$""")

    fun build(
        fileName: String,
        crashLog: String,
        fileModifiedAtMillis: Long,
        packageName: String,
        environment: String,
        userReference: String?,
    ): AegisIngestReport {
        val text = stripNulls(crashLog)
        val lines = text.lineSequence().toList()
        val crashType = header(lines, "Crash type")?.lowercase().orEmpty()
        val version = header(lines, "App version")?.let(appVersionLabel::matchEntire)
        val occurredAtMillis = parseCrashTimeMillis(header(lines, "Crash time"))
            ?: fileModifiedAtMillis.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        val exception = buildException(crashType, lines, fileName)
        val reportMessage = buildMessage(crashType, packageName, exception)

        return AegisIngestReport(
            eventId = deterministicEventId(
                "tinaide:crash:$packageName:$fileName:$fileModifiedAtMillis:$occurredAtMillis"
            ),
            typeKey = "crash",
            schemaVersion = 1,
            release = version?.groupValues?.get(1) ?: "unknown",
            buildId = version?.groups?.get(2)?.value,
            environment = environment,
            occurredAt = Instant.ofEpochMilli(occurredAtMillis).toString(),
            userReference = userReference?.takeIf { it.isNotBlank() },
            message = truncateBytes(reportMessage, MAX_MESSAGE_BYTES),
            exception = exception,
            breadcrumbs = buildBreadcrumbs(lines, fileName, crashType, packageName),
        )
    }

    private fun buildException(
        crashType: String,
        lines: List<String>,
        fileName: String,
    ): AegisException {
        val javaException = parseJavaException(lines)
        val signal = lines.firstNotNullOfOrNull { line -> signalRegex.find(line.trim()) }
        val abortMessage = lines.firstNotNullOfOrNull { abortMessageRegex.find(it.trim()) }
            ?.groupValues?.get(1)

        val type: String
        val message: String
        val frames: List<AegisFrame>

        when {
            crashType == "java" && javaException != null -> {
                type = javaException.first
                message = javaException.second
                frames = parseJavaFrames(lines)
            }
            crashType == "anr" -> {
                val reason = lines.firstNotNullOfOrNull { anrReasonRegex.find(it.trim()) }
                    ?.groupValues?.get(1)
                type = "ANR"
                message = reason ?: javaException?.second.orEmpty()
                frames = parseJavaFrames(lines)
            }
            signal != null -> {
                type = signal.groupValues[1]
                message = buildString {
                    append(signal.value)
                    abortMessage?.takeIf { it.isNotBlank() }?.let {
                        append(" | Abort message: ")
                        append(it)
                    }
                }
                frames = parseNativeFrames(lines)
            }
            javaException != null -> {
                type = javaException.first
                message = javaException.second
                frames = parseJavaFrames(lines)
            }
            else -> {
                type = "Crash"
                message = header(lines, "Crash type")?.let { "Unclassified $it crash" }.orEmpty()
                frames = emptyList()
            }
        }

        val safeType = truncateBytes(type.ifBlank { "Crash" }, MAX_TYPE_BYTES)
        val safeMessage = truncateBytes(message, MAX_MESSAGE_BYTES)
        val safeFrames = frames
            .take(MAX_FRAMES)
            .ifEmpty {
                listOf(
                    AegisFrame(
                        function = truncateBytes(safeType, MAX_FRAME_FUNCTION_BYTES),
                        file = truncateBytes(fileName.ifBlank { "tombstone" }, MAX_FRAME_FILE_BYTES),
                    )
                )
            }
        return AegisException(type = safeType, message = safeMessage, frames = safeFrames)
    }

    private fun parseJavaException(lines: List<String>): Pair<String, String>? {
        val start = lines.indexOfFirst { it.trim() == "java stacktrace:" }
        val candidates = if (start >= 0) lines.drop(start + 1) else lines.take(80)
        for (line in candidates) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("at ")) continue
            if (trimmed.startsWith("Caused by:")) continue
            val match = javaExceptionRegex.find(trimmed) ?: continue
            val type = match.groupValues[1]
            val message = match.groupValues.getOrNull(2).orEmpty()
            return type to message
        }
        return null
    }

    private fun parseJavaFrames(lines: List<String>): List<AegisFrame> {
        val frames = ArrayList<AegisFrame>()
        var started = false
        for (line in lines) {
            val match = javaFrameRegex.find(line)
            if (match != null) {
                started = true
                val function = match.groupValues[1].trim()
                val location = match.groupValues[2].trim()
                val locationParts = location.split(':').let { parts ->
                    // xCrash 会写出 `ActivityThread.java:0` 这类占位行号；服务端只接受正数，
                    // 因此 <= 0 统一视为未知行号（并去掉该后缀），而不是原样上报后被整条报告拒收。
                    val rawLine = parts.lastOrNull()?.trim()?.toIntOrNull()
                    if (parts.size >= 2 && rawLine != null) {
                        val file = parts.dropLast(1).joinToString(":")
                        file to rawLine.takeIf { it > 0 }
                    } else {
                        location to null
                    }
                }
                frames += AegisFrame(
                    function = truncateBytes(function, MAX_FRAME_FUNCTION_BYTES),
                    file = truncateBytes(locationParts.first.ifBlank { "<unknown>" }, MAX_FRAME_FILE_BYTES),
                    line = locationParts.second,
                )
                if (frames.size >= MAX_FRAMES) break
            } else if (started) {
                break
            }
        }
        return frames
    }

    private fun parseNativeFrames(lines: List<String>): List<AegisFrame> {
        val frames = ArrayList<AegisFrame>()
        var started = false
        for (line in lines) {
            val match = nativeFrameRegex.find(line)
            if (match != null) {
                started = true
                val tail = match.groupValues[3].trim()
                val split = tail.lastIndexOf(" (")
                val file: String
                val symbol: String
                if (split >= 0 && tail.endsWith(")")) {
                    file = tail.substring(0, split).trim()
                    symbol = tail.substring(split + 2, tail.length - 1).trim()
                } else {
                    file = tail
                    symbol = ""
                }
                val function = symbol.substringBefore('+').trim()
                frames += AegisFrame(
                    function = truncateBytes(function.ifBlank { "<unknown>" }, MAX_FRAME_FUNCTION_BYTES),
                    file = truncateBytes(file.ifBlank { "<unknown>" }, MAX_FRAME_FILE_BYTES),
                    module = file.substringAfterLast('/').takeIf {
                        it.isNotBlank() && !it.startsWith("<")
                    }?.let { truncateBytes(it, 256) },
                )
                if (frames.size >= MAX_FRAMES) break
            } else if (started && line.isNotBlank()) {
                break
            }
        }
        return frames
    }

    private fun buildMessage(crashType: String, packageName: String, exception: AegisException): String {
        val label = when (crashType) {
            "native" -> "Native"
            "java" -> "Java"
            "anr" -> "ANR"
            else -> "Crash"
        }
        val detail = buildString {
            append(exception.type)
            if (exception.message.isNotBlank()) {
                append(": ")
                append(exception.message)
            }
        }
        return "$label crash in ${packageName.ifBlank { "unknown" }}: $detail"
    }

    private fun buildBreadcrumbs(
        lines: List<String>,
        fileName: String,
        crashType: String,
        packageName: String,
    ): List<AegisBreadcrumb> {
        val deviceData = buildMap {
            header(lines, "Brand")?.let { put("brand", truncateBytes(it, 64)) }
            header(lines, "Manufacturer")?.let { put("manufacturer", truncateBytes(it, 64)) }
            header(lines, "Model")?.let { put("model", truncateBytes(it, 64)) }
            header(lines, "OS version")?.let { put("android_version", truncateBytes(it, 64)) }
            header(lines, "API level")?.let { put("sdk", truncateBytes(it, 32)) }
            header(lines, "ABI list")?.let { put("abi", truncateBytes(it, 64)) }
            header(lines, "Rooted")?.let { put("rooted", truncateBytes(it, 16)) }
            if (packageName.isNotBlank()) put("package", truncateBytes(packageName, 64))
            if (crashType.isNotBlank()) put("crash_type", truncateBytes(crashType, 32))
        }
        return listOf(
            AegisBreadcrumb(
                category = "device",
                level = "info",
                message = "Device context",
                data = deviceData,
            ),
            AegisBreadcrumb(
                category = "log",
                level = "info",
                message = truncateBytes("tombstone: ${fileName.ifBlank { "<unknown>" }}", 1024),
            ),
        )
    }

    private fun header(lines: List<String>, name: String): String? {
        val prefix = "$name:"
        return lines.asSequence()
            .take(80)
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(':')
            ?.trim()
            ?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseCrashTimeMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(value, crashTimeFormatter).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun deterministicEventId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun stripNulls(value: String): String = value.replace("\u0000", "")

    private fun truncateBytes(value: String, maxBytes: Int): String {
        if (value.isEmpty()) return value
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}

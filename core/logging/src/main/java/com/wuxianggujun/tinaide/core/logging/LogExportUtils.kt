package com.wuxianggujun.tinaide.core.logging

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import com.wuxianggujun.tinaide.core.common.AppVersionInfoReader
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.util.CrashLogPrivacyClassifier
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 日志导出工具类
 * 用于收集和导出应用日志以便问题诊断
 */
object LogExportUtils {
    private const val TAG = "LogExportUtils"
    private const val USER_RUNTIME_TOMBSTONE_SCAN_BYTES = 64 * 1024

    @Volatile
    private var installLogTextProvider: (() -> String?)? = null

    /**
     * HOST 启动后注入安装日志快照来源，避免 object 内 GlobalContext 取服务。
     */
    fun bindInstallLogTextProvider(provider: (() -> String?)?) {
        installLogTextProvider = provider
    }

    /**
     * 导出日志到ZIP文件
     *
     * @param context 上下文
     * @return 导出的ZIP文件，失败返回null
     */
    suspend fun exportLogs(
        context: Context,
        profile: LogExportProfile = LogExportProfile.LOCAL_EXPORT
    ): File? = withContext(Dispatchers.IO) {
        try {
            // 创建导出目录
            val exportDir = File(context.getExternalFilesDir(null), "logs")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            // 生成文件名（包含时间戳）
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = Strings.log_export_filename.strOr(context, timestamp)
            val zipFile = File(exportDir, fileName)

            val policy = LogExportPolicy.from(profile)

            // 创建ZIP文件
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                addLogBundleSources(context, zipOut, policy)
            }

            Timber.tag(TAG).d("Logs exported to: %s", zipFile.absolutePath)
            zipFile
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to export logs")
            null
        }
    }

    private fun addLogBundleSources(context: Context, zipOut: ZipOutputStream, policy: LogExportPolicy) {
        if (policy.includes(LogBundleSource.EXPORT_MANIFEST)) {
            addExportManifestToZip(zipOut, policy)
        }

        if (policy.includes(LogBundleSource.LOGCAT)) {
            // 服务器上传只按 TinaIDE 相关进程 pid 收集，不回退到全局 logcat。
            addLogcatToZip(
                context = context,
                zipOut = zipOut,
                fileName = policy.logcatEntryName,
                allowGlobalFallback = policy.allowGlobalLogcatFallback
            )
        }
        if (policy.includes(LogBundleSource.APP_INFO)) addAppInfoToZip(context, zipOut, "app_info.txt")
        if (policy.includes(LogBundleSource.DEVICE_INFO)) addDeviceInfoToZip(zipOut, "device_info.txt")
        if (policy.includes(LogBundleSource.CRASH_LOGS)) addCrashLogsToZip(context, zipOut)
        if (policy.includes(LogBundleSource.BUILD_LOGS)) addBuildLogsToZip(context, zipOut)
        if (policy.includes(LogBundleSource.INSTALL_LOGS)) addInstallLogsToZip(context, zipOut)
        if (policy.includes(LogBundleSource.INSTALL_LOG_SNAPSHOT)) {
            addInstallLogSnapshotToZip(zipOut, "install_log_snapshot.txt")
        }
        if (policy.includes(LogBundleSource.ROOTFS_PACKAGE_MANAGER_LOGS)) {
            addRootfsPackageManagerLogsToZip(context, zipOut)
        }
        if (policy.includes(LogBundleSource.PROOT_LOGS)) addPRootLogsToZip(context, zipOut)
        if (policy.includes(LogBundleSource.TOMBSTONES)) {
            addTombstoneLogsToZip(
                context = context,
                zipOut = zipOut,
                includeUserRuntimeTombstones = policy.includeUserRuntimeTombstones
            )
        }
        if (policy.includes(LogBundleSource.TIMBER_LOGS)) addTimberLogsToZip(zipOut)
    }

    private fun addExportManifestToZip(zipOut: ZipOutputStream, policy: LogExportPolicy) {
        try {
            val entry = ZipEntry(LogBundleSource.EXPORT_MANIFEST.entryName)
            zipOut.putNextEntry(entry)

            val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val manifest = LogExportManifestBuilder.build(policy, exportTime)

            zipOut.write(manifest.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add export manifest to zip")
        }
    }

    /**
     * 分享日志文件
     */
    fun shareLogs(context: Context, logFile: File) {
        try {
            val uri = ExternalFileIntents.getShareableUri(context, logFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, Strings.log_share_subject.strOr(context))
                putExtra(Intent.EXTRA_TEXT, Strings.log_share_text.strOr(context))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, Strings.log_share_chooser_title.strOr(context)))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to share logs")
        }
    }

    /**
     * 清空日志缓存
     */
    suspend fun clearLogs(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), "logs")
            if (exportDir.exists()) {
                exportDir.listFiles()?.forEach { deleteRecursively(it) }
            }

            // 清空内部日志目录（如果有）
            val internalLogDir = File(context.filesDir, "logs")
            if (internalLogDir.exists()) {
                internalLogDir.listFiles()?.forEach { deleteRecursively(it) }
            }

            Timber.tag(TAG).d("Logs cleared successfully")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear logs")
            false
        }
    }

    /**
     * 递归删除日志缓存文件。
     */
    private fun deleteRecursively(target: File) {
        if (!target.exists()) return
        if (target.isDirectory) {
            target.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { target.delete() }
    }

    /**
     * 添加 Logcat 日志到 ZIP。
     */
    private fun addLogcatToZip(
        context: Context,
        zipOut: ZipOutputStream,
        fileName: String,
        allowGlobalFallback: Boolean
    ) {
        try {
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)

            zipOut.write("=== Logcat Dump ===\n".toByteArray(Charsets.UTF_8))

            var wroteLines = 0

            fun dump(command: List<String>): Int {
                var commandLines = 0
                val process = runCatching {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                }.getOrElse { throwable ->
                    zipOut.write(
                        "(failed to start logcat: ${throwable.message ?: throwable.javaClass.simpleName})\n"
                            .toByteArray(Charsets.UTF_8)
                    )
                    return 0
                }
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        zipOut.write((line + "\n").toByteArray(Charsets.UTF_8))
                        wroteLines++
                        commandLines++
                    }
                }
                val exitCode = runCatching { process.waitFor() }.getOrNull()
                if (exitCode != null && exitCode != 0) {
                    zipOut.write("(logcat exited with code $exitCode)\n".toByteArray(Charsets.UTF_8))
                }
                return commandLines
            }

            val commands = LogcatDumpPlanner.buildCommands(
                LogcatDumpPlanner.collectTargets(context)
            )
            if (commands.isEmpty()) {
                zipOut.write(
                    "\n=== no TinaIDE process pid available for logcat collection ===\n"
                        .toByteArray(Charsets.UTF_8)
                )
            }

            commands.forEach { plan ->
                zipOut.write("\n=== ${plan.label} ===\n".toByteArray(Charsets.UTF_8))
                if (dump(plan.command) == 0) {
                    zipOut.write("(no logcat output for this process)\n".toByteArray(Charsets.UTF_8))
                }
            }

            if (wroteLines == 0 && allowGlobalFallback) {
                zipOut.write("\n=== logcat --pid produced no output, falling back to global dump ===\n".toByteArray(Charsets.UTF_8))
                dump(listOf("logcat", "-d", "-t", "10000", "-v", "threadtime"))
            } else if (wroteLines == 0) {
                zipOut.write("\n=== TinaIDE process logcat produced no output; global dump skipped for privacy ===\n".toByteArray(Charsets.UTF_8))
            }

            zipOut.closeEntry()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add logcat to zip")
        }
    }

    /**
     * 添加应用信息到ZIP
     */
    private fun addAppInfoToZip(context: Context, zipOut: ZipOutputStream, fileName: String) {
        try {
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)

            val appVersionInfo = AppVersionInfoReader.read(context)
            val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val appInfo = buildString {
                appendLine(Strings.log_export_app_info_title.strOr(context))
                appendLine(
                    Strings.log_export_app_name.strOr(
                        context,
                        context.applicationInfo.loadLabel(context.packageManager)
                    )
                )
                appendLine(Strings.log_export_package_name.strOr(context, context.packageName))
                appendLine(Strings.log_export_version_name.strOr(context, appVersionInfo.versionName))
                appendLine(Strings.log_export_version_code.strOr(context, appVersionInfo.baseVersionCode))
                appendLine(Strings.log_export_export_time.strOr(context, exportTime))
                appendLine()
            }

            zipOut.write(appInfo.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add app info to zip")
        }
    }

    /**
     * 添加设备信息到ZIP
     * 使用统一的 DeviceInfoProvider 确保数据一致性
     */
    private fun addDeviceInfoToZip(zipOut: ZipOutputStream, fileName: String) {
        try {
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)

            val deviceInfo = com.wuxianggujun.tinaide.core.device.DeviceInfoProvider.getDeviceInfoText()

            zipOut.write(deviceInfo.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add device info to zip")
        }
    }

    /**
     * 添加崩溃日志到ZIP
     */
    private fun addCrashLogsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val crashDir = File(context.filesDir, "crashes")
            if (crashDir.exists() && crashDir.isDirectory) {
                crashDir.listFiles()?.forEach { crashFile ->
                    if (crashFile.isFile && crashFile.name.endsWith(".log")) {
                        val entry = ZipEntry("crashes/${crashFile.name}")
                        zipOut.putNextEntry(entry)
                        crashFile.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add crash logs to zip")
        }
    }

    /**
     * 添加构建日志到ZIP
     */
    private fun addBuildLogsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val buildLogDir = File(context.filesDir, "build_logs")
            if (buildLogDir.exists() && buildLogDir.isDirectory) {
                buildLogDir.listFiles()?.forEach { logFile ->
                    if (logFile.isFile && logFile.name.endsWith(".log")) {
                        val entry = ZipEntry("build_logs/${logFile.name}")
                        zipOut.putNextEntry(entry)
                        logFile.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add build logs to zip")
        }
    }

    /**
     * 添加安装日志到ZIP
     *
     * 使用 ProjectPaths.getInstallLogsRoot() 获取安装日志目录
     */
    private fun addInstallLogsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val installLogDir = ProjectPaths.getInstallLogsRoot(context)
            if (installLogDir.exists() && installLogDir.isDirectory) {
                installLogDir.listFiles()?.forEach { logFile ->
                    if (logFile.isFile && (logFile.name.endsWith(".log") || logFile.name.endsWith(".txt"))) {
                        val entry = ZipEntry("install_logs/${logFile.name}")
                        zipOut.putNextEntry(entry)
                        logFile.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add install logs to zip")
        }
    }

    private fun addInstallLogSnapshotToZip(zipOut: ZipOutputStream, fileName: String) {
        try {
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)

            val content = buildString {
                appendLine("=== TinaIDE Install Log Snapshot ===")
                appendLine("This file is a snapshot generated at export time.")
                appendLine()
                appendLine(
                    installLogTextProvider?.invoke()
                        ?: "(InstallLogManager not available)"
                )
                appendLine()
                appendLine("--- End of Snapshot ---")
            }

            zipOut.write(content.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add install log snapshot to zip")
        }
    }

    private fun addRootfsPackageManagerLogsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val rootfsDir = runCatching { File(PRootBootstrap.getActiveRootfsPath(context)) }
                .getOrNull()
                ?: return
            if (!rootfsDir.exists() || !rootfsDir.isDirectory) return

            val candidates = listOf(
                "var/log/apt/term.log",
                "var/log/apt/history.log",
                "var/log/dpkg.log",
                "etc/apt/sources.list",
                "etc/apt/apt.conf.d/99-tinaide",
            )

            for (relative in candidates) {
                val file = File(rootfsDir, relative)
                if (!file.exists() || !file.isFile || file.length() <= 0L) continue
                addFileToZipWithTailLimit(
                    zipOut = zipOut,
                    entryName = "rootfs_logs/$relative",
                    file = file,
                    maxBytes = 1_000_000L
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add rootfs apt/dpkg logs to zip")
        }
    }

    private fun addPRootLogsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val prootLogDir = ProjectPaths.getPRootLogsRoot(context)
            if (!prootLogDir.exists() || !prootLogDir.isDirectory) return

            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val logFiles = prootLogDir.listFiles { file ->
                file.isFile && file.name.endsWith(".log") && file.lastModified() >= cutoff
            }?.sortedByDescending { it.lastModified() } ?: return

            for (logFile in logFiles) {
                addFileToZipWithTailLimit(
                    zipOut = zipOut,
                    entryName = "proot_logs/${logFile.name}",
                    file = logFile,
                    maxBytes = 2_000_000L
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add proot logs to zip")
        }
    }

    private fun addFileToZipWithTailLimit(
        zipOut: ZipOutputStream,
        entryName: String,
        file: File,
        maxBytes: Long
    ) {
        val safeMaxBytes = maxBytes.coerceAtLeast(1L)
        val len = file.length()
        val entry = ZipEntry(entryName)
        zipOut.putNextEntry(entry)

        try {
            if (len <= safeMaxBytes) {
                file.inputStream().use { it.copyTo(zipOut) }
            } else {
                val header = "=== File truncated (tail only) ===\npath=${file.absolutePath}\nsize=$len bytes\nkept=$safeMaxBytes bytes\n\n"
                zipOut.write(header.toByteArray(Charsets.UTF_8))

                val start = (len - safeMaxBytes).coerceAtLeast(0L)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(16 * 1024)
                    var remaining = safeMaxBytes
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        zipOut.write(buf, 0, read)
                        remaining -= read.toLong()
                    }
                }
            }
        } finally {
            zipOut.closeEntry()
        }
    }

    /**
     * 添加 Tombstone（Native崩溃）日志到ZIP
     *
     * 使用 ProjectPaths.getTombstonesRoot() 获取 tombstone 日志目录
     */
    private fun addTombstoneLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        includeUserRuntimeTombstones: Boolean
    ) {
        try {
            val tombstoneDir = ProjectPaths.getTombstonesRoot(context)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                tombstoneDir.listFiles()?.forEach { logFile ->
                    if (logFile.isFile && (includeUserRuntimeTombstones || !isUserRuntimeTombstone(context, logFile))) {
                        val entry = ZipEntry("tombstones/${logFile.name}")
                        zipOut.putNextEntry(entry)
                        logFile.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add tombstone logs to zip")
        }
    }

    private fun isUserRuntimeTombstone(context: Context, tombstone: File): Boolean {
        val header = runCatching {
            readUtf8MaxBytes(tombstone, USER_RUNTIME_TOMBSTONE_SCAN_BYTES)
        }.getOrDefault("")
        val packageName = context.packageName
        return CrashLogPrivacyClassifier.isUserRuntimeCrash(packageName, header)
    }

    private fun readUtf8MaxBytes(file: File, maxBytes: Int): String {
        val cap = maxBytes.coerceAtLeast(1)
        val buffer = ByteArray(cap)
        val read = file.inputStream().use { input -> input.read(buffer) }
        if (read <= 0) return ""
        return buffer.decodeToString(endIndex = read)
    }

    /**
     * 添加 Timber 日志文件到ZIP
     *
     * Timber 日志文件格式：tinaide_2025-01-08.log
     * 存放位置：/sdcard/Android/data/<package>/files/logs/
     */
    private fun addTimberLogsToZip(zipOut: ZipOutputStream) {
        try {
            val logFiles = TinaTimber.getAllLogFiles()
            if (logFiles.isEmpty()) {
                Timber.tag(TAG).d("No Timber log files to export")
                return
            }

            for (logFile in logFiles) {
                if (logFile.isFile && logFile.exists()) {
                    val entry = ZipEntry("timber_logs/${logFile.name}")
                    zipOut.putNextEntry(entry)
                    logFile.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            Timber.tag(TAG).d("Added %d Timber log files to zip", logFiles.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add Timber logs to zip")
        }
    }
}

internal object LogcatDumpPlanner {
    private const val DEFAULT_TAIL_LINES = 10000
    private const val MAX_TARGETS = 8
    private val runtimeTagFilters = listOf(
        "TINA_USER_OUTPUT:*",
        "SDL:*",
        "System.out:*",
        "System.err:*",
        "AndroidRuntime:*",
        "libc:*"
    )

    fun collectTargets(context: Context): List<LogcatDumpTarget> {
        val packageName = context.packageName
        val currentProcessName = Application.getProcessName().orEmpty()
            .takeIf { it.isNotBlank() }
            ?: packageName
        val targets = mutableListOf(
            LogcatDumpTarget(
                pid = Process.myPid(),
                processName = currentProcessName,
                source = "current"
            )
        )

        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager?.runningAppProcesses
            .orEmpty()
            .filter { process -> isTinaProcess(packageName, process.processName) }
            .forEach { process ->
                targets += LogcatDumpTarget(
                    pid = process.pid,
                    processName = process.processName,
                    source = "running"
                )
            }

        LogProcessRegistry.loadRecentRecords(context).forEach { record ->
            if (CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, record.processName)) {
                targets += LogcatDumpTarget(
                    pid = record.pid,
                    processName = record.processName,
                    source = "recent-runtime"
                )
            }
        }

        return normalizeTargets(packageName, targets)
    }

    fun buildCommands(
        targets: List<LogcatDumpTarget>,
        tailLines: Int = DEFAULT_TAIL_LINES
    ): List<LogcatDumpCommand> = targets
        .asSequence()
        .filter { target -> target.pid > 0 && target.processName.isNotBlank() }
        .take(MAX_TARGETS)
        .map { target ->
            val command = mutableListOf(
                "logcat",
                "-d",
                "--pid=${target.pid}",
                "-t",
                tailLines.toString(),
                "-v",
                "threadtime"
            )
            if (target.isUserRuntime) {
                command += "-s"
                command += runtimeTagFilters
            }
            LogcatDumpCommand(
                label = "process=${target.processName} pid=${target.pid} source=${target.source}",
                command = command
            )
        }
        .toList()

    internal fun normalizeTargets(
        packageName: String,
        targets: List<LogcatDumpTarget>
    ): List<LogcatDumpTarget> {
        val merged = linkedMapOf<Int, LogcatDumpTarget>()
        for (target in targets) {
            if (target.pid <= 0 || !isTinaProcess(packageName, target.processName)) continue
            val isUserRuntime = CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, target.processName)
            if (target.source == "recent-runtime" && !isUserRuntime) continue
            val normalized = target.copy(
                isUserRuntime = target.isUserRuntime || isUserRuntime
            )
            val existing = merged[target.pid]
            merged[target.pid] = if (existing == null) {
                normalized
            } else {
                existing.copy(
                    source = listOf(existing.source, normalized.source)
                        .distinct()
                        .joinToString("+"),
                    isUserRuntime = existing.isUserRuntime || normalized.isUserRuntime
                )
            }
        }
        return merged.values.take(MAX_TARGETS)
    }

    private fun isTinaProcess(packageName: String, processName: String?): Boolean {
        if (packageName.isBlank() || processName.isNullOrBlank()) return false
        return processName == packageName || processName.startsWith("$packageName:")
    }
}

internal data class LogcatDumpTarget(
    val pid: Int,
    val processName: String,
    val source: String,
    val isUserRuntime: Boolean = false
)

internal data class LogcatDumpCommand(
    val label: String,
    val command: List<String>
)

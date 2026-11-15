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

    /**
     * 单个上传附件的原始输入预算。
     *
     * AegisHub 对单个附件限制 10MB；ZIP deflate 最坏情况只比输入大约 0.03%，
     * 因此 8MB 输入可以保证最终 ZIP 低于上限。“保证可上传”比“整包丢弃”更重要。
     */
    const val UPLOAD_MAX_INPUT_BYTES = 8L * 1024 * 1024

    /** AegisHub 单个附件字节上限。 */
    const val MAX_UPLOAD_ATTACHMENT_BYTES = 10L * 1024 * 1024

    /** Logcat 数量无上限，只能占用剩余预算的一半，避免挤掉 Timber/墓碑。 */
    private const val LOGCAT_BUDGET_SHARE = 0.5

    /** Timber 是多文件历史，同样单独限流，保证构建/安装日志仍有空间。 */
    private const val TIMBER_BUDGET_SHARE = 0.7

    /** 共享 crash buffer 的读取上限（字符），过滤后最多保留 512KB。 */
    private const val MAX_CRASH_BUFFER_READ_CHARS = 4 * 1024 * 1024

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
     * @param profile 导出场景；决定包含哪些来源、是否允许全局 logcat 回退
     * @param maxInputBytes 写入 ZIP 的原始字节上限。自动上报用它保证制品可上传，
     *        而不是在超限时整包丢弃：超出后按“保留最新数据”的方式停止写入。
     * @return 导出的ZIP文件，失败返回null
     */
    suspend fun exportLogs(
        context: Context,
        profile: LogExportProfile = LogExportProfile.LOCAL_EXPORT,
        maxInputBytes: Long = Long.MAX_VALUE
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
            val budget = LogExportBudget(maxInputBytes.coerceAtLeast(1L))

            // 创建ZIP文件
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                addLogBundleSources(context, zipOut, policy, budget)
            }

            Timber.tag(TAG).d(
                "Logs exported to: %s (profile=%s, inputBudget=%d, truncated=%s)",
                zipFile.absolutePath,
                profile,
                maxInputBytes,
                budget.truncated
            )
            zipFile
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to export logs")
            null
        }
    }

    private fun addLogBundleSources(
        context: Context,
        zipOut: ZipOutputStream,
        policy: LogExportPolicy,
        budget: LogExportBudget
    ) {
        // A local export is a user-controlled file. Anything that leaves the device
        // (manual feedback or automatic crash upload) is sanitized because the user
        // does not review every line before it is uploaded.
        val sanitize = policy.profile != LogExportProfile.LOCAL_EXPORT
        val packageName = context.packageName
        if (policy.includes(LogBundleSource.EXPORT_MANIFEST)) {
            addExportManifestToZip(zipOut, policy)
        }

        // Small, deterministic entries first: a huge logcat/Timber history must not be
        // able to consume the whole budget before the high-value entries are written.
        if (policy.includes(LogBundleSource.APP_INFO)) addAppInfoToZip(context, zipOut, "app_info.txt")
        if (policy.includes(LogBundleSource.DEVICE_INFO)) addDeviceInfoToZip(zipOut, "device_info.txt")
        if (policy.includes(LogBundleSource.INSTALL_LOGS)) {
            addInstallLogsToZip(context, zipOut, packageName, sanitize, budget)
        }
        if (policy.includes(LogBundleSource.INSTALL_LOG_SNAPSHOT)) {
            addInstallLogSnapshotToZip(zipOut, "install_log_snapshot.txt", packageName, sanitize, budget)
        }
        if (policy.includes(LogBundleSource.CRASH_LOGS)) {
            addCrashLogsToZip(context, zipOut, packageName, sanitize, budget)
        }
        if (policy.includes(LogBundleSource.TOMBSTONES)) {
            addTombstoneLogsToZip(
                context = context,
                zipOut = zipOut,
                includeUserRuntimeTombstones = policy.includeUserRuntimeTombstones,
                sanitize = sanitize,
                budget = budget
            )
        }
        if (policy.includes(LogBundleSource.BUILD_LOGS)) {
            // Build/install diagnostics answer most user reports; they must not be
            // starved by the unbounded logcat dump, so they are written first.
            addBuildLogsToZip(context, zipOut, packageName, sanitize, budget)
        }
        if (policy.includes(LogBundleSource.LOGCAT)) {
            // 服务器上传只按 TinaIDE 相关进程 pid 收集，不回退到全局 logcat。
            // Logcat is unbounded, so it only gets a slice of the remaining budget.
            addLogcatToZip(
                context = context,
                zipOut = zipOut,
                fileName = policy.logcatEntryName,
                allowGlobalFallback = policy.allowGlobalLogcatFallback,
                includeRecentHost = policy.includeRecentHostLogcat,
                includeCrashBuffer = policy.includeCrashBuffer,
                sanitize = sanitize,
                budget = budget.reserve(LOGCAT_BUDGET_SHARE)
            )
        }
        if (policy.includes(LogBundleSource.ROOTFS_PACKAGE_MANAGER_LOGS)) {
            addRootfsPackageManagerLogsToZip(context, zipOut, budget)
        }
        if (policy.includes(LogBundleSource.PROOT_LOGS)) {
            addPRootLogsToZip(context, zipOut, budget)
        }
        if (policy.includes(LogBundleSource.TIMBER_LOGS)) {
            addTimberLogsToZip(zipOut, packageName, sanitize, budget.reserve(TIMBER_BUDGET_SHARE))
        }
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
     *
     * 服务器上传/崩溃自动上报只采集 TinaIDE 进程的 pid，并额外采集
     * 已退出进程（例如刚崩溃的宿主进程）的 pid 记录与包名过滤后的 crash buffer，
     * 这样崩溃现场不会因为进程消失而丢失。
     */
    private fun addLogcatToZip(
        context: Context,
        zipOut: ZipOutputStream,
        fileName: String,
        allowGlobalFallback: Boolean,
        includeRecentHost: Boolean,
        includeCrashBuffer: Boolean,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        // The crash buffer is the only source that survives the death of the crashing
        // process, so it is written before the voluminous per-pid logcat dump.
        if (includeCrashBuffer) {
            addCrashBufferToZip(context, zipOut, sanitize, budget)
        }

        val packageName = context.packageName
        var entryOpen = false
        try {
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)
            entryOpen = true

            fun emit(text: String): Boolean =
                budget.writeText(
                    zipOut,
                    if (sanitize) LogTextSanitizer.sanitize(text, packageName) else text
                )

            emit("=== Logcat Dump ===\n")

            var wroteLines = 0

            fun dump(command: List<String>): Int {
                var commandLines = 0
                if (budget.exhausted()) {
                    emit("(log bundle input budget exhausted before this command)\n")
                    return 0
                }
                val process = runCatching {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                }.getOrElse { throwable ->
                    emit(
                        "(failed to start logcat: ${throwable.message ?: throwable.javaClass.simpleName})\n"
                    )
                    return 0
                }
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (!emit(line + "\n")) break
                        wroteLines++
                        commandLines++
                    }
                }
                val exitCode = runCatching { process.waitFor() }.getOrNull()
                if (exitCode != null && exitCode != 0) {
                    emit("(logcat exited with code $exitCode)\n")
                }
                return commandLines
            }

            val commands = LogcatDumpPlanner.buildCommands(
                LogcatDumpPlanner.collectTargets(context, includeRecentHost = includeRecentHost)
            )
            if (commands.isEmpty()) {
                emit("\n=== no TinaIDE process pid available for logcat collection ===\n")
            }

            commands.forEach { plan ->
                emit("\n=== ${plan.label} ===\n")
                if (dump(plan.command) == 0) {
                    emit("(no logcat output for this process)\n")
                }
            }

            if (wroteLines == 0 && allowGlobalFallback) {
                emit("\n=== logcat --pid produced no output, falling back to global dump ===\n")
                dump(listOf("logcat", "-d", "-t", "10000", "-v", "threadtime"))
            } else if (wroteLines == 0) {
                emit("\n=== TinaIDE process logcat produced no output; global dump skipped for privacy ===\n")
            }

            zipOut.closeEntry()
            entryOpen = false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add logcat to zip")
        } finally {
            // Leaving a half-written entry open would corrupt every later entry.
            if (entryOpen) runCatching { zipOut.closeEntry() }
        }
    }

    /**
     * Android `crash` 缓冲区是所有进程共享的，因此只保留包含本应用包名的崩溃块。
     */
    private fun addCrashBufferToZip(
        context: Context,
        zipOut: ZipOutputStream,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val process = runCatching {
                ProcessBuilder(listOf("logcat", "-d", "-b", "crash", "-v", "threadtime"))
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return
            // The shared crash buffer is normally small; cap the read so a device with
            // a pathological buffer cannot exhaust memory in the crash upload path.
            val raw = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = StringBuilder()
                val chunk = CharArray(8 * 1024)
                while (buffer.length < MAX_CRASH_BUFFER_READ_CHARS) {
                    val read = reader.read(chunk)
                    if (read <= 0) break
                    buffer.append(chunk, 0, read)
                }
                buffer.toString()
            }
            runCatching { process.waitFor() }

            val filtered = LogcatCrashBuffer.filter(raw, context.packageName)
            val text = if (filtered.isEmpty()) {
                "(no crash-buffer entries matched ${context.packageName})\n"
            } else {
                filtered
            }
            zipOut.putNextEntry(ZipEntry("crash_buffer_logcat.txt"))
            try {
                budget.writeText(
                    zipOut,
                    if (sanitize) LogTextSanitizer.sanitize(text, context.packageName) else text
                )
            } finally {
                zipOut.closeEntry()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add crash buffer to zip")
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
    private fun addCrashLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        packageName: String,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val crashDir = File(context.filesDir, "crashes")
            if (crashDir.exists() && crashDir.isDirectory) {
                crashDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { crashFile ->
                        addFileToZipWithTailLimit(
                            zipOut = zipOut,
                            entryName = "crashes/${crashFile.name}",
                            file = crashFile,
                            maxBytes = 2_000_000L,
                            budget = budget,
                            packageName = packageName,
                            sanitize = sanitize
                        )
                    }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add crash logs to zip")
        }
    }

    /**
     * 添加构建日志到ZIP
     */
    private fun addBuildLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        packageName: String,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val buildLogDir = File(context.filesDir, "build_logs")
            if (buildLogDir.exists() && buildLogDir.isDirectory) {
                buildLogDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { logFile ->
                        addFileToZipWithTailLimit(
                            zipOut = zipOut,
                            entryName = "build_logs/${logFile.name}",
                            file = logFile,
                            maxBytes = 2_000_000L,
                            budget = budget,
                            packageName = packageName,
                            sanitize = sanitize
                        )
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
    private fun addInstallLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        packageName: String,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val installLogDir = ProjectPaths.getInstallLogsRoot(context)
            if (installLogDir.exists() && installLogDir.isDirectory) {
                installLogDir.listFiles()
                    ?.filter {
                        it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".txt"))
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { logFile ->
                        addFileToZipWithTailLimit(
                            zipOut = zipOut,
                            entryName = "install_logs/${logFile.name}",
                            file = logFile,
                            maxBytes = 1_000_000L,
                            budget = budget,
                            packageName = packageName,
                            sanitize = sanitize
                        )
                    }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add install logs to zip")
        }
    }

    private fun addInstallLogSnapshotToZip(
        zipOut: ZipOutputStream,
        fileName: String,
        packageName: String,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
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

            zipOut.putNextEntry(ZipEntry(fileName))
            try {
                budget.writeText(
                    zipOut,
                    if (sanitize) LogTextSanitizer.sanitize(content, packageName) else content
                )
            } finally {
                zipOut.closeEntry()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add install log snapshot to zip")
        }
    }

    private fun addRootfsPackageManagerLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        budget: LogExportBudget
    ) {
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
                    maxBytes = 1_000_000L,
                    budget = budget
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add rootfs apt/dpkg logs to zip")
        }
    }

    private fun addPRootLogsToZip(
        context: Context,
        zipOut: ZipOutputStream,
        budget: LogExportBudget
    ) {
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
                    maxBytes = 2_000_000L,
                    budget = budget
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
        maxBytes: Long,
        budget: LogExportBudget,
        packageName: String = "",
        sanitize: Boolean = false
    ) {
        val safeMaxBytes = maxBytes.coerceAtLeast(1L)
        val len = file.length()
        zipOut.putNextEntry(ZipEntry(entryName))

        try {
            if (budget.exhausted()) {
                budget.writeText(zipOut, "(skipped: log bundle input budget exhausted)\n")
                return
            }
            // Never read more than the remaining budget: a multi-MB tail would be thrown
            // away anyway, and reading it first risks an OOM on low-memory devices.
            val keep = minOf(len, safeMaxBytes, budget.remaining())
            val start = (len - keep).coerceAtLeast(0L)

            if (sanitize) {
                // Only the tail is read, and text is sanitized before it reaches the ZIP.
                val bytes = ByteArray(keep.toInt())
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    var offset = 0
                    while (offset < bytes.size) {
                        val read = raf.read(bytes, offset, bytes.size - offset)
                        if (read <= 0) break
                        offset += read
                    }
                }
                val text = if (start > 0L) {
                    "=== File truncated (tail only) ===\nsize=$len bytes\nkept=$keep bytes\n\n" +
                        String(bytes, Charsets.UTF_8)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                budget.writeText(zipOut, LogTextSanitizer.sanitize(text, packageName))
                return
            }

            if (start > 0L) {
                budget.writeText(
                    zipOut,
                    "=== File truncated (tail only) ===\nsize=$len bytes\nkept=$keep bytes\n\n"
                )
            }
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(16 * 1024)
                var remaining = keep
                while (remaining > 0 && !budget.exhausted()) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    budget.write(zipOut, buf.copyOf(read))
                    remaining -= read.toLong()
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
        includeUserRuntimeTombstones: Boolean,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val tombstoneDir = ProjectPaths.getTombstonesRoot(context)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                tombstoneDir.listFiles()
                    ?.filter { it.isFile }
                    ?.filter {
                        includeUserRuntimeTombstones || !isUserRuntimeTombstone(context, it)
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { logFile ->
                        addFileToZipWithTailLimit(
                            zipOut = zipOut,
                            entryName = "tombstones/${logFile.name}",
                            file = logFile,
                            maxBytes = 1_000_000L,
                            budget = budget,
                            packageName = context.packageName,
                            sanitize = sanitize
                        )
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
    private fun addTimberLogsToZip(
        zipOut: ZipOutputStream,
        packageName: String,
        sanitize: Boolean,
        budget: LogExportBudget
    ) {
        try {
            val logFiles = TinaTimber.getAllLogFiles()
            if (logFiles.isEmpty()) {
                Timber.tag(TAG).d("No Timber log files to export")
                return
            }

            // Newest first: when the budget runs out the remaining files are the oldest.
            for (logFile in logFiles.sortedByDescending { it.lastModified() }) {
                if (!logFile.isFile || !logFile.exists()) continue
                addFileToZipWithTailLimit(
                    zipOut = zipOut,
                    entryName = "timber_logs/${logFile.name}",
                    file = logFile,
                    maxBytes = 1_000_000L,
                    budget = budget,
                    packageName = packageName,
                    sanitize = sanitize
                )
                if (budget.exhausted()) break
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

    fun collectTargets(
        context: Context,
        includeRecentHost: Boolean = false
    ): List<LogcatDumpTarget> {
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

        // A crashed HOST process is gone by the time the next launch uploads its
        // tombstone. The persisted pid registry is the only way to still ask logcat
        // for that process instead of losing its output entirely.
        LogProcessRegistry.loadRecentRecords(context).forEach { record ->
            val isUserRuntime =
                CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, record.processName)
            if (isUserRuntime || includeRecentHost) {
                targets += LogcatDumpTarget(
                    pid = record.pid,
                    processName = record.processName,
                    source = if (isUserRuntime) "recent-runtime" else "recent-host"
                )
            }
        }

        return normalizeTargets(packageName, targets, includeRecentHost)
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
        targets: List<LogcatDumpTarget>,
        includeRecentHost: Boolean = false
    ): List<LogcatDumpTarget> {
        val merged = linkedMapOf<Int, LogcatDumpTarget>()
        for (target in targets) {
            if (target.pid <= 0 || !isTinaProcess(packageName, target.processName)) continue
            val isUserRuntime = CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, target.processName)
            if (target.source.startsWith("recent") && !isUserRuntime && !includeRecentHost) continue
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

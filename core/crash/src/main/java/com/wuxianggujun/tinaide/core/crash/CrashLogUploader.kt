package com.wuxianggujun.tinaide.core.crash

import android.content.Context
import com.wuxianggujun.tinaide.core.logging.LogExportProfile
import com.wuxianggujun.tinaide.core.logging.LogExportUtils
import com.wuxianggujun.tinaide.core.logging.LogTextSanitizer
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.aegis.AegisHubClient
import com.wuxianggujun.tinaide.core.network.aegis.AegisHubEnvironment
import com.wuxianggujun.tinaide.core.network.aegis.AegisIdentity
import com.wuxianggujun.tinaide.core.util.CrashLogPrivacyClassifier
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.delay
import timber.log.Timber

object CrashLogUploader {
    private const val TAG = "CrashLogUploader"

    /** 崩溃日志最大字节数 - 避免 OOM 和网络传输过大 */
    private const val MAX_CRASH_TEXT_BYTES = 512 * 1024 // 512KB

    /** 文件稳定性检查延迟 - 等待文件写入完成 */
    private const val STABILITY_CHECK_DELAY_MS = 1_200L // 1.2 秒

    /** 用户运行容器识别只读 tombstone 头部，避免为隐私过滤读取完整日志。 */
    private const val USER_RUNTIME_SCAN_BYTES = 64 * 1024

    /**
     * 是否存在真正允许上传的 tombstone。
     *
     * 这里会复用上传器的隐私过滤逻辑：用户 SDL/Native/NDK 运行容器崩溃会被标记为 skipped，
     * 因而不会因为“本地有 tombstone 文件”而反复调度服务器上传任务。
     */
    fun hasPendingUploadableTombstone(context: Context): Boolean {
        if (!CrashUploadState.isAutoUploadEnabled(context)) return false
        return findPendingUploadableTombstones(context).isNotEmpty()
    }

    /**
     * 上传未上报 tombstone。
     *
     * 说明：
     * - 按时间顺序上传所有未处理 tombstone，避免较新的崩溃覆盖较早的未上传记录；
     * - 崩溃文件可能在短时间内仍在写入（mtime/size 变化），此时返回“需要重试”避免误判重复。
     *
     * @return 是否需要重试（典型：网络异常/5xx/429）
     */
    suspend fun uploadPending(context: Context): Boolean {
        if (!CrashUploadState.isAutoUploadEnabled(context)) {
            Timber.tag(TAG).d("Crash auto-upload is disabled")
            return false
        }

        val lockHandle = acquireUploadLock(context)
        if (lockHandle == null) {
            Timber.tag(TAG).w("Failed to acquire upload lock - another upload in progress")
            return true
        }

        lockHandle.use {
            return uploadPendingLocked(context)
        }
    }

    private suspend fun uploadPendingLocked(context: Context): Boolean {
        val pending = findPendingUploadableTombstones(context)

        if (pending.isEmpty()) {
            Timber.tag(TAG).d("No uploadable tombstone file found")
            return false
        }

        if (AegisHubEnvironment.config == null) {
            Timber.tag(TAG).e("AegisHub is not configured; cannot upload %d tombstone(s)", pending.size)
            return false
        }

        Timber.tag(TAG).i("Found %d pending tombstone file(s)", pending.size)

        // 同一批崩溃共用一份日志包，避免为每个墓碑重复打包同一批日志。
        val logBundle = prepareLogBundle(context)

        var shouldRetry = false
        try {
            pending.forEach { tombstone ->
                val needsRetry = uploadSingle(context, tombstone, logBundle)
                shouldRetry = shouldRetry || needsRetry
            }
        } finally {
            logBundle?.let { runCatching { it.delete() } }
        }

        return shouldRetry
    }

    /**
     * 打包 IDE 自身诊断日志（Timber、logcat、安装日志、墓碑等）。
     *
     * 只包含无需用户逐次授权的数据；构建日志、PRoot/rootfs 属于用户项目/运行时内容，
     * 需要用户手动反馈时才会上传。
     */
    private suspend fun prepareLogBundle(context: Context): File? {
        val bundle = runCatching {
            LogExportUtils.exportLogs(
                context = context,
                profile = LogExportProfile.CRASH_UPLOAD,
                maxInputBytes = LogExportUtils.UPLOAD_MAX_INPUT_BYTES,
            )
        }.onFailure { t ->
            Timber.tag(TAG).w(t, "Failed to export crash log bundle")
        }.getOrNull() ?: return null

        if (!bundle.isFile || bundle.length() <= 0L) {
            runCatching { bundle.delete() }
            return null
        }
        // Defensive: the input budget guarantees a compressed bundle below the limit,
        // but a future source that bypasses the budget must not break the attachment.
        if (bundle.length() > LogExportUtils.MAX_UPLOAD_ATTACHMENT_BYTES) {
            Timber.tag(TAG).w(
                "Crash log bundle still exceeds the attachment limit (%d bytes): %s",
                bundle.length(),
                bundle.name,
            )
            runCatching { bundle.delete() }
            return null
        }
        Timber.tag(TAG).i("Prepared crash log bundle: %s (%d bytes)", bundle.name, bundle.length())
        return bundle
    }

    private fun findPendingUploadableTombstones(context: Context): List<File> = NativeCrashHandler.getAllTombstones(context)
        .asSequence()
        .filter { it.exists() && it.isFile && it.canRead() && it.length() > 0L }
        .filterNot { CrashUploadState.isUploaded(context, it.name) }
        .filterNot { CrashUploadState.isUploadTerminal(context, it.name) }
        .filterNot { shouldSkipUpload(context, it) }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        .toList()

    private suspend fun uploadSingle(context: Context, latest: File, logBundle: File?): Boolean {
        if (!latest.exists() || !latest.canRead()) {
            Timber.tag(TAG).w("Tombstone file not accessible: %s", latest.absolutePath)
            CrashUploadState.markUploadFailed(context, latest.name, latest.lastModified(), "file_not_accessible")
            return false
        }

        if (latest.length() <= 0L) {
            Timber.tag(TAG).w("Tombstone file is empty: %s", latest.name)
            CrashUploadState.markUploadSkipped(context, latest.name, "blank_tombstone")
            return false
        }

        if (CrashUploadState.isUploaded(context, latest.name)) {
            Timber.tag(TAG).d("Tombstone already uploaded: %s", latest.name)
            return false
        }

        if (shouldSkipUpload(context, latest)) {
            Timber.tag(TAG).i("Tombstone upload skipped: %s", latest.name)
            return false
        }

        if (!latest.isStableForUpload()) {
            Timber.tag(TAG).i("Tombstone file still being written, will retry: %s", latest.name)
            CrashUploadState.markUploadDeferred(context, latest.name, latest.lastModified(), "file_not_stable")
            return true
        }

        CrashUploadState.markUploadAttemptStarted(context, latest.name, latest.lastModified())

        val rawCrashText = runCatching { readUtf8MaxBytes(latest, MAX_CRASH_TEXT_BYTES).trim() }
            .getOrElse { t ->
                val reason = uploadReason("read_failed", t)
                Timber.tag(TAG).w(
                    "Failed to read tombstone before upload: file=%s, error=%s",
                    latest.name,
                    t::class.java.simpleName,
                )
                CrashUploadState.markUploadDeferred(context, latest.name, latest.lastModified(), reason)
                return true
            }
        if (rawCrashText.isBlank()) {
            Timber.tag(TAG).w("Tombstone content is blank after reading: %s", latest.name)
            CrashUploadState.markUploadSkipped(context, latest.name, "blank_tombstone_content")
            return false
        }

        val crashText = LogTextSanitizer.sanitize(rawCrashText, context.packageName)
            // The server validates `text/plain` as strict UTF-8 without NUL bytes; a
            // truncated binary tail must not turn the whole attachment into a 400.
            .replace("\u0000", "")

        Timber.tag(TAG).i(
            "Uploading crash log: file=%s, size=%d bytes",
            latest.name,
            crashText.toByteArray(Charsets.UTF_8).size,
        )

        return uploadToAegisHub(context, latest, crashText, logBundle)
    }

    /**
     * 使用 AegisHub 上报崩溃。
     *
     * 报告提交成功后会补传两个附件：
     * 1. 脱敏后的墓碑文本（`text/plain`）；
     * 2. IDE 自身诊断日志包（`application/octet-stream`，含 Timber/logcat/安装日志等）。
     *
     * 报告与附件在服务端都按内容幂等：重试同一个 event_id / 同一份附件不会重复入库，
     * 因此这里可以安全地在附件失败时把整条崩溃标记为“待重试”。
     */
    private suspend fun uploadToAegisHub(
        context: Context,
        tombstone: File,
        crashText: String,
        logBundle: File?,
    ): Boolean {
        val config = AegisHubEnvironment.config ?: run {
            Timber.tag(TAG).e("AegisHub configuration disappeared during upload")
            CrashUploadState.markUploadDeferred(
                context = context,
                fileName = tombstone.name,
                mtime = tombstone.lastModified(),
                reason = "aegis_not_configured",
            )
            return true
        }
        val report = AegisCrashReportMapper.build(
            fileName = tombstone.name,
            crashLog = crashText,
            fileModifiedAtMillis = tombstone.lastModified(),
            packageName = context.packageName,
            release = config.release,
            buildId = config.buildId,
            environment = config.environment,
            userReference = AegisIdentity.installId(context),
        )

        val attachment = runCatching {
            File.createTempFile("tinaide-crash-", ".txt", context.cacheDir).apply {
                writeText(crashText, Charsets.UTF_8)
            }
        }.getOrElse { t ->
            Timber.tag(TAG).w(t, "Failed to stage sanitized crash attachment")
            null
        }

        try {
            val submitted = runCatching { AegisHubClient.submitReport(report) }
                .getOrElse { t ->
                    val reason = uploadReason("aegis_exception", t)
                    Timber.tag(TAG).e(t, "AegisHub crash report threw exception")
                    CrashUploadState.markUploadDeferred(
                        context = context,
                        fileName = tombstone.name,
                        mtime = tombstone.lastModified(),
                        reason = reason,
                    )
                    return true
                }

            return when (submitted) {
                is ApiResult.Success -> {
                    val stagedAttachments = buildList {
                        attachment?.let { add(it to "text/plain") }
                        logBundle
                            ?.takeIf { it.isFile && it.length() > 0L }
                            ?.let { add(it to "application/octet-stream") }
                    }

                    var retryableAttachment = false
                    var rejectedAttachment: Pair<String, Int>? = null
                    for ((file, contentType) in stagedAttachments) {
                        val result = runCatching {
                            AegisHubClient.uploadAttachment(
                                reportId = submitted.data.id,
                                file = file,
                                contentType = contentType,
                            )
                        }.getOrElse { t ->
                            Timber.tag(TAG).w(t, "AegisHub crash attachment threw exception: %s", file.name)
                            ApiResult.NetworkError(t.message ?: "attachment failed")
                        }

                        when (result) {
                            is ApiResult.Success -> Unit
                            is ApiResult.NetworkError -> retryableAttachment = true
                            is ApiResult.Error -> {
                                if (result.code == 429 || result.code >= 500) {
                                    retryableAttachment = true
                                } else {
                                    rejectedAttachment = file.name to result.code
                                }
                            }
                        }
                    }

                    if (retryableAttachment) {
                        Timber.tag(TAG).w(
                            "AegisHub accepted crash report id=%s but attachments failed; retry scheduled",
                            submitted.data.id,
                        )
                        CrashUploadState.markUploadDeferred(
                            context = context,
                            fileName = tombstone.name,
                            mtime = tombstone.lastModified(),
                            reason = "aegis_attachment_retry",
                        )
                        return true
                    }
                    rejectedAttachment?.let { (name, code) ->
                        Timber.tag(TAG).w(
                            "AegisHub rejected crash attachment %s (id=%s, code=%d); keeping report",
                            name,
                            submitted.data.id,
                            code,
                        )
                    }

                    CrashUploadState.markUploaded(context, tombstone.name, tombstone.lastModified())
                    // The report is durable on the server and the tombstone is already in
                    // memory, so keeping the file only risks re-uploading this crash later.
                    val deleted = runCatching { tombstone.delete() }.getOrDefault(false)
                    Timber.tag(TAG).i(
                        "Crash report uploaded to AegisHub: id=%s, file=%s, attachments=%d, tombstoneDeleted=%s",
                        submitted.data.id,
                        tombstone.name,
                        stagedAttachments.size,
                        deleted,
                    )
                    false
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).e("AegisHub crash report network error; retry scheduled")
                    CrashUploadState.markUploadDeferred(
                        context = context,
                        fileName = tombstone.name,
                        mtime = tombstone.lastModified(),
                        reason = "aegis_network_error",
                    )
                    true
                }
                is ApiResult.Error -> {
                    when {
                        submitted.code == 429 -> {
                            Timber.tag(TAG).w("AegisHub rate limited crash report (429); retry scheduled")
                            CrashUploadState.markUploadDeferred(
                                context = context,
                                fileName = tombstone.name,
                                mtime = tombstone.lastModified(),
                                reason = "aegis_rate_limited_429",
                            )
                            true
                        }
                        submitted.code >= 500 -> {
                            Timber.tag(TAG).e(
                                "AegisHub server error (code=%d); retry scheduled",
                                submitted.code,
                            )
                            CrashUploadState.markUploadDeferred(
                                context = context,
                                fileName = tombstone.name,
                                mtime = tombstone.lastModified(),
                                reason = "aegis_server_error_${submitted.code}",
                            )
                            true
                        }
                        else -> {
                            Timber.tag(TAG).e(
                                "AegisHub rejected crash report permanently (code=%d): %s",
                                submitted.code,
                                submitted.message,
                            )
                            CrashUploadState.markUploadFailed(
                                context = context,
                                fileName = tombstone.name,
                                mtime = tombstone.lastModified(),
                                reason = "aegis_client_error_${submitted.code}",
                            )
                            false
                        }
                    }
                }
            }
        } finally {
            attachment?.delete()
        }
    }

    private fun shouldSkipUpload(context: Context, tombstone: File): Boolean {
        if (CrashUploadState.isUploadSkipped(context, tombstone.name)) {
            return true
        }

        val header = runCatching { readUtf8MaxBytes(tombstone, USER_RUNTIME_SCAN_BYTES) }
            .getOrDefault("")
        val packageName = context.packageName
        val isUserRuntimeCrash = CrashLogPrivacyClassifier.isUserRuntimeCrash(packageName, header)

        if (isUserRuntimeCrash) {
            CrashUploadState.markUploadSkipped(context, tombstone.name, "user_runtime_crash")
        }
        return isUserRuntimeCrash
    }

    private fun acquireUploadLock(context: Context): AutoCloseable? {
        val lockFile = File(context.filesDir, "crash_upload.lock")
        return try {
            val raf = RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                raf.close()
                null
            } else {
                AutoCloseable {
                    runCatching { lock.release() }
                    runCatching { channel.close() }
                    runCatching { raf.close() }
                }
            }
        } catch (_: OverlappingFileLockException) {
            null
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "acquireUploadLock failed")
            null
        }
    }

    private suspend fun File.isStableForUpload(): Boolean {
        val beforeSize = length()
        val beforeMtime = lastModified()
        delay(STABILITY_CHECK_DELAY_MS)
        val afterSize = length()
        val afterMtime = lastModified()
        return beforeSize == afterSize && beforeMtime == afterMtime && afterSize > 0L
    }

    private fun readUtf8MaxBytes(file: File, maxBytes: Int): String {
        val cap = maxBytes.coerceAtLeast(1)
        val buffer = ByteArray(cap)
        val read = FileInputStream(file).use { it.read(buffer) }.coerceAtLeast(0)
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun uploadReason(prefix: String, throwable: Throwable): String {
        return "$prefix:${throwable::class.java.simpleName}"
    }
}

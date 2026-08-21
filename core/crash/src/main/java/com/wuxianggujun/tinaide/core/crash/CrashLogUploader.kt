package com.wuxianggujun.tinaide.core.crash

import android.content.Context
import com.wuxianggujun.tinaide.core.device.DeviceFingerprint
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
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

        Timber.tag(TAG).i("Found %d pending tombstone file(s)", pending.size)

        var shouldRetry = false
        pending.forEach { tombstone ->
            val needsRetry = uploadSingle(context, tombstone)
            shouldRetry = shouldRetry || needsRetry
        }

        return shouldRetry
    }

    private fun findPendingUploadableTombstones(context: Context): List<File> = NativeCrashHandler.getAllTombstones(context)
        .asSequence()
        .filter { it.exists() && it.isFile && it.canRead() && it.length() > 0L }
        .filterNot { CrashUploadState.isUploaded(context, it.name) }
        .filterNot { shouldSkipUpload(context, it) }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        .toList()

    private suspend fun uploadSingle(context: Context, latest: File): Boolean {
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

        val serverConfig = TinaServerConfig.getInstance(context)
        val api = serverConfig.getApi()
        val deviceInfo = serverConfig.getDeviceInfo()
        val fingerprint = DeviceFingerprint.get(context)

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

        val crashText = CrashLogUploadSanitizer.sanitize(rawCrashText, context.packageName)

        Timber.tag(TAG).i(
            "Uploading crash log: file=%s, size=%d bytes",
            latest.name,
            crashText.toByteArray(Charsets.UTF_8).size,
        )

        val res = runCatching {
            api.uploadLog(
                logType = "crash",
                deviceFingerprint = fingerprint,
                deviceInfo = deviceInfo,
                title = Strings.crash_log_title.strOr(context),
                content = crashText,
                file = null,
                extraFields = emptyMap()
            )
        }.getOrElse { t ->
            val reason = uploadReason("exception", t)
            Timber.tag(TAG).e("Crash log upload threw exception: %s", t::class.java.simpleName)
            CrashUploadState.markUploadDeferred(context, latest.name, latest.lastModified(), reason)
            return true
        }

        return when (res) {
            is ApiResult.Success -> {
                CrashUploadState.markUploaded(context, latest.name, latest.lastModified())
                Timber.tag(TAG).i("Crash log uploaded successfully: id=%s, file=%s", res.data.id, latest.name)
                false
            }
            is ApiResult.NetworkError -> {
                Timber.tag(TAG).e("Crash log upload network error; retry scheduled")
                CrashUploadState.markUploadDeferred(
                    context = context,
                    fileName = latest.name,
                    mtime = latest.lastModified(),
                    reason = "network_error"
                )
                true
            }
            is ApiResult.Error -> {
                when {
                    res.code == 429 -> {
                        Timber.tag(TAG).w("Crash log upload rate limited (429), will retry with backoff")
                        CrashUploadState.markUploadDeferred(
                            context = context,
                            fileName = latest.name,
                            mtime = latest.lastModified(),
                            reason = "rate_limited_429"
                        )
                        true
                    }
                    res.code >= 500 -> {
                        Timber.tag(TAG).e("Crash log upload server error (code=%d); retry scheduled", res.code)
                        CrashUploadState.markUploadDeferred(
                            context = context,
                            fileName = latest.name,
                            mtime = latest.lastModified(),
                            reason = "server_error_${res.code}"
                        )
                        true
                    }
                    else -> {
                        Timber.tag(TAG).e("Crash log upload failed permanently (code=%d)", res.code)
                        CrashUploadState.markUploadFailed(
                            context = context,
                            fileName = latest.name,
                            mtime = latest.lastModified(),
                            reason = "client_error_${res.code}"
                        )
                        false
                    }
                }
            }
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

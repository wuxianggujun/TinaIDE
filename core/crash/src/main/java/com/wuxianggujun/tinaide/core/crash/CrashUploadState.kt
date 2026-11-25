package com.wuxianggujun.tinaide.core.crash

import android.content.Context

/**
 * 崩溃日志上报状态（同步落盘）。
 *
 * 背景：常规配置写入使用 apply() 异步提交，在崩溃/进程快速退出时可能来不及落盘，
 * 从而导致“同一个 tombstone 被重复上报”。这里对关键去重字段使用 commit() 强制落盘。
 */
object CrashUploadState {
    private const val PREFS_NAME = "tinaide_config"
    private const val UNKNOWN_FILE_NAME = "<unknown>"

    private const val KEY_AUTO_UPLOAD_ENABLED = "logs.crash.auto_upload"
    private const val KEY_LAST_UPLOADED_NAME = "logs.crash.last_uploaded.name"
    private const val KEY_LAST_UPLOADED_MTIME = "logs.crash.last_uploaded.mtime"
    private const val KEY_UPLOADED_NAMES = "logs.crash.uploaded.names"
    private const val KEY_UPLOAD_SKIPPED_NAMES = "logs.crash.upload_skipped.names"
    private const val KEY_FAILED_NAMES = "logs.crash.failed.names"
    private const val KEY_LAST_STATUS = "logs.crash.last_status"
    private const val KEY_LAST_STATUS_NAME = "logs.crash.last_status.name"
    private const val KEY_LAST_STATUS_MTIME = "logs.crash.last_status.mtime"
    private const val KEY_LAST_STATUS_AT = "logs.crash.last_status.at"
    private const val KEY_LAST_STATUS_REASON = "logs.crash.last_status.reason"
    private const val KEY_LAST_ATTEMPT_COUNT = "logs.crash.last_attempt.count"

    /**
     * Tombstones are deleted after a successful upload, so these bookkeeping sets only
     * need to remember recent names (fallback for a failed delete). The hard cap keeps
     * the preferences file from growing forever on devices that crash a lot.
     */
    private const val MAX_TRACKED_NAMES = 48

    enum class Status {
        NONE,
        QUEUED,
        UPLOADING,
        UPLOADED,
        SKIPPED,
        RETRY_PENDING,
        FAILED,
    }

    data class Snapshot(
        val status: Status,
        val fileName: String,
        val fileMtime: Long,
        val updatedAt: Long,
        val reason: String,
        val attemptCount: Int,
    )

    fun isAutoUploadEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_UPLOAD_ENABLED, true)

    fun getLastUploadedName(context: Context): String = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LAST_UPLOADED_NAME, "")
        .orEmpty()

    fun getLastUploadedMtime(context: Context): Long = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_UPLOADED_MTIME, 0L)

    fun getLastUploadSnapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawStatus = prefs.getString(KEY_LAST_STATUS, Status.NONE.name).orEmpty()
        val status = runCatching { Status.valueOf(rawStatus) }.getOrDefault(Status.NONE)
        return Snapshot(
            status = status,
            fileName = prefs.getString(KEY_LAST_STATUS_NAME, "").orEmpty(),
            fileMtime = prefs.getLong(KEY_LAST_STATUS_MTIME, 0L),
            updatedAt = prefs.getLong(KEY_LAST_STATUS_AT, 0L),
            reason = prefs.getString(KEY_LAST_STATUS_REASON, "").orEmpty(),
            attemptCount = prefs.getInt(KEY_LAST_ATTEMPT_COUNT, 0),
        )
    }

    fun isUploaded(context: Context, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        return fileName == getLastUploadedName(context) ||
            getUploadedNames(context).contains(fileName)
    }

    fun isUploadSkipped(context: Context, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        return getUploadSkippedNames(context).contains(fileName)
    }

    fun isUploadFailed(context: Context, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        return getFailedNames(context).contains(fileName)
    }

    /**
     * A tombstone that must never be retried: skipped by privacy policy, or rejected by
     * the server with a non-retryable status. Retrying these on every launch would burn
     * battery and, for attachment/count quota errors, keep failing forever.
     */
    fun isUploadTerminal(context: Context, fileName: String): Boolean =
        isUploadSkipped(context, fileName) || isUploadFailed(context, fileName)

    fun markUploadQueued(context: Context, fileName: String, reason: String = "queued"): Boolean = updateLastStatus(
        context = context,
        status = Status.QUEUED,
        fileName = fileName,
        mtime = 0L,
        reason = reason,
        incrementAttempt = false,
    )

    fun markUploadAttemptStarted(context: Context, fileName: String, mtime: Long): Boolean {
        if (fileName.isBlank()) return false
        return updateLastStatus(
            context = context,
            status = Status.UPLOADING,
            fileName = fileName,
            mtime = mtime,
            reason = "upload_started",
            incrementAttempt = true,
        )
    }

    fun markUploadDeferred(context: Context, fileName: String, mtime: Long, reason: String): Boolean {
        if (fileName.isBlank()) return false
        return updateLastStatus(
            context = context,
            status = Status.RETRY_PENDING,
            fileName = fileName,
            mtime = mtime,
            reason = reason,
            incrementAttempt = false,
        )
    }

    fun markUploadFailed(context: Context, fileName: String, mtime: Long, reason: String): Boolean {
        if (fileName.isBlank()) return false
        val failedNames = trackName(getFailedNames(context), fileName)
        return updateLastStatus(
            context = context,
            status = Status.FAILED,
            fileName = fileName,
            mtime = mtime,
            reason = reason,
            incrementAttempt = false,
        ) && context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAILED_NAMES, failedNames)
            .commit()
    }

    fun markUploadSkipped(context: Context, fileName: String, reason: String = "skipped_by_policy"): Boolean {
        if (fileName.isBlank()) return false
        val skippedNames = trackName(getUploadSkippedNames(context), fileName)
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_UPLOAD_SKIPPED_NAMES, skippedNames)
            .putLastStatus(
                status = Status.SKIPPED,
                fileName = fileName,
                mtime = 0L,
                reason = reason,
                attemptCount = currentAttemptCount(context, fileName),
            )
            .commit()
    }

    /**
     * 标记某个 tombstone 已完成上报（commit 同步落盘）。
     */
    fun markUploaded(context: Context, fileName: String, mtime: Long): Boolean {
        if (fileName.isBlank()) return false
        val uploadedNames = trackName(getUploadedNames(context), fileName)
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPLOADED_NAME, fileName)
            .putLong(KEY_LAST_UPLOADED_MTIME, mtime)
            .putStringSet(KEY_UPLOADED_NAMES, uploadedNames)
            .putLastStatus(
                status = Status.UPLOADED,
                fileName = fileName,
                mtime = mtime,
                reason = "upload_success",
                attemptCount = currentAttemptCount(context, fileName),
            )
            .commit()
    }

    private fun updateLastStatus(
        context: Context,
        status: Status,
        fileName: String,
        mtime: Long,
        reason: String,
        incrementAttempt: Boolean,
    ): Boolean {
        val normalizedName = normalizeFileName(fileName)
        val attemptCount = currentAttemptCount(context, normalizedName) + if (incrementAttempt) 1 else 0
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLastStatus(
                status = status,
                fileName = normalizedName,
                mtime = mtime,
                reason = reason,
                attemptCount = attemptCount,
            )
            .commit()
    }

    private fun android.content.SharedPreferences.Editor.putLastStatus(
        status: Status,
        fileName: String,
        mtime: Long,
        reason: String,
        attemptCount: Int,
    ): android.content.SharedPreferences.Editor = putString(KEY_LAST_STATUS, status.name)
        .putString(KEY_LAST_STATUS_NAME, normalizeFileName(fileName))
        .putLong(KEY_LAST_STATUS_MTIME, mtime)
        .putLong(KEY_LAST_STATUS_AT, System.currentTimeMillis())
        .putString(KEY_LAST_STATUS_REASON, reason.take(240))
        .putInt(KEY_LAST_ATTEMPT_COUNT, attemptCount.coerceAtLeast(0))

    private fun currentAttemptCount(context: Context, fileName: String): Int {
        val normalizedName = normalizeFileName(fileName)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastName = prefs.getString(KEY_LAST_STATUS_NAME, "").orEmpty()
        return if (lastName == normalizedName) {
            prefs.getInt(KEY_LAST_ATTEMPT_COUNT, 0)
        } else {
            0
        }
    }

    private fun getUploadedNames(context: Context): Set<String> = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_UPLOADED_NAMES, emptySet())
        .orEmpty()

    private fun getUploadSkippedNames(context: Context): Set<String> = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_UPLOAD_SKIPPED_NAMES, emptySet())
        .orEmpty()

    private fun getFailedNames(context: Context): Set<String> = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_FAILED_NAMES, emptySet())
        .orEmpty()

    private fun trackName(current: Set<String>, fileName: String): Set<String> {
        if (current.contains(fileName) && current.size <= MAX_TRACKED_NAMES) return current
        val others = current.filterNot { it == fileName }
        val capacity = (MAX_TRACKED_NAMES - 1).coerceAtLeast(0)
        return (others.takeLast(capacity) + fileName).toSet()
    }

    private fun normalizeFileName(fileName: String): String = fileName.trim().ifBlank { UNKNOWN_FILE_NAME }
}

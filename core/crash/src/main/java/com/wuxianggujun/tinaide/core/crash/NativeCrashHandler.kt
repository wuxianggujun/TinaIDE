package com.wuxianggujun.tinaide.core.crash

import android.content.Context
import java.io.File
import timber.log.Timber
import xcrash.ICrashCallback
import xcrash.TombstoneManager
import xcrash.XCrash

/**
 * Native 崩溃捕获处理器
 *
 * 基于 xCrash 实现，支持捕获：
 * - Java 异常（作为 CrashHandler 的补充）
 * - Native 崩溃（SIGSEGV、SIGABRT 等信号）
 * - ANR（应用无响应）
 *
 * 崩溃日志保存在：/data/data/包名/files/tombstones/
 */
object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"

    @Volatile
    private var appContext: Context? = null

    fun interface CrashDisplayer {
        fun show(context: Context, crashReport: String, crashLogFileName: String)
    }

    @Volatile
    private var crashDisplayer: CrashDisplayer? = null

    @Volatile
    private var crashUploadEnabled: Boolean = true

    fun setCrashDisplayer(displayer: CrashDisplayer?) {
        crashDisplayer = displayer
    }

    fun setCrashUploadEnabled(enabled: Boolean) {
        crashUploadEnabled = enabled
    }

    /**
     * 初始化 xCrash
     *
     * 必须在 Application.attachBaseContext() 中尽早调用
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        Timber.tag(TAG).i("Installing xCrash...")

        // 崩溃回调：当崩溃发生时跳转到 CrashActivity
        val callback = ICrashCallback { logPath, emergency ->
            Timber.tag(TAG).e(
                "Crash captured: file=%s, hasEmergency=%s",
                crashLogFileName(logPath).ifBlank { "<unknown>" },
                !emergency.isNullOrBlank(),
            )
            handleCrash(context, logPath, emergency)
        }

        // 配置 xCrash 初始化参数
        val params = XCrash.InitParameters()
            // 设置崩溃日志目录
            .setLogDir(getTombstoneDir(context).absolutePath)
            // Java 崩溃配置
            .setJavaRethrow(false) // 不重新抛出，由 xCrash 统一处理
            .setJavaLogCountMax(10)
            .setJavaDumpAllThreadsWhiteList(arrayOf("^main$", "^Binder:.*", "^.*coroutine.*"))
            .setJavaCallback(callback)
            // Native 崩溃配置
            .setNativeRethrow(false)
            .setNativeLogCountMax(10)
            .setNativeDumpAllThreadsWhiteList(arrayOf("^xcrash\\.sample$", "^Signal Catcher$", "^.*coroutine.*"))
            .setNativeCallback(callback)
            // ANR 配置
            .setAnrRethrow(false)
            .setAnrLogCountMax(10)
            .setAnrCallback(callback)

        // 初始化 xCrash
        val result = XCrash.init(context, params)
        if (result == 0) {
            Timber.tag(TAG).i("xCrash initialized successfully")
        } else {
            Timber.tag(TAG).e("xCrash initialization failed with code: $result")
        }
    }

    /**
     * 处理崩溃
     */
    private fun handleCrash(context: Context, logPath: String?, emergency: String?) {
        try {
            val crashInfo = buildCrashInfo(logPath, emergency)
            val crashLogFileName = crashLogFileName(logPath)

            val appContext = context.applicationContext
            if (crashUploadEnabled && CrashUploadState.isAutoUploadEnabled(appContext)) {
                markCrashLogUploadQueued(context, crashLogFileName)
                // 入队后台上传任务：崩溃回调可能早于 tombstone 完全落盘，直接入队避免扫描竞态。
                runCatching {
                    CrashLogUploadScheduler.schedule(appContext)
                }.onFailure { t ->
                    Timber.tag(TAG).w(t, "Failed to schedule crash log upload job")
                }
            } else {
                val reason = if (crashUploadEnabled) "auto_upload_disabled" else "process_not_uploadable"
                markCrashLogUploadSkipped(context, crashLogFileName, reason)
            }

            val displayer = crashDisplayer
            if (displayer != null) {
                displayer.show(context.applicationContext, crashInfo, crashLogFileName)
            } else {
                Timber.tag(TAG).e(
                    "Crash captured with no displayer registered: file=%s",
                    crashLogFileName.ifBlank { "<unknown>" },
                )
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to handle crash")
        }
    }

    private fun markCrashLogUploadQueued(context: Context, fileName: String) {
        CrashUploadState.markUploadQueued(context.applicationContext, fileName, "captured_by_xcrash")
        Timber.tag(TAG).i("Crash log upload queued: %s", fileName.ifBlank { "<unknown>" })
    }

    private fun markCrashLogUploadSkipped(context: Context, fileName: String, reason: String) {
        if (fileName.isNotBlank()) {
            CrashUploadState.markUploadSkipped(context.applicationContext, fileName, reason)
        }
        Timber.tag(TAG).i(
            "Crash log upload skipped for this process: %s, reason=%s",
            fileName.ifBlank { "<unknown>" },
            reason
        )
    }

    private fun crashLogFileName(logPath: String?): String = logPath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it).name }
        .orEmpty()

    /**
     * 构建崩溃信息
     *
     * 直接使用 xCrash 生成的完整日志，无需额外解析
     * xCrash 日志已包含所有必要信息：设备信息、应用信息、崩溃时间、堆栈等
     */
    private fun buildCrashInfo(logPath: String?, emergency: String?): String {
        // 优先显示 xCrash 生成的完整日志（避免重复展示 "崩溃类型 / Java 堆栈"等片段）
        if (!logPath.isNullOrBlank()) {
            val logFile = File(logPath)
            if (logFile.exists() && logFile.canRead()) {
                return buildString {
                    appendLine("===== Full Log =====")
                    append(logFile.readText())
                }
            }
            return buildString {
                appendLine("===== Log File Unreadable =====")
                appendLine("Path: $logPath")
                appendLine("Exists: ${logFile.exists()}")
                appendLine("Readable: ${logFile.canRead()}")
            }
        }

        // 无完整日志时再降级显示紧急信息
        if (!emergency.isNullOrBlank()) {
            return buildString {
                appendLine("===== Emergency Crash Info =====")
                append(emergency)
            }
        }

        return buildString {
            appendLine("===== No Crash Log =====")
            append("Crash happened but no log file was generated")
        }
    }

    /**
     * 获取 tombstone 目录
     */
    fun getTombstoneDir(context: Context): File = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
        com.wuxianggujun.tinaide.storage.ProjectPaths.getTombstonesRoot(context)
    )

    /**
     * 获取所有崩溃日志文件
     */
    fun getAllTombstones(context: Context? = appContext): Array<File> {
        val managed = TombstoneManager.getAllTombstones()?.asList().orEmpty()
        val diskFiles = context
            ?.let { getTombstoneDir(it) }
            ?.listFiles { file -> file.isFile }
            ?.asList()
            .orEmpty()

        return (managed + diskFiles)
            .filter { it.isFile }
            .distinctBy { it.absolutePath }
            .toTypedArray()
    }

    /**
     * 清理旧的崩溃日志
     *
     * @param keepCount 保留的日志数量
     */
    fun cleanupOldTombstones(keepCount: Int = 10) {
        try {
            val tombstones = getAllTombstones()
            if (tombstones.size <= keepCount) return

            val uploadContext = appContext?.takeIf { CrashUploadState.isAutoUploadEnabled(it) }
            val keepRecentPaths = tombstones
                .sortedByDescending { it.lastModified() }
                .take(keepCount.coerceAtLeast(0))
                .map { it.absolutePath }
                .toSet()
            val pendingUploadPaths = uploadContext?.let { context ->
                tombstones
                    .filter { file ->
                        file.exists() &&
                            file.isFile &&
                            file.canRead() &&
                            file.length() > 0L &&
                            !CrashUploadState.isUploaded(context, file.name) &&
                            !CrashUploadState.isUploadSkipped(context, file.name)
                    }
                    .map { it.absolutePath }
                    .toSet()
            }.orEmpty()

            val toDelete = tombstones
                .filterNot { it.absolutePath in keepRecentPaths }
                .filterNot { it.absolutePath in pendingUploadPaths }

            toDelete.forEach { file ->
                if (file.delete()) {
                    Timber.tag(TAG).d("Deleted old tombstone: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to cleanup tombstones")
        }
    }

    /**
     * 检查是否有未处理的崩溃日志
     */
    fun hasUnhandledCrash(context: Context? = appContext): Boolean = getAllTombstones(context).isNotEmpty()

    /**
     * 获取最新的崩溃日志
     */
    fun getLatestTombstone(context: Context? = appContext): File? = getAllTombstones(context)
        .filter { it.exists() && it.isFile && it.canRead() && it.length() > 0L }
        .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
}

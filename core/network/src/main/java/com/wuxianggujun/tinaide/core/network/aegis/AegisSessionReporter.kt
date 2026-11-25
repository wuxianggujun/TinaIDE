package com.wuxianggujun.tinaide.core.network.aegis

import android.content.Context
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

/**
 * IDE 启动统计上报。
 *
 * 每次主进程启动向 AegisHub 追加一条 `session` 报告，用于统计 IDE 被打开的次数、
 * 独立安装数以及按版本/日期的分布。启动事件先同步写入有界待发队列，
 * 网络失败时留待下次启动重试；最多保留最近 20 次，超出的旧事件会被淘汰。
 *
 * `user_reference` 使用 [AegisIdentity] 的安装 ID，和崩溃/反馈共用同一身份。
 */
object AegisSessionReporter {
    private const val TAG = "AegisSessionReporter"
    private const val PREFS_NAME = "aegis_session"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_PENDING = "pending_launches"

    /** Bounds prefs growth when the device is offline for a long time. */
    private const val MAX_PENDING = 20

    private const val TYPE_KEY = "session"

    private val json = JsonSerializer.default

    data class Launch(
        val installId: String,
        val launchCount: Long,
        val firstLaunch: Boolean,
    )

    /**
     * 记录并上报一次启动。
     *
     * 只有主进程代表“用户打开了 IDE”：`:host` 等子进程随用户程序启动，
     * 若也计入会把启动次数放大成进程启动次数。
     *
     * @return 最近一次投递结果；未配置 AegisHub 或本地记录失败时返回 null。
     */
    suspend fun reportLaunch(context: Context): ApiResult<AegisReportReceipt>? =
        withContext(Dispatchers.IO) {
            val config = AegisHubEnvironment.config ?: return@withContext null
            if (!isMainProcess(context)) return@withContext null
            val launch = recordLaunch(context) ?: return@withContext null
            val report = buildReport(config, launch)
            enqueue(context, report)
            drain(context)
        }

    private fun isMainProcess(context: Context): Boolean {
        val processName = android.app.Application.getProcessName()
            ?: return false
        return processName == context.packageName
    }

    /**
     * 累加本机启动次数。
     *
     * 使用 `commit()` 而不是 `apply()`，保证次数在进程被杀前已经落盘。
     */
    internal fun recordLaunch(context: Context): Launch? = runCatching {
        val prefs = prefs(context)
        val installId = AegisIdentity.installId(context)
        val previous = prefs.getLong(KEY_LAUNCH_COUNT, 0L)
        val count = previous + 1
        val committed = prefs.edit()
            .putLong(KEY_LAUNCH_COUNT, count)
            .commit()
        if (!committed) {
            Timber.tag(TAG).w("Failed to persist launch counter")
            return null
        }
        Launch(
            installId = installId,
            launchCount = count,
            firstLaunch = previous == 0L,
        )
    }.onFailure { t ->
        Timber.tag(TAG).w(t, "Failed to record launch")
    }.getOrNull()

    private fun buildReport(
        config: AegisHubEnvironment.Config,
        launch: Launch,
    ): AegisIngestReport {
        val attributes = buildMap<String, JsonPrimitive> {
            put("event", JsonPrimitive("app_launch"))
            put("app_version", JsonPrimitive(AegisFeedback.truncateUtf8Bytes(config.release, 64)))
            put("abi", JsonPrimitive(AegisFeedback.truncateUtf8Bytes(currentAbi(), 32)))
            put("install_id", JsonPrimitive(AegisFeedback.truncateUtf8Bytes(launch.installId, 64)))
            put("launch_count", JsonPrimitive(launch.launchCount))
            put("first_launch", JsonPrimitive(launch.firstLaunch))
        }
        return AegisIngestReport(
            eventId = UUID.randomUUID().toString(),
            typeKey = TYPE_KEY,
            schemaVersion = 1,
            release = config.release,
            buildId = config.buildId,
            environment = config.environment,
            occurredAt = Instant.now().toString(),
            userReference = launch.installId,
            message = "App launched",
            attributes = JsonObject(attributes),
            exception = null,
            breadcrumbs = emptyList(),
        )
    }

    private fun enqueue(context: Context, report: AegisIngestReport) {
        val prefs = prefs(context)
        val pending = readPending(prefs) + json.encodeToJsonElement(report).jsonObject
        val compacted = pending.takeLast(MAX_PENDING)
        if (!writePending(prefs, compacted)) {
            Timber.tag(TAG).w("Failed to persist pending launch report")
        }
    }

    private suspend fun drain(context: Context): ApiResult<AegisReportReceipt>? {
        val prefs = prefs(context)
        val pending = readPending(prefs)
        if (pending.isEmpty()) return null
        var sent = 0
        var last: ApiResult<AegisReportReceipt>? = null
        while (sent < pending.size) {
            val report = runCatching {
                json.decodeFromJsonElement<AegisIngestReport>(pending[sent])
            }.getOrNull()
            if (report == null) {
                // Corrupt/older entry: drop it instead of blocking the queue forever.
                sent++
                continue
            }
            when (val result = AegisHubClient.submitReport(report)) {
                is ApiResult.Success -> {
                    Timber.tag(TAG).i(
                        "Launch reported: id=%s, queued=%d",
                        result.data.id,
                        pending.size - sent,
                    )
                    sent++
                    last = result
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w("Launch report network error: %s", result.message)
                    last = result
                    break
                }
                is ApiResult.Error -> {
                    last = result
                    if (result.code == 429 || result.code >= 500) {
                        Timber.tag(TAG).w(
                            "Launch report deferred (code=%d): %s",
                            result.code,
                            result.message,
                        )
                        break
                    }
                    // Permanently rejected (for example a schema mismatch): keep the
                    // launch counter, drop the payload so it cannot block the queue.
                    Timber.tag(TAG).w(
                        "Launch report rejected (code=%d): %s",
                        result.code,
                        result.message,
                    )
                    sent++
                }
            }
        }
        if (sent > 0) {
            writePending(prefs, pending.drop(sent))
        }
        return last
    }

    /** 清空本地启动计数，仅用于测试。 */
    internal fun resetForTesting(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readPending(prefs: android.content.SharedPreferences): List<JsonObject> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrElse {
            Timber.tag(TAG).w(it, "Discarding unreadable launch queue")
            emptyList()
        }
    }

    private fun writePending(
        prefs: android.content.SharedPreferences,
        entries: List<JsonObject>,
    ): Boolean = prefs.edit()
        .putString(KEY_PENDING, JsonArray(entries).toString())
        .commit()

    private fun currentAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }

}

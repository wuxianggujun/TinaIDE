package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.logging.LogTextSanitizer
import java.io.File
import org.json.JSONObject

/** App-private evidence remains accessible through run-as on scoped-storage devices. */
internal fun regressionEvidenceDirectory(context: Context): File =
    File(context.filesDir, "test-regression-evidence").apply { check(isDirectory || mkdirs()) }

/** Release evidence does not require a debuggable APK, adb root, or an exported provider. */
internal fun emitRegressionReport(report: JSONObject) {
    InstrumentationRegistry.getInstrumentation().sendStatus(
        2,
        Bundle().apply { putString("tina_regression_report", report.toString()) },
    )
}

/** Persist a bounded diagnostic trail before a timeout can interrupt test cleanup. */
internal class RegressionStageTracker(context: Context, name: String, private val report: JSONObject) {
    private val packageName = context.packageName
    private val directory = regressionEvidenceDirectory(context)
    private val evidence = AtomicFile(File(directory, "$name.json"))
    private val logFile = File(directory, "$name.log")
    private val started = SystemClock.elapsedRealtime()
    private val output = StringBuilder()
    private var lastCheckpoint = started

    init {
        require(name.matches(Regex("[a-z0-9-]+"))) { "Evidence name must stay inside the regression directory" }
        logFile.writeText("", Charsets.UTF_8)
        checkpoint("setup")
    }

    @Synchronized
    fun checkpoint(stage: String) {
        val now = SystemClock.elapsedRealtime()
        report.put("stage", LogTextSanitizer.sanitize(stage, packageName)).put("elapsedMs", now - started)
            .put("logTail", output.takeLast(MaximumInlineLogCharacters))
        persist()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("tina_regression_progress", report.toString()) },
        )
        lastCheckpoint = now
    }

    @Synchronized
    fun appendLog(line: String) {
        val remaining = MaximumLogCharacters - output.length
        if (remaining > 0) {
            val text = (LogTextSanitizer.sanitize(line, packageName) + "\n").take(remaining)
            output.append(text)
            logFile.appendText(text, Charsets.UTF_8)
        }
        if (SystemClock.elapsedRealtime() - lastCheckpoint >= CheckpointIntervalMs) {
            checkpoint(report.optString("stage", "setup"))
        }
    }

    @Synchronized
    fun finish() {
        report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("logTail", output.takeLast(MaximumInlineLogCharacters))
        persist()
        emitRegressionReport(report)
    }

    private fun persist() {
        val stream = evidence.startWrite()
        try {
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            evidence.finishWrite(stream)
        } catch (error: Throwable) {
            evidence.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MaximumLogCharacters = 128 * 1024
        const val MaximumInlineLogCharacters = 1_000
        const val CheckpointIntervalMs = 1_000L
    }
}

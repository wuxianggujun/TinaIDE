package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
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

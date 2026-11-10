package com.wuxianggujun.tinaide.ui

import android.content.Context
import java.io.File

/** App-private evidence remains accessible through run-as on scoped-storage devices. */
internal fun regressionEvidenceDirectory(context: Context): File =
    File(context.filesDir, "test-regression-evidence").apply { check(isDirectory || mkdirs()) }

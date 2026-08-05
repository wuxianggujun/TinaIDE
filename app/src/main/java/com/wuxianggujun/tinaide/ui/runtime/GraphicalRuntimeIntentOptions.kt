package com.wuxianggujun.tinaide.ui.runtime

import android.content.Intent

internal object GraphicalRuntimeIntentOptions {
    private const val EXTRA_ENABLE_FLOATING_LOG = "extra_graphical_enable_floating_log"

    fun putIntoIntent(intent: Intent, enableFloatingLog: Boolean) {
        intent.putExtra(EXTRA_ENABLE_FLOATING_LOG, enableFloatingLog)
    }

    fun readFloatingLogEnabled(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_ENABLE_FLOATING_LOG, false)
}

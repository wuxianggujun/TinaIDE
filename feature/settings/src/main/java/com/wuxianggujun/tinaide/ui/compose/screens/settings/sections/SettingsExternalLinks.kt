package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun Context.openFirstAvailableExternalUrl(urls: List<String>): Boolean {
    urls.forEach { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (runCatching { startActivity(intent) }.isSuccess) {
            return true
        }
    }
    return false
}

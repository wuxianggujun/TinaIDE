package com.wuxianggujun.tinaide.core.config

import android.content.SharedPreferences

/** Owns the two widths and their legacy migration without depending on a global Context. */
internal class EditorIndentationPreferences(private val preferences: SharedPreferences) {
    val tabSize: Int
        get() = readWidth(TAB_SIZE_KEY)?.coerceIn(2, MAX_WIDTH) ?: DEFAULT_WIDTH

    val indentSize: Int
        get() = readWidth(INDENT_SIZE_KEY)?.coerceIn(1, MAX_WIDTH) ?: tabSize

    private fun readWidth(key: String): Int? = preferences.all[key]?.toString()?.toIntOrNull()

    fun setTabSize(tabSize: Int) {
        val legacyIndentSize = indentSize
        preferences.edit().apply {
            if (!preferences.contains(INDENT_SIZE_KEY)) {
                putString(INDENT_SIZE_KEY, legacyIndentSize.toString())
            }
            putString(TAB_SIZE_KEY, tabSize.coerceIn(2, MAX_WIDTH).toString())
        }.apply()
    }

    fun setIndentSize(indentSize: Int) {
        preferences.edit().putString(INDENT_SIZE_KEY, indentSize.coerceIn(1, MAX_WIDTH).toString()).apply()
    }

    private companion object {
        const val TAB_SIZE_KEY = "editor_tab_size"
        const val INDENT_SIZE_KEY = "editor_indent_size"
        const val DEFAULT_WIDTH = 4
        const val MAX_WIDTH = 8
    }
}

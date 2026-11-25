package com.wuxianggujun.tinaide.core.format

import com.wuxianggujun.tinaide.core.config.EditorSettings
import com.wuxianggujun.tinaide.core.config.Prefs

/** Shared indentation values, independent of the editor UI and of a formatter backend. */
data class FormatterIndentation(
    val indentWidth: Int,
    val tabWidth: Int,
    val insertSpacesForTabs: Boolean,
    val continuationIndentWidth: Int = indentWidth,
) {
    init {
        require(indentWidth in 0..MAX_WIDTH)
        require(tabWidth in 1..MAX_WIDTH)
        require(continuationIndentWidth in 0..MAX_WIDTH)
    }

    internal fun applyTo(style: FormatStyle): FormatStyle {
        require(style != FormatStyle.FILE && style !is FormatStyle.Custom)
        val baseStyle = style.toClangFormatArgument().removePrefix("--style=")
        val useTab = if (insertSpacesForTabs) "Never" else "ForIndentation"
        return FormatStyle.Custom(
            "{BasedOnStyle: $baseStyle, IndentWidth: $indentWidth, TabWidth: $tabWidth, " +
                "ContinuationIndentWidth: $continuationIndentWidth, UseTab: $useTab}"
        )
    }

    companion object {
        private const val MAX_WIDTH = 256
        private val USE_TAB_VALUES = setOf(
            "Never", "ForIndentation", "ForContinuationAndIndentation", "AlignWithSpaces", "Always"
        )

        fun fromPreferences(): FormatterIndentation = fromEditorSettings(Prefs.editorSettingsFlow.value)

        fun fromEditorSettings(settings: EditorSettings): FormatterIndentation = FormatterIndentation(
            indentWidth = settings.indentSize,
            tabWidth = settings.tabSize,
            insertSpacesForTabs = settings.insertSpacesForTabs,
        )

        /** Parse only clang-format's resolved top-level scalars, never a user's YAML document. */
        fun fromResolvedConfig(config: String): FormatterIndentation? {
            val values = config.lineSequence()
                .filter { it.isNotEmpty() && !it.first().isWhitespace() }
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator < 0) return@mapNotNull null
                    line.substring(0, separator) to line.substring(separator + 1).trim()
                }
                .toMap()
            val indentWidth = values["IndentWidth"]?.toIntOrNull() ?: return null
            val tabWidth = values["TabWidth"]?.toIntOrNull() ?: return null
            val useTab = values["UseTab"]?.takeIf { it in USE_TAB_VALUES } ?: return null
            val continuationWidth = if ("ContinuationIndentWidth" in values) {
                values["ContinuationIndentWidth"]?.toIntOrNull() ?: return null
            } else {
                indentWidth
            }
            if (indentWidth !in 0..MAX_WIDTH || tabWidth !in 1..MAX_WIDTH) return null
            if (continuationWidth !in 0..MAX_WIDTH) return null
            return FormatterIndentation(indentWidth, tabWidth, useTab == "Never", continuationWidth)
        }
    }
}

sealed interface FormatterIndentationResult {
    data class Success(
        val indentation: FormatterIndentation,
        val usesProjectConfig: Boolean,
    ) : FormatterIndentationResult

    data class Error(val message: String) : FormatterIndentationResult
}

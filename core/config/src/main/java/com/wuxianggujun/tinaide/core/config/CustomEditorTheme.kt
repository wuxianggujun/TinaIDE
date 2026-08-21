package com.wuxianggujun.tinaide.core.config

import java.util.Locale
import org.json.JSONObject

const val CUSTOM_EDITOR_THEME_ID: String = "CUSTOM"

enum class EditorThemeBase(val themeId: String) {
    GRAY("GRAY"),
    DARK("DARK"),
    LIGHT("LIGHT");

    companion object {
        fun fromThemeId(themeId: String?): EditorThemeBase = entries.firstOrNull {
            it.themeId.equals(themeId, ignoreCase = true)
        } ?: GRAY
    }
}

enum class EditorThemeColorGroup {
    EDITOR,
    SYNTAX,
    DIAGNOSTICS
}

/** Stable color keys shared by built-in customization and plugin themes. */
enum class EditorThemeColorKey(
    val wireName: String,
    val group: EditorThemeColorGroup
) {
    EDITOR_BACKGROUND("editor.background", EditorThemeColorGroup.EDITOR),
    EDITOR_FOREGROUND("editor.foreground", EditorThemeColorGroup.EDITOR),
    EDITOR_SELECTION("editor.selection", EditorThemeColorGroup.EDITOR),
    EDITOR_CURSOR_LINE("editor.cursorLine", EditorThemeColorGroup.EDITOR),
    EDITOR_CURSOR("editor.cursor", EditorThemeColorGroup.EDITOR),
    EDITOR_LINE_NUMBER("editor.lineNumber", EditorThemeColorGroup.EDITOR),
    EDITOR_LINE_NUMBER_ACTIVE("editor.lineNumberActive", EditorThemeColorGroup.EDITOR),
    GUTTER_BACKGROUND("gutter.background", EditorThemeColorGroup.EDITOR),
    GUTTER_DIVIDER("gutter.divider", EditorThemeColorGroup.EDITOR),
    EDITOR_WHITESPACE("editor.whitespace", EditorThemeColorGroup.EDITOR),
    BRACKET_PAIR_GUIDE("editor.bracketPairGuide", EditorThemeColorGroup.EDITOR),
    BRACKET_PAIR_GUIDE_ACTIVE("editor.bracketPairGuideActive", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_1("rainbowBrackets.0", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_2("rainbowBrackets.1", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_3("rainbowBrackets.2", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_4("rainbowBrackets.3", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_5("rainbowBrackets.4", EditorThemeColorGroup.EDITOR),
    RAINBOW_BRACKET_6("rainbowBrackets.5", EditorThemeColorGroup.EDITOR),
    SYNTAX_KEYWORD("syntax.keyword", EditorThemeColorGroup.SYNTAX),
    SYNTAX_FUNCTION("syntax.function", EditorThemeColorGroup.SYNTAX),
    SYNTAX_VARIABLE("syntax.variable", EditorThemeColorGroup.SYNTAX),
    SYNTAX_PROPERTY("syntax.property", EditorThemeColorGroup.SYNTAX),
    SYNTAX_TYPE("syntax.type", EditorThemeColorGroup.SYNTAX),
    SYNTAX_STRING("syntax.string", EditorThemeColorGroup.SYNTAX),
    SYNTAX_NUMBER("syntax.number", EditorThemeColorGroup.SYNTAX),
    SYNTAX_COMMENT("syntax.comment", EditorThemeColorGroup.SYNTAX),
    SYNTAX_OPERATOR("syntax.operator", EditorThemeColorGroup.SYNTAX),
    SYNTAX_PUNCTUATION("syntax.punctuation", EditorThemeColorGroup.SYNTAX),
    SYNTAX_CONSTANT("syntax.constant", EditorThemeColorGroup.SYNTAX),
    SYNTAX_BUILTIN("syntax.builtin", EditorThemeColorGroup.SYNTAX),
    SYNTAX_DEPRECATED("syntax.deprecated", EditorThemeColorGroup.SYNTAX),
    DIAGNOSTIC_ERROR("diagnostic.error", EditorThemeColorGroup.DIAGNOSTICS),
    DIAGNOSTIC_WARNING("diagnostic.warning", EditorThemeColorGroup.DIAGNOSTICS),
    DIAGNOSTIC_INFO("diagnostic.info", EditorThemeColorGroup.DIAGNOSTICS),
    DIAGNOSTIC_HINT("diagnostic.hint", EditorThemeColorGroup.DIAGNOSTICS);

    companion object {
        private val byWireName = entries.associateBy(EditorThemeColorKey::wireName)

        fun fromWireName(wireName: String): EditorThemeColorKey? = byWireName[wireName]
    }
}

data class CustomEditorTheme(
    val base: EditorThemeBase = EditorThemeBase.GRAY,
    val colors: Map<String, String> = emptyMap()
) {
    fun sanitized(): CustomEditorTheme {
        val sanitizedColors = buildMap {
            EditorThemeColorKey.entries.forEach { key ->
                normalizeEditorThemeColor(colors[key.wireName])?.let { color ->
                    put(key.wireName, color)
                }
            }
        }
        return copy(colors = sanitizedColors)
    }

    fun withColor(key: EditorThemeColorKey, color: String?): CustomEditorTheme {
        val updated = colors.toMutableMap()
        val normalized = normalizeEditorThemeColor(color)
        if (normalized == null) {
            updated.remove(key.wireName)
        } else {
            updated[key.wireName] = normalized
        }
        return copy(colors = updated).sanitized()
    }
}

fun normalizeEditorThemeColor(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val hex = when {
        raw.startsWith("#") -> raw.drop(1)
        raw.startsWith("0x", ignoreCase = true) -> raw.drop(2)
        else -> raw
    }
    if (hex.length != 6 && hex.length != 8) return null
    if (hex.any { it.digitToIntOrNull(16) == null }) return null
    return "#${hex.uppercase(Locale.ROOT)}"
}

data class EditorThemeColorChannels(
    val alpha: Int,
    val red: Int,
    val green: Int,
    val blue: Int
) {
    fun sanitized(): EditorThemeColorChannels = EditorThemeColorChannels(
        alpha = alpha.coerceIn(0, 255),
        red = red.coerceIn(0, 255),
        green = green.coerceIn(0, 255),
        blue = blue.coerceIn(0, 255)
    )

    fun toHexColor(): String {
        val value = sanitized()
        return String.format(
            Locale.ROOT,
            "#%02X%02X%02X%02X",
            value.alpha,
            value.red,
            value.green,
            value.blue
        )
    }
}

fun parseEditorThemeColorChannels(value: String?): EditorThemeColorChannels? {
    val normalized = normalizeEditorThemeColor(value) ?: return null
    val hex = normalized.drop(1)
    val argb = if (hex.length == 6) "FF$hex" else hex
    return EditorThemeColorChannels(
        alpha = argb.substring(0, 2).toInt(16),
        red = argb.substring(2, 4).toInt(16),
        green = argb.substring(4, 6).toInt(16),
        blue = argb.substring(6, 8).toInt(16)
    )
}

internal object CustomEditorThemeCodec {
    private const val VERSION = 1

    fun encode(theme: CustomEditorTheme): String {
        val sanitized = theme.sanitized()
        val colorsJson = JSONObject()
        EditorThemeColorKey.entries.forEach { key ->
            sanitized.colors[key.wireName]?.let { colorsJson.put(key.wireName, it) }
        }
        return JSONObject()
            .put("version", VERSION)
            .put("base", sanitized.base.themeId)
            .put("colors", colorsJson)
            .toString()
    }

    fun decode(raw: String?): CustomEditorTheme {
        if (raw.isNullOrBlank()) return CustomEditorTheme()
        return runCatching {
            val json = JSONObject(raw)
            val colorsJson = json.optJSONObject("colors") ?: JSONObject()
            val colors = buildMap {
                EditorThemeColorKey.entries.forEach { key ->
                    val rawColor = if (colorsJson.has(key.wireName)) {
                        colorsJson.getString(key.wireName)
                    } else {
                        null
                    }
                    normalizeEditorThemeColor(rawColor)?.let { color ->
                        put(key.wireName, color)
                    }
                }
            }
            CustomEditorTheme(
                base = EditorThemeBase.fromThemeId(
                    if (json.has("base")) json.getString("base") else null
                ),
                colors = colors
            ).sanitized()
        }.getOrDefault(CustomEditorTheme())
    }
}

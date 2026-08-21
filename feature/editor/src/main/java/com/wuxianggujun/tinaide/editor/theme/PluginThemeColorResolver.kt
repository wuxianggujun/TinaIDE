package com.wuxianggujun.tinaide.editor.theme

import com.wuxianggujun.tinaide.core.config.EditorThemeColorKey
import com.wuxianggujun.tinaide.plugin.ThemeConfig

/** Maps common TextMate scopes onto TinaIDE's finite syntax color categories. */
object PluginThemeColorResolver {

    private data class ScopeRule(
        val fragments: List<String>,
        val colorKey: EditorThemeColorKey
    )

    private val rules = listOf(
        ScopeRule(listOf("invalid.deprecated", "invalid.illegal"), EditorThemeColorKey.SYNTAX_DEPRECATED),
        ScopeRule(listOf("keyword.operator"), EditorThemeColorKey.SYNTAX_OPERATOR),
        ScopeRule(listOf("constant.numeric"), EditorThemeColorKey.SYNTAX_NUMBER),
        ScopeRule(
            listOf("variable.other.property", "variable.other.object.property", "meta.object-literal.key"),
            EditorThemeColorKey.SYNTAX_PROPERTY
        ),
        ScopeRule(
            listOf("entity.name.function", "support.function", "meta.function-call"),
            EditorThemeColorKey.SYNTAX_FUNCTION
        ),
        ScopeRule(
            listOf(
                "entity.name.type",
                "entity.name.class",
                "entity.name.struct",
                "entity.name.enum",
                "storage.type",
                "support.type",
                "support.class"
            ),
            EditorThemeColorKey.SYNTAX_TYPE
        ),
        ScopeRule(
            listOf("variable.language", "support.variable", "support.other.variable"),
            EditorThemeColorKey.SYNTAX_BUILTIN
        ),
        ScopeRule(
            listOf("constant.language", "constant.character", "constant.other", "support.constant"),
            EditorThemeColorKey.SYNTAX_CONSTANT
        ),
        ScopeRule(listOf("comment"), EditorThemeColorKey.SYNTAX_COMMENT),
        ScopeRule(listOf("string"), EditorThemeColorKey.SYNTAX_STRING),
        ScopeRule(listOf("keyword", "storage.modifier"), EditorThemeColorKey.SYNTAX_KEYWORD),
        ScopeRule(listOf("punctuation"), EditorThemeColorKey.SYNTAX_PUNCTUATION),
        ScopeRule(listOf("variable", "identifier"), EditorThemeColorKey.SYNTAX_VARIABLE),
        ScopeRule(listOf("operator"), EditorThemeColorKey.SYNTAX_OPERATOR)
    )

    fun resolve(theme: ThemeConfig): Map<String, String> {
        val resolved = linkedMapOf<String, String>()
        theme.tokenColors.orEmpty().forEach { tokenColor ->
            val foreground = tokenColor.settings.foreground?.trim().orEmpty()
            if (foreground.isEmpty()) return@forEach

            val scopes = tokenColor.scope
                .asSequence()
                .flatMap { scope -> scope.splitToSequence(',', ' ', '\t') }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
            if (scopes.isEmpty()) {
                resolved[EditorThemeColorKey.EDITOR_FOREGROUND.wireName] = foreground
            } else {
                scopes.asSequence()
                    .mapNotNull(::resolveScope)
                    .forEach { key -> resolved[key.wireName] = foreground }
            }
        }

        // TinaIDE's explicit stable keys are more precise than inferred TextMate scopes.
        // Blank values are invalid input and must not hide a usable inferred color.
        theme.colors.forEach { (key, value) ->
            if (value.isNotBlank()) resolved[key] = value
        }
        return resolved
    }

    private fun resolveScope(scope: String): EditorThemeColorKey? {
        val normalized = scope.lowercase()
        return rules.firstOrNull { rule ->
            rule.fragments.any { fragment ->
                normalized == fragment ||
                    normalized.startsWith("$fragment.") ||
                    normalized.endsWith(".$fragment") ||
                    normalized.contains(".$fragment.")
            }
        }?.colorKey
    }
}

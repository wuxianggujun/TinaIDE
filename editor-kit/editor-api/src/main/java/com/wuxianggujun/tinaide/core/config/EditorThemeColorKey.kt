package com.wuxianggujun.tinaide.core.config

enum class EditorThemeColorGroup {
    EDITOR,
    SYNTAX,
    DIAGNOSTICS
}

/** Stable color keys shared by the editor and host theme adapters. */
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

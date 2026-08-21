package com.wuxianggujun.tinaide.ui.compose.components.markdown

internal object LatexRenderPolicy {
    internal const val MAX_SOURCE_CHARS = 2_048
    internal const val MAX_RENDER_DIMENSION_PX = 8_192
    private const val MAX_SOURCE_UTF8_BYTES = 4_096
    private const val MAX_NESTING_DEPTH = 64
    private const val MAX_COMMAND_COUNT = 256

    private val blockedCommands = setOf(
        "catcode",
        "csname",
        "def",
        "edef",
        "futurelet",
        "gdef",
        "include",
        "input",
        "let",
        "loop",
        "newcommand",
        "openin",
        "openout",
        "providecommand",
        "read",
        "renewcommand",
        "repeat",
        "usepackage",
        "write",
        "xdef",
    )

    fun isSafeToRender(source: String): Boolean {
        if (source.length > MAX_SOURCE_CHARS) return false
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_UTF8_BYTES) return false

        var braceDepth = 0
        var commandCount = 0
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> {
                    braceDepth++
                    if (braceDepth > MAX_NESTING_DEPTH) return false
                    index++
                }
                '}' -> {
                    braceDepth--
                    if (braceDepth < 0) return false
                    index++
                }
                '\\' -> {
                    commandCount++
                    if (commandCount > MAX_COMMAND_COUNT) return false
                    index++
                    if (index >= source.length) continue

                    if (source[index].isLetter()) {
                        val commandStart = index
                        while (index < source.length && source[index].isLetter()) index++
                        if (source.substring(commandStart, index) in blockedCommands) return false
                    } else {
                        index++
                    }
                }
                else -> index++
            }
        }
        return braceDepth == 0
    }

    fun fallbackText(source: String): String =
        if (source.length <= MAX_SOURCE_CHARS) source else source.take(MAX_SOURCE_CHARS) + "..."

    fun prepareForRendering(source: String): String? {
        if (!isSafeToRender(source)) return null
        val trimmed = source.trim()
        val processed = when {
            displayDollarRegex.matches(trimmed) -> displayDollarRegex.matchEntire(trimmed)?.groupValues?.get(1)
            inlineDollarRegex.matches(trimmed) -> inlineDollarRegex.matchEntire(trimmed)?.groupValues?.get(1)
            displayBracketRegex.matches(trimmed) -> displayBracketRegex.matchEntire(trimmed)?.groupValues?.get(1)
            inlineParenRegex.matches(trimmed) -> inlineParenRegex.matchEntire(trimmed)?.groupValues?.get(1)
            else -> trimmed
        }?.trim() ?: return null
        return processed.takeIf(::isSafeToRender)
    }

    private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
    private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)
    private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
}

package com.wuxianggujun.tinaide.project

import java.io.File

/** Resolves the effective C++ standard flag used by TinaIDE fallback compile commands. */
object ProjectCppStandardResolver {

    val DEFAULT_FLAG: String = CppStandard.DEFAULT.flag

    private val makefileNames = listOf("Makefile", "makefile", "GNUmakefile")
    private val bracketCommentPattern = Regex("""(?s)#\[(=*)\[.*?]\1]""")
    private val cmakeOperationPattern = Regex(
        """(?s)\b((?i:set|unset))[\t ]*\([\t\r\n]*CMAKE_CXX_STANDARD\b(.*?)\)""",
    )
    private val standardOptionPattern = Regex(
        """(?i)(?:^|[\s="'])-std=((?:c|gnu)\+\+(?:98|03|11|14|17|20|23|26|2a|2b|2c))(?![A-Za-z0-9_+.-])""",
    )
    private val standardDialectPattern = Regex(
        """(?i)^(?:c|gnu)\+\+(?:98|03|11|14|17|20|23|26|2a|2b|2c)$""",
    )
    private val standardVersionPattern = Regex(
        """(?i)^(?:CPP_)?(98|03|11|14|17|20|23|26|2a|2b|2c)$""",
    )

    /**
     * Resolution order: explicit override, root CMakeLists.txt, root Makefile, metadata, C++17.
     *
     * The returned value never includes the `-std=` prefix. Dynamic build expressions are not
     * evaluated here; they fall through to the next source so fallback commands remain predictable.
     */
    fun resolveFlag(projectRoot: File?, override: String? = null): String {
        resolveFromOverride(override)?.let { return it }
        if (projectRoot == null) return DEFAULT_FLAG

        resolveFromCMakeLists(File(projectRoot, "CMakeLists.txt"))?.let { return it }
        resolveFromRootMakefile(projectRoot)?.let { return it }
        resolveFromMetadata(projectRoot)?.let { return it }
        return DEFAULT_FLAG
    }

    internal fun normalizeFlag(rawValue: String?): String? {
        var value = rawValue
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.trim()
            .orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("-std=", ignoreCase = true)) {
            value = value.substringAfter('=').trim()
        }

        CppStandard.entries.firstOrNull { standard ->
            standard.name.equals(value, ignoreCase = true) ||
                standard.cmakeValue.equals(value, ignoreCase = true) ||
                standard.flag.equals(value, ignoreCase = true)
        }?.let { return it.flag }

        standardVersionPattern.matchEntire(value)?.let { match ->
            return "c++${match.groupValues[1].lowercase()}"
        }
        return value.lowercase().takeIf { standardDialectPattern.matches(it) }
    }

    internal fun resolveFromCMakeLists(cmakeListsFile: File): String? {
        if (!cmakeListsFile.isFile) return null
        val content = runCatching { cmakeListsFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val uncommented = content
            .replace(bracketCommentPattern, "")
            .lineSequence()
            .joinToString("\n", transform = ::stripCmakeLineComment)
        val lastOperation = cmakeOperationPattern.findAll(uncommented).lastOrNull() ?: return null
        if (lastOperation.groupValues[1].equals("unset", ignoreCase = true)) return null

        val operationBody = lastOperation.groupValues[2].trimStart()
        val rawValue = when {
            operationBody.startsWith('"') -> operationBody.drop(1).substringBefore('"')
            else -> operationBody.takeWhile { !it.isWhitespace() }
        }
        return normalizeFlag(rawValue)
    }

    internal fun resolveFromMakefile(makefile: File): String? {
        if (!makefile.isFile) return null
        val content = runCatching { makefile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val uncommented = content
            .lineSequence()
            .joinToString("\n", transform = ::stripMakeLineComment)
        return extractLastStandardOption(uncommented)
    }

    private fun resolveFromOverride(override: String?): String? {
        if (override.isNullOrBlank()) return null
        return extractLastStandardOption(override) ?: normalizeFlag(override)
    }

    private fun resolveFromRootMakefile(projectRoot: File): String? {
        val makefile = makefileNames
            .asSequence()
            .map { fileName -> File(projectRoot, fileName) }
            .firstOrNull(File::isFile)
            ?: return null
        return resolveFromMakefile(makefile)
    }

    private fun resolveFromMetadata(projectRoot: File): String? = runCatching {
        normalizeFlag(ProjectMetadataStore.read(projectRoot)?.cppStandard)
    }.getOrNull()

    private fun extractLastStandardOption(content: String): String? = standardOptionPattern
        .findAll(content)
        .mapNotNull { match -> normalizeFlag(match.groupValues[1]) }
        .lastOrNull()

    private fun stripCmakeLineComment(line: String): String {
        var inDoubleQuote = false
        var escaping = false
        line.forEachIndexed { index, char ->
            when {
                escaping -> escaping = false
                char == '\\' -> escaping = true
                char == '"' -> inDoubleQuote = !inDoubleQuote
                char == '#' && !inDoubleQuote -> return line.substring(0, index)
            }
        }
        return line
    }

    private fun stripMakeLineComment(line: String): String {
        line.forEachIndexed { index, char ->
            if (char != '#') return@forEachIndexed
            var precedingBackslashes = 0
            var cursor = index - 1
            while (cursor >= 0 && line[cursor] == '\\') {
                precedingBackslashes += 1
                cursor -= 1
            }
            if (precedingBackslashes % 2 == 0) return line.substring(0, index)
        }
        return line
    }
}

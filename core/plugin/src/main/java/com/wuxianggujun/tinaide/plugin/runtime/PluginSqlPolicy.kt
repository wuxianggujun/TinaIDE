package com.wuxianggujun.tinaide.plugin.runtime

internal object PluginSqlPolicy {
    private const val MAX_SQL_LENGTH = 64 * 1024
    private val queryPrefix = Regex("^\\s*(SELECT|WITH|EXPLAIN)\\b", RegexOption.IGNORE_CASE)
    private val mutationPrefix = Regex(
        "^\\s*(CREATE|INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val forbiddenKeywords = setOf("ATTACH", "DETACH", "PRAGMA", "RECURSIVE", "VACUUM")
    private val forbiddenFunctions = setOf(
        "FORMAT",
        "GROUP_CONCAT",
        "HEX",
        "JSON_GROUP_ARRAY",
        "JSON_GROUP_OBJECT",
        "LOAD_EXTENSION",
        "PRINTF",
        "RANDOMBLOB",
        "REPLACE",
        "ZEROBLOB",
    )
    private val cteStatementKeywords = setOf("SELECT", "VALUES", "INSERT", "UPDATE", "DELETE", "REPLACE")

    fun isAllowedQuery(sql: String?): Boolean {
        val statement = validatedStatement(sql, queryPrefix) ?: return false
        val topLevelWords = scanSql(statement)?.topLevelWords ?: return false
        return topLevelWords.firstOrNull() != "WITH" ||
            topLevelWords.drop(1).firstOrNull { it in cteStatementKeywords } in setOf("SELECT", "VALUES")
    }

    fun isAllowedMutation(sql: String?): Boolean = validatedStatement(sql, mutationPrefix) != null

    private fun validatedStatement(sql: String?, allowedPrefix: Regex): String? {
        val statement = sql?.takeIf { it.isNotBlank() && it.length <= MAX_SQL_LENGTH } ?: return null
        if ('\u0000' in statement || !allowedPrefix.containsMatchIn(statement)) return null
        val scan = scanSql(statement) ?: return null
        if (scan.words.any { it in forbiddenKeywords }) return null
        if (scan.functions.any { it in forbiddenFunctions }) return null
        return statement
    }

    private fun scanSql(statement: String): SqlScan? {
        val words = mutableListOf<String>()
        val topLevelWords = mutableListOf<String>()
        val functions = mutableListOf<String>()
        var depth = 0
        var index = 0
        while (index < statement.length) {
            when (val char = statement[index]) {
                '\'' -> index = skipQuoted(statement, index, char) ?: return null
                '"', '`' -> {
                    val end = skipQuoted(statement, index, char) ?: return null
                    recordQuotedIdentifier(
                        statement = statement,
                        start = index + 1,
                        end = end - 1,
                        quote = char,
                        depth = depth,
                        words = words,
                        topLevelWords = topLevelWords,
                        functions = functions,
                        followedByOpenParen = isFollowedByOpenParen(statement, end),
                    )
                    index = end
                }
                '[' -> {
                    val end = skipBracketIdentifier(statement, index) ?: return null
                    recordQuotedIdentifier(
                        statement = statement,
                        start = index + 1,
                        end = end - 1,
                        quote = null,
                        depth = depth,
                        words = words,
                        topLevelWords = topLevelWords,
                        functions = functions,
                        followedByOpenParen = isFollowedByOpenParen(statement, end),
                    )
                    index = end
                }
                '-' -> if (statement.getOrNull(index + 1) == '-') {
                    index = statement.indexOf('\n', index + 2).takeIf { it >= 0 } ?: statement.length
                } else {
                    index++
                }
                '/' -> if (statement.getOrNull(index + 1) == '*') {
                    val end = statement.indexOf("*/", index + 2)
                    if (end < 0) return null
                    index = end + 2
                } else {
                    index++
                }
                '(' -> {
                    depth++
                    index++
                }
                ')' -> {
                    if (depth == 0) return null
                    depth--
                    index++
                }
                ';' -> return null
                else -> if (char.isLetter() || char == '_') {
                    val start = index
                    while (index < statement.length && (statement[index].isLetterOrDigit() || statement[index] == '_')) {
                        index++
                    }
                    val word = statement.substring(start, index).uppercase()
                    words += word
                    if (depth == 0) topLevelWords += word
                    if (isFollowedByOpenParen(statement, index)) functions += word
                } else {
                    index++
                }
            }
        }
        if (depth != 0) return null
        return SqlScan(words, topLevelWords, functions)
    }

    private fun skipQuoted(statement: String, start: Int, quote: Char): Int? {
        var index = start + 1
        while (index < statement.length) {
            if (statement[index] == quote) {
                if (statement.getOrNull(index + 1) == quote) {
                    index += 2
                } else {
                    return index + 1
                }
            } else {
                index++
            }
        }
        return null
    }

    private fun skipBracketIdentifier(statement: String, start: Int): Int? {
        val end = statement.indexOf(']', start + 1)
        return end.takeIf { it >= 0 }?.plus(1)
    }

    private fun recordQuotedIdentifier(
        statement: String,
        start: Int,
        end: Int,
        quote: Char?,
        depth: Int,
        words: MutableList<String>,
        topLevelWords: MutableList<String>,
        functions: MutableList<String>,
        followedByOpenParen: Boolean,
    ) {
        val raw = statement.substring(start, end)
        val identifier = if (quote == null) raw else raw.replace("$quote$quote", quote.toString())
        if (!identifier.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) return
        val word = identifier.uppercase()
        words += word
        if (depth == 0) topLevelWords += word
        if (followedByOpenParen) functions += word
    }

    private fun isFollowedByOpenParen(statement: String, start: Int): Boolean {
        var index = start
        while (index < statement.length) {
            when {
                statement[index].isWhitespace() -> index++
                statement.startsWith("--", index) -> {
                    index = statement.indexOf('\n', index + 2).takeIf { it >= 0 } ?: return false
                }
                statement.startsWith("/*", index) -> {
                    val end = statement.indexOf("*/", index + 2)
                    if (end < 0) return false
                    index = end + 2
                }
                else -> return statement[index] == '('
            }
        }
        return false
    }

    private data class SqlScan(
        val words: List<String>,
        val topLevelWords: List<String>,
        val functions: List<String>,
    )
}

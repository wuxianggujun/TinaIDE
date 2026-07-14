package com.wuxianggujun.tinaide.plugin.runtime

internal object PluginSqlPolicy {
    private const val MAX_SQL_LENGTH = 64 * 1024
    private val queryPrefix = Regex("^\\s*(SELECT|WITH|EXPLAIN)\\b", RegexOption.IGNORE_CASE)
    private val mutationPrefix = Regex(
        "^\\s*(CREATE|INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val forbiddenKeyword = Regex(
        "\\b(ATTACH|DETACH|PRAGMA|VACUUM|LOAD_EXTENSION)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isAllowedQuery(sql: String?): Boolean = isAllowed(sql, queryPrefix)

    fun isAllowedMutation(sql: String?): Boolean = isAllowed(sql, mutationPrefix)

    private fun isAllowed(sql: String?, allowedPrefix: Regex): Boolean {
        val statement = sql?.takeIf { it.isNotBlank() && it.length <= MAX_SQL_LENGTH } ?: return false
        if ('\u0000' in statement || forbiddenKeyword.containsMatchIn(statement)) return false
        return allowedPrefix.containsMatchIn(statement)
    }
}

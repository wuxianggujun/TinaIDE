package com.wuxianggujun.tinaide.core.symbol

/**
 * 符号索引状态
 */
data class SymbolIndexStatus(
    val projectRoot: String? = null,
    val isIndexing: Boolean = false,
    val indexedFiles: Int = 0,
    val totalFiles: Int = 0,
    val lastIndexedAt: Long? = null,
    val lastError: String? = null,
    val revision: Long = 0L,
    val cacheLoaded: Boolean = false,
    val cacheHitFiles: Int = 0,
)

/**
 * 符号信息
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val detail: String,
    val filePath: String,
    val location: SymbolLocation? = null,
    val signature: String? = null,
    val documentation: String? = null,
    val containerName: String? = null,
) {
    val displayDetail: String
        get() {
            val parts = mutableListOf<String>()
            containerName?.let(parts::add)
            if (signature != null) {
                parts.add(signature)
            }
            if (detail.isNotEmpty()) {
                parts.add(detail)
            }
            return parts.joinToString(" • ")
        }
}

/**
 * 符号位置
 */
data class SymbolLocation(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/**
 * 符号类型
 */
enum class SymbolKind {
    FILE,
    MODULE,
    NAMESPACE,
    PACKAGE,
    CLASS,
    METHOD,
    PROPERTY,
    FIELD,
    CONSTRUCTOR,
    ENUM,
    INTERFACE,
    FUNCTION,
    VARIABLE,
    CONSTANT,
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
    KEY,
    NULL,
    ENUM_MEMBER,
    STRUCT,
    EVENT,
    OPERATOR,
    TYPE_PARAMETER,
}

/**
 * 模糊匹配结果
 */
data class FuzzySymbolMatch(
    val symbol: SymbolInfo,
    val score: Int,
    val matchedIndices: List<Int>,
)

package com.wuxianggujun.tinaide.core.lsp

/**
 * 诊断信息模型
 *
 * 用于在 UI 中显示编译/LSP 的错误与警告等信息。
 */
data class Diagnostic(
    val fileUri: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val endLine: Int = line,
    val endColumn: Int = column + 1,
    val message: String,
    val severity: Severity,
    val source: String? = null,
    val code: String? = null,
    val codeDescriptionUri: String? = null,
    val tags: List<Tag> = emptyList(),
    val relatedInformation: List<RelatedInformation> = emptyList(),
    val data: Any? = null
) {
    enum class Severity {
        ERROR,
        WARNING,
        INFO,
        HINT
    }

    enum class Tag {
        UNNECESSARY,
        DEPRECATED
    }

    data class RelatedInformation(
        val fileUri: String,
        val line: Int,
        val column: Int,
        val endLine: Int = line,
        val endColumn: Int = column + 1,
        val message: String
    )

    val displayLocation: String
        get() = "$fileName:${line + 1}:${column + 1}"
}

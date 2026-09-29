package com.wuxianggujun.tinaide.core.lang

import java.io.File

/**
 * Makefile 相关文件能力注册表（单一数据源）。
 *
 * 目标：
 * - 统一编辑器、Tree-sitter、LSP 对 make-like 文件的识别规则
 * - 覆盖常见变体：`Makefile`、`GNUmakefile`、`BSDmakefile`、`*.mk`、`*.mak`
 * - 兼容 `Makefile.in` / `Makefile.am` 这类以 canonical name 为前缀的模板文件
 */
object MakeFileSupport {

    val canonicalNames: Set<String> = setOf(
        "makefile",
        "gnumakefile",
        "bsdmakefile"
    )

    val recognizedExtensions: Set<String> = setOf(
        "mk",
        "mak"
    )

    fun isMakeLikeFile(file: File): Boolean = isMakeLikeFileName(file.name)

    fun isMakeLikeFileName(fileName: String): Boolean {
        val normalized = fileName.trim().lowercase()
        if (normalized.isBlank()) return false
        if (normalized in canonicalNames) return true
        if (canonicalNames.any { normalized.startsWith("$it.") }) return true
        val extension = normalized.substringAfterLast('.', "")
        return extension in recognizedExtensions
    }
}

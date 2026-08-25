package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.io.File

private fun File.resolveKnownEditorLanguageId(cHeaderLanguageId: String): String? {
    val ext = extension.lowercase()
    return when {
        ext == "c" -> "c"
        ext == "h" -> cHeaderLanguageId
        ext in CxxFileSupport.cxxSourceExtensions ||
            ext in (CxxFileSupport.headerExtensions - "h") -> "cpp"
        ext in CxxFileSupport.objcSourceExtensions -> "objective-c"
        ext in CxxFileSupport.objcxxSourceExtensions -> "objective-cpp"
        ext == "java" -> "java"
        ext == "kt" || ext == "kts" -> "kotlin"
        ext == "py" -> "python"
        ext == "js" -> "javascript"
        ext == "ts" -> "typescript"
        ext == "json" -> "json"
        ext == "xml" -> "xml"
        ext == "html" || ext == "htm" -> "html"
        ext == "css" -> "css"
        ext == "md" -> "markdown"
        ext == "lua" -> "lua"
        ext == "sh" || ext == "bash" || ext == "zsh" -> "bash"
        ext == "rs" -> "rust"
        ext == "go" -> "go"
        ext == "yaml" || ext == "yml" -> "yaml"
        ext == "toml" -> "toml"
        else -> null
    }
}

fun File.resolveEditorLanguageId(
    cHeaderLanguageId: String = "cpp",
    fallbackLanguageId: String = "plaintext"
): String = resolveKnownEditorLanguageId(cHeaderLanguageId)
    ?: extension.lowercase().ifBlank { fallbackLanguageId }

fun File.resolveLspLanguageId(fallbackLanguageId: String = "plaintext"): String = resolveKnownEditorLanguageId(cHeaderLanguageId = "cpp")
    ?: extension.lowercase().ifBlank { fallbackLanguageId }

fun File.resolveCodeAnalysisLanguageLabel(unknownLanguageId: String = "unknown"): String {
    val ext = extension.lowercase()
    if (ext in CxxFileSupport.headerExtensions) {
        return "c/c++ header"
    }
    return resolveKnownEditorLanguageId(cHeaderLanguageId = "cpp") ?: unknownLanguageId
}

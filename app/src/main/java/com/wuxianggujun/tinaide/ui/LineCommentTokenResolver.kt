package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport

internal fun resolveLineCommentToken(extension: String): String = when (extension.lowercase()) {
    in CxxFileSupport.editorRelatedExtensions,
    "java", "kt", "kts",
    "js", "ts",
    "rs", "go", "cs", "swift" -> "//"

    "py", "sh", "bash", "zsh", "rb", "pl", "yaml", "yml", "toml", "ini", "conf" -> "#"
    else -> "//"
}

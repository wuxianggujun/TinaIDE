package com.wuxianggujun.tinaide.core.treesitter

import com.itsaky.androidide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.core.lang.MakeFileSupport
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

object TreeSitterLanguageRegistry {
    private const val CMAKE_LISTS_FILE_NAME = "cmakelists.txt"

    private val extensionToLanguage = mapOf(
        "aidl" to "aidl",
        "bash" to "bash",
        "c" to "c",
        "cc" to "cpp",
        "cmake" to "cmake",
        "cpp" to "cpp",
        "cxx" to "cpp",
        "h" to "c",
        "hh" to "cpp",
        "hpp" to "cpp",
        "hxx" to "cpp",
        "java" to "java",
        "json" to "json",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "log" to "log",
        "mak" to "make",
        "mk" to "make",
        "properties" to "properties",
        "prop" to "properties",
        "py" to "python",
        "rs" to "rust",
        "sh" to "bash",
        "toml" to "toml",
        "xml" to "xml",
        "yaml" to "yaml",
        "yml" to "yaml",
        "zsh" to "bash"
    )

    /**
     * 语言别名映射（markdown 代码块标识 → tree-sitter 语言名）。
     *
     * 覆盖 extensionToLanguage 之外的常见写法。
     */
    private val aliasToLanguage = mapOf(
        "kotlin" to "kotlin",
        "java" to "java",
        "python" to "python",
        "rust" to "rust",
        "json" to "json",
        "yaml" to "yaml",
        "toml" to "toml",
        "xml" to "xml",
        "bash" to "bash",
        "c" to "c",
        "cpp" to "cpp",
        "c++" to "cpp",
        "shell" to "bash",
        "make" to "make",
        "makefile" to "make",
        "cmake" to "cmake",
        "properties" to "properties"
    )

    private val languageClassByName = mapOf(
        "aidl" to "com.itsaky.androidide.treesitter.aidl.TSLanguageAidl",
        "bash" to "com.itsaky.androidide.treesitter.bash.TSLanguageBash",
        "c" to "com.itsaky.androidide.treesitter.c.TSLanguageC",
        "cmake" to "com.itsaky.androidide.treesitter.cmake.TSLanguageCmake",
        "cpp" to "com.itsaky.androidide.treesitter.cpp.TSLanguageCpp",
        "java" to "com.itsaky.androidide.treesitter.java.TSLanguageJava",
        "json" to "com.itsaky.androidide.treesitter.json.TSLanguageJson",
        "kotlin" to "com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin",
        "log" to "com.itsaky.androidide.treesitter.log.TSLanguageLog",
        "make" to "com.itsaky.androidide.treesitter.make.TSLanguageMake",
        "properties" to "com.itsaky.androidide.treesitter.properties.TSLanguageProperties",
        "python" to "com.itsaky.androidide.treesitter.python.TSLanguagePython",
        "rust" to "com.itsaky.androidide.treesitter.rust.TSLanguageRust",
        "toml" to "com.itsaky.androidide.treesitter.toml.TSLanguageToml",
        "xml" to "com.itsaky.androidide.treesitter.xml.TSLanguageXml",
        "yaml" to "com.itsaky.androidide.treesitter.yaml.TSLanguageYaml"
    )

    private val loadedLanguageCache = ConcurrentHashMap<String, TSLanguage>()
    private val missingLanguageCache = ConcurrentHashMap.newKeySet<String>()

    fun languageNameForFile(file: File?): String? {
        file ?: return null
        if (MakeFileSupport.isMakeLikeFile(file)) {
            return "make"
        }
        if (file.name.equals(CMAKE_LISTS_FILE_NAME, ignoreCase = true)) {
            return "cmake"
        }
        val extension = file.extension
            .lowercase(Locale.ROOT)
            .trim()
        if (extension.isEmpty()) return null
        return extensionToLanguage[extension]
    }

    /**
     * 将文件扩展名或语言别名解析为 tree-sitter 语言名。
     *
     * 支持：`kt` → `kotlin`、`py` → `python`、`sh` → `bash`、`c++` → `cpp` 等。
     * 返回 null 表示不支持。
     */
    fun resolveLanguageName(alias: String?): String? {
        if (alias.isNullOrBlank()) return null
        val lower = alias.lowercase(Locale.ROOT).trim()
        return aliasToLanguage[lower]
            ?: extensionToLanguage[lower]
    }

    fun resolveLanguage(languageName: String): TSLanguage? {
        val normalized = languageName.lowercase(Locale.ROOT)
        loadedLanguageCache[normalized]?.let { return it }
        if (normalized in missingLanguageCache) return null

        val className = languageClassByName[normalized]
        if (className == null) {
            missingLanguageCache.add(normalized)
            Timber.tag("TreeSitter").d("No TSLanguage binding registered for language=%s", normalized)
            return null
        }
        val resolved = runCatching {
            val clazz = Class.forName(className)
            val getInstance = clazz.getMethod("getInstance")
            getInstance.invoke(null) as? TSLanguage
        }.onFailure { error ->
            Timber.tag("TreeSitter").w(
                error,
                "Failed to resolve TSLanguage: language=%s class=%s",
                normalized,
                className
            )
        }.getOrNull()

        return if (resolved != null) {
            loadedLanguageCache.putIfAbsent(normalized, resolved) ?: resolved
        } else {
            null
        }
    }
}

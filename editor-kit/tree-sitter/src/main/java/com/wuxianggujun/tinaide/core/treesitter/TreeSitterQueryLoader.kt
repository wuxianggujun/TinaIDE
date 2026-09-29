package com.wuxianggujun.tinaide.core.treesitter

import android.content.Context
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal object TreeSitterQueryLoader {
    data class QueryBundle(
        val highlights: String,
        val blocks: String,
        val brackets: String,
        val locals: String
    )

    private val cache = ConcurrentHashMap<String, QueryBundle>()

    fun load(context: Context, languageName: String): QueryBundle? {
        val appContext = context.applicationContext
        return cache[languageName] ?: run {
            val loaded = loadInternal(appContext, languageName) ?: return null
            cache.putIfAbsent(languageName, loaded) ?: loaded
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun loadInternal(context: Context, languageName: String): QueryBundle? {
        val base = "tree-sitter-queries/$languageName"
        val highlights = readHighlightsWithInherits(
            context = context,
            languageName = languageName,
            visited = mutableSetOf()
        ) ?: return null
        val blocks = readAssetOrEmpty(context, "$base/blocks.scm")
        val brackets = readAssetOrEmpty(context, "$base/brackets.scm")
        val locals = readAssetOrEmpty(context, "$base/locals.scm")
        return QueryBundle(
            highlights = highlights,
            blocks = blocks,
            brackets = brackets,
            locals = locals
        )
    }

    private fun readHighlightsWithInherits(
        context: Context,
        languageName: String,
        visited: MutableSet<String>
    ): String? {
        if (!visited.add(languageName)) return ""

        val raw = readAssetOrNull(context, "tree-sitter-queries/$languageName/highlights.scm")
            ?: return null
        val inheritedLanguages = parseInherits(raw)
        if (inheritedLanguages.isEmpty()) {
            return raw
        }

        return buildString {
            for (baseLanguage in inheritedLanguages) {
                val inheritedText = readHighlightsWithInherits(context, baseLanguage, visited)
                    ?: continue
                if (inheritedText.isNotBlank()) {
                    append(inheritedText.trimEnd())
                    append("\n\n")
                }
            }
            append(raw.trimStart())
            append('\n')
        }
    }

    private fun parseInherits(highlightsText: String): List<String> {
        val regex = Regex("""^\s*;\s*inherits:\s*(.+?)\s*$""")
        val line = highlightsText.lineSequence()
            .firstOrNull { regex.matches(it) }
            ?: return emptyList()
        val raw = regex.find(line)?.groupValues?.getOrNull(1).orEmpty()
        return raw
            .split(',', ' ', '\t')
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotEmpty() }
            }
    }

    private fun readAssetOrNull(context: Context, path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        null
    }

    private fun readAssetOrEmpty(context: Context, path: String): String = readAssetOrNull(context, path) ?: ""
}

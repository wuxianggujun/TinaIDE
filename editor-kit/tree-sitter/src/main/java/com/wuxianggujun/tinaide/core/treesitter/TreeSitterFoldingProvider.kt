package com.wuxianggujun.tinaide.core.treesitter

import android.content.Context
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSTree
import java.io.File
import java.util.Locale
import timber.log.Timber

/**
 * 基于 Tree-sitter `blocks.scm` 的代码折叠区间计算器。
 */
class TreeSitterFoldingProvider private constructor(
    private val parser: TSParser,
    private val query: TSQuery,
    private val queryCursor: TSQueryCursor
) {
    private val lock = Any()
    private var disposed = false
    private var parsedTextSnapshot: String? = null
    private var parsedTree: TSTree? = null
    private var parsedRevision: Long = 0L
    private var cachedRevision: Long = -1L
    private var cachedRegions: List<FoldRegion> = emptyList()

    data class FoldRegion(
        val startLine: Int,
        val endLine: Int
    ) {
        val isFoldable: Boolean get() = endLine > startLine
    }

    fun computeFoldRegions(text: String): List<FoldRegion> {
        if (text.isEmpty()) return emptyList()
        return synchronized(lock) {
            if (disposed || !query.canAccess()) return@synchronized emptyList()
            runCatching {
                val tree = ensureParsedTree(text) ?: return@runCatching emptyList()
                if (cachedRevision == parsedRevision) {
                    return@runCatching cachedRegions
                }

                val rootNode = tree.getRootNode()
                queryCursor.exec(query, rootNode)

                val startToEndLine = HashMap<Int, Int>(64)
                var match = queryCursor.nextMatch()
                while (match != null) {
                    val captures = match.captures
                    for (capture in captures) {
                        val node = capture.node
                        if (!node.canAccess() || node.isNull) continue

                        val foldNode = resolveFoldBoundaryNode(node)
                        if (!foldNode.canAccess() || foldNode.isNull) continue

                        val startLine = foldNode.startPoint.row
                        var endLine = foldNode.endPoint.row
                        if (foldNode.endPoint.column == 0 && endLine > startLine) {
                            endLine -= 1
                        }
                        if (endLine <= startLine) continue

                        val existing = startToEndLine[startLine]
                        if (existing == null || endLine > existing) {
                            startToEndLine[startLine] = endLine
                        }
                    }
                    match = queryCursor.nextMatch()
                }

                val regions = startToEndLine.entries.asSequence()
                    .map { (start, end) -> FoldRegion(startLine = start, endLine = end) }
                    .filter { it.isFoldable }
                    .sortedWith(compareBy<FoldRegion> { it.startLine }.thenByDescending { it.endLine })
                    .toList()

                if (regions.size <= 50) {
                    val lines = regions.joinToString { "${it.startLine}→${it.endLine}" }
                    Timber.tag("TreeSitter").d(
                        "Fold regions: raw=%d, foldable=%d, lines=[%s]",
                        startToEndLine.size,
                        regions.size,
                        lines
                    )
                } else {
                    Timber.tag("TreeSitter").d(
                        "Fold regions: raw=%d, foldable=%d (too many to list)",
                        startToEndLine.size,
                        regions.size
                    )
                }

                cachedRevision = parsedRevision
                cachedRegions = regions
                regions
            }.getOrElse { error ->
                Timber.tag("TreeSitter").d(error, "Compute folding regions failed")
                emptyList()
            }
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            closeParsedTree()
            runCatching { queryCursor.close() }
            runCatching { query.close() }
            runCatching { parser.close() }
        }
    }

    private fun ensureParsedTree(text: String): TSTree? {
        val currentTree = parsedTree
        if (currentTree?.canAccess() == true) {
            if (parsedTextSnapshot === text || parsedTextSnapshot == text) {
                return currentTree
            }
        }
        closeParsedTree()
        parser.reset()
        val newTree = parser.parseString(text) ?: return null
        parsedTree = newTree
        parsedTextSnapshot = text
        parsedRevision++
        cachedRevision = -1L
        cachedRegions = emptyList()
        return newTree
    }

    private fun closeParsedTree() {
        runCatching { parsedTree?.close() }
        parsedTree = null
        parsedTextSnapshot = null
        cachedRevision = -1L
        cachedRegions = emptyList()
    }

    private fun resolveFoldBoundaryNode(captureNode: com.itsaky.androidide.treesitter.TSNode): com.itsaky.androidide.treesitter.TSNode {
        val parent = runCatching { captureNode.parent }.getOrNull()
        if (parent != null && parent.canAccess() && !parent.isNull) {
            val body = runCatching { parent.getChildByFieldName("body") }.getOrNull()
            if (body != null && body.canAccess() && !body.isNull) {
                if (runCatching { body.isEqualTo(captureNode) }.getOrDefault(false)) {
                    return parent
                }
            }
        }
        return captureNode
    }

    companion object {
        fun create(context: Context, file: File?): TreeSitterFoldingProvider? {
            val languageName = TreeSitterLanguageRegistry.languageNameForFile(file) ?: return null
            return create(context, languageName)
        }

        fun create(context: Context, languageName: String): TreeSitterFoldingProvider? {
            val normalizedLanguageName = languageName.lowercase(Locale.ROOT)
            val queryBundle = TreeSitterQueryLoader.load(context, normalizedLanguageName) ?: return null
            val blocksQueryText = queryBundle.blocks
            if (blocksQueryText.isBlank()) return null

            val language = TreeSitterLanguageRegistry.resolveLanguage(normalizedLanguageName) ?: return null
            val parser = runCatching { TSParser.create() }.getOrNull() ?: return null

            var query: TSQuery? = null
            var cursor: TSQueryCursor? = null
            return try {
                parser.setLanguage(language)
                query = TreeSitterQueryCompiler.compileWithRecovery(
                    language = language,
                    queryText = blocksQueryText,
                    languageName = normalizedLanguageName,
                    queryName = "blocks"
                ) ?: run {
                    runCatching { parser.close() }
                    return null
                }
                cursor = TSQueryCursor.create()
                TreeSitterFoldingProvider(
                    parser = parser,
                    query = query,
                    queryCursor = cursor
                )
            } catch (error: Throwable) {
                Timber.tag("TreeSitter").w(
                    error,
                    "Failed to init folding provider for language=%s",
                    normalizedLanguageName
                )
                runCatching { cursor?.close() }
                runCatching { query?.close() }
                runCatching { parser.close() }
                null
            }
        }
    }
}

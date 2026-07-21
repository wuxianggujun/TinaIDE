package com.wuxianggujun.tinaide.ui.compose.components

/**
 * Internal models used by [FileTreeState].
 */

internal data class CachedDirectoryEntry(
    val absolutePath: String,
    val isDirectory: Boolean,
    val name: String,
    val nameLower: String
)

internal data class SelectedTarget(
    val path: String,
    val isDirectory: Boolean
)

internal data class PendingAppendNode(
    val path: String,
    val level: Int,
    val relativePath: String?,
    val cachedName: String,
    val cachedIsDirectory: Boolean
)

internal data class NodeSliceRange(
    val startIndex: Int,
    val endIndexExclusive: Int
)

internal data class RevealRefreshTarget(
    val path: String,
    val directoryIndex: Int?
)

internal data class RootContext(
    val rootPath: String,
    val rootName: String,
    val artifactsDirPath: String
)

internal class VisibleNodeLookup(
    private val nodes: List<FileTreeNode>
) {
    private val indexByPath = HashMap<String, Int>(nodes.size)

    init {
        nodes.forEachIndexed { index, node ->
            indexByPath[node.absolutePath] = index
        }
    }

    fun containsPath(path: String): Boolean = path in indexByPath

    fun indexOfDirectory(path: String): Int? {
        val index = indexByPath[path] ?: return null
        return index.takeIf { nodes[it].isDirectory }
    }

    fun isDirectoryExpanded(path: String): Boolean? {
        val index = indexByPath[path] ?: return null
        val node = nodes[index]
        return if (node.isDirectory) node.isExpanded else null
    }
}

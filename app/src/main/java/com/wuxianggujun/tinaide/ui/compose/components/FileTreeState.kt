package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import java.io.File
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 文件树状态管理。
 *
 * 设计目标：
 * - 仅维护“可见节点列表 + 展开路径集合”，不再保存整棵 children 树；
 * - 所有结构性变更串行执行，避免 refresh / reveal / toggle 互相踩状态；
 * - 目录项做一次缓存，减少反复展开时的磁盘扫描成本；
 * - 选择态单独维护，避免被异步刷新回写覆盖。
 */
class FileTreeState(
    initialRootPath: String? = null
) {
    private val mutationMutex = Mutex()
    private val selectedTargetRef = AtomicReference<SelectedTarget?>(null)

    @Volatile
    private var rootContextRef: RootContext? = initialRootPath
        ?.takeIf { it.isNotBlank() }
        ?.let { rootContextOf(File(it).absoluteFile) }
    private val expandedPaths = linkedSetOf<String>()
    private val directoryCache = TreeMap<String, List<CachedDirectoryEntry>>()
    private val directoryEntryComparator = compareBy<CachedDirectoryEntry> { !it.isDirectory }
        .thenBy { it.nameLower }

    @Volatile
    private var isAppVisible = true

    @Volatile
    private var hasPendingResumeRefresh = false

    private val _uiState = MutableStateFlow(FileTreeUiState(rootPath = initialRootPath))
    val uiState: StateFlow<FileTreeUiState> = _uiState.asStateFlow()

    val visibleNodes: List<FileTreeNode> get() = _uiState.value.visibleNodes
    val rootPath: String? get() = _uiState.value.rootPath
    val isRefreshing: Boolean get() = _uiState.value.isRefreshing
    val selectedDirectoryPath: String?
        get() = selectedTargetRef.get()?.let { target ->
            if (target.isDirectory) target.path else parentPathOf(target.path)
        }

    enum class FileChangeKind {
        CREATED,
        DELETED,
        MODIFIED,
    }

    data class PendingFileChange(
        val path: String,
        val kind: FileChangeKind
    )

    suspend fun loadRoot(path: String) {
        val rootContext = rootContextOf(File(path).absoluteFile)
        rootContextRef = rootContext
        mutationMutex.withLock {
            expandedPaths.clear()
            directoryCache.clear()
            selectedTargetRef.set(null)
            hasPendingResumeRefresh = false
            rebuildLocked(
                rootContext = rootContext,
                intermediateVisibleNodes = emptyList(),
                isRefreshing = true,
                clearOnMissingRoot = true
            )
        }
    }

    suspend fun refresh() {
        val rootContext = currentRootContextOrNull() ?: return
        mutationMutex.withLock {
            directoryCache.clear()
            hasPendingResumeRefresh = false
            rebuildLocked(
                rootContext = rootContext,
                isRefreshing = true,
                clearOnMissingRoot = true
            )
        }
    }

    suspend fun reveal(file: File, selectTarget: Boolean = true) {
        val rootContext = currentRootContextOrNull() ?: return
        val targetPath = file.absolutePath
        val targetIsDirectory = file.isDirectory

        mutationMutex.withLock {
            val visibleNodeLookup = VisibleNodeLookup(visibleNodes)
            val currentRootPath = rootContext.rootPath
            if (selectTarget) {
                updateSelectionLocked(targetPath, targetIsDirectory, rootContext)
            } else {
                publishUiStateLocked(rootContext = rootContext, visibleNodes = visibleNodes, isRefreshing = false)
            }

            if (!isPathUnderRoot(targetPath, rootContext.rootPath)) return
            if (!targetIsDirectory && visibleNodeLookup.containsPath(targetPath)) return

            var cursorPath: String? = if (targetIsDirectory) targetPath else parentPathOf(targetPath)
            while (cursorPath != null && cursorPath != currentRootPath) {
                expandedPaths.add(cursorPath)
                cursorPath = parentPathOf(cursorPath)
            }

            val updatedNodes = withContext(Dispatchers.IO) {
                revealVisibleNodes(
                    nodes = visibleNodes,
                    targetPath = targetPath,
                    targetIsDirectory = targetIsDirectory,
                    rootContext = rootContext,
                    visibleNodeLookup = visibleNodeLookup
                )
            } ?: emptyList()

            publishUiStateLocked(
                rootContext = rootContext,
                visibleNodes = updatedNodes,
                isRefreshing = false
            )
        }
    }

    suspend fun toggleNode(path: String) {
        val rootContext = currentRootContextOrNull() ?: return

        mutationMutex.withLock {
            val nodeLookup = VisibleNodeLookup(visibleNodes)
            val nodeIndex = nodeLookup.indexOfDirectory(path) ?: return
            val node = visibleNodes[nodeIndex]
            if (node.isExpanded) {
                expandedPaths.remove(path)
                val collapsedNodes = collapseDirectorySubtree(
                    nodes = visibleNodes,
                    directoryPath = path,
                    directoryIndex = nodeIndex
                ) ?: return
                publishUiStateLocked(
                    rootContext = rootContext,
                    visibleNodes = collapsedNodes,
                    isRefreshing = false
                )
                return
            }

            expandedPaths.add(path)
            invalidateSubtreeCaches(path)
            val expandedNodes = withContext(Dispatchers.IO) {
                refreshDirectoryChildren(
                    nodes = visibleNodes,
                    directoryPath = path,
                    rootContext = rootContext,
                    directoryIndex = nodeIndex
                )
            } ?: return

            publishUiStateLocked(
                rootContext = rootContext,
                visibleNodes = expandedNodes,
                isRefreshing = false
            )
        }
    }

    suspend fun handleFileChanges(changes: List<PendingFileChange>) {
        if (changes.isEmpty()) return

        val rootContext = currentRootContextOrNull() ?: return
        val filteredChanges = normalizePendingChanges(
            changes = changes,
            rootPath = rootContext.rootPath
        )
        if (filteredChanges.isEmpty()) return

        if (!isAppVisible) {
            hasPendingResumeRefresh = true
            return
        }

        mutationMutex.withLock {
            if (filteredChanges.any { change ->
                    change.kind == FileChangeKind.DELETED &&
                        change.path == rootContext.rootPath
                }
            ) {
                expandedPaths.clear()
                directoryCache.clear()
                selectedTargetRef.set(null)
                _uiState.value = FileTreeUiState(
                    rootPath = rootContext.rootPath,
                    visibleNodes = emptyList(),
                    selectedPath = null,
                    isRefreshing = false
                )
                return
            }

            val visibleNodeLookup = VisibleNodeLookup(visibleNodes)

            val cacheInvalidationTargets = LinkedHashSet<String>()
            val refreshTargets = ArrayList<String>(filteredChanges.size)
            filteredChanges.forEach { change ->
                val parentDirPath = parentPathOf(change.path)
                    ?.takeIf { isPathUnderRoot(it, rootContext.rootPath) }
                    ?: rootContext.rootPath
                val collapsedVisibleAncestorPath = findNearestVisibleCollapsedDirectoryPath(
                    startPath = parentDirPath,
                    rootPath = rootContext.rootPath,
                    visibleNodeLookup = visibleNodeLookup
                )
                if (collapsedVisibleAncestorPath != null) {
                    cacheInvalidationTargets += collapsedVisibleAncestorPath
                } else {
                    refreshTargets += findNearestRefreshDirectoryPath(
                        startPath = parentDirPath,
                        rootPath = rootContext.rootPath,
                        visibleNodeLookup = visibleNodeLookup
                    )
                }
            }
            val minimizedTargets = minimizeRefreshDirectoryPaths(
                paths = refreshTargets,
                rootPath = rootContext.rootPath
            )
            cacheInvalidationTargets += minimizedTargets
            cacheInvalidationTargets.forEach(::invalidateSubtreeCaches)

            if (minimizedTargets.isEmpty()) {
                publishUiStateLocked(
                    rootContext = rootContext,
                    visibleNodes = visibleNodes,
                    isRefreshing = false
                )
                return
            }

            val updatedNodes = withContext(Dispatchers.IO) {
                var nodes = visibleNodes
                minimizedTargets.forEach { directoryPath ->
                    nodes = refreshDirectoryChildren(
                        nodes = nodes,
                        directoryPath = directoryPath,
                        rootContext = rootContext
                    ) ?: nodes
                }
                nodes
            }

            publishUiStateLocked(
                rootContext = rootContext,
                visibleNodes = updatedNodes,
                isRefreshing = false
            )
        }
    }

    fun setAppVisibility(isVisible: Boolean) {
        isAppVisible = isVisible
    }

    fun consumePendingResumeRefresh(): Boolean {
        val pending = hasPendingResumeRefresh
        hasPendingResumeRefresh = false
        return pending
    }

    fun select(target: File?) {
        selectPath(
            targetPath = target?.absolutePath,
            targetIsDirectory = target?.isDirectory
        )
    }

    fun selectPath(targetPath: String?, targetIsDirectory: Boolean?) {
        val normalized = normalizeSelection(
            targetPath = targetPath,
            rootPath = rootPath,
            targetIsDirectory = targetIsDirectory
        )
        val normalizedPath = normalized?.path
        if (_uiState.value.selectedPath == normalizedPath && selectedTargetRef.get() == normalized) {
            return
        }
        selectedTargetRef.set(normalized)
        _uiState.value = _uiState.value.copy(selectedPath = normalizedPath)
    }

    private suspend fun rebuildLocked(
        rootContext: RootContext,
        intermediateVisibleNodes: List<FileTreeNode> = visibleNodes,
        isRefreshing: Boolean,
        clearOnMissingRoot: Boolean
    ) {
        publishUiStateLocked(
            rootContext = rootContext,
            visibleNodes = intermediateVisibleNodes,
            isRefreshing = isRefreshing
        )

        val nodes = withContext(Dispatchers.IO) {
            buildVisibleNodes(rootContext)
        }

        if (nodes == null) {
            if (clearOnMissingRoot) {
                expandedPaths.clear()
                directoryCache.clear()
                selectedTargetRef.set(null)
                _uiState.value = FileTreeUiState(
                    rootPath = rootContext.rootPath,
                    visibleNodes = emptyList(),
                    selectedPath = null,
                    isRefreshing = false
                )
            } else {
                publishUiStateLocked(rootContext = rootContext, visibleNodes = emptyList(), isRefreshing = false)
            }
            return
        }

        publishUiStateLocked(
            rootContext = rootContext,
            visibleNodes = nodes,
            isRefreshing = false
        )
    }

    private fun publishUiStateLocked(
        rootContext: RootContext,
        visibleNodes: List<FileTreeNode>,
        isRefreshing: Boolean
    ) {
        val sanitizedVisibleNodes = sanitizeVisibleNodes(visibleNodes)
        val currentSelection = selectedTargetRef.get()
        val normalizedSelection = normalizeSelection(
            targetPath = currentSelection?.path,
            rootPath = rootContext.rootPath,
            targetIsDirectory = currentSelection?.isDirectory
        )
        val nextRootPath = rootContext.rootPath
        val nextSelectedPath = normalizedSelection?.path
        val currentState = _uiState.value
        if (
            currentState.rootPath == nextRootPath &&
            currentState.visibleNodes === sanitizedVisibleNodes &&
            currentState.selectedPath == nextSelectedPath &&
            currentState.isRefreshing == isRefreshing
        ) {
            selectedTargetRef.set(normalizedSelection)
            return
        }

        selectedTargetRef.set(normalizedSelection)
        _uiState.value = FileTreeUiState(
            rootPath = nextRootPath,
            visibleNodes = sanitizedVisibleNodes,
            selectedPath = nextSelectedPath,
            isRefreshing = isRefreshing
        )
    }

    /**
     * 防御性去重：如果某次增量刷新错误地拼出了重复路径，
     * 直接丢弃后续重复节点及其整段子树，避免把损坏状态发布给 Compose。
     */
    private fun sanitizeVisibleNodes(nodes: List<FileTreeNode>): List<FileTreeNode> {
        if (nodes.size < 2) return nodes

        val seenPaths = HashSet<String>(nodes.size)
        var sanitized: ArrayList<FileTreeNode>? = null
        var cursor = 0
        while (cursor < nodes.size) {
            val node = nodes[cursor]
            if (seenPaths.add(node.absolutePath)) {
                sanitized?.add(node)
                cursor++
                continue
            }

            if (sanitized == null) {
                sanitized = ArrayList(nodes.size - 1)
                sanitized.addAll(nodes.subList(0, cursor))
            }
            cursor = findDescendantsEndIndex(nodes, cursor, node.level)
        }
        return sanitized ?: nodes
    }

    private fun updateSelectionLocked(
        targetPath: String?,
        targetIsDirectory: Boolean?,
        rootContext: RootContext
    ) {
        val normalizedSelection = normalizeSelection(
            targetPath = targetPath,
            rootPath = rootContext.rootPath,
            targetIsDirectory = targetIsDirectory
        )
        val normalizedPath = normalizedSelection?.path
        if (_uiState.value.selectedPath == normalizedPath && selectedTargetRef.get() == normalizedSelection) {
            return
        }
        selectedTargetRef.set(normalizedSelection)
        _uiState.value = _uiState.value.copy(selectedPath = normalizedPath)
    }

    private fun buildVisibleNodes(rootContext: RootContext): List<FileTreeNode>? {
        val rootDir = File(rootContext.rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return null

        val visibleNodes = ArrayList<FileTreeNode>()
        appendSubtree(
            path = rootContext.rootPath,
            rootContext = rootContext,
            level = 0,
            relativePath = null,
            visibleNodes = visibleNodes,
            cachedName = rootContext.rootName,
            cachedIsDirectory = true
        )
        return visibleNodes
    }

    private fun revealVisibleNodes(
        nodes: List<FileTreeNode>,
        targetPath: String,
        targetIsDirectory: Boolean,
        rootContext: RootContext,
        visibleNodeLookup: VisibleNodeLookup
    ): List<FileTreeNode>? {
        val chain = buildRevealDirectoryPathChain(
            targetPath = targetPath,
            targetIsDirectory = targetIsDirectory,
            rootPath = rootContext.rootPath
        )
        if (chain.isEmpty()) {
            invalidateSubtreeCaches(rootContext.rootPath)
            return refreshDirectoryChildren(
                nodes = nodes,
                directoryPath = rootContext.rootPath,
                rootContext = rootContext
            )
                ?: buildVisibleNodes(rootContext)
        }

        val refreshTarget = determineRevealRefreshTarget(
            nodes = nodes,
            chain = chain,
            rootContext = rootContext,
            visibleNodeLookup = visibleNodeLookup
        )
        invalidateSubtreeCaches(refreshTarget.path)
        return refreshDirectoryChildren(
            nodes = nodes,
            directoryPath = refreshTarget.path,
            rootContext = rootContext,
            directoryIndex = refreshTarget.directoryIndex
        ) ?: nodes
    }

    private fun buildRevealDirectoryPathChain(
        targetPath: String,
        targetIsDirectory: Boolean,
        rootPath: String
    ): List<String> {
        val chain = ArrayList<String>()
        var cursorPath: String? = if (targetIsDirectory) targetPath else parentPathOf(targetPath)
        while (cursorPath != null && cursorPath != rootPath) {
            chain += cursorPath
            cursorPath = parentPathOf(cursorPath)
        }
        chain.reverse()
        return chain
    }

    private fun determineRevealRefreshTarget(
        nodes: List<FileTreeNode>,
        chain: List<String>,
        rootContext: RootContext,
        visibleNodeLookup: VisibleNodeLookup
    ): RevealRefreshTarget {
        if (chain.isEmpty()) {
            return RevealRefreshTarget(
                path = rootContext.rootPath,
                directoryIndex = visibleNodeLookup.indexOfDirectory(rootContext.rootPath)
            )
        }

        var parentPath = rootContext.rootPath
        chain.forEach { directoryPath ->
            val nodeIndex = visibleNodeLookup.indexOfDirectory(directoryPath)
                ?: return RevealRefreshTarget(path = parentPath, directoryIndex = visibleNodeLookup.indexOfDirectory(parentPath))
            val node = nodes[nodeIndex]
            if (!node.isExpanded) {
                return RevealRefreshTarget(path = node.absolutePath, directoryIndex = nodeIndex)
            }
            parentPath = node.absolutePath
        }
        val deepestPath = chain.last()
        return RevealRefreshTarget(
            path = deepestPath,
            directoryIndex = visibleNodeLookup.indexOfDirectory(deepestPath)
        )
    }

    private fun refreshDirectoryChildren(
        nodes: List<FileTreeNode>,
        directoryPath: String,
        rootContext: RootContext,
        directoryIndex: Int? = null
    ): List<FileTreeNode>? {
        val index = directoryIndex ?: VisibleNodeLookup(nodes).indexOfDirectory(directoryPath) ?: return null

        val currentNode = nodes[index]
        val descendantsEnd = findDescendantsEndIndex(nodes, index, currentNode.level)
        val sourceSubtreeHasDuplicates = hasDuplicateNodePaths(
            nodes = nodes,
            start = index,
            endExclusive = descendantsEnd
        )
        val refreshedNode = reuseNodeIfEquivalent(
            existingNode = currentNode,
            candidate = createNode(
                absolutePath = currentNode.absolutePath,
                rootContext = rootContext,
                level = currentNode.level,
                relativePath = currentNode.relativePath,
                cachedName = currentNode.name,
                cachedIsDirectory = currentNode.isDirectory
            )
        )

        val children = if (refreshedNode.isExpanded) {
            if (sourceSubtreeHasDuplicates) {
                buildChildSubtree(
                    parentDirPath = currentNode.absolutePath,
                    rootContext = rootContext,
                    level = currentNode.level + 1,
                    parentRelativePath = currentNode.relativePath,
                )
            } else {
                buildRefreshedChildSubtree(
                    nodes = nodes,
                    subtreeStart = index + 1,
                    subtreeEnd = descendantsEnd,
                    parentDirPath = currentNode.absolutePath,
                    rootContext = rootContext,
                    parentLevel = currentNode.level,
                    parentRelativePath = currentNode.relativePath,
                )
            }
        } else {
            emptyList()
        }

        if (
            refreshedNode === currentNode &&
            hasIdenticalNodeRange(
                sourceNodes = nodes,
                sourceStart = index + 1,
                sourceEndExclusive = descendantsEnd,
                candidates = children
            )
        ) {
            return nodes
        }

        val replacementNodes = if (refreshedNode.isExpanded) {
            ArrayList<FileTreeNode>(children.size + 1).apply {
                add(refreshedNode)
                addAll(children)
            }
        } else {
            listOf(refreshedNode)
        }
        return rebuildNodeRange(
            nodes = nodes,
            replaceStart = index,
            replaceEndExclusive = descendantsEnd,
            replacementNodes = replacementNodes
        )
    }

    private fun buildRefreshedChildSubtree(
        nodes: List<FileTreeNode>,
        subtreeStart: Int,
        subtreeEnd: Int,
        parentDirPath: String,
        rootContext: RootContext,
        parentLevel: Int,
        parentRelativePath: String?,
    ): List<FileTreeNode> {
        val existingSlices = collectImmediateChildSlices(
            nodes = nodes,
            subtreeStart = subtreeStart,
            subtreeEnd = subtreeEnd,
            parentLevel = parentLevel
        )
        val entries = listDirectoryEntries(parentDirPath)
        val childNodes = ArrayList<FileTreeNode>((subtreeEnd - subtreeStart).coerceAtLeast(entries.size))
        val emittedChildPaths = HashSet<String>(entries.size)
        entries.forEach { entry ->
            if (!emittedChildPaths.add(entry.absolutePath)) {
                return@forEach
            }
            val childRelativePath = buildChildRelativePath(parentRelativePath, entry.name)
            val existingSlice = existingSlices[entry.absolutePath]
            val existingNode = existingSlice?.let { nodes[it.startIndex] }
            val childNode = reuseNodeIfEquivalent(
                existingNode = existingNode,
                candidate = createNode(
                    absolutePath = entry.absolutePath,
                    rootContext = rootContext,
                    level = parentLevel + 1,
                    relativePath = childRelativePath,
                    cachedName = entry.name,
                    cachedIsDirectory = entry.isDirectory
                )
            )

            if (existingSlice != null && childNode === existingNode) {
                appendNodeSlice(
                    sourceNodes = nodes,
                    slice = existingSlice,
                    target = childNodes
                )
                return@forEach
            }

            childNodes.add(childNode)

            if (!childNode.isDirectory || !childNode.isExpanded) return@forEach

            if (existingSlice != null) {
                appendExistingChildSlice(
                    sourceNodes = nodes,
                    slice = existingSlice,
                    target = childNodes
                )
            } else {
                childNodes.addAll(
                    buildChildSubtree(
                        parentDirPath = entry.absolutePath,
                        rootContext = rootContext,
                        level = parentLevel + 2,
                        parentRelativePath = childRelativePath,
                    )
                )
            }
        }
        return childNodes
    }

    private fun collectImmediateChildSlices(
        nodes: List<FileTreeNode>,
        subtreeStart: Int,
        subtreeEnd: Int,
        parentLevel: Int
    ): Map<String, NodeSliceRange> {
        val slices = LinkedHashMap<String, NodeSliceRange>()
        var cursor = subtreeStart
        while (cursor < subtreeEnd) {
            val childNode = nodes[cursor]
            val childEnd = findDescendantsEndIndex(nodes, cursor, childNode.level)
                .coerceAtMost(subtreeEnd)
            if (childNode.level == parentLevel + 1) {
                slices[childNode.absolutePath] = NodeSliceRange(
                    startIndex = cursor,
                    endIndexExclusive = childEnd
                )
            }
            cursor = childEnd
        }
        return slices
    }

    private fun appendExistingChildSlice(
        sourceNodes: List<FileTreeNode>,
        slice: NodeSliceRange,
        target: MutableList<FileTreeNode>
    ) {
        val descendantsStart = slice.startIndex + 1
        if (descendantsStart >= slice.endIndexExclusive) return
        target.addAll(sourceNodes.subList(descendantsStart, slice.endIndexExclusive))
    }

    private fun appendNodeSlice(
        sourceNodes: List<FileTreeNode>,
        slice: NodeSliceRange,
        target: MutableList<FileTreeNode>
    ) {
        target.addAll(sourceNodes.subList(slice.startIndex, slice.endIndexExclusive))
    }

    private fun buildChildSubtree(
        parentDirPath: String,
        rootContext: RootContext,
        level: Int,
        parentRelativePath: String?,
    ): List<FileTreeNode> {
        val entries = listDirectoryEntries(parentDirPath)
        val childNodes = ArrayList<FileTreeNode>(entries.size)
        entries.forEach { entry ->
            appendSubtree(
                path = entry.absolutePath,
                rootContext = rootContext,
                level = level,
                relativePath = buildChildRelativePath(parentRelativePath, entry.name),
                visibleNodes = childNodes,
                cachedName = entry.name,
                cachedIsDirectory = entry.isDirectory
            )
        }
        return childNodes
    }

    private fun collapseDirectorySubtree(
        nodes: List<FileTreeNode>,
        directoryPath: String,
        directoryIndex: Int? = null
    ): List<FileTreeNode>? {
        val index = directoryIndex ?: VisibleNodeLookup(nodes).indexOfDirectory(directoryPath) ?: return null

        val currentNode = nodes[index]
        val collapsedNode = currentNode.copy(isExpanded = false)
        if (collapsedNode == currentNode) return nodes

        val descendantsEnd = findDescendantsEndIndex(nodes, index, currentNode.level)
        return rebuildNodeRange(
            nodes = nodes,
            replaceStart = index,
            replaceEndExclusive = descendantsEnd,
            replacementNodes = listOf(collapsedNode)
        )
    }

    private fun rebuildNodeRange(
        nodes: List<FileTreeNode>,
        replaceStart: Int,
        replaceEndExclusive: Int,
        replacementNodes: List<FileTreeNode>
    ): List<FileTreeNode> {
        val prefixSize = replaceStart
        val suffixSize = nodes.size - replaceEndExclusive
        val rebuiltNodes = ArrayList<FileTreeNode>(prefixSize + replacementNodes.size + suffixSize)
        if (prefixSize > 0) {
            rebuiltNodes.addAll(nodes.subList(0, replaceStart))
        }
        rebuiltNodes.addAll(replacementNodes)
        if (suffixSize > 0) {
            rebuiltNodes.addAll(nodes.subList(replaceEndExclusive, nodes.size))
        }
        return rebuiltNodes
    }

    private fun appendSubtree(
        path: String,
        rootContext: RootContext,
        level: Int,
        relativePath: String?,
        visibleNodes: MutableList<FileTreeNode>,
        cachedName: String,
        cachedIsDirectory: Boolean
    ) {
        val pending = ArrayDeque<PendingAppendNode>()
        pending.addLast(
            PendingAppendNode(
                path = path,
                level = level,
                relativePath = relativePath,
                cachedName = cachedName,
                cachedIsDirectory = cachedIsDirectory
            )
        )

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val node = createNode(
                absolutePath = current.path,
                rootContext = rootContext,
                level = current.level,
                relativePath = current.relativePath,
                cachedName = current.cachedName,
                cachedIsDirectory = current.cachedIsDirectory
            )
            visibleNodes.add(node)

            if (!node.isDirectory || !node.isExpanded) continue

            val entries = listDirectoryEntries(current.path)
            for (index in entries.lastIndex downTo 0) {
                val entry = entries[index]
                pending.addLast(
                    PendingAppendNode(
                        path = entry.absolutePath,
                        level = current.level + 1,
                        relativePath = buildChildRelativePath(current.relativePath, entry.name),
                        cachedName = entry.name,
                        cachedIsDirectory = entry.isDirectory
                    )
                )
            }
        }
    }

    private fun createNode(
        absolutePath: String,
        rootContext: RootContext,
        level: Int,
        relativePath: String?,
        cachedName: String,
        cachedIsDirectory: Boolean
    ): FileTreeNode {
        val isDirectory = cachedIsDirectory
        return FileTreeNode(
            absolutePath = absolutePath,
            name = cachedName,
            relativePath = relativePath,
            level = level,
            isDirectory = isDirectory,
            isExpanded = isDirectory && (level == 0 || expandedPaths.contains(absolutePath)),
            isArtifactsDirectory = absolutePath == rootContext.artifactsDirPath
        )
    }

    private fun reuseNodeIfEquivalent(
        existingNode: FileTreeNode?,
        candidate: FileTreeNode
    ): FileTreeNode = if (existingNode != null && existingNode == candidate) existingNode else candidate

    private fun listDirectoryEntries(dirPath: String): List<CachedDirectoryEntry> {
        directoryCache[dirPath]?.let { return it }

        val children = File(dirPath).listFiles().orEmpty()
        val entries = ArrayList<CachedDirectoryEntry>(children.size)
        val seenPaths = HashSet<String>(children.size)
        children.forEach { child ->
            val absolutePath = child.absolutePath
            if (!seenPaths.add(absolutePath)) return@forEach
            entries += CachedDirectoryEntry(
                absolutePath = absolutePath,
                isDirectory = child.isDirectory,
                name = child.name,
                nameLower = child.name.lowercase()
            )
        }
        if (entries.size > 1) {
            entries.sortWith(directoryEntryComparator)
        }

        directoryCache[dirPath] = entries
        return entries
    }

    private fun buildChildRelativePath(parentRelativePath: String?, childName: String): String = parentRelativePath?.let { "$it/$childName" } ?: childName

    private fun findDescendantsEndIndex(
        nodes: List<FileTreeNode>,
        index: Int,
        parentLevel: Int
    ): Int {
        var cursor = index + 1
        while (cursor < nodes.size && nodes[cursor].level > parentLevel) {
            cursor++
        }
        return cursor
    }

    private fun hasIdenticalNodeRange(
        sourceNodes: List<FileTreeNode>,
        sourceStart: Int,
        sourceEndExclusive: Int,
        candidates: List<FileTreeNode>
    ): Boolean {
        val rangeLength = sourceEndExclusive - sourceStart
        if (rangeLength != candidates.size) return false
        for (offset in 0 until rangeLength) {
            if (sourceNodes[sourceStart + offset] !== candidates[offset]) {
                return false
            }
        }
        return true
    }

    private fun hasDuplicateNodePaths(
        nodes: List<FileTreeNode>,
        start: Int,
        endExclusive: Int
    ): Boolean {
        if (endExclusive - start < 2) return false

        val seenPaths = HashSet<String>(endExclusive - start)
        for (index in start until endExclusive) {
            if (!seenPaths.add(nodes[index].absolutePath)) {
                return true
            }
        }
        return false
    }

    private fun normalizePendingChanges(
        changes: List<PendingFileChange>,
        rootPath: String
    ): List<PendingFileChange> {
        val merged = LinkedHashMap<String, PendingFileChange>()
        changes.forEach { change ->
            if (change.kind == FileChangeKind.MODIFIED) return@forEach

            val normalizedPath = File(change.path).absolutePath
            if (!isPathUnderRoot(normalizedPath, rootPath)) return@forEach

            val existing = merged[normalizedPath]
            val normalizedChange = if (existing == null || existing.kind == change.kind) {
                PendingFileChange(path = normalizedPath, kind = change.kind)
            } else {
                PendingFileChange(
                    path = normalizedPath,
                    kind = if (File(normalizedPath).exists()) {
                        FileChangeKind.CREATED
                    } else {
                        FileChangeKind.DELETED
                    }
                )
            }
            merged[normalizedPath] = normalizedChange
        }
        return minimizePendingChanges(merged.values.toList())
    }

    private fun minimizePendingChanges(
        changes: List<PendingFileChange>
    ): List<PendingFileChange> {
        if (changes.size < 2) return changes

        val sortedChanges = changes.sortedWith(
            compareBy<PendingFileChange> { absolutePathDepth(it.path) }
                .thenBy { it.path }
        )
        val selectedPaths = LinkedHashSet<String>()
        val minimized = ArrayList<PendingFileChange>(sortedChanges.size)
        sortedChanges.forEach { change ->
            val path = change.path
            val coveredByAncestor = hasAncestorPath(
                path = path,
                selectedPaths = selectedPaths
            )
            if (!coveredByAncestor) {
                selectedPaths += path
                minimized += change
            }
        }
        return minimized
    }

    private fun findNearestRefreshDirectoryPath(
        startPath: String,
        rootPath: String,
        visibleNodeLookup: VisibleNodeLookup
    ): String {
        val normalizedRootPath = normalizeDirectoryPath(rootPath)
        var cursorPath = startPath
        while (true) {
            if (cursorPath == normalizedRootPath) return normalizedRootPath

            if (visibleNodeLookup.isDirectoryExpanded(cursorPath) == true) {
                return cursorPath
            }

            val parentPath = parentPathOf(cursorPath) ?: return normalizedRootPath
            if (!isPathUnderRoot(parentPath, normalizedRootPath)) return normalizedRootPath
            cursorPath = parentPath
        }
    }

    private fun findNearestVisibleCollapsedDirectoryPath(
        startPath: String,
        rootPath: String,
        visibleNodeLookup: VisibleNodeLookup
    ): String? {
        val normalizedRootPath = normalizeDirectoryPath(rootPath)
        var cursorPath = startPath
        while (true) {
            val isExpanded = visibleNodeLookup.isDirectoryExpanded(cursorPath)
            if (isExpanded != null) {
                return if (isExpanded) null else cursorPath
            }
            if (cursorPath == normalizedRootPath) return null

            val parentPath = parentPathOf(cursorPath) ?: return null
            if (!isPathUnderRoot(parentPath, normalizedRootPath)) return null
            cursorPath = parentPath
        }
    }

    private fun minimizeRefreshDirectoryPaths(
        paths: List<String>,
        rootPath: String
    ): List<String> {
        val sortedPaths = paths
            .map { it.trimEnd(File.separatorChar) }
            .distinct()
            .sortedBy { pathDepth(it, rootPath) }

        val minimized = LinkedHashSet<String>()
        sortedPaths.forEach { path ->
            val coveredByAncestor = hasSelectedAncestorPath(
                path = path,
                rootPath = rootPath,
                selectedPaths = minimized
            )
            if (!coveredByAncestor) {
                minimized += path
            }
        }
        return minimized.toList()
    }

    private fun hasSelectedAncestorPath(
        path: String,
        rootPath: String,
        selectedPaths: Set<String>
    ): Boolean {
        val normalizedRootPath = normalizeDirectoryPath(rootPath)
        var cursorPath: String? = path
        while (cursorPath != null) {
            if (cursorPath in selectedPaths) return true
            if (cursorPath == normalizedRootPath) return false

            val parentPath = parentPathOf(cursorPath)
                ?.takeIf { isPathUnderRoot(it, normalizedRootPath) }
            cursorPath = parentPath
        }
        return false
    }

    private fun hasAncestorPath(
        path: String,
        selectedPaths: Set<String>
    ): Boolean {
        var cursorPath: String? = path
        while (cursorPath != null) {
            if (cursorPath in selectedPaths) return true
            cursorPath = parentPathOf(cursorPath)
        }
        return false
    }

    private fun pathDepth(path: String, rootPath: String): Int {
        val normalizedRootPath = normalizeDirectoryPath(rootPath)
        if (path == normalizedRootPath) return 0
        return path.removePrefix(normalizedRootPath)
            .count { it == File.separatorChar }
    }

    private fun absolutePathDepth(path: String): Int = path.count { it == File.separatorChar }

    private fun invalidateSubtreeCaches(path: String) {
        val normalizedPath = path.trimEnd(File.separatorChar)
        val subtreePrefix = "$normalizedPath${File.separator}"
        val tailKeys = directoryCache.tailMap(normalizedPath, true).keys.iterator()
        while (tailKeys.hasNext()) {
            val cachedPath = tailKeys.next()
            if (cachedPath == normalizedPath || cachedPath.startsWith(subtreePrefix)) {
                tailKeys.remove()
                continue
            }
            break
        }
    }

    private fun normalizeSelection(
        targetPath: String?,
        rootPath: String?,
        targetIsDirectory: Boolean? = null
    ): SelectedTarget? {
        val absolutePath = targetPath?.let { File(it).absolutePath } ?: return null
        val absoluteRootPath = rootPath?.let { File(it).absolutePath }
        if (absoluteRootPath != null && !isPathUnderRoot(absolutePath, absoluteRootPath)) return null

        val normalizedFile = File(absolutePath)
        val isRoot = absolutePath == absoluteRootPath
        if (!isRoot && !normalizedFile.exists()) return null

        return SelectedTarget(
            path = absolutePath,
            isDirectory = isRoot || (targetIsDirectory ?: normalizedFile.isDirectory)
        )
    }

    private fun isPathUnderRoot(targetPath: String, rootPath: String): Boolean {
        val normalizedRootPath = normalizeDirectoryPath(rootPath)
        return targetPath == normalizedRootPath ||
            targetPath.startsWith("$normalizedRootPath${File.separator}")
    }

    private fun normalizeDirectoryPath(path: String): String = path.trimEnd(File.separatorChar)

    private fun parentPathOf(path: String): String? {
        val normalizedPath = normalizeDirectoryPath(path)
        val separatorIndex = normalizedPath.lastIndexOf(File.separatorChar)
        if (separatorIndex <= 0) return null
        return normalizedPath.substring(0, separatorIndex)
    }

    private fun rootContextOf(rootFile: File): RootContext {
        val rootPath = rootFile.absolutePath
        return RootContext(
            rootPath = rootPath,
            rootName = rootFile.name,
            artifactsDirPath = ProjectDirStructure.getArtifactsDir(rootPath).absolutePath
        )
    }

    private fun currentRootContextOrNull(): RootContext? {
        val currentRootPath = rootPath ?: return null
        val cached = rootContextRef
        if (cached != null && cached.rootPath == currentRootPath) {
            return cached
        }
        return rootContextOf(File(currentRootPath).absoluteFile).also {
            rootContextRef = it
        }
    }
}

@Composable
fun rememberFileTreeState(initialRootPath: String? = null): FileTreeState = remember { FileTreeState(initialRootPath) }

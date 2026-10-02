package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.wuxianggujun.tinaide.core.editor.EditorFileSizeLimits
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import com.wuxianggujun.tinaide.utils.FileTypeUtils
import java.io.File
import timber.log.Timber

/**
 * 编辑器标签页管理器
 *
 * 职责：
 * - 管理标签页列表和活动标签索引
 * - 处理标签页的打开、关闭、选择操作
 * - 与 EditorManager 同步标签状态
 * - 管理待关闭标签（未保存文件确认）
 *
 * 从 EditorContainerState 中提取，简化主状态类
 */
class EditorTabManager(
    private val context: android.content.Context,
    private val editorManager: IEditorManager
) {

    // ========== 标签页状态 ==========

    data class RetargetedTab(
        val oldId: String,
        val newId: String,
        val oldFile: File,
        val newFile: File,
        val contentType: ContentType
    )

    private val _tabs = mutableStateListOf<EditorTabState>()

    /**
     * 暴露 SnapshotStateList 以便 Compose 能正确追踪列表元素的变化
     */
    val tabs: SnapshotStateList<EditorTabState> get() = _tabs

    var activeTabIndex by mutableIntStateOf(-1)
        private set

    // ========== 待关闭的 Tab ==========

    var pendingCloseTab by mutableStateOf<EditorTabState?>(null)
        private set

    /**
     * 最近一次打开文件失败信息（由 UI 消费并展示后清空）
     */
    var lastOpenError by mutableStateOf<String?>(null)
        private set

    // ========== 回调 ==========

    /**
     * 标签关闭时的回调，用于清理编辑器缓存等资源
     */
    var onTabClosed: ((tabId: String, contentType: ContentType) -> Unit)? = null

    // ========== 同步方法 ==========

    /**
     * 从 EditorManager 同步标签列表
     */
    fun syncFromManager(managerTabs: List<EditorTab>, activeTabId: String?) {
        val previousActiveTabId = _tabs.getOrNull(activeTabIndex)?.id
        val previousStateById = _tabs.associateBy { it.id }
        val nonManagerTabsInOrder = _tabs.filter { it.contentType != ContentType.CODE && it.contentType != ContentType.JSON }
        val managerTabIds = managerTabs.map { it.id }.toSet()
        _tabs.clear()
        managerTabs.forEach { tab ->
            val contentType = when (FileTypeUtils.getFileType(tab.file)) {
                FileTypeUtils.FileType.JSON -> ContentType.JSON // JSON 默认使用树形查看器（可切换源码视图）
                else -> ContentType.CODE
            }
            val previous = previousStateById[tab.id]
            _tabs.add(
                EditorTabState(
                    id = tab.id,
                    file = tab.file,
                    contentType = contentType,
                    isDirty = previous?.isDirty ?: false,
                    canUndo = previous?.canUndo ?: false,
                    canRedo = previous?.canRedo ?: false
                )
            )
        }
        // 保留非 EditorManager 管理的标签页（图片/十六进制/大文件等）
        nonManagerTabsInOrder
            .filter { it.id !in managerTabIds }
            .forEach { _tabs.add(it) }

        val activeIndexFromManager = activeTabId?.let { id -> _tabs.indexOfFirst { it.id == id } } ?: -1
        val activeIndexFromPrevious = previousActiveTabId?.let { id -> _tabs.indexOfFirst { it.id == id } } ?: -1
        val previousWasManagerTab = previousActiveTabId != null && previousActiveTabId in managerTabIds
        val shouldPreferManagerActive = previousActiveTabId == null || previousWasManagerTab
        applyActiveTabIndex(
            when {
                shouldPreferManagerActive && activeIndexFromManager >= 0 -> activeIndexFromManager
                activeIndexFromPrevious >= 0 -> activeIndexFromPrevious
                activeIndexFromManager >= 0 -> activeIndexFromManager
                _tabs.isNotEmpty() -> 0
                else -> -1
            }
        )
    }

    /**
     * 从 EditorManager 恢复状态
     */
    fun restoreFromManager() {
        val openTabs = editorManager.getOpenTabs()
        val activeId = editorManager.getActiveTabId()
        syncFromManager(openTabs, activeId)
    }

    // ========== 打开文件 ==========

    /**
     * 打开文件，自动判断内容类型
     */
    fun openFile(file: File): Int {
        val contentType = determineContentType(file)
        return openFileWithType(file, contentType)
    }

    /**
     * 以指定类型打开文件
     */
    fun openFileWithType(file: File, contentType: ContentType): Int {
        if (file.isDirectory) {
            val message = Strings.toast_open_with_dir_not_supported.strOr(context)
            lastOpenError = message
            Timber.tag("EditorTabManager").w("Refuse to open directory as file: %s", file.absolutePath)
            return activeTabIndex
        }

        if (file.exists() && !file.canRead()) {
            val message = Strings.toast_open_with_failed.strOr(context, file.name.ifBlank { file.absolutePath })
            lastOpenError = message
            Timber.tag("EditorTabManager").w("Cannot read file: %s", file.absolutePath)
            return activeTabIndex
        }

        if (contentType == ContentType.CODE || contentType == ContentType.JSON) {
            val existingTabIds = _tabs.map { it.id }.toSet()
            val editorTab = runCatching { editorManager.openFile(file) }
                .onFailure { error ->
                    Timber.tag("EditorTabManager").e(error, "openFile failed: %s", file.absolutePath)
                }
                .getOrElse {
                    lastOpenError = Strings.toast_open_with_failed.strOr(
                        context,
                        file.name.ifBlank { file.absolutePath }
                    )
                    return activeTabIndex
                }
            lastOpenError = null
            editorManager.setActiveTab(editorTab.id)
            syncFromManager(
                managerTabs = editorManager.getOpenTabs(),
                activeTabId = editorManager.getActiveTabId()
            )
            if (editorTab.id !in existingTabIds) {
                PluginHostEventDispatcher.emitEditorOpened(
                    tabId = editorTab.id,
                    file = editorTab.file,
                    contentType = contentType.name
                )
            }
            return activeTabIndex
        } else {
            val tabId = "${file.absolutePath}#${contentType.name}"
            val existingIndex = _tabs.indexOfFirst { it.id == tabId }
            if (existingIndex >= 0) {
                applyActiveTabIndex(existingIndex)
                return existingIndex
            }

            val tabState = EditorTabState(
                id = tabId,
                file = file,
                contentType = contentType
            )
            lastOpenError = null
            _tabs.add(tabState)
            applyActiveTabIndex(_tabs.size - 1)
            PluginHostEventDispatcher.emitEditorOpened(
                tabId = tabState.id,
                file = tabState.file,
                contentType = tabState.contentType.name
            )
            return activeTabIndex
        }
    }

    fun consumeLastOpenError(): String? {
        val message = lastOpenError
        lastOpenError = null
        return message
    }

    private fun determineContentType(file: File): ContentType {
        val fileType = FileTypeUtils.getFileType(file)
        if (fileType == FileTypeUtils.FileType.IMAGE) return ContentType.IMAGE
        if (fileType == FileTypeUtils.FileType.BINARY) return ContentType.HEX

        // 对超大文本文件默认使用只读查看器，避免直接加载到 CodeEditor 造成卡顿/内存压力
        if (EditorFileSizeLimits.isLargeTextFile(file)) return ContentType.LARGE_TEXT

        return when (fileType) {
            FileTypeUtils.FileType.JSON -> ContentType.JSON
            FileTypeUtils.FileType.CODE -> ContentType.CODE
            else -> ContentType.CODE
        }
    }

    // ========== 关闭标签页 ==========

    /**
     * 请求关闭标签页（会检查是否有未保存内容）
     */
    fun requestCloseTab(index: Int) {
        if (index < 0 || index >= _tabs.size) return
        val tab = _tabs[index]
        if (tab.isDirty) {
            pendingCloseTab = tab
        } else {
            closeTabInternal(index)
        }
    }

    fun requestCloseActiveTab(): Boolean {
        val activeIndex = activeTabIndex.takeIf { it in _tabs.indices } ?: return false
        requestCloseTab(activeIndex)
        return true
    }

    /**
     * 确认保存后关闭
     */
    fun confirmSaveAndClose(): Boolean {
        val tab = pendingCloseTab ?: return false
        pendingCloseTab = null
        val index = _tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return false
        closeTabInternal(index)
        return true
    }

    /**
     * 确认放弃更改并关闭
     */
    fun confirmDiscardAndClose() {
        val tab = pendingCloseTab ?: return
        val index = _tabs.indexOfFirst { it.id == tab.id }
        if (index >= 0) {
            closeTabInternal(index)
        }
        pendingCloseTab = null
    }

    /**
     * 取消关闭操作
     */
    fun cancelClose() {
        pendingCloseTab = null
    }

    fun closeTabsByIds(tabIds: Set<String>): List<EditorTabState> {
        if (tabIds.isEmpty()) return emptyList()

        pendingCloseTab = pendingCloseTab?.takeUnless { it.id in tabIds }
        val closedTabs = mutableListOf<EditorTabState>()
        _tabs.indices
            .filter { index -> _tabs[index].id in tabIds }
            .reversed()
            .forEach { index ->
                closedTabs += _tabs[index]
                closeTabInternal(index)
            }
        return closedTabs.asReversed()
    }

    fun retargetTabsForMovedPath(oldPath: File, newPath: File): List<RetargetedTab> {
        val retargetedTabs = mutableListOf<RetargetedTab>()
        _tabs.indices.forEach { index ->
            val tab = _tabs[index]
            val newFile = resolveMovedPathForOpenFile(
                openFile = tab.file,
                oldPath = oldPath,
                newPath = newPath
            ) ?: return@forEach
            val newId = resolveRetargetedTabId(tab, newFile)
            _tabs[index] = tab.copy(
                id = newId,
                file = newFile
            )
            retargetedTabs += RetargetedTab(
                oldId = tab.id,
                newId = newId,
                oldFile = tab.file,
                newFile = newFile,
                contentType = tab.contentType
            )
        }
        return retargetedTabs
    }

    /**
     * 内部关闭标签页方法
     */
    private fun closeTabInternal(index: Int) {
        if (index < 0 || index >= _tabs.size) return
        val tab = _tabs[index]

        val wasActive = index == activeTabIndex
        val oldActiveIndex = activeTabIndex
        val willBecomeEmpty = _tabs.size == 1

        val nextActiveIndex = when {
            willBecomeEmpty -> -1
            !wasActive && index < oldActiveIndex -> oldActiveIndex - 1
            !wasActive -> oldActiveIndex
            index >= _tabs.lastIndex -> _tabs.lastIndex - 1
            else -> index
        }

        _tabs.removeAt(index)

        if (tab.contentType == ContentType.CODE || tab.contentType == ContentType.JSON) {
            editorManager.closeFile(EditorTab(tab.id, tab.file))
        }

        onTabClosed?.invoke(tab.id, tab.contentType)
        PluginHostEventDispatcher.emitEditorClosed(
            tabId = tab.id,
            file = tab.file,
            contentType = tab.contentType.name
        )

        applyActiveTabIndex(if (nextActiveIndex in _tabs.indices) nextActiveIndex else _tabs.lastIndex)

        val newActiveTab = _tabs.getOrNull(activeTabIndex)
        if (newActiveTab != null && (newActiveTab.contentType == ContentType.CODE || newActiveTab.contentType == ContentType.JSON)) {
            editorManager.setActiveTab(newActiveTab.id)
        }
    }

    /**
     * 关闭其他标签页
     */
    fun closeOtherTabs(exceptIndex: Int): Boolean {
        if (exceptIndex < 0 || exceptIndex >= _tabs.size) return false
        val tabsToClose = _tabs.indices.filter { it != exceptIndex }.reversed()
        val dirtyTab = tabsToClose
            .asSequence()
            .map { index -> _tabs[index] }
            .firstOrNull { tab -> tab.isDirty }
        if (dirtyTab != null) {
            pendingCloseTab = dirtyTab
            return false
        }
        tabsToClose.forEach { index -> closeTabInternal(index) }
        return true
    }

    fun closeOtherTabsForActiveTab(): Boolean {
        val activeIndex = activeTabIndex.takeIf { it in _tabs.indices } ?: return false
        return closeOtherTabs(activeIndex)
    }

    /**
     * 关闭所有标签页
     */
    fun closeAllTabs(): Boolean {
        val dirtyTab = _tabs.firstOrNull { tab -> tab.isDirty }
        if (dirtyTab != null) {
            pendingCloseTab = dirtyTab
            return false
        }
        val tabsToClose = _tabs.indices.reversed().toList()
        tabsToClose.forEach { index -> closeTabInternal(index) }
        return true
    }

    // ========== 选择标签页 ==========

    /**
     * 选择指定索引的标签页
     */
    fun selectTab(index: Int) {
        if (index in _tabs.indices) {
            applyActiveTabIndex(index)
            val tab = _tabs[index]
            if (tab.contentType == ContentType.CODE || tab.contentType == ContentType.JSON) {
                editorManager.setActiveTab(tab.id)
            }
        }
    }

    // ========== 状态更新 ==========

    /**
     * 更新标签页状态（isDirty, canUndo, canRedo）
     */
    fun updateTabState(tabId: String, isDirty: Boolean, canUndo: Boolean, canRedo: Boolean) {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            val oldTab = _tabs[index]
            val newTab = oldTab.copy(
                isDirty = isDirty,
                canUndo = canUndo,
                canRedo = canRedo
            )
            // 只有状态真正变化时才更新，避免不必要的重组
            if (oldTab.isDirty != isDirty || oldTab.canUndo != canUndo || oldTab.canRedo != canRedo) {
                _tabs[index] = newTab
                if (oldTab.isDirty != isDirty) {
                    PluginHostEventDispatcher.emitEditorDirtyChanged(
                        tabId = newTab.id,
                        file = newTab.file,
                        isDirty = isDirty
                    )
                }
                Timber.tag("EditorTabManager").d("updateTabState: tabId=%s, isDirty=%s, canUndo=%s, canRedo=%s", tabId, isDirty, canUndo, canRedo)
            }
        } else {
            Timber.tag("EditorTabManager").w("updateTabState: tab not found for tabId=%s", tabId)
        }
    }

    // ========== 查询方法 ==========

    /**
     * 获取当前活动标签页
     */
    fun getActiveTab(): EditorTabState? {
        if (activeTabIndex < 0 || activeTabIndex >= _tabs.size) return null
        return _tabs[activeTabIndex]
    }

    /**
     * 根据 ID 查找标签页
     */
    fun findTab(tabId: String): EditorTabState? = _tabs.find { it.id == tabId }

    /**
     * 根据 ID 查找标签页索引
     */
    fun findTabIndex(tabId: String): Int = _tabs.indexOfFirst { it.id == tabId }

    private fun applyActiveTabIndex(index: Int) {
        val normalizedIndex = index.takeIf { it in _tabs.indices } ?: -1
        val oldTabId = _tabs.getOrNull(activeTabIndex)?.id
        activeTabIndex = normalizedIndex
        val activeTab = _tabs.getOrNull(activeTabIndex) ?: return
        if (activeTab.id != oldTabId) {
            PluginHostEventDispatcher.emitEditorActiveChanged(
                tabId = activeTab.id,
                file = activeTab.file,
                contentType = activeTab.contentType.name,
                isDirty = activeTab.isDirty
            )
        }
    }

    // ========== 未保存文件检查 ==========

    /**
     * 检查是否有未保存的更改
     */
    fun hasUnsavedChanges(): Boolean = _tabs.any { it.isDirty }

    /**
     * 获取所有有未保存更改的标签页
     */
    fun getUnsavedTabs(): List<EditorTabState> = _tabs.filter { it.isDirty }

    /**
     * 获取未保存文件的数量
     */
    fun getUnsavedCount(): Int = _tabs.count { it.isDirty }

    private fun resolveRetargetedTabId(tab: EditorTabState, newFile: File): String {
        if (tab.contentType == ContentType.CODE || tab.contentType == ContentType.JSON) {
            return tab.id
        }

        val candidate = "${newFile.absolutePath}#${tab.contentType.name}"
        return if (_tabs.any { it.id == candidate && it.id != tab.id }) tab.id else candidate
    }

    private fun resolveMovedPathForOpenFile(
        openFile: File,
        oldPath: File,
        newPath: File
    ): File? {
        val oldNormalized = normalizeOpenTabLookupPath(oldPath.absolutePath).trimEnd('/')
        if (oldNormalized.isBlank()) return null

        val openNormalized = normalizeOpenTabLookupPath(openFile.absolutePath)
        val compareOld = normalizeOpenTabLookupPathForCompare(oldNormalized)
        val compareOpen = normalizeOpenTabLookupPathForCompare(openNormalized)
        val oldPrefix = "$compareOld/"
        return when {
            compareOpen == compareOld -> newPath
            compareOpen.startsWith(oldPrefix) -> {
                val suffix = openNormalized.substring(oldNormalized.length + 1)
                File(newPath, suffix.replace('/', File.separatorChar))
            }
            else -> null
        }
    }

    private fun normalizeOpenTabLookupPath(path: String): String = path.replace('\\', '/')

    private fun normalizeOpenTabLookupPathForCompare(path: String): String = if (File.separatorChar == '\\') {
        path.lowercase()
    } else {
        path
    }
}

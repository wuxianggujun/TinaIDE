package com.wuxianggujun.tinaide.editor

import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.DocumentSessionState
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface IEditorManager : IEditorTabProvider {
    /**
     * 打开的标签列表（StateFlow）
     *
     * 暴露给 Compose UI 使用，避免 EditorContainerState 维护重复状态
     */
    val tabsFlow: StateFlow<List<EditorTab>>

    /**
     * 当前活动标签 ID（StateFlow）
     *
     * 暴露给 Compose UI 使用，避免 EditorContainerState 维护重复状态
     */
    val activeTabIdFlow: StateFlow<String?>

    fun openFile(file: File, initialViewState: EditorViewState? = null): EditorTab
    fun closeFile(tab: EditorTab)
    fun closeAll(clearPersistentState: Boolean = true)
    fun persistStateSnapshot()
    override fun getOpenTabs(): List<EditorTab>
    override fun getActiveTabId(): String?
    fun setActiveTab(tabId: String?)
    fun retargetOpenTabsForMovedPath(oldPath: File, newPath: File): List<EditorTab>
    fun getSession(tabId: String): DocumentSession?
    fun getSessionState(tabId: String): StateFlow<DocumentSessionState>?
    suspend fun save(tabId: String, reason: SaveReason = SaveReason.MANUAL): SaveResult
    suspend fun saveAll(reason: SaveReason = SaveReason.MANUAL): List<SaveResult>
    fun performUndo(tabId: String)
    fun performRedo(tabId: String)

    // 外部文件修改相关方法
    suspend fun forceOverwrite(tabId: String, reason: SaveReason = SaveReason.MANUAL): SaveResult
    suspend fun reloadFromDisk(tabId: String): Boolean
    fun acknowledgeExternalModification(tabId: String)
}

package com.wuxianggujun.tinaide.core.symbol

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 项目符号索引服务接口
 *
 * 提供项目级符号索引功能，用于全局符号搜索和跳转。
 */
interface IProjectSymbolIndexService {

    /**
     * 索引状态
     */
    val status: StateFlow<SymbolIndexStatus>

    /**
     * 项目打开时触发索引
     */
    fun onProjectOpened(projectRoot: File)

    /**
     * 项目关闭时清理索引
     */
    fun onProjectClosed()

    /**
     * 文件保存时增量更新索引
     */
    fun onFileSaved(file: File, content: String)

    /** A file or subtree was created, imported, or changed outside the editor. */
    fun onFileChanged(file: File)

    /** Remove a file or subtree, including any parse still using its former contents. */
    fun onFileDeleted(file: File)

    fun onFileRenamed(oldFile: File, newFile: File)

    /**
     * 查询全局符号（前缀匹配）
     *
     * @param prefix 搜索前缀
     * @param limit 返回数量限制
     * @return 匹配的符号列表
     */
    fun queryGlobals(prefix: String, limit: Int): List<SymbolInfo>

    /**
     * 模糊查询全局符号
     *
     * @param pattern 搜索模式
     * @param limit 返回数量限制
     * @return 匹配的符号列表（按匹配分数降序排列）
     */
    fun queryGlobalsFuzzy(pattern: String, limit: Int): List<FuzzySymbolMatch>

    /**
     * 清除项目缓存
     */
    fun clearCache()
}

package com.wuxianggujun.tinaide.core.user

/**
 * 收藏插件信息
 */
data class FavoritePlugin(
    val id: String,
    val pluginId: String,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val category: String?,
    val tags: List<String>?,
    val latestVersion: String?,
    val addedAt: String
)

/**
 * 下载历史项
 */
data class DownloadHistoryItem(
    val id: String,
    val itemType: String, // 项目类型（plugin, template, snippet 等）
    val itemId: String, // 项目 ID
    val version: String?,
    val downloadedAt: String,
    val fileSize: Long?
)

/**
 * 收藏列表响应
 */
data class FavoritesResponse(
    val plugins: List<FavoritePlugin>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

/**
 * 下载历史响应
 */
data class DownloadHistoryResponse(
    val items: List<DownloadHistoryItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

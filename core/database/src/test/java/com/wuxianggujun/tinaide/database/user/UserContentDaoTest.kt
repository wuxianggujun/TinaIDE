package com.wuxianggujun.tinaide.database.user

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserContentDaoTest {

    private lateinit var database: UserContentDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            UserContentDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun favoriteDao_shouldReplaceByPrimaryKey() = runBlocking {
        val dao = database.favoriteDao()
        dao.insertFavorite(favorite(id = "fav-1", pluginId = "plugin-a", name = "Old", synced = false))
        dao.insertFavorite(favorite(id = "fav-1", pluginId = "plugin-a", name = "New", synced = true))
        dao.insertFavorite(favorite(id = "fav-2", pluginId = "plugin-b", name = "Unsynced", synced = false))

        assertThat(dao.getFavoritesCount()).isEqualTo(2)
        assertThat(dao.getFavoriteByPluginId("plugin-a")!!.name).isEqualTo("New")
    }

    @Test
    fun downloadHistoryDao_shouldPageAndFilterByTypeInDescendingTimeOrder() = runBlocking {
        val dao = database.downloadHistoryDao()
        dao.insertDownloads(
            listOf(
                download(id = "d1", itemType = "plugin", itemId = "p1", downloadedAt = "2026-01-01T00:00:00Z"),
                download(id = "d2", itemType = "package", itemId = "pkg1", downloadedAt = "2026-01-03T00:00:00Z"),
                download(id = "d3", itemType = "plugin", itemId = "p2", downloadedAt = "2026-01-02T00:00:00Z")
            )
        )

        assertThat(dao.getDownloadsCount()).isEqualTo(3)
        assertThat(dao.getDownloadsPaged(limit = 2, offset = 0).map { it.id })
            .containsExactly("d2", "d3")
            .inOrder()
        assertThat(dao.getDownloadsByTypePaged("plugin", limit = 10, offset = 0).map { it.id })
            .containsExactly("d3", "d1")
            .inOrder()
        assertThat(dao.getDownloadsCountByType("package")).isEqualTo(1)
    }

    private fun favorite(
        id: String,
        pluginId: String,
        name: String,
        synced: Boolean
    ): FavoriteEntity = FavoriteEntity(
        id = id,
        pluginId = pluginId,
        name = name,
        description = null,
        iconUrl = null,
        category = "tool",
        tags = null,
        latestVersion = "1.0.0",
        addedAt = "2026-01-01T00:00:00Z",
        synced = synced
    )

    private fun download(
        id: String,
        itemType: String,
        itemId: String,
        downloadedAt: String
    ): DownloadHistoryEntity = DownloadHistoryEntity(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = "1.0.0",
        fileSize = 1024L,
        downloadedAt = downloadedAt,
        synced = true
    )
}

package com.wuxianggujun.tinaide.editor.bookmark.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.wuxianggujun.tinaide.editor.session.db.EditorFileStateEntity
import com.wuxianggujun.tinaide.editor.session.db.EditorSessionDao
import com.wuxianggujun.tinaide.editor.session.db.EditorSessionEntity

/**
 * 编辑器数据库（书签 + 会话）
 */
@Database(
    entities = [
        BookmarkEntity::class,
        EditorSessionEntity::class,
        EditorFileStateEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class BookmarkDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun editorSessionDao(): EditorSessionDao

    companion object {
        @Volatile
        private var instanceRef: BookmarkDatabase? = null

        fun getInstance(context: Context): BookmarkDatabase = instanceRef ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                BookmarkDatabase::class.java,
                "tinaide_editor.db"
            )
                .build()
            instanceRef = instance
            instance
        }

        @Synchronized
        fun closeInstance() {
            instanceRef?.close()
            instanceRef = null
        }
    }
}

package com.wuxianggujun.tinaide.terminal.persistence.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 终端数据库
 */
@Database(
    entities = [
        TerminalStateEntity::class,
        TerminalSessionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TerminalDatabase : RoomDatabase() {

    abstract fun terminalStateDao(): TerminalStateDao

    companion object {
        @Volatile
        private var instanceRef: TerminalDatabase? = null

        fun getInstance(context: Context): TerminalDatabase = instanceRef ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                TerminalDatabase::class.java,
                "tinaide_terminal.db"
            )
                .build()
            instanceRef = instance
            instance
        }
    }
}

package com.atpro.db

import android.content.Context
import androidx.room.*
import com.atpro.db.dao.*
import com.atpro.db.entity.*

// ═══════════════════════════════════════════════════════════════
//  AtProDatabase — Room SQLite database
//  File: /data/data/com.atpro/databases/atpro.db
//  Thay thế hoàn toàn Supabase — không cần internet để đọc/ghi data
// ═══════════════════════════════════════════════════════════════

@Database(
    entities = [
        AccountEntity::class,
        SessionEntity::class,
        FarmLogEntity::class,
        ConfigEntity::class,
    ],
    version  = 1,
    exportSchema = false,
)
abstract class AtProDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun sessionDao(): SessionDao
    abstract fun logDao():     LogDao
    abstract fun configDao():  ConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AtProDatabase? = null

        fun getInstance(context: Context): AtProDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AtProDatabase::class.java,
                    "atpro.db",
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Seed mặc định nếu cần
                    }
                })
                .build()
                .also { INSTANCE = it }
            }
    }
}

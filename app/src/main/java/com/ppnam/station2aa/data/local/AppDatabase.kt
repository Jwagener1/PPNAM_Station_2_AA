package com.ppnam.station2aa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [OfflineQueueEntity::class, BomCacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offlineQueueDao(): OfflineQueueDao
    abstract fun bomCacheDao(): BomCacheDao
}

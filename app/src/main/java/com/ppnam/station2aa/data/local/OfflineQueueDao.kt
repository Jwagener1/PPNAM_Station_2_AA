package com.ppnam.station2aa.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OfflineQueueEntity)

    @Query("SELECT * FROM offline_queue WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPending(): List<OfflineQueueEntity>

    @Query("SELECT COUNT(*) FROM offline_queue WHERE status = 'pending'")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE offline_queue SET status = 'sent' WHERE id = :id")
    suspend fun markSent(id: String)

    @Query("UPDATE offline_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: String)

    @Query("UPDATE offline_queue SET status = 'failed' WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("SELECT * FROM offline_queue WHERE status = 'failed' ORDER BY createdAt DESC")
    fun getFailedAsFlow(): Flow<List<OfflineQueueEntity>>
}

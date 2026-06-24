package com.ppnam.station2aa.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_queue")
data class OfflineQueueEntity(
    @PrimaryKey val id: String,
    val action: String,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val status: String = "pending"
)

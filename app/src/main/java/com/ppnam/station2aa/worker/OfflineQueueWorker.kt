package com.ppnam.station2aa.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ppnam.station2aa.data.local.OfflineQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class OfflineQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val offlineQueueRepository: OfflineQueueRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            offlineQueueRepository.drainQueue()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

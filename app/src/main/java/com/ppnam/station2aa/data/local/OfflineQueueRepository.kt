package com.ppnam.station2aa.data.local

import com.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.ppnam.station2aa.data.mqtt.MqttResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineQueueRepository @Inject constructor(
    private val dao: OfflineQueueDao,
    private val mqttRepository: MqttRepositoryImpl
) {
    fun pendingCount(): Flow<Int> = dao.pendingCount()

    suspend fun drainQueue() {
        val pending = dao.getPending()
        for (item in pending) {
            val result = mqttRepository.sendWithTimeout(item.action, item.payload, timeoutMs = 10_000L)
            when (result) {
                is MqttResult.Success -> dao.markSent(item.id)
                is MqttResult.Queued -> {
                    dao.incrementRetry(item.id)
                    if (item.retryCount + 1 >= 10) dao.markFailed(item.id)
                }
                is MqttResult.Error -> {
                    dao.incrementRetry(item.id)
                    if (item.retryCount + 1 >= 10) dao.markFailed(item.id)
                }
            }
        }
    }
}

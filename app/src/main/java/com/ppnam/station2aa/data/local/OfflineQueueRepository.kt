package com.ppnam.station2aa.data.local

import com.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineQueueRepository @Inject constructor(
    private val dao: OfflineQueueDao,
    private val mqttRepository: MqttRepositoryImpl
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            mqttRepository.connectionState
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { drainQueue() }
        }
    }

    fun pendingCount(): Flow<Int> = dao.pendingCount()

    suspend fun drainQueue() {
        if (mqttRepository.connectionState.value != MqttConnectionState.CONNECTED) return
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

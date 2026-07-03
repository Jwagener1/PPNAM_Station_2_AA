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
            // One-time cleanup: until now, the only producer of offline_queue rows was
            // the legacy send()/sendWithTimeout() path, which publishes to a
            // {station}/request topic the backend has never subscribed to (see
            // MqttRepositoryImpl.sendWithTimeout). Every row here is permanently
            // undeliverable — clear the backlog so it stops being replayed on every
            // reconnect. sendWithTimeout no longer enqueues on failure, so this table
            // should stay empty going forward. Revisit if typed-contract offline
            // queuing (MqttRepositoryImpl.enqueue) is ever wired up.
            dao.deletePending()
            mqttRepository.connectionState
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { drainQueue() }
        }
    }

    fun pendingCount(): Flow<Int> = dao.pendingCount()

    suspend fun deletePending() = dao.deletePending()

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

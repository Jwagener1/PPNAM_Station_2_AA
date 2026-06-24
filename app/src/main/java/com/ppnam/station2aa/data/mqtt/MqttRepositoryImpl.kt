package com.ppnam.station2aa.data.mqtt

import android.content.Context
import android.provider.Settings
import com.google.gson.Gson
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.local.OfflineQueueEntity
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttRepositoryImpl internal constructor(
    private val mqttClient: Mqtt5AsyncClient,
    private val offlineQueueDao: OfflineQueueDao,
    private val deviceId: String
) : MqttRepository {

    /** Production constructor: resolved via Hilt dependency injection. */
    @Inject
    constructor(
        mqttClient: Mqtt5AsyncClient,
        offlineQueueDao: OfflineQueueDao,
        @ApplicationContext context: Context
    ) : this(
        mqttClient,
        offlineQueueDao,
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    )

    private val gson = Gson()

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingResponses = MutableSharedFlow<MqttResponseMessage>(extraBufferCapacity = 64)

    override suspend fun connect() {
        _connectionState.value = MqttConnectionState.RECONNECTING
        try {
            mqttClient.connectWith()
                .cleanStart(false)
                .keepAlive(30)
                .send()
                .await()

            mqttClient.subscribeWith()
                .topicFilter(MqttTopics.response(deviceId))
                .callback { publish -> handleIncoming(publish.payloadAsBytes) }
                .send()
                .await()

            _connectionState.value = MqttConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = MqttConnectionState.DISCONNECTED
        }
    }

    override fun disconnect() {
        mqttClient.disconnect()
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }

    override suspend fun send(action: String, dataJson: String): MqttResult =
        sendWithTimeout(action, dataJson, timeoutMs = 10_000L)

    internal suspend fun sendWithTimeout(action: String, dataJson: String, timeoutMs: Long): MqttResult {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return queue(action, dataJson)
        }

        val correlationId = UUID.randomUUID().toString()
        val request = MqttRequest(correlationId, deviceId, action, dataJson)
        val payload = gson.toJson(request).toByteArray()

        return try {
            withTimeout(timeoutMs) {
                val responseDeferred = async {
                    _incomingResponses
                        .filter { it.correlationId == correlationId }
                        .first()
                }
                mqttClient.publishWith()
                    .topic(MqttTopics.REQUEST)
                    .payload(payload)
                    .send()
                    .await()
                val response = responseDeferred.await()
                if (response.success) {
                    MqttResult.Success(response.data ?: "{}")
                } else {
                    MqttResult.Error(response.error ?: "Unknown error")
                }
            }
        } catch (e: TimeoutCancellationException) {
            queue(action, dataJson)
        } catch (e: Exception) {
            queue(action, dataJson)
        }
    }

    private suspend fun queue(action: String, dataJson: String): MqttResult.Queued {
        val correlationId = UUID.randomUUID().toString()
        offlineQueueDao.insert(
            OfflineQueueEntity(
                id = correlationId,
                action = action,
                payload = dataJson,
                createdAt = Instant.now().toEpochMilli()
            )
        )
        return MqttResult.Queued(correlationId)
    }

    private fun handleIncoming(bytes: ByteArray) {
        try {
            val msg = gson.fromJson(String(bytes), MqttResponseMessage::class.java)
            _incomingResponses.tryEmit(msg)
        } catch (_: Exception) { }
    }
}

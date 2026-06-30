package com.ppnam.station2aa.data.mqtt

import android.content.Context
import android.provider.Settings
import com.google.gson.Gson
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.local.OfflineQueueEntity
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.model.HopperStatus
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
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val offlineQueueDao: OfflineQueueDao,
    private val deviceId: String
) : MqttRepository {

    @Inject
    constructor(
        clientFactory: MqttClientFactory,
        settingsRepository: SettingsRepository,
        offlineQueueDao: OfflineQueueDao,
        @ApplicationContext context: Context
    ) : this(
        clientFactory,
        settingsRepository,
        offlineQueueDao,
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    )

    private val gson = Gson()

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingResponses = MutableSharedFlow<MqttResponseMessage>(extraBufferCapacity = 64)

    private val _hopperStatusUpdates = MutableSharedFlow<HopperStatus>(replay = 1, extraBufferCapacity = 16)
    override val hopperStatusUpdates: SharedFlow<HopperStatus> = _hopperStatusUpdates.asSharedFlow()

    private var mqttClient: Mqtt5AsyncClient? = null
    private var currentStationName: String = AppSettings().stationName
    private var requestTimeoutMs: Long = AppSettings().requestTimeoutMs

    override suspend fun connect() {
        _connectionState.value = MqttConnectionState.RECONNECTING
        try {
            val settings = settingsRepository.current()
            currentStationName = settings.stationName
            requestTimeoutMs = settings.requestTimeoutMs
            if (mqttClient == null) {
                mqttClient = clientFactory.build(settings)
            }
            val client = mqttClient!!
            client.connectWith()
                .cleanStart(false)
                .keepAlive(30)
                .send()
                .await()
            client.subscribeWith()
                .topicFilter(MqttTopics.response(currentStationName, deviceId))
                .callback { publish -> handleIncoming(publish.payloadAsBytes) }
                .send()
                .await()
            client.subscribeWith()
                .topicFilter(MqttTopics.hopperStatus(currentStationName))
                .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
                .send()
                .await()
            _connectionState.value = MqttConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = MqttConnectionState.DISCONNECTED
        }
    }

    override fun disconnect() {
        mqttClient?.disconnect()
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }

    override suspend fun send(action: String, dataJson: String): MqttResult =
        sendWithTimeout(action, dataJson, requestTimeoutMs)

    override suspend fun reconnectWith(settings: AppSettings): Result<Unit> {
        val candidate = clientFactory.build(settings)
        return try {
            withTimeout(15_000L) {
                candidate.connectWith()
                    .cleanStart(false)
                    .keepAlive(30)
                    .send()
                    .await()
                candidate.subscribeWith()
                    .topicFilter(MqttTopics.response(settings.stationName, deviceId))
                    .callback { publish -> handleIncoming(publish.payloadAsBytes) }
                    .send()
                    .await()
                candidate.subscribeWith()
                    .topicFilter(MqttTopics.hopperStatus(settings.stationName))
                    .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
                    .send()
                    .await()
            }
            val old = mqttClient
            mqttClient = candidate
            currentStationName = settings.stationName
            requestTimeoutMs = settings.requestTimeoutMs
            _connectionState.value = MqttConnectionState.CONNECTED
            try { old?.disconnect() } catch (_: Exception) { }
            Result.success(Unit)
        } catch (e: Exception) {
            try { candidate.disconnect() } catch (_: Exception) { }
            Result.failure(e)
        }
    }

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
                mqttClient!!.publishWith()
                    .topic(MqttTopics.request(currentStationName))
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

    private fun handleHopperStatus(bytes: ByteArray) {
        try {
            val status = gson.fromJson(String(bytes), HopperStatus::class.java)
            _hopperStatusUpdates.tryEmit(status)
        } catch (_: Exception) { }
    }
}

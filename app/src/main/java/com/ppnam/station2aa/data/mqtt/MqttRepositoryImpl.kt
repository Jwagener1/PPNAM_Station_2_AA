package com.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.local.OfflineQueueEntity
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.model.HopperStatus
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttRepositoryImpl @Inject constructor(
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val offlineQueueDao: OfflineQueueDao
) : MqttRepository {

    companion object {
        // Raw text, not JSON — the deviceId status topic (PPNAM/{deviceId}/status) is
        // presence-only, so payloads are plain "online"/"offline" bytes.
        private val STATUS_ONLINE = "online".toByteArray()
        private val STATUS_OFFLINE = "offline".toByteArray()
        private const val RECONNECT_RETRY_DELAY_MS = 5_000L
        private const val SUBSCRIBE_RETRY_ATTEMPTS = 3
        private const val SUBSCRIBE_RETRY_DELAY_MS = 2_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingResponses = MutableSharedFlow<MqttResponseMessage>(extraBufferCapacity = 64)
    private val _incomingTyped = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    private val _hopperStatusUpdates = MutableSharedFlow<HopperStatus>(replay = 1, extraBufferCapacity = 16)
    override val hopperStatusUpdates: SharedFlow<HopperStatus> = _hopperStatusUpdates.asSharedFlow()

    private var mqttClient: Mqtt5AsyncClient? = null
    private val isTransportConnected = AtomicBoolean(false)
    private var currentStationName: String = AppSettings().stationName
    private var currentDeviceId: String = AppSettings().deviceId
    private var requestTimeoutMs: Long = AppSettings().requestTimeoutMs
    private var retryJob: Job? = null

    // The HiveMQ client's automaticReconnect() only takes over once a connection has
    // succeeded at least once — it does not retry a failed *initial* connect. Each built
    // client also gets connected/disconnected listeners so that later drops/restores
    // (whether from automaticReconnect or a network blip) keep _connectionState honest
    // and re-run the subscribe step, since MQTT5 sessions aren't configured to persist
    // subscriptions across a disconnect (no sessionExpiryInterval set).
    private fun buildClient(settings: AppSettings): Mqtt5AsyncClient {
        lateinit var client: Mqtt5AsyncClient
        var initialConnectHandled = false
        client = clientFactory.build(
            settings,
            onConnected = {
                isTransportConnected.set(true)
                scope.launch {
                    if (mqttClient !== client) return@launch
                    if (!initialConnectHandled) {
                        // First CONNACK for this client is handled synchronously by the
                        // caller (connect()/reconnectWith()) so it can report success/failure.
                        initialConnectHandled = true
                        return@launch
                    }
                    // This branch only runs when HiveMQ's automaticReconnect() has just
                    // silently re-established the transport after a mid-session drop.
                    // connect() is deliberately NOT called here — per Task 1's guard it
                    // would just no-op against the now-live transport, which is exactly
                    // the re-entrancy bug this replaces. Only the subscribe step (which is
                    // what actually needs redoing, since MQTT5 sessions here don't persist
                    // subscriptions across a disconnect) is retried.
                    try {
                        retryBounded(SUBSCRIBE_RETRY_ATTEMPTS, SUBSCRIBE_RETRY_DELAY_MS) {
                            withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                                subscribeAndAnnounce(client, settings.stationName, settings.deviceId)
                            }
                        }
                        _connectionState.value = MqttConnectionState.CONNECTED
                    } catch (e: Exception) {
                        _connectionState.value = MqttConnectionState.DISCONNECTED
                    }
                }
            },
            onDisconnected = { handleTransportDisconnected(client) }
        )
        return client
    }

    private fun handleTransportDisconnected(client: Mqtt5AsyncClient) {
        isTransportConnected.set(false)
        if (mqttClient === client) {
            _connectionState.value = MqttConnectionState.RECONNECTING
        }
    }

    private fun scheduleReconnectRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RECONNECT_RETRY_DELAY_MS)
            if (_connectionState.value != MqttConnectionState.CONNECTED) {
                connect()
            }
        }
    }

    internal suspend fun <T> retryBounded(maxAttempts: Int, delayMs: Long, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("retryBounded exhausted with no recorded error")
    }

    private suspend fun subscribeAndAnnounce(client: Mqtt5AsyncClient, stationName: String, deviceId: String) {
        client.subscribeWith()
            .topicFilter(MqttTopics.response(stationName, deviceId))
            .callback { publish -> handleIncoming(publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.hopperStatus(stationName))
            .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.contractResponseWildcard(deviceId))
            .callback { publish -> handleIncomingTyped(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.publishWith()
            .topic(MqttTopics.deviceStatus(deviceId))
            .payload(STATUS_ONLINE)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
            .await()
    }

    override suspend fun connect() {
        if (isTransportConnected.get()) return
        retryJob?.cancel()
        _connectionState.value = MqttConnectionState.RECONNECTING
        val settings = settingsRepository.current()
        currentStationName = settings.stationName
        currentDeviceId = settings.deviceId
        requestTimeoutMs = settings.requestTimeoutMs
        // buildClient()/connectWith() do synchronous SSLContext/Netty setup (disk I/O +
        // crypto init) before the first suspension point — running that on the caller's
        // dispatcher (Main, via viewModelScope) blocks the UI thread long enough to trip
        // the input-dispatch ANR watchdog. Dispatchers.IO keeps it off Main.
        withContext(Dispatchers.IO) {
            if (mqttClient == null) {
                mqttClient = buildClient(settings)
            }
            try {
                val client = mqttClient!!
                client.connectWith()
                    .cleanStart(false)
                    .keepAlive(30)
                    .willPublish()
                        .topic(MqttTopics.deviceStatus(currentDeviceId))
                        .payload(STATUS_OFFLINE)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .applyWillPublish()
                    .send()
                    .await()
                subscribeAndAnnounce(client, currentStationName, currentDeviceId)
                _connectionState.value = MqttConnectionState.CONNECTED
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleReconnectRetry()
            }
        }
    }

    override fun disconnect() {
        retryJob?.cancel()
        publishOfflineBestEffort(mqttClient, currentDeviceId)
        mqttClient?.disconnect()
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }

    override suspend fun send(action: String, dataJson: String): MqttResult =
        sendWithTimeout(action, dataJson, requestTimeoutMs)

    override suspend fun reconnectWith(settings: AppSettings): Result<Unit> {
        retryJob?.cancel()
        // See connect() — buildClient()/connectWith() block synchronously before their
        // first suspension point, so this must run off the caller's (Main) dispatcher.
        return withContext(Dispatchers.IO) {
            val candidate = buildClient(settings)
            try {
                withTimeout(15_000L) {
                    candidate.connectWith()
                        .cleanStart(false)
                        .keepAlive(30)
                        .willPublish()
                            .topic(MqttTopics.deviceStatus(settings.deviceId))
                            .payload(STATUS_OFFLINE)
                            .qos(MqttQos.AT_LEAST_ONCE)
                            .retain(true)
                            .applyWillPublish()
                        .send()
                        .await()
                    subscribeAndAnnounce(candidate, settings.stationName, settings.deviceId)
                }
                val old = mqttClient
                val oldDeviceId = currentDeviceId
                mqttClient = candidate
                currentStationName = settings.stationName
                currentDeviceId = settings.deviceId
                requestTimeoutMs = settings.requestTimeoutMs
                _connectionState.value = MqttConnectionState.CONNECTED
                publishOfflineBestEffort(old, oldDeviceId)
                try { old?.disconnect() } catch (_: Exception) { }
                Result.success(Unit)
            } catch (e: Exception) {
                try { candidate.disconnect() } catch (_: Exception) { }
                Result.failure(e)
            }
        }
    }

    // The backend (RfidMqttService.RequestSuffixes) has never subscribed to the
    // {station}/request topic this legacy action-string protocol publishes to — only
    // per-device PPNAM/{deviceId}/{suffix} contract topics are handled. Every call here
    // has therefore always silently timed out. Previously that meant a permanent retry
    // queued via OfflineQueueRepository; now it just fails fast instead of queuing
    // traffic that a fixed backend would never answer either way.
    internal suspend fun sendWithTimeout(action: String, dataJson: String, timeoutMs: Long): MqttResult {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return MqttResult.Error("Not connected to Station 2")
        }

        val correlationId = UUID.randomUUID().toString()
        val request = MqttRequest(correlationId, currentDeviceId, action, dataJson)
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
            MqttResult.Error("Request timed out")
        } catch (e: Exception) {
            MqttResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Disconnected
            }
        }

        return try {
            withTimeout(requestTimeoutMs) {
                val responseDeferred = async {
                    _incomingTyped.filter { it.first == responseType }.first()
                }
                mqttClient!!.publishWith()
                    .topic(MqttTopics.contractRequest(currentDeviceId, requestType))
                    .payload(requestJson.toByteArray())
                    .send()
                    .await()
                val (_, rawJson) = responseDeferred.await()
                MqttTypedResult.Success(gson.fromJson(rawJson, responseClass))
            }
        } catch (e: TimeoutCancellationException) {
            if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Error("Request timed out")
            }
        } catch (e: Exception) {
            if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    // Fire-and-forget: publishes to a contract request topic without waiting for (or
    // expecting) a response. For request types the backend doesn't yet handle, it just
    // publishes best-effort and gives up silently rather than hanging until timeout.
    override suspend fun publishTyped(requestType: String, requestJson: String) {
        if (_connectionState.value != MqttConnectionState.CONNECTED) return
        try {
            mqttClient!!.publishWith()
                .topic(MqttTopics.contractRequest(currentDeviceId, requestType))
                .payload(requestJson.toByteArray())
                .send()
                .await()
        } catch (_: Exception) {
        }
    }

    // OfflineQueueRepository.drainQueue() replays queued items via the old
    // sendWithTimeout()/`{station}/request` path, not sendTyped()'s contract
    // topics/envelope. No caller sets allowOfflineQueue=true on sendTyped yet
    // (login/logout always pass false), so this is dormant — but the drain
    // path must be updated before any typed request enables queuing.
    private suspend fun enqueue(action: String, payload: String): String {
        val id = UUID.randomUUID().toString()
        offlineQueueDao.insert(
            OfflineQueueEntity(
                id = id,
                action = action,
                payload = payload,
                createdAt = Instant.now().toEpochMilli()
            )
        )
        return id
    }

    private fun handleIncoming(bytes: ByteArray) {
        try {
            val msg = gson.fromJson(String(bytes), MqttResponseMessage::class.java)
            _incomingResponses.tryEmit(msg)
        } catch (_: Exception) { }
    }

    private fun handleIncomingTyped(topic: String, bytes: ByteArray) {
        _incomingTyped.tryEmit(MqttTopics.responseTypeOf(topic) to String(bytes))
    }

    // A graceful disconnect/reconnect doesn't trigger the connection's LWT (that only
    // fires on an ungraceful drop), so the "offline" status has to be published by hand
    // here. Best-effort: bounded blocking wait since disconnect() isn't suspend.
    private fun publishOfflineBestEffort(client: Mqtt5AsyncClient?, deviceId: String) {
        try {
            client?.publishWith()
                ?.topic(MqttTopics.deviceStatus(deviceId))
                ?.payload(STATUS_OFFLINE)
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.retain(true)
                ?.send()
                ?.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    private fun handleHopperStatus(bytes: ByteArray) {
        try {
            val status = gson.fromJson(String(bytes), HopperStatus::class.java)
            _hopperStatusUpdates.tryEmit(status)
        } catch (_: Exception) { }
    }
}

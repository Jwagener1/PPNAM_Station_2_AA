package com.ppnam.station2aa.data.mqtt

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.local.OfflineQueueEntity
import com.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttRepositoryImpl @Inject constructor(
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val offlineQueueDao: OfflineQueueDao,
    private val sessionHolder: OperatorSessionHolder
) : MqttRepository {

    companion object {
        private const val TAG = "MqttRepositoryImpl"
        // Raw text, not JSON — the deviceId status topic (PPNAM/{deviceId}/status) is
        // presence-only, so payloads are plain "online"/"offline" bytes.
        private val STATUS_ONLINE = "online".toByteArray()
        private val STATUS_OFFLINE = "offline".toByteArray()
        private const val RECONNECT_RETRY_DELAY_MS = 5_000L
        private const val SUBSCRIBE_RETRY_ATTEMPTS = 3
        private const val SUBSCRIBE_RETRY_DELAY_MS = 2_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Bounded retry. The contract's replay design makes this safe: the same replay identity
        // (deviceId + requestType + messageId) with the same body returns the stored response
        // without repeating the workflow action. Keep the total budget well inside Station 2's
        // timestamp acceptance window — a retry must not outlive its own timestampUtc.
        internal const val REQUEST_MAX_ATTEMPTS = 3
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _stationOnline = MutableStateFlow(false)
    override val stationOnline: StateFlow<Boolean> = _stationOnline.asStateFlow()

    // Correlation registry: messageId -> the caller awaiting that exact response. The contract is
    // explicit that inResponseToMessageId is the ONLY correct way to match a response to a request
    // — several in-flight messages deliberately share a correlationKey, and two request types can
    // share one response topic (login_requested and reader_logout_requested both answer on
    // operator_context), so neither key nor topic can discriminate.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // Test seam. Production path publishes workflow messages at QoS 1, not retained, per contract.
    @VisibleForTesting
    internal var publishFn: suspend (String, ByteArray) -> Unit = { topic, bytes ->
        mqttClient!!.publishWith()
            .topic(topic)
            .payload(bytes)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(false)
            .send()
            .await()
    }

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
                                subscribeAndAnnounce(client, settings.deviceId)
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
        if (mqttClient === client) {
            isTransportConnected.set(false)
            _connectionState.value = MqttConnectionState.RECONNECTING
            // The retained presence we last saw is no longer evidence of anything — we are not
            // subscribed any more. Re-subscribing replays the retained value.
            _stationOnline.value = false
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

    // Presence is raw text, not JSON — the contract defines only the literal payloads
    // "online" and "offline". Anything else means we cannot claim Station 2 is up.
    @VisibleForTesting
    internal fun handleStationPresence(bytes: ByteArray) {
        val payload = String(bytes).trim().lowercase()
        _stationOnline.value = payload == "online"
        if (payload != "online" && payload != "offline") {
            Log.w(TAG, "Unrecognised station presence payload: '$payload' — treating as offline")
        }
    }

    private suspend fun subscribeAndAnnounce(client: Mqtt5AsyncClient, deviceId: String) {
        client.subscribeWith()
            .topicFilter(MqttTopics.responseWildcard(deviceId))
            .callback { publish -> handleIncomingResponse(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.STATION_STATUS)
            .callback { publish -> handleStationPresence(publish.payloadAsBytes) }
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
            val client = mqttClient!!
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
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
                }
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleReconnectRetry()
                return@withContext
            }
            // The transport is now genuinely connected (onConnected already flipped
            // isTransportConnected to true). A subscribe failure here must NOT fall through
            // to scheduleReconnectRetry(): that would re-invoke connect(), which immediately
            // no-ops against the now-live transport (see the isTransportConnected guard
            // above), permanently stranding the app in DISCONNECTED with a live-but-
            // unsubscribed client. Retry the subscribe step directly instead, mirroring the
            // automatic-reconnect path in buildClient()'s onConnected callback.
            try {
                retryBounded(SUBSCRIBE_RETRY_ATTEMPTS, SUBSCRIBE_RETRY_DELAY_MS) {
                    withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                        subscribeAndAnnounce(client, currentDeviceId)
                    }
                }
                _connectionState.value = MqttConnectionState.CONNECTED
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
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
                    subscribeAndAnnounce(candidate, settings.deviceId)
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
    internal suspend fun sendWithTimeout(action: String, dataJson: String, timeoutMs: Long): MqttResult =
        MqttResult.Error("Legacy action protocol removed; use request()")

    @Deprecated("Schema 2.0 path. Use request() instead; removed in Task 15.")
    override suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T> = MqttTypedResult.Error("sendTyped removed; use request()")

    @Deprecated("Schema 2.0 path. Removed in Task 15.")
    override suspend fun publishTyped(requestType: String, requestJson: String) {
        Log.w(TAG, "publishTyped($requestType) ignored: schema 2.0 fire-and-forget path removed")
    }

    override suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        correlationKey: String?,
        responseClass: Class<T>,
    ): MqttOutcome<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return MqttOutcome.NoResponse(FailureKind.NotConnected)
        }

        val messageId = UUID.randomUUID().toString()
        val json = RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = messageId,
            deviceId = currentDeviceId,
            operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
            timestampUtc = Instant.now().toString(),
            correlationKey = correlationKey,
        )
        val topic = MqttTopics.request(currentDeviceId, requestType)

        val bytes = json.toByteArray()  // frozen: every attempt republishes these exact bytes
        val waiter = CompletableDeferred<String>()
        pending[messageId] = waiter
        try {
            repeat(REQUEST_MAX_ATTEMPTS) { attempt ->
                val publishOk = try {
                    publishFn(topic, bytes)
                    true
                } catch (e: CancellationException) {
                    // CancellationException is an Exception in Kotlin, so the generic catch below
                    // would swallow it and report a normal failure — breaking structured
                    // concurrency when a caller's scope is torn down mid-publish. Rethrow first.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "publish attempt ${attempt + 1} failed for $requestType", e)
                    false
                }
                if (publishOk) {
                    val raw = withTimeoutOrNull(requestTimeoutMs) { waiter.await() }
                    if (raw != null) return parseOutcome(raw, responseClass, responseType)
                }
                if (attempt < REQUEST_MAX_ATTEMPTS - 1) {
                    Log.w(TAG, "retrying $requestType (messageId=$messageId, attempt ${attempt + 2})")
                }
            }
            return MqttOutcome.NoResponse(FailureKind.Timeout)
        } finally {
            pending.remove(messageId)
        }
    }

    private fun <T : Any> parseOutcome(
        raw: String,
        responseClass: Class<T>,
        expectedResponseType: String,
    ): MqttOutcome<T> = try {
        val envelope = gson.fromJson(raw, ResponseEnvelope::class.java)
        val body = gson.fromJson(raw, responseClass)
            ?: throw IllegalStateException("Response body parsed to null for $expectedResponseType")
        val nextAction = NextAction(envelope.nextAction ?: "")
        if (envelope.accepted) {
            MqttOutcome.Accepted(body, nextAction)
        } else {
            MqttOutcome.Rejected(
                body = body,
                errorCode = envelope.errorCode?.let { ErrorCode(it) },
                reason = envelope.reason,
                nextAction = nextAction,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not parse $expectedResponseType response", e)
        MqttOutcome.NoResponse(FailureKind.MalformedResponse)
    }

    @VisibleForTesting
    internal fun handleIncomingResponse(topic: String, bytes: ByteArray) {
        val raw = String(bytes)
        val envelope = try {
            gson.fromJson(raw, ResponseEnvelope::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable response on $topic", e)
            return
        }
        val id = envelope?.inResponseToMessageId
        if (id.isNullOrBlank()) {
            Log.w(TAG, "Dropping response on $topic with no inResponseToMessageId")
            return
        }
        val waiter = pending.remove(id)
        if (waiter == null) {
            // Late duplicate, or a response to a request that already timed out. The contract says
            // an unknown message gets no workflow side effect.
            Log.w(TAG, "Dropping unmatched response on $topic for messageId=$id")
            return
        }
        waiter.complete(raw)
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

}

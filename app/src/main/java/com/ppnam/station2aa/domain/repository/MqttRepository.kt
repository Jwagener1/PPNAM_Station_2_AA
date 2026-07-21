package com.ppnam.station2aa.domain.repository

import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.domain.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    /**
     * Whether Station 2 itself has announced `online` on its retained presence topic.
     *
     * Distinct from [connectionState], which only reports the broker link. The broker can be up
     * while Station 2 is down, in which case every request will time out.
     */
    val stationOnline: StateFlow<Boolean>
    /**
     * Station 2's clock minus this device's clock, in milliseconds, as of the last response
     * carrying a parseable timestamp. `null` when no such response has arrived yet.
     *
     * Every request must carry a `timestampUtc` inside Station 2's acceptance window, so a badly
     * drifted device clock fails every message with `message_expired`. This surfaces that as a
     * clock problem rather than a generic request failure. Detection only — never auto-correct.
     */
    val clockSkewMillis: StateFlow<Long?>
    /**
     * Latched true when Station 2 answers anything with `client_upgrade_required` — the reader
     * build is too old for the workflow it attempted. There is no un-latch short of installing
     * the required build; surfacing it as state (not a one-shot error) is the point.
     */
    val upgradeRequired: StateFlow<Boolean>
    suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        correlationKey: String?,
        responseClass: Class<T>,
    ): MqttOutcome<T>
    suspend fun connect()
    fun disconnect()
    suspend fun reconnectWith(settings: AppSettings): Result<Unit>
}

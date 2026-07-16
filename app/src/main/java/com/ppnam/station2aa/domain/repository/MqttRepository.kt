package com.ppnam.station2aa.domain.repository

import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
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
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T>
    suspend fun publishTyped(requestType: String, requestJson: String)
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

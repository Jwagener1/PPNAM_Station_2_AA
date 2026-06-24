package com.ppnam.station2aa.domain.repository

import com.ppnam.station2aa.data.mqtt.MqttResult
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun connect()
    fun disconnect()
}

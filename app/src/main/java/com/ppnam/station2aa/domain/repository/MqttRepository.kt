package com.ppnam.station2aa.domain.repository

import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.model.HopperStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    val hopperStatusUpdates: SharedFlow<HopperStatus>
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun connect()
    fun disconnect()
    suspend fun reconnectWith(settings: AppSettings): Result<Unit>
}

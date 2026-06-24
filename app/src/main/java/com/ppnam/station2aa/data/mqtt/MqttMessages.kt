package com.ppnam.station2aa.data.mqtt

data class MqttRequest(
    val correlationId: String,
    val deviceId: String,
    val action: String,
    val data: String
)

data class MqttResponseMessage(
    val correlationId: String,
    val success: Boolean,
    val data: String?,
    val error: String?
)

sealed class MqttResult {
    data class Success(val dataJson: String) : MqttResult()
    data class Error(val message: String) : MqttResult()
    data class Queued(val correlationId: String) : MqttResult()
}

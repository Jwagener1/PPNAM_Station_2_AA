package com.ppnam.station2aa.data.mqtt

sealed class MqttTypedResult<out T> {
    data class Success<T>(val response: T) : MqttTypedResult<T>()
    data class Error(val message: String) : MqttTypedResult<Nothing>()
    object Disconnected : MqttTypedResult<Nothing>()
    object Queued : MqttTypedResult<Nothing>()
}

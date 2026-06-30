package com.ppnam.station2aa.data.mqtt

object MqttTopics {
    fun request(stationName: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/request"

    fun response(stationName: String, deviceId: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/response/$deviceId"

    fun hopperStatus(stationName: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/hopper/status"
}

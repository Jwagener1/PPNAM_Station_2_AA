package com.ppnam.station2aa.data.mqtt

object MqttTopics {
    fun request(stationName: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "")}/request"

    fun response(stationName: String, deviceId: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "")}/response/$deviceId"

    fun hopperStatus(stationName: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "")}/hopper/status"

    fun contractRequest(deviceId: String, requestType: String): String =
        "PPNAM/$deviceId/$requestType"

    fun contractResponse(deviceId: String, responseType: String): String =
        "PPNAM/$deviceId/$responseType"

    fun contractResponseWildcard(deviceId: String): String =
        "PPNAM/$deviceId/+"

    fun deviceStatus(deviceId: String): String =
        "PPNAM/$deviceId/status"

    fun stationStatus(stationName: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "_")}/status"

    fun responseTypeOf(topic: String): String =
        topic.substringAfterLast('/')
}

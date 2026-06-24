package com.ppnam.station2aa.data.mqtt

import com.ppnam.station2aa.BuildConfig

object MqttTopics {
    const val BROKER_HOST: String = BuildConfig.MQTT_HOST
    const val BROKER_PORT: Int = BuildConfig.MQTT_PORT
    const val REQUEST = "station2/request"
    fun response(deviceId: String) = "station2/response/$deviceId"
}

package com.ppnam.station2aa.domain.model

data class AppSettings(
    val deviceId: String = "handheld_1",
    val mqttHost: String = "mqtt.sysone.co.za",
    val mqttPort: Int = 443,
    val mqttUseWebSocket: Boolean = true,
    val mqttUseTls: Boolean = true,
    val mqttUsername: String = "admin",
    val mqttPassword: String = "admin",
    val requestTimeoutMs: Long = 20_000L
)

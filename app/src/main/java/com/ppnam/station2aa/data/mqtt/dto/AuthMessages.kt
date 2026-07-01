package com.ppnam.station2aa.data.mqtt.dto

data class ReaderLoginRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val username: String,
    val password: String
)

data class LoginTagScannedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val badgeTag: String
)

data class ReaderLogoutRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String,
    val timestampUtc: String,
    val correlationKey: String
)

data class OperatorContextResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val success: Boolean = false,
    val errorMessage: String? = null,
    val operatorId: String? = null,
    val operatorName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList()
)

package com.ppnam.station2aa.data.mqtt.dto

/**
 * The envelope every contract v3.0 response carries. Parsed from the same flat JSON object as the
 * message-specific body, so the transport can route and classify a response without knowing its type.
 *
 * `inResponseToMessageId` is the only correct way to match a response to its request. `correlationKey`
 * is a trace-grouping key that several in-flight messages deliberately share.
 */
data class ResponseEnvelope(
    val messageId: String = "",
    val inResponseToMessageId: String = "",
    val schemaVersion: String = "",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String? = null,
    val accepted: Boolean = false,
    val reason: String? = null,
    val errorCode: String? = null,
    val nextAction: String? = null,
)

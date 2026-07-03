package com.ppnam.station2aa.data.mqtt.dto

data class JobCardSubmittedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val jobCardNumber: String
)

data class BomLineResponse(
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val issueType: String = "",
    val requiresIngredientCollection: Boolean = false,
    val uomCode: String = "",
    val warehouse: String = ""
)

data class BomLoadedResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val preMixId: String = "",
    val resumedExistingPreMix: Boolean = false,
    val bomSnapshotCapturedAtUtc: String? = null,
    val nextAction: String = "",
    val ingredients: List<BomLineResponse> = emptyList()
)

// Not part of the current RFID_MQTT_CONTRACT — the backend has a PreMixStatus.Cancelled
// value but nothing sets it yet, and RfidWorkflowMessageProcessor has no handler for this
// request type, so it is silently dropped for now. Sent best-effort so the app is ready
// the moment the backend adds a handler.
data class PreMixCancelledRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val jobCardNumber: String,
    val reason: String = "Operator cancelled — incorrect job card"
)

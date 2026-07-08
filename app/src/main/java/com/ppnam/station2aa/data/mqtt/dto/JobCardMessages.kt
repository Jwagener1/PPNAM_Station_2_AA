package com.ppnam.station2aa.data.mqtt.dto

data class JobCardSubmittedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val jobCardNumber: String,
    val preMixId: String = ""
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

data class ActiveJobCardsRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String
)

data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val preMixId: String = "",
    val productName: String = "",
    val status: String = ""
)

data class ActiveJobCardsListResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val jobs: List<ActiveJobCardSummary> = emptyList()
)

data class PreMixCancelledRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val jobCardNumber: String,
    val reason: String = "Operator cancelled — incorrect job card",
    val managerUsername: String = "",
    val managerPassword: String = "",
    val managerBadgeTag: String = ""
)

data class PreMixCancelResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val preMixId: String = "",
    val jobCardNumber: String = "",
    val preMixStatus: String = "",
    val nextAction: String = "",
    val approverUserId: String = "",
    val approverDisplayName: String = "",
    val approverRole: String = ""
)

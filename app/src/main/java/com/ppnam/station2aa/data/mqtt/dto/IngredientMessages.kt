package com.ppnam.station2aa.data.mqtt.dto

/**
 * `ingredient_scan_requested`. Message-specific fields only.
 *
 * Sub-project 3 adds v3's inline manager approval (managerUsername / managerPassword / auditReason
 * on a resubmitted scan with a FRESH messageId) and removes `approvalId`, which v3 does not have.
 */
data class IngredientScanPayload(
    val collectionId: String,
    val palletRfidTag: String,
    val requestedMaterialCode: String? = null,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double? = null,
)

data class BomProgressLineResponse(
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val requiredQuantity: Double = 0.0,
    val scannedQuantity: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val expectedBags: Double = 0.0,
    val scannedBags: Double = 0.0,
    val approvedExtraBags: Double = 0.0,
    val approvedShortBags: Double = 0.0,
    val remainingBags: Double = 0.0,
    val requiresManagerApproval: Boolean = false,
    val uomCode: String = "",
    val unit: String = ""
)

data class IngredientScanResultResponse(
    val collectionId: String = "",
    val scannedQuantity: Double = 0.0,
    val isRequirementSatisfied: Boolean = false,
    val hasApprovedException: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val exceptionId: String = "",
    val consumedApprovalId: String = "",
    val ingredientProgress: List<BomProgressLineResponse> = emptyList()
)

data class ManagerApprovalRequest(
    val messageId: String,
    val schemaVersion: String = "2.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val managerUsername: String,
    val managerPassword: String,
    val approvalTargetType: String = "Exception",
    val approvalTargetId: String,
    val preMixId: String,
    val palletRfidTag: String = "",
    val requestedMaterialCode: String = "",
    val actualMaterialCode: String = "",
    val quantityDelta: Double = 0.0,
    val bagCountDelta: Double = 0.0,
    val reason: String = ""
)

data class ManagerApprovalResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "2.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val managerUserId: String = "",
    val managerDisplayName: String = "",
    val role: String = "",
    val roleLabel: String = "",
    val approvalTargetType: String = "",
    val approvalTargetId: String = "",
    val approvalType: String = "",
    val approvalId: String = "",
    val expiresAtUtc: String? = null
)

data class HoldingRecoveryRequest(
    val messageId: String,
    val schemaVersion: String = "2.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val palletRfidTag: String,
    val productCode: String = "",
    val quantity: Double = 0.0
)

data class HoldingRecoveryResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "2.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val preMixId: String = "",
    val palletRfidTag: String = "",
    val productCode: String = "",
    val exceptionId: String = "",
    val nextAction: String = ""
)

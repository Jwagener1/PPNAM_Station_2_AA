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

/**
 * BomLineResponse (JobCardMessages.kt) is the same shape returned here — the contract's
 * ingredient_scan_result carries the full refreshed ingredients[], identical to bom_loaded's.
 */
data class IngredientScanResultResponse(
    val collectionId: String = "",
    val scannedQuantity: Double = 0.0,
    val isRequirementSatisfied: Boolean = false,
    val hasApprovedException: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val exceptionId: String = "",
    val consumedApprovalId: String = "",
    val ingredientProgress: List<BomLineResponse> = emptyList()
)

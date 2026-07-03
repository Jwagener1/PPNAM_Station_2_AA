package com.ppnam.station2aa.domain.model

data class ProductionOrder(
    val docNo: String,
    val preMixId: String = "",
    // The backflush BOM line (issueType "im_Backflush") represents the product being
    // made, not a component the operator scans — it's surfaced separately here rather
    // than in `lines`, and isn't always present in the response.
    val productBeingMade: String? = null,
    val lines: List<BomLine>
)

data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val valid: Boolean = true,
    val reason: String? = null
)

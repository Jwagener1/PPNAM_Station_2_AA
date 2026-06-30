package com.ppnam.station2aa.domain.model

data class ProductionOrder(
    val docNo: String,
    val itemCode: String,
    val plannedQty: Double,
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

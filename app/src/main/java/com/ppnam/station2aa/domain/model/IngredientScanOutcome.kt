package com.ppnam.station2aa.domain.model

sealed class IngredientScanOutcome {
    data class Accepted(val updatedLines: List<BomLine>) : IngredientScanOutcome()
    data class NeedsManagerApproval(
        val exceptionId: String,
        val reason: String,
        val requestedMaterialCode: String = ""
    ) : IngredientScanOutcome()
    data class NeedsRecovery(val reason: String?) : IngredientScanOutcome()
    data class Rejected(val reason: String) : IngredientScanOutcome()
}

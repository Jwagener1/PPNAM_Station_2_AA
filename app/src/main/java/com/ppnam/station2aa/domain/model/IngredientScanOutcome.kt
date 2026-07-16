package com.ppnam.station2aa.domain.model

sealed class IngredientScanOutcome {
    data class Accepted(val updatedLines: List<BomLine>) : IngredientScanOutcome()

    /**
     * Station 2 rejected the scan pending manager approval. Carries the whole original scan, because
     * v3's approval is a RESUBMIT of it with credentials attached — there is no approval token to
     * carry instead.
     */
    data class NeedsManagerApproval(
        val collectionId: String,
        val palletRfidTag: String,
        val requestedMaterialCode: String,
        val bagSizeOption: String?,
        val bagCount: Double?,
        val reason: String,
    ) : IngredientScanOutcome()
    data class NeedsRecovery(val reason: String?) : IngredientScanOutcome()
    data class Rejected(val reason: String) : IngredientScanOutcome()
}

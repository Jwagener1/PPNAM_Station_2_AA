package com.ppnam.station2aa.data.mqtt.dto

import com.ppnam.station2aa.domain.model.HopperBoardEntry

/** `job_card_load_requested` — always starts a new collection; never silently resumes. */
data class JobCardLoadPayload(
    val jobCardNumber: String,
)

/** `collection_resume_requested` — replays the stored BOM snapshot without calling SAP again. */
data class CollectionResumePayload(
    val jobCardNumber: String,
    val collectionId: String,
)

/**
 * One BOM line, as returned by BOTH `bom_loaded` and `ingredient_scan_result` — the contract returns
 * "the full refreshed ingredients[]" in the scan result, identical in shape.
 *
 * `lineNumber` is the identity: duplicate SAP material rows remain separate by it.
 *
 * On a bulk line `bagSize` is null and EVERY *Bags field is null — "no bags" is a different fact
 * from "zero bags", and a bulk line has no automatic over-collection tolerance at all.
 *
 * `collectedQuantity` and `weightReceived` legitimately differ: a scan inside tolerance credits only
 * the remaining required amount to collectedQuantity while recording the full weightReceived.
 */
data class BomLineResponse(
    val lineNumber: Int = 0,
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val requiredQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    val weightReceived: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val availableQuantity: Double = 0.0,
    val bagSize: String? = null,
    val expectedBags: Double? = null,
    val scannedBags: Double? = null,
    val approvedExtraBags: Double? = null,
    val approvedShortBags: Double? = null,
    val remainingBags: Double? = null,
    val action: String = "",
    val collected: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val issueType: String = "",
    val requiresIngredientCollection: Boolean = false,
    val uomCode: String = "",
    val unit: String = "",
    val warehouse: String = "",
)

data class CollectionSummaryResponse(
    val waitingProductCount: Int = 0,
    val collectedProductCount: Int = 0,
    val waitingQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    /** Station 2's own human-readable line, e.g. "1 product waiting for collection." */
    val summary: String = "",
)

data class BomLoadedResponse(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val resumed: Boolean = false,
    /** Collecting | ReadyForRouting | Routed | Cancelled */
    val collectionStatus: String = "",
    val bomSnapshotCapturedAtUtc: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
    /** Required by the contract in every bom_loaded — the operator chooses equipment from it. */
    val hoppers: List<HopperBoardEntry> = emptyList(),
)

data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val productName: String = "",
    val status: String = ""
)

data class ActiveJobCardsListResponse(
    val jobs: List<ActiveJobCardSummary> = emptyList()
)

/**
 * `ingredient_collection_cancel_requested`. A privileged action: manager credentials travel inline
 * and are checked against the APPROVER's account, never the sender's session — so they are required
 * even when the sender is themselves a Manager.
 *
 * `auditReason` is the operator's justification, written to the audit trail. It is not the same
 * field as a response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientCollectionCancelPayload(
    val collectionId: String,
    val managerUsername: String,
    val managerPassword: String,
    val auditReason: String,
)

data class IngredientCollectionCancelResultResponse(
    val preMixId: String = "",
    val jobCardNumber: String = "",
    val preMixStatus: String = "",
    val nextAction: String = "",
    val approverUserId: String = "",
    val approverDisplayName: String = "",
    val approverRole: String = ""
)

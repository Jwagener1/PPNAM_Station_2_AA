package com.ppnam.station2aa.data.mqtt.dto

/** `job_card_load_requested` — always starts a new collection; never silently resumes. */
data class JobCardLoadPayload(
    val jobCardNumber: String,
)

/** `collection_resume_requested` — replays the stored BOM snapshot without calling SAP again. */
data class CollectionResumePayload(
    val jobCardNumber: String,
    val collectionId: String,
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
    val unit: String = "",
    val warehouse: String = ""
)

data class BomLoadedResponse(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val resumed: Boolean = false,
    val bomSnapshotCapturedAtUtc: String? = null,
    val ingredients: List<BomLineResponse> = emptyList()
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

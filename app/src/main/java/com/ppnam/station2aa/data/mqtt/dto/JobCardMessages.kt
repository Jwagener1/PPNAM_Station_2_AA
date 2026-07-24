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
    /** Collecting | ReadyForMixing | Mixing | Cancelled */
    val collectionStatus: String = "",
    val bomSnapshotCapturedAtUtc: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
)

/**
 * One entry in `active_job_cards_list`.
 *
 * Several concurrent collections per job card is intended, client-requested behaviour, so
 * [jobCardNumber] alone does NOT identify a row — [collectionId] does. The progress fields are
 * what let the operator tell four collections of the same card apart; they were already on the
 * wire and simply weren't being parsed.
 */
data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val productName: String = "",
    val status: String = "",
    /**
     * 0–100, server-computed. Never recomputed from the counts below.
     *
     * Nullable because null and 0.0 mean different things and the live backend sends null: on
     * 2026-07-23 every one of these four arrived null, so a collection sitting at ReadyForMixing
     * rendered as a confident "0%". "Not told" must not be displayed as "no progress" — the same
     * null-is-not-zero rule [BomLine] already applies to its bag fields.
     */
    val progressPercent: Double? = null,
    val completedIngredientCount: Int? = null,
    val requiredIngredientCount: Int? = null,
    /** Lines sitting on a manager approval — worth flagging, the operator can't clear them alone. */
    val pendingApprovalCount: Int? = null,
) {
    /**
     * `status` reads as a wire token ("ReadyForMixing"). Operators read this list to pick the
     * collection they are working on, so it is spelled out. Unknown values pass through verbatim
     * rather than being flattened — a new server status must stay visible, not vanish.
     */
    val statusLabel: String
        get() = when (status) {
            "Collecting" -> "Collecting"
            "ReadyForMixing" -> "Ready to mix"
            "Mixing" -> "Mixing"
            "Cancelled" -> "Cancelled"
            else -> status
        }
}

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
    val collectionId: String = "",
    val jobCardNumber: String = "",
    val collectionStatus: String = "",
    val nextAction: String = "",
    val approverUserId: String = "",
    val approverDisplayName: String = "",
    val approverRole: String = ""
)

package com.ppnam.station2aa.data.mqtt.dto

/**
 * `ingredient_scan_requested`.
 *
 * Manager credentials travel INLINE on a resubmitted scan — v3 has no separate approval message and
 * no approval token. The resubmit MUST carry a fresh messageId: reusing the rejected one is
 * rejected as `message_id_reused` and does NOT perform the approval. The transport mints a new UUID
 * per request() call, so a resubmit is automatically a new operation.
 *
 * `auditReason` is the operator's justification for the audit trail — not the same field as a
 * response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientScanPayload(
    val collectionId: String,
    val palletRfidTag: String,
    val requestedMaterialCode: String? = null,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double? = null,
    val managerUsername: String? = null,
    val managerPassword: String? = null,
    val auditReason: String? = null,
)

/**
 * `ingredient_scan_result`.
 *
 * Returns the FULL refreshed `ingredients[]` — not just the line this scan touched — so the scanner
 * always holds the current picture. Every scan on one collection shares a correlationKey, so
 * `inResponseToMessageId` is the only way to tell which scan a result belongs to (the transport
 * handles that).
 *
 * `overCollectionToleranceBags` is the tolerance Station 2 ACTUALLY APPLIED — never hardcode it.
 * It is null on a bulk line, where no automatic tolerance applies and any over-collection needs
 * approval.
 *
 * The approver fields are null on an ordinary scan and name the account that authorised an override or
 * waiver. `approverRole` is informational only.
 */
data class IngredientScanResultResponse(
    val collectionId: String = "",
    /** Collecting | ReadyForMixing | Mixing | Cancelled — refreshed on every scan result. */
    val collectionStatus: String = "",
    val requiresManagerApproval: Boolean = false,
    /** Null on a bulk line: no automatic tolerance applies there. */
    val overCollectionToleranceBags: Double? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
)

/**
 * A short-bag waiver. Shares the `ingredient_scan_requested` topic but is a DISTINCT operation:
 * there is no pallet and no bag size — the operator is declaring up front that a line will be short.
 *
 * Credentials go on the FIRST submission, not a retry: there is no scan to attempt and fail. Sent
 * without them it is rejected outright with requiresManagerApproval.
 *
 * `requestedMaterialCode` is REQUIRED — there is no pallet to identify the line.
 *
 * The approver must hold `ingredient_approve_short_bag` — a different action id from an override's
 * `ingredient_approve_override`. Station 2 checks that against the approver's account; we never do.
 */
data class ShortBagWaiverPayload(
    val collectionId: String,
    val requestedMaterialCode: String,
    val shortBagCount: Double,
    val managerUsername: String,
    val managerPassword: String,
    val auditReason: String,
)

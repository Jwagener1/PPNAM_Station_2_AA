package com.ppnam.station2aa.data.mqtt.dto

import com.ppnam.station2aa.domain.model.HopperBoardEntry

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
    val requiresManagerApproval: Boolean = false,
    /** Null on a bulk line: no automatic tolerance applies there. */
    val overCollectionToleranceBags: Double? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
    /** Required by the contract in every scan result — including the ingredient-ready scan. */
    val hoppers: List<HopperBoardEntry> = emptyList(),
)

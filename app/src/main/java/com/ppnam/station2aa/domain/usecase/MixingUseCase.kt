package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.EmptyPayload
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.NextAction
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelPayload
import com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload
import com.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.ppnam.station2aa.data.mqtt.dto.JobCardLoadPayload
import com.ppnam.station2aa.data.mqtt.dto.ShortBagWaiverPayload
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao,
    private val palletUseCase: PalletUseCase,
) {
    private val gson = Gson()

    /**
     * Loads a job card, or resumes an exact existing collection when [collectionId] is supplied.
     *
     * v3 splits what v2 sent as one message: a load always creates a NEW destination-neutral
     * collection (loading the same job again after earlier work is valid and traceable), whereas a
     * resume replays the stored immutable BOM snapshot without calling SAP.
     */
    suspend fun lookupJob(jobCardNumber: String, collectionId: String = ""): Result<ProductionOrder> {
        val resuming = collectionId.isNotBlank()
        val outcome = mqttRepository.request(
            requestType = if (resuming) "collection_resume_requested" else "job_card_load_requested",
            responseType = "bom_loaded",
            payload = if (resuming) {
                CollectionResumePayload(jobCardNumber = jobCardNumber, collectionId = collectionId)
            } else {
                JobCardLoadPayload(jobCardNumber = jobCardNumber)
            },
            correlationKey = if (resuming) collectionId else jobCardNumber,
            responseClass = BomLoadedResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val order = outcome.body.toProductionOrder()
                bomCacheDao.put(
                    BomCacheEntity(jobCardNumber, gson.toJson(order), Instant.now().toEpochMilli())
                )
                Result.success(order)
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Job card rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    private fun BomLoadedResponse.toProductionOrder() = ProductionOrder(
        docNo = jobCardNumber,
        collectionId = collectionId,
        collectionStatus = collectionStatus,
        summary = collectionSummary.summary,
        // im_Backflush lines stay in Station 2's snapshot but are excluded from the handheld's
        // collection array — the one such line names the product being made.
        productBeingMade = ingredients.firstOrNull { it.issueType == "im_Backflush" }?.materialName,
        lines = ingredients
            .filter { it.issueType != "im_Backflush" }
            .map { it.toBomLine() },
    )

    private fun BomLineResponse.toBomLine() = BomLine(
        // Identity. Two lines may legitimately share a materialCode.
        lineNumber = lineNumber,
        itemCode = materialCode,
        itemName = materialName,
        requiredQty = requiredQuantity,
        collectedQty = collectedQuantity,
        weightReceived = weightReceived,
        remainingQty = remainingQuantity,
        availableQty = availableQuantity,
        // SAP UoM 269 displays as kg and 268 as each; unknown values pass through.
        uom = unit.ifBlank { uomCode },
        // Null on a bulk line, and null is meaningful — do NOT coalesce to 0.0.
        bagSize = bagSize,
        expectedBags = expectedBags,
        scannedBags = scannedBags,
        approvedExtraBags = approvedExtraBags,
        approvedShortBags = approvedShortBags,
        remainingBags = remainingBags,
    )

    suspend fun fetchActiveJobCards(): Result<List<ActiveJobCardSummary>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = EmptyPayload,
                correlationKey = null,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body.jobs)
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load active jobs"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /**
     * Cancels an eligible collection. Manager credentials are ALWAYS required — v3 authorises this
     * on the approver named in the request, never on the sending session, so a Manager cancelling
     * from their own handheld must still supply credentials. Rejected once routing or other
     * protected downstream activity has happened.
     */
    suspend fun cancelJob(
        collectionId: String,
        jobCardNumber: String,
        reason: String,
        managerUsername: String,
        managerPassword: String,
    ): Result<IngredientCollectionCancelResultResponse> =
        when (
            val outcome = mqttRepository.request(
                requestType = "ingredient_collection_cancel_requested",
                responseType = "ingredient_collection_cancel_result",
                payload = IngredientCollectionCancelPayload(
                    collectionId = collectionId,
                    managerUsername = managerUsername,
                    managerPassword = managerPassword,
                    auditReason = reason,
                ),
                correlationKey = collectionId.ifBlank { jobCardNumber },
                responseClass = IngredientCollectionCancelResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body)
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Cancel rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    suspend fun scanIngredient(
        collectionId: String,
        palletRfidTag: String,
        bagSizeOption: String,
        bagCount: Double,
        requestedMaterialCode: String,
        managerUsername: String? = null,
        managerPassword: String? = null,
        auditReason: String? = null,
    ): Result<IngredientScanOutcome> {
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                requestedMaterialCode = requestedMaterialCode,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    updatedLines = outcome.body.ingredients
                        // Same rule as the initial load: the backflush line is the product being
                        // made, not a component to collect. Without this filter a scan response
                        // reintroduces it as a collectible line, because MixingViewModel replaces
                        // the whole line list wholesale with this output.
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() },
                    collectionSummary = outcome.body.collectionSummary.summary,
                    collectionStatus = outcome.body.collectionStatus,
                    overCollectionToleranceBags = outcome.body.overCollectionToleranceBags,
                    nextAction = outcome.nextAction,
                )
            )
            is MqttOutcome.Rejected -> Result.success(
                when {
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        // Rebuilt from the REQUEST — the response doesn't echo these back.
                        collectionId = collectionId,
                        palletRfidTag = palletRfidTag,
                        requestedMaterialCode = requestedMaterialCode,
                        bagSizeOption = bagSizeOption,
                        bagCount = bagCount,
                        reason = outcome.reason ?: "Manager approval required",
                    )
                    outcome.nextAction == NextAction.RECOVER_HOLDING ->
                        IngredientScanOutcome.NeedsRecovery(outcome.reason)
                    else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Ingredient scan rejected")
                }
            )
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * Waives a short bag on a line. NOT a reject-then-retry: credentials travel on the FIRST
     * submission, because there is no scan to attempt and fail first — the operator is declaring
     * up front that a line will be short. Shares `ingredient_scan_requested` but sends a distinct
     * [ShortBagWaiverPayload]: no pallet, no bag size, `shortBagCount` instead.
     */
    suspend fun waiveShortBags(
        collectionId: String,
        requestedMaterialCode: String,
        shortBagCount: Double,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ): Result<IngredientScanOutcome> {
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = ShortBagWaiverPayload(
                collectionId = collectionId,
                requestedMaterialCode = requestedMaterialCode,
                shortBagCount = shortBagCount,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    // Same rule as scanIngredient: a waiver adjusts the line's requirement
                    // directly and never produces a scanned line, but the backflush line still
                    // needs filtering out of the wholesale-replaced line list.
                    updatedLines = outcome.body.ingredients
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() },
                    collectionSummary = outcome.body.collectionSummary.summary,
                    collectionStatus = outcome.body.collectionStatus,
                    overCollectionToleranceBags = outcome.body.overCollectionToleranceBags,
                    nextAction = outcome.nextAction,
                )
            )
            is MqttOutcome.Rejected -> Result.success(
                when {
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsApprovalForWaiver(
                        collectionId = collectionId,
                        requestedMaterialCode = requestedMaterialCode,
                        shortBagCount = shortBagCount,
                        reason = outcome.reason ?: "Manager approval required",
                    )
                    outcome.nextAction == NextAction.RECOVER_HOLDING ->
                        IngredientScanOutcome.NeedsRecovery(outcome.reason)
                    else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Waiver rejected")
                }
            )
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /** Delegates to [PalletUseCase] — holding recovery has one implementation, in one place. */
    suspend fun recoverHolding(collectionId: String, palletRfidTag: String): Result<Unit> =
        palletUseCase.recoverToHolding(
            palletRfidTag = palletRfidTag,
            collectionId = collectionId.ifBlank { null },
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        ).map { }
}

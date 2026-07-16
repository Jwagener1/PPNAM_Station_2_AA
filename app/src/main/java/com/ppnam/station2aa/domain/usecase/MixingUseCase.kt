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
        // im_Backflush lines stay in Station 2's snapshot but are excluded from the handheld's
        // collection array — the one such line names the product being made.
        productBeingMade = ingredients.firstOrNull { it.issueType == "im_Backflush" }?.materialName,
        lines = ingredients
            .filter { it.issueType != "im_Backflush" }
            .map { line ->
                BomLine(
                    lineNumber = line.lineNumber,
                    itemCode = line.materialCode,
                    itemName = line.materialName,
                    requiredQty = line.plannedQuantity,
                    collectedQty = line.issuedQuantity,
                    remainingQty = line.remainingQuantity,
                    // SAP UoM 269 displays as kg and 268 as each; unknown values pass through.
                    uom = line.unit.ifBlank { line.uomCode },
                )
            },
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
    ): Result<IngredientScanOutcome> {
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(outcome.body.ingredientProgress.toBomLines())
            )
            is MqttOutcome.Rejected -> {
                val body = outcome.body
                Result.success(
                    when {
                        body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                            exceptionId = body.exceptionId,
                            reason = outcome.reason ?: "Manager approval required",
                            requestedMaterialCode = body.ingredientProgress
                                .firstOrNull { it.requiresManagerApproval }?.materialCode.orEmpty(),
                        )
                        outcome.nextAction == NextAction.RECOVER_HOLDING ->
                            IngredientScanOutcome.NeedsRecovery(outcome.reason)
                        else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Ingredient scan rejected")
                    }
                )
            }
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    private fun List<BomLineResponse>.toBomLines(): List<BomLine> = map { line ->
        BomLine(
            lineNumber = line.lineNumber,
            itemCode = line.materialCode,
            itemName = line.materialName,
            requiredQty = line.requiredQuantity,
            collectedQty = line.collectedQuantity,
            remainingQty = line.remainingQuantity,
            uom = line.unit.ifBlank { line.uomCode },
            bagSize = line.bagSize,
            expectedBags = line.expectedBags,
            scannedBags = line.scannedBags,
            remainingBags = line.remainingBags,
        )
    }

    /** Delegates to [PalletUseCase] — holding recovery has one implementation, in one place. */
    suspend fun recoverHolding(collectionId: String, palletRfidTag: String): Result<Unit> =
        palletUseCase.recoverToHolding(
            palletRfidTag = palletRfidTag,
            collectionId = collectionId.ifBlank { null },
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        ).map { }

    @Deprecated(
        "v3 has no manager_approval_requested topic and no approvalId. A scan needing approval is " +
            "resubmitted inline with managerUsername/managerPassword/auditReason and a FRESH " +
            "messageId. Sub-project 3 replaces this flow and deletes this method. Kept only so " +
            "MixingViewModel and IngredientScanScreen keep compiling; it will not work against a " +
            "v3 backend."
    )
    suspend fun approveManagerException(
        exceptionId: String,
        collectionId: String,
        palletRfidTag: String,
        requestedMaterialCode: String,
        managerUsername: String,
        managerPassword: String,
        reason: String,
    ): Result<String> = Result.failure(
        UnsupportedOperationException("Manager approval is reimplemented in sub-project 3")
    )
}

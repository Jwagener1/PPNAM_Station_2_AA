package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsRequest
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryRequest
import com.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryResultResponse
import com.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.ppnam.station2aa.data.mqtt.dto.IngredientScannedRequest
import com.ppnam.station2aa.data.mqtt.dto.JobCardSubmittedRequest
import com.ppnam.station2aa.data.mqtt.dto.ManagerApprovalRequest
import com.ppnam.station2aa.data.mqtt.dto.ManagerApprovalResultResponse
import com.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse
import com.ppnam.station2aa.data.mqtt.dto.PreMixCancelledRequest
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao,
    private val settingsRepository: SettingsRepository,
    private val sessionHolder: OperatorSessionHolder
) {
    private val gson = Gson()

    private data class HopperCheckResponse(
        val available: Boolean,
        val hopperCode: String,
        val reason: String?
    )

    suspend fun lookupJob(jobCardNumber: String, collectionId: String = ""): Result<ProductionOrder> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            JobCardSubmittedRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = jobCardNumber,
                jobCardNumber = jobCardNumber,
                collectionId = collectionId
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "job_card_submitted",
            responseType = "ingredient_collection_loaded",
            requestJson = requestJson,
            responseClass = BomLoadedResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) {
                    val order = ProductionOrder(
                        docNo = response.jobCardNumber,
                        collectionId = response.collectionId,
                        productBeingMade = response.ingredients
                            .firstOrNull { it.issueType == "im_Backflush" }
                            ?.materialName,
                        lines = response.ingredients
                            .filter { it.issueType != "im_Backflush" }
                            .map { line ->
                                BomLine(
                                    itemCode = line.materialCode,
                                    itemName = line.materialName,
                                    requiredQty = line.plannedQuantity,
                                    scannedQty = line.issuedQuantity,
                                    remainingQty = line.remainingQuantity,
                                    uom = line.unit.ifBlank { line.uomCode }
                                )
                            }
                    )
                    bomCacheDao.put(BomCacheEntity(jobCardNumber, gson.toJson(order), Instant.now().toEpochMilli()))
                    Result.success(order)
                } else {
                    Result.failure(Exception(response.reason ?: "Job card rejected"))
                }
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun fetchActiveJobCards(): Result<List<ActiveJobCardSummary>> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            ActiveJobCardsRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = UUID.randomUUID().toString()
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "active_ingredient_collections_requested",
            responseType = "active_ingredient_collections_list",
            requestJson = requestJson,
            responseClass = ActiveJobCardsListResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response.collections)
                else Result.failure(Exception(response.reason ?: "Could not load active jobs"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun cancelJob(
        collectionId: String,
        jobCardNumber: String,
        reason: String,
        managerUsername: String = "",
        managerPassword: String = ""
    ): Result<PreMixCancelResultResponse> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            PreMixCancelledRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = collectionId.ifBlank { jobCardNumber },
                preMixId = collectionId,
                jobCardNumber = jobCardNumber,
                reason = reason,
                managerUsername = managerUsername,
                managerPassword = managerPassword
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "premix_cancelled",
            responseType = "premix_cancel_result",
            requestJson = requestJson,
            responseClass = PreMixCancelResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response)
                else Result.failure(Exception(response.reason ?: "Cancel rejected"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun scanIngredient(
        collectionId: String,
        palletRfidTag: String,
        bagSizeOption: String,
        bagCount: Double,
        approvalId: String = ""
    ): Result<IngredientScanOutcome> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            IngredientScannedRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = collectionId,
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                approvalId = approvalId
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "ingredient_scanned",
            responseType = "ingredient_scan_result",
            requestJson = requestJson,
            responseClass = IngredientScanResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                val outcome = when {
                    response.accepted -> IngredientScanOutcome.Accepted(
                        response.ingredientProgress.map { line ->
                            BomLine(
                                itemCode = line.materialCode,
                                itemName = line.materialName,
                                requiredQty = line.requiredQuantity,
                                scannedQty = line.scannedQuantity,
                                remainingQty = line.remainingQuantity,
                                uom = line.uomCode,
                                expectedBags = line.expectedBags,
                                scannedBags = line.scannedBags,
                                remainingBags = line.remainingBags
                            )
                        }
                    )
                    response.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        exceptionId = response.exceptionId,
                        reason = response.reason ?: "Manager approval required",
                        requestedMaterialCode = response.ingredientProgress
                            .firstOrNull { it.requiresManagerApproval }
                            ?.materialCode
                            ?: ""
                    )
                    response.nextAction == "recover_holding" -> IngredientScanOutcome.NeedsRecovery(response.reason)
                    else -> IngredientScanOutcome.Rejected(response.reason ?: "Ingredient scan rejected")
                }
                Result.success(outcome)
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun approveManagerException(
        exceptionId: String,
        collectionId: String,
        palletRfidTag: String,
        requestedMaterialCode: String,
        managerUsername: String,
        managerPassword: String,
        reason: String
    ): Result<String> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            ManagerApprovalRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = exceptionId,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                approvalTargetId = exceptionId,
                preMixId = collectionId,
                palletRfidTag = palletRfidTag,
                requestedMaterialCode = requestedMaterialCode,
                reason = reason
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "manager_approval_requested",
            responseType = "manager_approval_result",
            requestJson = requestJson,
            responseClass = ManagerApprovalResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response.approvalId)
                else Result.failure(Exception(response.reason ?: "Approval denied"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun recoverHolding(collectionId: String, palletRfidTag: String): Result<Unit> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            HoldingRecoveryRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = collectionId,
                preMixId = collectionId,
                palletRfidTag = palletRfidTag
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "holding_recovery_requested",
            responseType = "holding_recovery_result",
            requestJson = requestJson,
            responseClass = HoldingRecoveryResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(Unit)
                else Result.failure(Exception(response.reason ?: "Recovery rejected"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun checkHopper(orderNo: String, hopperCode: String): Result<Unit> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "hopperCode" to hopperCode))
        return when (val result = mqttRepository.send("check-hopper", payload)) {
            is MqttResult.Success -> {
                val response = gson.fromJson(result.dataJson, HopperCheckResponse::class.java)
                if (response.available) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.reason ?: "Hopper unavailable"))
                }
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("Hopper check requires a connection"))
        }
    }

    suspend fun completePremix(orderNo: String, hopperCode: String): Result<Unit> {
        if (hopperCode.isBlank()) return Result.failure(Exception("Hopper code is required"))
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "hopperCode" to hopperCode))
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.failure(Exception("Queued: will send when online"))
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
}

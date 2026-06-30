package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.IngredientValidationResult
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao
) {
    private val gson = Gson()

    private data class ApprovalResponse(
        val approved: Boolean,
        val supervisorName: String?,
        val reason: String?
    )

    private data class HopperCheckResponse(
        val available: Boolean,
        val hopperCode: String,
        val reason: String?
    )

    suspend fun lookupJob(orderNo: String): Result<ProductionOrder> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo))
        return when (val result = mqttRepository.send("lookup-job", payload)) {
            is MqttResult.Success -> {
                val order = gson.fromJson(result.dataJson, ProductionOrder::class.java)
                bomCacheDao.put(BomCacheEntity(orderNo, result.dataJson, Instant.now().toEpochMilli()))
                Result.success(order)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection — reconnecting"))
        }
    }

    suspend fun validateIngredient(orderNo: String, tagId: String): Result<IngredientValidationResult> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "tagId" to tagId))
        return when (val result = mqttRepository.send("validate-ingredient", payload)) {
            is MqttResult.Success -> {
                val bomLine = gson.fromJson(result.dataJson, BomLine::class.java)
                if (bomLine.valid) {
                    Result.success(IngredientValidationResult.Valid(bomLine))
                } else {
                    Result.success(IngredientValidationResult.Invalid(tagId, bomLine.reason ?: "Invalid ingredient"))
                }
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> {
                // Offline — accept optimistically; WPF validates at complete-premix
                val offlineBomLine = BomLine(itemCode = tagId, itemName = "Offline scan", requiredQty = 1.0)
                Result.success(IngredientValidationResult.Valid(offlineBomLine))
            }
        }
    }

    suspend fun approveIngredientException(
        orderNo: String,
        tagId: String,
        supervisorTagId: String
    ): Result<ScannedIngredient> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "tagId" to tagId, "supervisorTagId" to supervisorTagId))
        return when (val result = mqttRepository.send("approve-ingredient-exception", payload)) {
            is MqttResult.Success -> {
                val response = gson.fromJson(result.dataJson, ApprovalResponse::class.java)
                if (response.approved) {
                    Result.success(
                        ScannedIngredient(
                            tagId = tagId,
                            itemCode = tagId,
                            qty = 1.0,
                            isException = true,
                            approvedBy = response.supervisorName
                        )
                    )
                } else {
                    Result.failure(Exception(response.reason ?: "Approval denied"))
                }
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("Supervisor approval requires a connection"))
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

    suspend fun completePremix(
        orderNo: String,
        hopperCode: String,
        ingredients: List<ScannedIngredient>
    ): Result<Unit> {
        if (hopperCode.isBlank()) return Result.failure(Exception("Hopper code is required"))
        val exceptions = ingredients.filter { it.isException }
        val payload = gson.toJson(mapOf(
            "orderNo" to orderNo,
            "hopperCode" to hopperCode,
            "ingredients" to ingredients,
            "exceptions" to exceptions
        ))
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.failure(Exception("Queued: will send when online"))
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
}

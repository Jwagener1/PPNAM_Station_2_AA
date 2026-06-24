package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.BomLine
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

    suspend fun validateIngredient(orderNo: String, tagId: String): Result<BomLine> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "tagId" to tagId))
        return when (val result = mqttRepository.send("validate-ingredient", payload)) {
            is MqttResult.Success -> {
                val bomLine = gson.fromJson(result.dataJson, BomLine::class.java)
                Result.success(bomLine)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> {
                // Offline — accept optimistically; WPF validates at complete-premix
                Result.success(BomLine(itemCode = tagId, itemName = "Offline scan", requiredQty = 1.0))
            }
        }
    }

    suspend fun completePremix(
        orderNo: String,
        mixerCode: String,
        ingredients: List<ScannedIngredient>
    ): Result<Unit> {
        if (mixerCode.isBlank()) return Result.failure(Exception("Mixer code is required"))
        val payload = gson.toJson(mapOf(
            "orderNo" to orderNo,
            "mixerCode" to mixerCode,
            "ingredients" to ingredients
        ))
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.failure(Exception("Queued: will send when online"))
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
}

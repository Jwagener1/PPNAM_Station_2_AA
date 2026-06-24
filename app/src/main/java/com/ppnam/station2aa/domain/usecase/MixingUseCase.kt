package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject

class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao
) {
    private val gson = Gson()

    suspend fun lookupJob(orderNo: String): Result<ProductionOrder> {
        val payload = """{"orderNo":"$orderNo"}"""
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

    suspend fun validateIngredient(orderNo: String, tagId: String): Result<com.ppnam.station2aa.domain.model.BomLine> {
        val cached = bomCacheDao.get(orderNo)
        if (cached != null) {
            val order = gson.fromJson(cached.bomJson, ProductionOrder::class.java)
            val line = order.lines.find { it.itemCode == tagId }
            return if (line != null) Result.success(line)
            else Result.failure(Exception("Tag $tagId not found in BOM"))
        }
        // Cache miss — delegate to MQTT
        val payload = """{"orderNo":"$orderNo","tagId":"$tagId"}"""
        return when (val result = mqttRepository.send("validate-ingredient", payload)) {
            is MqttResult.Success -> {
                val line = gson.fromJson(result.dataJson, com.ppnam.station2aa.domain.model.BomLine::class.java)
                Result.success(line)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection — reconnecting"))
        }
    }

    suspend fun validateIngredientOffline(tagId: String, orderNo: String): Boolean {
        val cached = bomCacheDao.get(orderNo) ?: return false
        val order = gson.fromJson(cached.bomJson, ProductionOrder::class.java)
        return order.lines.any { it.itemCode == tagId }
    }

    suspend fun completePremix(
        orderNo: String,
        mixerCode: String,
        ingredients: List<ScannedIngredient>
    ): Result<Unit> {
        if (mixerCode.isBlank()) return Result.failure(Exception("Mixer code is required"))
        val ingredientsJson = gson.toJson(ingredients)
        val payload = """{"orderNo":"$orderNo","mixerCode":"$mixerCode","ingredients":$ingredientsJson}"""
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.success(Unit) // queued — operator can proceed
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
}

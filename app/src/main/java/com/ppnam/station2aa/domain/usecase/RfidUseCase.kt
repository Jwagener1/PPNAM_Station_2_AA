package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.Pallet
import com.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RfidUseCase @Inject constructor(
    private val mqttRepository: MqttRepository
) {
    private val gson = Gson()

    suspend fun lookupPallet(tagId: String): Result<Pallet> {
        val payload = gson.toJson(mapOf("tagId" to tagId))
        return when (val result = mqttRepository.send("lookup-pallet", payload)) {
            is MqttResult.Success -> {
                val pallet = gson.fromJson(result.dataJson, Pallet::class.java)
                Result.success(pallet)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection — reconnecting"))
        }
    }
}

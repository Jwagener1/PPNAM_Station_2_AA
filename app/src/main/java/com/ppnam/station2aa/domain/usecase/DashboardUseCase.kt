package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardUseCase @Inject constructor(private val mqttRepository: MqttRepository) {

    suspend fun fetchPalletLocation(tagId: String): Result<String> {
        val payload = """{"tagId":"$tagId"}"""
        return when (val r = mqttRepository.send("fetch-pallet-location", payload)) {
            is MqttResult.Success -> Result.success(r.dataJson)
            is MqttResult.Error -> Result.failure(Exception(r.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection"))
        }
    }

    suspend fun fetchPreMixList(filter: String = "{}"): Result<String> {
        return when (val r = mqttRepository.send("fetch-premix-list", """{"filter":$filter}""")) {
            is MqttResult.Success -> Result.success(r.dataJson)
            is MqttResult.Error -> Result.failure(Exception(r.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection"))
        }
    }

    suspend fun fetchExceptions(): Result<String> {
        return when (val r = mqttRepository.send("fetch-exceptions", "{}")) {
            is MqttResult.Success -> Result.success(r.dataJson)
            is MqttResult.Error -> Result.failure(Exception(r.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection"))
        }
    }
}

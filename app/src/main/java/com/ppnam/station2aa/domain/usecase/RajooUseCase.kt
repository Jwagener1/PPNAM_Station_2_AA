package com.ppnam.station2aa.domain.usecase

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.AllocationRecord
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.lang.reflect.Type
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RajooUseCase @Inject constructor(
    private val mqttRepository: MqttRepository
) {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, object : JsonDeserializer<Instant> {
            override fun deserialize(
                json: JsonElement,
                typeOfT: Type,
                context: JsonDeserializationContext
            ): Instant {
                // Accept epoch millis as a number, or ISO-8601 string
                return if (json.isJsonPrimitive) {
                    val prim = json.asJsonPrimitive
                    if (prim.isNumber) Instant.ofEpochMilli(prim.asLong)
                    else Instant.parse(prim.asString)
                } else {
                    // Fallback: epoch seconds object {"seconds": ..., "nanos": ...}
                    val obj = json.asJsonObject
                    val seconds = obj.get("seconds")?.asLong ?: 0L
                    val nanos = obj.get("nanos")?.asInt ?: 0
                    Instant.ofEpochSecond(seconds, nanos.toLong())
                }
            }
        })
        .create()

    suspend fun getMachines(): Result<List<String>> {
        return when (val result = mqttRepository.send("get-machines", "{}")) {
            is MqttResult.Success -> {
                val type = object : TypeToken<List<String>>() {}.type
                val machines: List<String> = gson.fromJson(result.dataJson, type)
                Result.success(machines)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection — reconnecting"))
        }
    }

    suspend fun allocatePallet(machineCode: String, tagId: String): Result<AllocationRecord> {
        val payload = gson.toJson(mapOf("machineCode" to machineCode, "tagId" to tagId))
        return when (val result = mqttRepository.send("allocate-rajoo", payload)) {
            is MqttResult.Success -> {
                val record = gson.fromJson(result.dataJson, AllocationRecord::class.java)
                Result.success(record)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("Offline: allocation queued for retry"))
        }
    }
}

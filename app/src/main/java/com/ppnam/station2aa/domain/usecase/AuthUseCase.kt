package com.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.LoginTagScannedRequest
import com.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.ppnam.station2aa.data.mqtt.dto.ReaderLoginRequest
import com.ppnam.station2aa.data.mqtt.dto.ReaderLogoutRequest
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}

class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()

    suspend fun login(method: LoginMethod): Result<OperatorSession> {
        val deviceId = settingsRepository.current().deviceId
        val messageId = UUID.randomUUID().toString()
        val timestampUtc = Instant.now().toString()

        val requestType: String
        val requestJson: String
        when (method) {
            is LoginMethod.Credentials -> {
                requestType = "reader_login_requested"
                requestJson = gson.toJson(
                    ReaderLoginRequest(
                        messageId = messageId,
                        deviceId = deviceId,
                        timestampUtc = timestampUtc,
                        correlationKey = messageId,
                        username = method.username,
                        password = method.password
                    )
                )
            }
            is LoginMethod.Badge -> {
                requestType = "login_tag_scanned"
                requestJson = gson.toJson(
                    LoginTagScannedRequest(
                        messageId = messageId,
                        deviceId = deviceId,
                        timestampUtc = timestampUtc,
                        correlationKey = messageId,
                        badgeTag = method.badgeTag
                    )
                )
            }
        }

        val result = mqttRepository.sendTyped(
            requestType = requestType,
            responseType = "operator_context",
            requestJson = requestJson,
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                val sessionId = response.operatorSessionId
                if (response.success && !sessionId.isNullOrBlank()) {
                    val session = OperatorSession(
                        operatorSessionId = sessionId,
                        operatorId = response.operatorId ?: "",
                        operatorName = response.operatorName ?: "",
                        role = response.role ?: "",
                        allowedActions = response.allowedActions,
                        allowedTabs = response.allowedTabs
                    )
                    sessionHolder.set(session)
                    Result.success(session)
                } else {
                    Result.failure(Exception(response.errorMessage ?: "Login failed"))
                }
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun logout(): Result<Unit> {
        val deviceId = settingsRepository.current().deviceId
        val messageId = UUID.randomUUID().toString()
        val requestJson = gson.toJson(
            ReaderLogoutRequest(
                messageId = messageId,
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = messageId
            )
        )
        mqttRepository.sendTyped(
            requestType = "reader_logout_requested",
            responseType = "operator_context",
            requestJson = requestJson,
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

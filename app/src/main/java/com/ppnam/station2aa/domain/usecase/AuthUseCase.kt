package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.EmptyPayload
import com.ppnam.station2aa.data.mqtt.FailureKind
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.ppnam.station2aa.data.mqtt.dto.CredentialsLoginPayload
import com.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject

sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}

class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
) {

    suspend fun login(method: LoginMethod): Result<OperatorSession> {
        val payload: Any = when (method) {
            is LoginMethod.Credentials -> CredentialsLoginPayload(method.username, method.password)
            is LoginMethod.Badge -> BadgeLoginPayload(method.badgeTag)
        }

        val outcome = mqttRepository.request(
            requestType = "login_requested",
            responseType = "operator_context",
            payload = payload,
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val response = outcome.body
                if (response.operatorSessionId.isBlank()) {
                    Result.failure(Exception("Station 2 accepted the login but issued no session"))
                } else {
                    val session = OperatorSession(
                        operatorSessionId = response.operatorSessionId,
                        operatorId = response.operatorId.orEmpty(),
                        operatorName = response.displayName.orEmpty(),
                        role = response.role.orEmpty(),
                        allowedActions = response.allowedActions,
                        allowedTabs = response.allowedTabs,
                    )
                    sessionHolder.set(session)
                    Result.success(session)
                }
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Login failed"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * Closes this handheld's session. The local session is cleared regardless of the outcome:
     * stranding an operator logged-in because the network blipped would be worse than a server-side
     * session that expires on its own at sessionExpiresAtUtc.
     */
    suspend fun logout(): Result<Unit> {
        mqttRepository.request(
            requestType = "reader_logout_requested",
            responseType = "operator_context",
            payload = EmptyPayload,
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond"
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}

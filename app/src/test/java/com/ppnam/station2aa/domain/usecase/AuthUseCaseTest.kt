package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.ErrorCode
import com.ppnam.station2aa.data.mqtt.FailureKind
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.NextAction
import com.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.ppnam.station2aa.data.mqtt.dto.CredentialsLoginPayload
import com.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var useCase: AuthUseCase

    private val accepted = OperatorContextResponse(
        operatorSessionId = "session-id",
        operatorId = "OP-001",
        username = "operator1",
        displayName = "Operator One",
        role = "Operator",
        allowedActions = listOf("scan_ingredient", "start_machine_cycle"),
        allowedTabs = listOf("collect", "premix"),
    )

    @Before
    fun setup() {
        mqtt = mock()
        sessionHolder = OperatorSessionHolder()
        useCase = AuthUseCase(mqtt, sessionHolder)
    }

    private suspend fun stub(outcome: MqttOutcome<OperatorContextResponse>) {
        whenever(
            mqtt.request(any(), any(), any(), anyOrNull(), eq(OperatorContextResponse::class.java))
        ).thenReturn(outcome)
    }

    @Test
    fun `credentials login uses the single v3 login topic`() = runTest {
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        useCase.login(LoginMethod.Credentials("operator1", "secret"))

        verify(mqtt).request(
            eq("login_requested"),
            eq("operator_context"),
            argThat<Any> {
                this is CredentialsLoginPayload && username == "operator1" && password == "secret"
            },
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
    }

    @Test
    fun `badge login uses the same v3 login topic with a badge payload`() = runTest {
        // v2 had two topics (reader_login_requested / login_tag_scanned). v3 has one, distinguished
        // only by which authentication field is supplied.
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        useCase.login(LoginMethod.Badge("BADGE001"))

        verify(mqtt).request(
            eq("login_requested"),
            eq("operator_context"),
            argThat<Any> { this is BadgeLoginPayload && badgeTag == "BADGE001" },
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
    }

    @Test
    fun `a successful login stores the session`() = runTest {
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Operator", session.role)
        assertEquals(listOf("scan_ingredient", "start_machine_cycle"), session.allowedActions)
        assertEquals(listOf("collect", "premix"), session.allowedTabs)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `an accepted login with no session id is still a failure`() = runTest {
        stub(MqttOutcome.Accepted(accepted.copy(operatorSessionId = ""), NextAction.NONE))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejected login fails with the operator-readable reason and stores no session`() = runTest {
        stub(
            MqttOutcome.Rejected(
                body = OperatorContextResponse(),
                errorCode = ErrorCode.PERMISSION_DENIED,
                reason = "Incorrect username or password.",
                nextAction = NextAction.LOGIN,
            )
        )

        val result = useCase.login(LoginMethod.Credentials("operator1", "wrong"))

        assertTrue(result.isFailure)
        assertEquals("Incorrect username or password.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a timeout fails with a connection message`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond", result.exceptionOrNull()?.message)
    }

    @Test
    fun `being disconnected fails with a connection message`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `logout sends the envelope-only request and clears the session`() = runTest {
        sessionHolder.set(
            com.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stub(MqttOutcome.Accepted(OperatorContextResponse(), NextAction.LOGIN))

        val result = useCase.logout()

        assertTrue(result.isSuccess)
        verify(mqtt).request(
            eq("reader_logout_requested"),
            eq("operator_context"),
            eq(com.ppnam.station2aa.data.mqtt.EmptyPayload),
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `logout clears the local session even when Station 2 never answers`() = runTest {
        sessionHolder.set(
            com.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.logout()

        // Leaving an operator stuck logged-in on the handheld because the network blipped would be
        // worse than a server-side session that expires on its own.
        assertTrue(result.isSuccess)
        assertNull(sessionHolder.session.value)
    }
}

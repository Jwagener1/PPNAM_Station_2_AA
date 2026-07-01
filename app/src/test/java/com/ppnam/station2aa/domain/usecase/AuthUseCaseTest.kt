package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AuthUseCaseTest {

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var useCase: AuthUseCase

    @Before
    fun setup() = runTest {
        mockMqttRepository = mock()
        mockSessionHolder = mock()
        mockSettingsRepository = mock()
        whenever(mockSettingsRepository.current()).thenReturn(AppSettings(deviceId = "handheld_1"))
        useCase = AuthUseCase(mockMqttRepository, mockSessionHolder, mockSettingsRepository)
    }

    @Test
    fun `login with credentials success sets session and returns it`() = runTest {
        val response = OperatorContextResponse(
            operatorSessionId = "sess-1",
            success = true,
            operatorId = "OP-1",
            operatorName = "Jane Smith",
            role = "Operator",
            allowedActions = listOf("job_card_submitted"),
            allowedTabs = listOf("Mixing")
        )
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_login_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Credentials("operator1", "1234"))

        assertTrue(result.isSuccess)
        assertEquals("sess-1", result.getOrNull()?.operatorSessionId)
        verify(mockSessionHolder).set(
            argThat { operatorSessionId == "sess-1" && operatorName == "Jane Smith" }
        )
    }

    @Test
    fun `login with credentials failure returns failure and does not set session`() = runTest {
        val response = OperatorContextResponse(success = false, errorMessage = "Invalid credentials")
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_login_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Credentials("operator1", "wrong"))

        assertTrue(result.isFailure)
        assertEquals("Invalid credentials", result.exceptionOrNull()?.message)
        verify(mockSessionHolder, never()).set(any())
    }

    @Test
    fun `login with badge uses login_tag_scanned request type`() = runTest {
        val response = OperatorContextResponse(operatorSessionId = "sess-2", success = true, operatorName = "Bob")
        whenever(
            mockMqttRepository.sendTyped(
                eq("login_tag_scanned"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Badge("TAG-JSMITH"))

        assertTrue(result.isSuccess)
        verify(mockSessionHolder).set(argThat { operatorSessionId == "sess-2" })
    }

    @Test
    fun `login when disconnected returns failure without setting session`() = runTest {
        whenever(
            mockMqttRepository.sendTyped(
                any(), any(), any(), eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.login(LoginMethod.Credentials("operator1", "1234"))

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
        verify(mockSessionHolder, never()).set(any())
    }

    @Test
    fun `logout always clears session locally`() = runTest {
        whenever(mockSessionHolder.currentSessionIdOrEmpty()).thenReturn("sess-1")
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_logout_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        val result = useCase.logout()

        assertTrue(result.isSuccess)
        verify(mockSessionHolder).clear()
    }
}

package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DashboardUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var useCase: DashboardUseCase

    @Before
    fun setup() {
        mockMqtt = mock()
        useCase = DashboardUseCase(mockMqtt)
    }

    @Test
    fun `fetchExceptions sends correct action`() = runTest {
        whenever(mockMqtt.send("fetch-exceptions", "{}")).thenReturn(MqttResult.Success("[]"))
        val result = useCase.fetchExceptions()
        assertTrue(result.isSuccess)
        verify(mockMqtt).send("fetch-exceptions", "{}")
    }

    @Test
    fun `fetchPalletLocation sends tagId in payload`() = runTest {
        whenever(mockMqtt.send(eq("fetch-pallet-location"), any()))
            .thenReturn(MqttResult.Success("{\"location\":\"MIXING\"}"))
        val result = useCase.fetchPalletLocation("TAG-001")
        assertTrue(result.isSuccess)
        verify(mockMqtt).send(eq("fetch-pallet-location"), argThat { contains("TAG-001") })
    }
}

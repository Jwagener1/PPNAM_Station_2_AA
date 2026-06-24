package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class RajooUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var useCase: RajooUseCase

    @Before
    fun setup() {
        mockMqtt = mock()
        useCase = RajooUseCase(mockMqtt)
    }

    @Test
    fun `getMachines returns list on success`() = runTest {
        whenever(mockMqtt.send("get-machines", "{}"))
            .thenReturn(MqttResult.Success("""["M1","M2"]"""))

        val result = useCase.getMachines()

        assertTrue(result.isSuccess)
        assertEquals(listOf("M1", "M2"), result.getOrThrow())
    }

    @Test
    fun `allocatePallet returns AllocationRecord on success`() = runTest {
        // allocatedAt as epoch millis — handled by the custom Instant TypeAdapter
        val recordJson = """{"preMixId":"PREMIX-001","machineCode":"M1","allocatedAt":1704067200000}"""
        whenever(mockMqtt.send(eq("allocate-pallet"), any()))
            .thenReturn(MqttResult.Success(recordJson))

        val result = useCase.allocatePallet("M1", "TAG-001")

        assertTrue(result.isSuccess)
        val record = result.getOrThrow()
        assertEquals("PREMIX-001", record.preMixId)
        assertEquals("M1", record.machineCode)
        assertNotNull(record.allocatedAt)
    }
}

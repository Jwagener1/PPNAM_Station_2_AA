package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class RfidUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var useCase: RfidUseCase

    @Before
    fun setup() {
        mockMqtt = mock()
        useCase = RfidUseCase(mockMqtt)
    }

    @Test
    fun `lookupPallet returns Pallet on success`() = runTest {
        val palletJson = """{"tagId":"TAG001","batchNo":"BATCH-1","itemCode":"ITEM-A","location":"LOC-1"}"""
        whenever(mockMqtt.send("lookup-pallet", """{"tagId":"TAG001"}"""))
            .thenReturn(MqttResult.Success(palletJson))

        val result = useCase.lookupPallet("TAG001")

        assertTrue(result.isSuccess)
        val pallet = result.getOrThrow()
        assertEquals("TAG001", pallet.tagId)
        assertEquals("BATCH-1", pallet.batchNo)
        assertEquals("ITEM-A", pallet.itemCode)
        assertEquals("LOC-1", pallet.location)
    }

    @Test
    fun `lookupPallet returns failure on MQTT error`() = runTest {
        whenever(mockMqtt.send(eq("lookup-pallet"), any()))
            .thenReturn(MqttResult.Error("Pallet not found"))

        val result = useCase.lookupPallet("TAG999")

        assertTrue(result.isFailure)
        assertEquals("Pallet not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `lookupPallet returns failure when queued (offline)`() = runTest {
        whenever(mockMqtt.send(eq("lookup-pallet"), any()))
            .thenReturn(MqttResult.Queued("corr-1"))

        val result = useCase.lookupPallet("TAG001")

        assertTrue(result.isFailure)
    }
}

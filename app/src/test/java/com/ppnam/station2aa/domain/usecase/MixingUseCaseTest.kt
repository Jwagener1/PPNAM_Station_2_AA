package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.BomCacheEntity
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockBomCacheDao: BomCacheDao
    private lateinit var useCase: MixingUseCase

    private val bomJson = """{"docNo":"510019068","itemCode":"9000002064","plannedQty":100.0,
        "lines":[{"itemCode":"MAT-001","itemName":"Resin","requiredQty":50.0,"scannedQty":0.0}]}"""

    @Before
    fun setup() {
        mockMqtt = mock()
        mockBomCacheDao = mock()
        useCase = MixingUseCase(mockMqtt, mockBomCacheDao)
    }

    @Test
    fun `lookupJob success caches bom and returns ProductionOrder`() = runTest {
        whenever(mockMqtt.send("lookup-job", """{"orderNo":"510019068"}"""))
            .thenReturn(MqttResult.Success(bomJson))
        whenever(mockBomCacheDao.put(any())).thenReturn(Unit)

        val result = useCase.lookupJob("510019068")

        assertTrue(result.isSuccess)
        assertEquals("510019068", result.getOrThrow().docNo)
        verify(mockBomCacheDao).put(any())
    }

    @Test
    fun `validateIngredient uses cache when available`() = runTest {
        whenever(mockBomCacheDao.get("510019068"))
            .thenReturn(BomCacheEntity("510019068", bomJson, 1000L))

        val result = useCase.validateIngredient("510019068", "MAT-001")

        // MqttRepository.send should NOT be called — pure cache hit
        verify(mockMqtt, never()).send(any(), any())
        assertTrue(result.isSuccess)
        assertEquals("MAT-001", result.getOrThrow().itemCode)
    }

    @Test
    fun `completePremix delegates to mqtt`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val result = useCase.completePremix(
            orderNo = "510019068",
            mixerCode = "MIX-01",
            ingredients = listOf(ScannedIngredient("TAG-001", "MAT-001", 50.0))
        )

        assertTrue(result.isSuccess)
        verify(mockMqtt).send(eq("complete-premix"), any())
    }

    @Test
    fun `lookupJob returns failure on MQTT error`() = runTest {
        whenever(mockMqtt.send("lookup-job", """{"orderNo":"510019068"}"""))
            .thenReturn(MqttResult.Error("Not found"))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
    }

    @Test
    fun `completePremix fails when mixerCode is blank`() = runTest {
        val result = useCase.completePremix(
            orderNo = "510019068",
            mixerCode = "",
            ingredients = emptyList()
        )
        assertTrue(result.isFailure)
        assertEquals("Mixer code is required", result.exceptionOrNull()?.message)
    }
}

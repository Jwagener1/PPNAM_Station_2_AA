package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.model.IngredientValidationResult
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
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var useCase: MixingUseCase

    @Before
    fun setup() = runTest {
        mockMqtt = mock()
        mockBomCacheDao = mock()
        mockSettingsRepository = mock()
        mockSessionHolder = mock()
        whenever(mockSettingsRepository.current()).thenReturn(AppSettings(deviceId = "handheld_1"))
        whenever(mockSessionHolder.currentSessionIdOrEmpty()).thenReturn("session-id")
        useCase = MixingUseCase(mockMqtt, mockBomCacheDao, mockSettingsRepository, mockSessionHolder)
    }

    // --- lookupJob ---

    @Test
    fun `lookupJob success caches bom and returns ProductionOrder`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0)
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))
        whenever(mockBomCacheDao.put(any())).thenReturn(Unit)

        val result = useCase.lookupJob("510019068")

        assertTrue(result.isSuccess)
        assertEquals("510019068", result.getOrThrow().docNo)
        assertEquals("premix-1", result.getOrThrow().preMixId)
        verify(mockBomCacheDao).put(any())
    }

    @Test
    fun `lookupJob separates the backflush line as the product being made`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019231",
            productionOrderDocumentNumber = "510019231",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "1500000326",
                    materialName = "MASTERBATCH WHITE MH 1316 HD / 5001",
                    plannedQuantity = 74.812,
                    issueType = "im_Manual",
                    requiresIngredientCollection = true
                ),
                BomLineResponse(
                    materialCode = "22306",
                    materialName = "CARRIER BAG LEVY",
                    plannedQuantity = 300000.0,
                    issueType = "im_Backflush",
                    requiresIngredientCollection = false
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val order = useCase.lookupJob("510019231").getOrThrow()

        assertEquals("CARRIER BAG LEVY", order.productBeingMade)
        assertEquals(1, order.lines.size)
        assertEquals("1500000326", order.lines.single().itemCode)
    }

    @Test
    fun `lookupJob leaves productBeingMade null when no backflush line is present`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", issueType = "im_Manual")
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals(null, order.productBeingMade)
        assertEquals(1, order.lines.size)
    }

    @Test
    fun `lookupJob returns failure when rejected`() = runTest {
        val response = BomLoadedResponse(accepted = false, reason = "Job card not found")
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
        assertEquals("Job card not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `lookupJob returns failure on MQTT error`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("Not found"))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
    }

    @Test
    fun `lookupJob sends job_card_submitted on the correct request envelope`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.lookupJob("510019068")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("job_card_submitted"), eq("bom_loaded"), captor.capture(),
            eq(BomLoadedResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"jobCardNumber\":\"510019068\""))
        assertTrue(captor.firstValue.contains("\"correlationKey\":\"510019068\""))
        assertTrue(captor.firstValue.contains("\"deviceId\":\"handheld_1\""))
    }

    // --- notifyJobCardCancelled ---

    @Test
    fun `notifyJobCardCancelled publishes with preMixId as correlationKey when present`() = runTest {
        useCase.notifyJobCardCancelled("510019068", "premix-1")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).publishTyped(eq("premix_cancelled"), captor.capture())
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"jobCardNumber\":\"510019068\""))
        assertTrue(captor.firstValue.contains("\"correlationKey\":\"premix-1\""))
    }

    @Test
    fun `notifyJobCardCancelled falls back to jobCardNumber as correlationKey when no preMixId`() = runTest {
        useCase.notifyJobCardCancelled("510019068", "")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).publishTyped(eq("premix_cancelled"), captor.capture())
        assertTrue(captor.firstValue.contains("\"correlationKey\":\"510019068\""))
    }

    // --- validateIngredient ---

    @Test
    fun `validateIngredient returns Valid when WPF confirms ingredient`() = runTest {
        val bomLineJson = """{"itemCode":"MAT-001","itemName":"Resin","requiredQty":50.0,"valid":true}"""
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Success(bomLineJson))

        val result = useCase.validateIngredient("510019068", "TAG-001")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Valid)
        assertEquals("MAT-001", (validation as IngredientValidationResult.Valid).bomLine.itemCode)
    }

    @Test
    fun `validateIngredient returns Invalid when WPF rejects ingredient`() = runTest {
        val bomLineJson = """{"itemCode":"MAT-999","itemName":"Unknown","requiredQty":0.0,"valid":false,"reason":"Not in BOM"}"""
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Success(bomLineJson))

        val result = useCase.validateIngredient("510019068", "TAG-BAD")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Invalid)
        assertEquals("TAG-BAD", (validation as IngredientValidationResult.Invalid).tagId)
        assertEquals("Not in BOM", validation.reason)
    }

    @Test
    fun `validateIngredient returns optimistic Valid when queued offline`() = runTest {
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Queued("offline-corr-id"))

        val result = useCase.validateIngredient("510019068", "EPC-HEX-TAG")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Valid)
        assertEquals("EPC-HEX-TAG", (validation as IngredientValidationResult.Valid).bomLine.itemCode)
        assertEquals("Offline scan", validation.bomLine.itemName)
    }

    @Test
    fun `validateIngredient returns failure on MQTT error`() = runTest {
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Error("Server error"))

        val result = useCase.validateIngredient("510019068", "TAG-001")
        assertTrue(result.isFailure)
    }

    // --- approveIngredientException ---

    @Test
    fun `approveIngredientException returns exception ScannedIngredient on approval`() = runTest {
        val responseJson = """{"approved":true,"supervisorName":"Jane Smith","reason":null}"""
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "SUP-TAG-001")

        assertTrue(result.isSuccess)
        val ingredient = result.getOrThrow()
        assertEquals("TAG-BAD", ingredient.tagId)
        assertTrue(ingredient.isException)
        assertEquals("Jane Smith", ingredient.approvedBy)
    }

    @Test
    fun `approveIngredientException returns failure when supervisor not authorised`() = runTest {
        val responseJson = """{"approved":false,"supervisorName":null,"reason":"Tag not a supervisor"}"""
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "NOT-SUP-TAG")

        assertTrue(result.isFailure)
        assertEquals("Tag not a supervisor", result.exceptionOrNull()?.message)
    }

    @Test
    fun `approveIngredientException fails when offline`() = runTest {
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Queued("q-id"))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "SUP-TAG")
        assertTrue(result.isFailure)
        assertEquals("Supervisor approval requires a connection", result.exceptionOrNull()?.message)
    }

    // --- checkHopper ---

    @Test
    fun `checkHopper returns success when hopper is available`() = runTest {
        val responseJson = """{"available":true,"hopperCode":"H-01","reason":null}"""
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkHopper returns failure when hopper is unavailable`() = runTest {
        val responseJson = """{"available":false,"hopperCode":"H-01","reason":"Already in use"}"""
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isFailure)
        assertEquals("Already in use", result.exceptionOrNull()?.message)
    }

    @Test
    fun `checkHopper fails when offline`() = runTest {
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Queued("q-id"))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isFailure)
        assertEquals("Hopper check requires a connection", result.exceptionOrNull()?.message)
    }

    // --- completePremix ---

    @Test
    fun `completePremix delegates to mqtt with hopperCode`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val result = useCase.completePremix(
            orderNo = "510019068",
            hopperCode = "H-01",
            ingredients = listOf(ScannedIngredient("TAG-001", "MAT-001", 50.0))
        )

        assertTrue(result.isSuccess)
        verify(mockMqtt).send(eq("complete-premix"), any())
    }

    @Test
    fun `completePremix fails when hopperCode is blank`() = runTest {
        val result = useCase.completePremix(
            orderNo = "510019068",
            hopperCode = "",
            ingredients = emptyList()
        )
        assertTrue(result.isFailure)
        assertEquals("Hopper code is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `completePremix includes exceptions array in payload`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val normal = ScannedIngredient("TAG-001", "MAT-001", 50.0)
        val exception = ScannedIngredient("TAG-BAD", "MAT-999", 1.0, isException = true, approvedBy = "Jane")
        useCase.completePremix("510019068", "H-01", listOf(normal, exception))

        val captor = argumentCaptor<String>()
        verify(mockMqtt).send(eq("complete-premix"), captor.capture())
        assertTrue(captor.firstValue.contains("\"exceptions\""))
        assertTrue(captor.firstValue.contains("TAG-BAD"))
        val occurrences = captor.firstValue.split("TAG-BAD").size - 1
        assertTrue("TAG-BAD must appear in both ingredients and exceptions arrays", occurrences >= 2)
        assertTrue(captor.firstValue.contains("TAG-001"))
    }
}

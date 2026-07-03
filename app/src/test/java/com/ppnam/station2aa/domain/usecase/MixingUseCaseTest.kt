package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse
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
    fun `lookupJob carries remainingQty through for every manual line`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 50.0, remainingQuantity = 0.0,
                    issueType = "im_Manual"
                ),
                BomLineResponse(
                    materialCode = "MAT-002", materialName = "Colorant",
                    plannedQuantity = 10.0, issuedQuantity = 3.0, remainingQuantity = 7.0,
                    issueType = "im_Manual"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val order = useCase.lookupJob("510019068").getOrThrow()

        val resin = order.lines.single { it.itemCode == "MAT-001" }
        val colorant = order.lines.single { it.itemCode == "MAT-002" }
        assertEquals(0.0, resin.remainingQty, 0.0001)
        assertTrue(resin.isFullyAllocated)
        assertEquals(7.0, colorant.remainingQty, 0.0001)
        assertFalse(colorant.isFullyAllocated)
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

    // --- cancelJob ---

    @Test
    fun `cancelJob succeeds without manager credentials when not required`() = runTest {
        val response = PreMixCancelResultResponse(
            accepted = true,
            preMixId = "premix-1",
            jobCardNumber = "510019068",
            preMixStatus = "Cancelled",
            nextAction = "scan_job_card"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.cancelJob("premix-1", "510019068", "Operator cancelled — incorrect job card")

        assertTrue(result.isSuccess)
        assertEquals("Cancelled", result.getOrThrow().preMixStatus)
    }

    @Test
    fun `cancelJob sends manager credentials in the request payload when provided`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(PreMixCancelResultResponse(accepted = true)))

        useCase.cancelJob("premix-1", "510019068", "reason", managerUsername = "Manager1", managerPassword = "5678")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("premix_cancelled"), eq("premix_cancel_result"), captor.capture(),
            eq(PreMixCancelResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"managerUsername\":\"Manager1\""))
        assertTrue(captor.firstValue.contains("\"managerPassword\":\"5678\""))
    }

    @Test
    fun `cancelJob returns failure with backend reason when rejected`() = runTest {
        val response = PreMixCancelResultResponse(accepted = false, reason = "Manager or admin approval is required.")
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.cancelJob("premix-1", "510019068", "reason")

        assertTrue(result.isFailure)
        assertEquals("Manager or admin approval is required.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancelJob returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.cancelJob("premix-1", "510019068", "reason")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- fetchActiveJobCards ---

    @Test
    fun `fetchActiveJobCards returns the job list on success`() = runTest {
        val response = ActiveJobCardsListResponse(
            accepted = true,
            jobs = listOf(
                ActiveJobCardSummary(
                    jobCardNumber = "510019068",
                    productionOrderDocumentNumber = "510019068",
                    preMixId = "premix-1",
                    productName = "Layer Mash",
                    status = "Open"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("510019068", result.getOrThrow().first().jobCardNumber)
        assertEquals("Layer Mash", result.getOrThrow().first().productName)
    }

    @Test
    fun `fetchActiveJobCards returns failure when backend rejects`() = runTest {
        val response = ActiveJobCardsListResponse(accepted = false, reason = "Operator session is not active for this RFID device. Log in again on this reader.")
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Operator session is not active for this RFID device. Log in again on this reader.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchActiveJobCards returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
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

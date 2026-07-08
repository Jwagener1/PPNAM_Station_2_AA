package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.mqtt.MqttResult
import com.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.BomProgressLineResponse
import com.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryResultResponse
import com.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.ppnam.station2aa.data.mqtt.dto.ManagerApprovalResultResponse
import com.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
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
    fun `lookupJob maps uomCode onto each BomLine's uom`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0, uomCode = "KG")
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("KG", order.lines.single().uom)
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

    @Test
    fun `lookupJob includes preMixId in the request envelope when supplied`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.lookupJob("510019068", "premix-1")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("job_card_submitted"), eq("bom_loaded"), captor.capture(),
            eq(BomLoadedResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
    }

    @Test
    fun `lookupJob sends an empty preMixId when the caller omits it`() = runTest {
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
        assertTrue(captor.firstValue.contains("\"preMixId\":\"\""))
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

    // --- scanIngredient ---

    @Test
    fun `scanIngredient accepted maps ingredientProgress into updated BomLine list`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = true,
            preMixId = "premix-1",
            ingredientProgress = listOf(
                BomProgressLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 20.0, requiredQuantity = 50.0,
                    scannedQuantity = 20.0, remainingQuantity = 30.0,
                    expectedBags = 5.0, scannedBags = 2.0, remainingBags = 3.0,
                    uomCode = "kg", unit = "kg"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue(outcome is IngredientScanOutcome.Accepted)
        val line = (outcome as IngredientScanOutcome.Accepted).updatedLines.single()
        assertEquals("MAT-001", line.itemCode)
        assertEquals(50.0, line.requiredQty, 0.0001)
        assertEquals(30.0, line.remainingQty, 0.0001)
        assertEquals(3.0, line.remainingBags, 0.0001)
        assertEquals("kg", line.uom)
    }

    @Test
    fun `scanIngredient sends palletRfidTag, bagSizeOption and bagCount in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("ingredient_scanned"), eq("ingredient_scan_result"), captor.capture(),
            eq(IngredientScanResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
        assertTrue(captor.firstValue.contains("\"bagSizeOption\":\"full\""))
        assertTrue(captor.firstValue.contains("\"bagCount\":2.0"))
    }

    @Test
    fun `scanIngredient includes approvalId when retrying after an approved exception`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, approvalId = "approval-1")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("ingredient_scanned"), eq("ingredient_scan_result"), captor.capture(),
            eq(IngredientScanResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"approvalId\":\"approval-1\""))
    }

    @Test
    fun `scanIngredient rejected with requiresManagerApproval returns NeedsManagerApproval`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = false,
            reason = "Wrong material for this pallet",
            requiresManagerApproval = true,
            exceptionId = "exception-1",
            nextAction = "manager_approval"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        assertEquals("exception-1", (outcome as IngredientScanOutcome.NeedsManagerApproval).exceptionId)
        assertEquals("Wrong material for this pallet", outcome.reason)
    }

    @Test
    fun `scanIngredient rejected with recover_holding returns NeedsRecovery`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = false,
            reason = "Pallet not in Holding or Mixing",
            nextAction = "recover_holding"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsRecovery)
        assertEquals("Pallet not in Holding or Mixing", (outcome as IngredientScanOutcome.NeedsRecovery).reason)
    }

    @Test
    fun `scanIngredient plainly rejected returns Rejected`() = runTest {
        val response = IngredientScanResultResponse(accepted = false, reason = "Unknown pallet")
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.Rejected)
        assertEquals("Unknown pallet", (outcome as IngredientScanOutcome.Rejected).reason)
    }

    @Test
    fun `scanIngredient returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- approveManagerException ---

    @Test
    fun `approveManagerException returns the approvalId on success`() = runTest {
        val response = ManagerApprovalResultResponse(accepted = true, approvalId = "approval-1")
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "Operator requested override"
        )

        assertTrue(result.isSuccess)
        assertEquals("approval-1", result.getOrThrow())
    }

    @Test
    fun `approveManagerException sends the exception and pallet in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "Operator requested override"
        )

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("manager_approval_requested"), eq("manager_approval_result"), captor.capture(),
            eq(ManagerApprovalResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"approvalTargetId\":\"exception-1\""))
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
        assertTrue(captor.firstValue.contains("\"managerUsername\":\"manager1\""))
        assertTrue(captor.firstValue.contains("\"managerPassword\":\"5678\""))
    }

    @Test
    fun `approveManagerException returns failure with backend reason when denied`() = runTest {
        val response = ManagerApprovalResultResponse(accepted = false, reason = "Invalid manager credentials")
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "baduser", "badpass", "reason"
        )

        assertTrue(result.isFailure)
        assertEquals("Invalid manager credentials", result.exceptionOrNull()?.message)
    }

    @Test
    fun `approveManagerException returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "reason"
        )

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- recoverHolding ---

    @Test
    fun `recoverHolding succeeds when the pallet is recovered`() = runTest {
        val response = HoldingRecoveryResultResponse(accepted = true, nextAction = "scan_ingredient")
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `recoverHolding sends preMixId and palletRfidTag in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.recoverHolding("premix-1", "EPC:300833")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("holding_recovery_requested"), eq("holding_recovery_result"), captor.capture(),
            eq(HoldingRecoveryResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
    }

    @Test
    fun `recoverHolding returns failure with backend reason when rejected`() = runTest {
        val response = HoldingRecoveryResultResponse(accepted = false, reason = "Pallet is blocked", nextAction = "retry_recovery")
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

        assertTrue(result.isFailure)
        assertEquals("Pallet is blocked", result.exceptionOrNull()?.message)
    }

    @Test
    fun `recoverHolding returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

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

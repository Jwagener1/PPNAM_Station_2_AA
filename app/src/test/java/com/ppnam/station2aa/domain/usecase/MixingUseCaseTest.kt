package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.mqtt.FailureKind
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.NextAction
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.BomProgressLineResponse
import com.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.ppnam.station2aa.data.mqtt.dto.JobCardLoadPayload
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockBomCacheDao: BomCacheDao
    private lateinit var mockPalletUseCase: PalletUseCase
    private lateinit var useCase: MixingUseCase

    @Before
    fun setup() = runTest {
        mockMqtt = mock()
        mockBomCacheDao = mock()
        mockPalletUseCase = mock()
        useCase = MixingUseCase(mockMqtt, mockBomCacheDao, mockPalletUseCase)
    }

    // --- lookupJob ---

    @Test
    fun `lookupJob success caches bom and returns ProductionOrder`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0)
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))
        whenever(mockBomCacheDao.put(any())).thenReturn(Unit)

        val result = useCase.lookupJob("510019068")

        assertTrue(result.isSuccess)
        assertEquals("510019068", result.getOrThrow().docNo)
        assertEquals("premix-1", result.getOrThrow().collectionId)
        verify(mockBomCacheDao).put(any())
    }

    @Test
    fun `lookupJob falls back to uomCode when unit is blank`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0, uomCode = "KG")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("KG", order.lines.single().uom)
    }

    @Test
    fun `lookupJob prefers the humanized unit over the raw SAP uomCode`() = runTest {
        // Real Station 2 schema 2.0 responses send the raw SAP code ("269") in uomCode
        // and the humanized value ("kg") in a separate unit field.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0, uomCode = "269", unit = "kg")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("kg", order.lines.single().uom)
    }

    @Test
    fun `lookupJob uses jobCardNumber as docNo since productionOrderDocumentNumber is no longer returned`() = runTest {
        // Real Station 2 schema 2.0 ingredient_collection_loaded responses omit
        // productionOrderDocumentNumber entirely — only jobCardNumber comes back.
        val response = BomLoadedResponse(
            jobCardNumber = "510019359",
            collectionId = "COL_000004",
            ingredients = emptyList()
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019359").getOrThrow()

        assertEquals("510019359", order.docNo)
    }

    @Test
    fun `lookupJob separates the backflush line as the product being made`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019231",
            productionOrderDocumentNumber = "510019231",
            collectionId = "premix-1",
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
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019231").getOrThrow()

        assertEquals("CARRIER BAG LEVY", order.productBeingMade)
        assertEquals(1, order.lines.size)
        assertEquals("1500000326", order.lines.single().itemCode)
    }

    @Test
    fun `lookupJob leaves productBeingMade null when no backflush line is present`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "premix-1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", issueType = "im_Manual")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals(null, order.productBeingMade)
        assertEquals(1, order.lines.size)
    }

    @Test
    fun `lookupJob carries remainingQty through for every manual line`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "premix-1",
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
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

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
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(BomLoadedResponse(), null, "Job card not found", NextAction.SCAN_JOB_CARD)
        )

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
        assertEquals("Job card not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `lookupJob returns failure on no response`() = runTest {
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
    }

    @Test
    fun `a blank collectionId loads a new job card`() = runTest {
        whenever(
            mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(BomLoadedResponse(jobCardNumber = "510019068"), NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068")

        verify(mockMqtt).request(
            eq("job_card_load_requested"), eq("bom_loaded"),
            argThat<Any> { this is JobCardLoadPayload && jobCardNumber == "510019068" },
            eq("510019068"), eq(BomLoadedResponse::class.java),
        )
    }

    @Test
    fun `a supplied collectionId resumes that exact collection instead of reloading SAP`() = runTest {
        whenever(
            mockMqtt.request(eq("collection_resume_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(BomLoadedResponse(jobCardNumber = "510019068"), NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068", "COL_000123")

        verify(mockMqtt).request(
            eq("collection_resume_requested"), eq("bom_loaded"),
            argThat<Any> {
                this is CollectionResumePayload && collectionId == "COL_000123" && jobCardNumber == "510019068"
            },
            eq("COL_000123"), eq(BomLoadedResponse::class.java),
        )
    }

    // --- cancelJob ---

    @Test
    fun `cancelJob succeeds and returns the result body`() = runTest {
        val response = IngredientCollectionCancelResultResponse(
            preMixId = "premix-1",
            jobCardNumber = "510019068",
            preMixStatus = "Cancelled",
            nextAction = "scan_job_card"
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_JOB_CARD))

        val result = useCase.cancelJob("premix-1", "510019068", "Operator cancelled — incorrect job card", "Manager1", "5678")

        assertTrue(result.isSuccess)
        assertEquals("Cancelled", result.getOrThrow().preMixStatus)
    }

    @Test
    fun `cancelJob sends manager credentials and audit reason in the request payload`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(IngredientCollectionCancelResultResponse(), NextAction.SCAN_JOB_CARD))

        useCase.cancelJob("premix-1", "510019068", "reason", "Manager1", "5678")

        verify(mockMqtt).request(
            eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
            argThat<Any> {
                this is com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelPayload &&
                    collectionId == "premix-1" && managerUsername == "Manager1" &&
                    managerPassword == "5678" && auditReason == "reason"
            },
            any(), eq(IngredientCollectionCancelResultResponse::class.java),
        )
    }

    @Test
    fun `cancelJob returns failure with backend reason when rejected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(
                IngredientCollectionCancelResultResponse(), null,
                "Manager or admin approval is required.", NextAction.SCAN_JOB_CARD
            )
        )

        val result = useCase.cancelJob("premix-1", "510019068", "reason", "Manager1", "5678")

        assertTrue(result.isFailure)
        assertEquals("Manager or admin approval is required.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancelJob returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.cancelJob("premix-1", "510019068", "reason", "Manager1", "5678")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- fetchActiveJobCards ---

    @Test
    fun `fetchActiveJobCards returns the job list on success`() = runTest {
        val response = ActiveJobCardsListResponse(
            jobs = listOf(
                ActiveJobCardSummary(
                    jobCardNumber = "510019068",
                    productionOrderDocumentNumber = "510019068",
                    collectionId = "premix-1",
                    productName = "Layer Mash",
                    status = "Open"
                )
            )
        )
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("510019068", result.getOrThrow().first().jobCardNumber)
        assertEquals("Layer Mash", result.getOrThrow().first().productName)
    }

    @Test
    fun `fetchActiveJobCards returns failure when backend rejects`() = runTest {
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(
                ActiveJobCardsListResponse(), null,
                "Operator session is not active for this RFID device. Log in again on this reader.",
                NextAction.LOGIN,
            )
        )

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Operator session is not active for this RFID device. Log in again on this reader.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchActiveJobCards returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- scanIngredient ---

    @Test
    fun `scanIngredient accepted maps ingredientProgress into updated BomLine list`() = runTest {
        val response = IngredientScanResultResponse(
            collectionId = "premix-1",
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
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
                any(), any(), eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

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
    fun `scanIngredient sends collectionId, palletRfidTag, bagSizeOption and bagCount in the request`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
                any(), any(), eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        verify(mockMqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is com.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload &&
                    collectionId == "premix-1" && palletRfidTag == "EPC:300833" &&
                    bagSizeOption == "full" && bagCount == 2.0
            },
            eq("premix-1"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `a scan needing approval arrives as a rejection that still carries refreshed progress`() = runTest {
        val body = IngredientScanResultResponse(
            collectionId = "COL_000123",
            requiresManagerApproval = true,
            exceptionId = "EXC-1",
            ingredientProgress = listOf(
                BomProgressLineResponse(materialCode = "1600000301", requiresManagerApproval = true)
            ),
        )
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(body, null, "Over tolerance", NextAction.RETRY_WITH_MANAGER_APPROVAL)
        )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "full", 1.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        assertEquals("1600000301", (outcome as IngredientScanOutcome.NeedsManagerApproval).requestedMaterialCode)
        assertEquals("EXC-1", outcome.exceptionId)
        assertEquals("Over tolerance", outcome.reason)
    }

    @Test
    fun `a scan against an unarrived pallet asks for recovery`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(
                IngredientScanResultResponse(), null, "Pallet is not at Station 2", NextAction.RECOVER_HOLDING
            )
        )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "full", 1.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsRecovery)
        assertEquals("Pallet is not at Station 2", (outcome as IngredientScanOutcome.NeedsRecovery).reason)
    }

    @Test
    fun `scanIngredient plainly rejected returns Rejected`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(IngredientScanResultResponse(), null, "Unknown pallet", NextAction.NONE)
        )

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.Rejected)
        assertEquals("Unknown pallet", (outcome as IngredientScanOutcome.Rejected).reason)
    }

    @Test
    fun `scanIngredient returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- recoverHolding ---

    @Test
    fun `recoverHolding delegates to PalletUseCase rather than re-implementing the message`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq("COL_000123"), any()))
            .thenReturn(Result.success(mock()))

        val result = useCase.recoverHolding("COL_000123", "TAG-1")

        assertTrue(result.isSuccess)
        verify(mockPalletUseCase).recoverToHolding(eq("TAG-1"), eq("COL_000123"), any())
    }

    @Test
    fun `recoverHolding treats a blank collectionId as null`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.success(mock()))

        val result = useCase.recoverHolding("", "TAG-1")

        assertTrue(result.isSuccess)
        verify(mockPalletUseCase).recoverToHolding(eq("TAG-1"), eq(null), any())
    }

    @Test
    fun `recoverHolding surfaces PalletUseCase failure`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq("COL_000123"), any()))
            .thenReturn(Result.failure(Exception("Pallet is blocked")))

        val result = useCase.recoverHolding("COL_000123", "TAG-1")

        assertTrue(result.isFailure)
        assertEquals("Pallet is blocked", result.exceptionOrNull()?.message)
    }

    // --- approveManagerException (deprecated stub) ---

    @Suppress("DEPRECATION")
    @Test
    fun `approveManagerException always fails since v3 has no manager_approval_requested topic`() = runTest {
        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "MAT-001", "manager1", "5678", "reason"
        )

        assertTrue(result.isFailure)
        verify(mockMqtt, never()).request<Any>(any(), any(), any(), anyOrNull(), any())
    }
}

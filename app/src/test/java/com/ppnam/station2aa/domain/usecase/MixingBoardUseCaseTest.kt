package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.auth.ManagerAuthorization
import com.ppnam.station2aa.data.mqtt.dto.ReadyCollectionDto
import com.ppnam.station2aa.data.mqtt.ErrorCode
import com.ppnam.station2aa.data.mqtt.FailureKind
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.NextAction
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.ppnam.station2aa.domain.model.LayerInput
import com.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingBoardUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockManagerAuthorization: ManagerAuthorization
    private lateinit var useCase: MixingBoardUseCase

    @Before
    fun setup() = runTest {
        mockMqtt = mock()
        mockManagerAuthorization = mock()
        // 4.1: force-close first exchanges the manager's credentials for a token scoped to that
        // exact cycle. Default to success so the force-close tests stay about the cycle message.
        whenever(mockManagerAuthorization.authorize(any(), any(), any(), any()))
            .thenReturn(Result.success("auth-token-1"))
        useCase = MixingBoardUseCase(mockMqtt, mockManagerAuthorization)
    }

    @Test
    fun `fetchOverview sends the area wire value and maps equipment`() = runTest {
        val response = MixingOverviewResponse(
            mixingArea = "JandiBulkMixing",
            equipment = listOf(EquipmentDto(
                mixingArea = "JandiBulkMixing", equipmentRole = "Mixer",
                machineCode = "JAN-MIX-01", displayName = "JANDI Mixer",
                isEnabled = true, isAvailable = true, status = "Available",
                validDestinationMachineCodes = listOf("JAN-02"))))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION))

        val overview = useCase.fetchOverview(MixingArea.Jandi).getOrThrow()

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MixingOverviewResponse::class.java))
        }.firstValue as MixingOverviewPayload
        assertEquals("JandiBulkMixing", payload.mixingArea)
        val eq = overview.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertEquals(MixingArea.Jandi, eq.area)
        assertEquals("Mixer", eq.role)
    }

    @Test
    fun `fetchReadyCollections reads the mixing overview and carries no plan data`() = runTest {
        val response = MixingOverviewResponse(
            readyCollections = listOf(
                ReadyCollectionDto(
                    jobCardNumber = "JC-24001", collectionId = "COL_000123",
                    productName = "HD Film", status = "IngredientsCollected",
                    validMixerCodes = listOf("MXR-01", "MXR-02"),
                    nextAction = "scan_same_machine_to_finish"),
            ))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION))

        val ready = useCase.fetchReadyCollections().getOrThrow()

        val collection = ready.single()
        assertEquals("COL_000123", collection.collectionId)
        assertEquals("JC-24001", collection.jobCardNumber)
        // One completed collection creates exactly one mix; the mixers it may start come from
        // the server, not from a saved plan.
        assertEquals(listOf("MXR-01", "MXR-02"), collection.validMixerCodes)
    }

    @Test
    fun `fetchCollectedMaterials resumes the collection and keeps collected manual lines`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068", collectionId = "COL_1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-1", materialName = "Resin",
                    collectedQuantity = 550.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-2", materialName = "Uncollected",
                    collectedQuantity = 0.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-3", materialName = "Product",
                    collectedQuantity = 5.0, issueType = "im_Backflush"),
            ))
        whenever(mockMqtt.request(
            eq("collection_resume_requested"), eq("bom_loaded"), any(), anyOrNull(),
            eq(BomLoadedResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.OPEN_MIXING))

        val materials = useCase.fetchCollectedMaterials("510019068", "COL_1").getOrThrow()

        assertEquals(listOf("MAT-1"), materials.map { it.materialCode })
        assertEquals(550.0, materials.single().collectedQty, 0.0)
    }

    @Test
    fun `startMixer sends collectionId and no mixBatchIds`() = runTest {
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(
            MachineCycleResultResponse(action = "Started", machineCode = "MXR-01",
                cycleId = "CYC_1", mixBatchId = "MIX_1"),
            NextAction.SCAN_SAME_MACHINE_TO_FINISH))

        val outcome = useCase.startMixer("MXR-01", "510019068", "COL_1")

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals("COL_1", payload.collectionId)
        assertNull(payload.mixBatchIds)
        assertNull(payload.layerInputs)
        assertTrue(outcome is MachineCycleOutcome.Accepted)
        assertEquals("CYC_1", (outcome as MachineCycleOutcome.Accepted).cycleId)
    }

    @Test
    fun `startRajoo carries layer inputs`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(action = "Started"), NextAction.NONE))

        useCase.startRajoo("RAJ-GM-01", "510019068", "COL_1",
            listOf(LayerInput("MAT-1", 12.5), LayerInput("MAT-2", 3.0)))

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals(2, payload.layerInputs!!.size)
        assertEquals("MAT-1", payload.layerInputs!![0].materialCode)
        assertEquals(12.5, payload.layerInputs!![0].dosingQuantity, 0.0)
        assertEquals("COL_1", payload.collectionId)
    }

    @Test
    fun `startDownstream sends mixBatchIds and no collectionId`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(action = "Started"), NextAction.NONE))

        useCase.startDownstream("EXT-03", "510019068", listOf("MIX_1", "MIX_2"))

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals(listOf("MIX_1", "MIX_2"), payload.mixBatchIds)
        assertNull(payload.collectionId)
    }

    @Test
    fun `a rejected start still carries the embedded areaStatus`() = runTest {
        val response = MachineCycleResultResponse(
            areaStatus = MixingOverviewResponse(equipment = listOf(EquipmentDto(machineCode = "MXR-01"))))
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(response, ErrorCode.EQUIPMENT_IN_USE, "Busy.", NextAction.NONE))

        val outcome = useCase.startMixer("MXR-01", "510019068", "COL_1")

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        val rejected = outcome as MachineCycleOutcome.Rejected
        assertEquals(ErrorCode.EQUIPMENT_IN_USE, rejected.errorCode)
        assertEquals("Busy.", rejected.reason)
        assertEquals(1, rejected.areaStatus!!.equipment.size)
    }

    @Test
    fun `an envelope-level rejection with no areaStatus maps to null`() = runTest {
        val response = MachineCycleResultResponse()
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(response, ErrorCode.SESSION_REQUIRED, "No valid session.", NextAction.NONE))

        val outcome = useCase.finish("MXR-01", "CYC_1")

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        assertNull((outcome as MachineCycleOutcome.Rejected).areaStatus)
    }

    @Test
    fun `no response maps to Failed`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        val outcome = useCase.finish("MXR-01", "CYC_1")

        assertTrue(outcome is MachineCycleOutcome.Failed)
    }
}

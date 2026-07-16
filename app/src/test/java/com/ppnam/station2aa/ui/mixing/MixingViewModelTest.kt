package com.ppnam.station2aa.ui.mixing

import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.HopperAvailability
import com.ppnam.station2aa.domain.model.HopperStatus
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "premix-1",
        lines = listOf(BomLine("MAT-001", "Resin", 1.0))
    )

    private fun sessionWithActions(vararg actions: String) = OperatorSession(
        operatorSessionId = "session-id",
        operatorId = "op-1",
        operatorName = "Test Operator",
        role = "Worker",
        allowedActions = actions.toList()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(sessionWithActions("cancel_premix")))

        viewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `lookupJob success sets OrderLoaded state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `lookupJob failure sets Error state`() = runTest {
        whenever(mockUseCase.lookupJob(any(), any())).thenReturn(Result.failure(Exception("Not found")))
        viewModel.lookupJob("bad")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.Error)
        assertEquals("Not found", (state as MixingUiState.Error).message)
    }

    @Test
    fun `lookupJob forwards preMixId to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068", "premix-1")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068", "premix-1")
        advanceUntilIdle()
        verify(mockUseCase).lookupJob("510019068", "premix-1")
    }

    @Test
    fun `startListeningForPalletScans opens EnteringBagDetails on a pallet scan`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `startListeningForPalletScans opens EnteringBagDetails on a barcode scan too`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.Barcode("123456789012", "EAN13", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("123456789012", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `cancelBagEntry returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        vm.cancelBagEntry()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `confirmIngredientScan on Accepted replaces order lines and returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0))
            .thenReturn(Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine))))

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(listOf(updatedLine), (state as MixingUiState.OrderLoaded).order.lines)
    }

    @Test
    fun `confirmIngredientScan on NeedsManagerApproval sets IngredientExceptionApproval state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsManagerApproval("exception-1", "Wrong material"))
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.IngredientExceptionApproval)
        assertEquals("exception-1", (state as MixingUiState.IngredientExceptionApproval).exceptionId)
        assertEquals("Wrong material", state.reason)
    }

    @Test
    fun `confirmIngredientScan on NeedsRecovery sets PalletRecoveryPrompt state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.PalletRecoveryPrompt)
        assertEquals("EPC:300833", (state as MixingUiState.PalletRecoveryPrompt).palletTag)
    }

    @Test
    fun `submitManagerApproval on success retries the pending scan with the approvalId`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsManagerApproval("exception-1", "Wrong material", "MAT-002"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        whenever(mockUseCase.approveManagerException(eq("exception-1"), eq("premix-1"), eq("EPC:300833"), eq("MAT-002"), eq("manager1"), eq("5678"), any()))
            .thenReturn(Result.success("approval-1"))
        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, "approval-1")).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine)))
        )

        viewModel.submitManagerApproval("manager1", "5678")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase).scanIngredient("premix-1", "EPC:300833", "full", 2.0, "approval-1")
    }

    @Test
    fun `confirmPalletRecovery on success retries the pending scan`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        whenever(mockUseCase.recoverHolding("premix-1", "EPC:300833")).thenReturn(Result.success(Unit))
        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, "")).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine)))
        )

        viewModel.confirmPalletRecovery()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase).recoverHolding("premix-1", "EPC:300833")
    }

    @Test
    fun `dismissPalletRecovery returns to OrderLoaded without retrying`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        viewModel.dismissPalletRecovery()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase, never()).recoverHolding(any(), any())
    }

    @Test
    fun `checkAndAllocateHopper on success sets hopperCode and fires nav event`() = runTest {
        whenever(mockUseCase.checkHopper("510019068", "H-01")).thenReturn(Result.success(Unit))

        val navEvents = mutableListOf<String>()
        val job = launch(testDispatcher) {
            viewModel.navigationEvent.collect { navEvents.add(it) }
        }

        viewModel.checkAndAllocateHopper("510019068", "H-01")
        advanceUntilIdle()

        assertEquals("H-01", viewModel.hopperCode.value)
        assertTrue(navEvents.contains(MixingNavDestination.PREMIX_COMPLETE))
        job.cancel()
    }

    @Test
    fun `checkAndAllocateHopper on failure sets HopperUnavailable state`() = runTest {
        whenever(mockUseCase.checkHopper(any(), any()))
            .thenReturn(Result.failure(Exception("Already in use")))

        viewModel.checkAndAllocateHopper("510019068", "H-01")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.HopperUnavailable)
        assertEquals("Already in use", (state as MixingUiState.HopperUnavailable).reason)
    }

    @Test
    fun `cancelJob resets state on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse(accepted = true))
        )
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob()
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
        assertEquals("", viewModel.hopperCode.value)
        assertTrue(outcomes.contains(CancelOutcome.Confirmed))
        job.cancel()
    }

    @Test
    fun `cancelJob on rejection keeps the order loaded and emits Failed`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(Exception("Pre-mix has ingredient activity and cannot be closed.")))
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        assertEquals(
            listOf(CancelOutcome.Failed("Pre-mix has ingredient activity and cannot be closed.")),
            outcomes
        )
        job.cancel()
    }

    @Test
    fun `cancelJob passes manager credentials through to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse(accepted = true))
        )

        viewModel.cancelJob(managerUsername = "Manager1", managerPassword = "5678")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(
            eq("premix-1"), eq("510019068"), any(), eq("Manager1"), eq("5678")
        )
    }

    @Test
    fun `operatorCanCancelDirectly reflects the cancel_premix_direct allowed action`() = runTest {
        whenever(mockSessionHolder.session).thenReturn(
            MutableStateFlow(sessionWithActions("cancel_premix", "cancel_premix_direct"))
        )
        val directViewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
        )

        assertTrue(directViewModel.operatorCanCancelDirectly())
    }

    @Test
    fun `operatorCanCancelDirectly is false without the capability`() = runTest {
        assertFalse(viewModel.operatorCanCancelDirectly())
    }

    @Test
    fun `loadActiveJobs populates activeJobs on success`() = runTest {
        val jobs = listOf(
            com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary(
                jobCardNumber = "510019068", productName = "Layer Mash", status = "Open"
            )
        )
        whenever(mockUseCase.fetchActiveJobCards()).thenReturn(Result.success(jobs))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertEquals(jobs, viewModel.activeJobs.value)
        assertEquals(null, viewModel.activeJobsError.value)
    }

    @Test
    fun `loadActiveJobs sets activeJobsError on failure and leaves list untouched`() = runTest {
        whenever(mockUseCase.fetchActiveJobCards()).thenReturn(Result.failure(Exception("Not connected to Station 2")))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertTrue(viewModel.activeJobs.value.isEmpty())
        assertEquals("Not connected to Station 2", viewModel.activeJobsError.value)
    }

    @Test
    fun `session reflects the operator session holder`() = runTest {
        assertEquals("Test Operator", viewModel.session.value?.operatorName)
    }

    @Test
    fun `logout calls AuthUseCase and fires logoutEvent`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun `pauseScanning cancels the active scan job so further scans are ignored`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
        )
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        vm.pauseScanning()
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }
}

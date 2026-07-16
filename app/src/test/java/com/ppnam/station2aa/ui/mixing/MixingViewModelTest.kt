package com.ppnam.station2aa.ui.mixing

import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "premix-1",
        lines = listOf(BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0))
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
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(sessionWithActions("cancel_premix")))

        viewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
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
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
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
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
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
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
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

        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
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
        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine)))
        )

        viewModel.submitManagerApproval("manager1", "5678")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        // v3's scanIngredient has no approvalId argument, so the initial rejected scan and the
        // retry after approval are indistinguishable by argument alone — hence times(2), not eq(1).
        verify(mockUseCase, times(2)).scanIngredient("premix-1", "EPC:300833", "full", 2.0)
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
        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
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
    fun `cancelJob resets state on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse())
        )
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
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

        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
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
            Result.success(com.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse())
        )

        viewModel.cancelJob(managerUsername = "Manager1", managerPassword = "5678")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(
            eq("premix-1"), eq("510019068"), any(), eq("Manager1"), eq("5678")
        )
    }

    @Test
    fun `cancelJob refuses to send without manager credentials`() = runTest {
        // v3 has no direct-cancel path: manager credentials are required on every privileged
        // action, checked against the approver's account, even when the sender is a Manager.
        // A job must be loaded first so this exercises the credential guard specifically,
        // rather than the pre-existing blank-currentOrderNo guard.
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.cancelJob(managerUsername = "", managerPassword = "")
        advanceUntilIdle()

        verify(mockUseCase, never()).cancelJob(any(), any(), any(), any(), any())
    }

    @Test
    fun `cancelJob forwards the supplied manager credentials`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        whenever(mockUseCase.cancelJob(any(), any(), any(), eq("manager1"), eq("secret")))
            .thenReturn(Result.success(mock()))

        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(any(), any(), any(), eq("manager1"), eq("secret"))
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
    fun `a stray scan while the manager-approval dialog is open is ignored and the dialog survives`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        // Scan listening starts once when the job loads and stays active continuously —
        // it is not restarted per-dialog, so this reproduces the real collector lifecycle.
        vm.startListeningForPalletScans("510019068")

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsManagerApproval("exception-1", "Wrong material"))
        )
        vm.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()
        assertTrue(
            "sanity check: the manager-approval dialog should be showing before the stray scan arrives",
            vm.uiState.value is MixingUiState.IngredientExceptionApproval
        )

        // A stray read for a different pallet lands while the manager-approval dialog owns the
        // screen. Unguarded, the collector would blindly flip state to EnteringBagDetails and
        // dismiss the dialog out from under the operator, losing the pending exception.
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:999999", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(
            "a stray scan mid-dialog must not clobber the manager-approval prompt",
            state is MixingUiState.IngredientExceptionApproval
        )
        assertEquals("exception-1", (state as MixingUiState.IngredientExceptionApproval).exceptionId)
    }

    @Test
    fun `a scan in the normal OrderLoaded state is still processed despite the guard`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)

        vm.startListeningForPalletScans("510019068")
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        // Over-correcting into ignoring every scan is worse than the original bug: the operator
        // would think the reader is broken. A legitimate scan from the normal scanning state must
        // still open the bag-entry dialog.
        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `a scan arriving while Error is showing still reaches the scan flow`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0))
            .thenReturn(Result.failure(RuntimeException("Station 2 did not respond")))
        vm.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()
        assertTrue(
            "sanity check: a failed scan attempt should leave the screen in Error",
            vm.uiState.value is MixingUiState.Error
        )

        // Error is a settled state, not an in-flight request or an open dialog. Rescanning from
        // here is the operator's only recovery path on this screen (clearError() is unwired and
        // the error card has no dismiss button), so the guard must let it through rather than
        // trapping the operator behind a dead-end error card.
        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(
            "a rescan over a settled Error must open EnteringBagDetails, not be dropped",
            state is MixingUiState.EnteringBagDetails
        )
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `pauseScanning cancels the active scan job so further scans are ignored`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
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

package com.ppnam.station2aa.ui.mixing

import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.domain.model.BomLine
import com.ppnam.station2aa.domain.model.HopperAvailability
import com.ppnam.station2aa.domain.model.HopperStatus
import com.ppnam.station2aa.domain.model.IngredientValidationResult
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
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
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        preMixId = "premix-1",
        lines = listOf(BomLine("MAT-001", "Resin", 1.0))
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.hopperStatusUpdates)
            .thenReturn(MutableSharedFlow())
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())

        viewModel = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository)
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
        whenever(mockUseCase.lookupJob(any())).thenReturn(Result.failure(Exception("Not found")))
        viewModel.lookupJob("bad")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.Error)
        assertEquals("Not found", (state as MixingUiState.Error).message)
    }

    @Test
    fun `discardInvalidIngredient resets state to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.discardInvalidIngredient()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `requestSupervisorOverride sets WaitingForSupervisor state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.WaitingForSupervisor)
        assertEquals("TAG-BAD", (state as MixingUiState.WaitingForSupervisor).tagId)
    }

    @Test
    fun `submitSupervisorTag on approval appends exception ingredient and resets to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val exceptionIngredient = ScannedIngredient("TAG-BAD", "MAT-999", 1.0, isException = true, approvedBy = "Jane")
        whenever(mockUseCase.approveIngredientException("510019068", "TAG-BAD", "SUP-001"))
            .thenReturn(Result.success(exceptionIngredient))

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-BAD", "SUP-001")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        assertTrue(viewModel.scannedIngredients.value.any { it.isException && it.tagId == "TAG-BAD" })
    }

    @Test
    fun `submitSupervisorTag on rejection stays WaitingForSupervisor`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.approveIngredientException(any(), any(), any()))
            .thenReturn(Result.failure(Exception("Tag not a supervisor")))

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-BAD", "NOT-SUP")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.WaitingForSupervisor)
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
    fun `cancelJob resets state and scanned ingredients so a new job can be looked up`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val exceptionIngredient = ScannedIngredient("TAG-001", "MAT-001", 1.0)
        whenever(mockUseCase.approveIngredientException(any(), any(), any()))
            .thenReturn(Result.success(exceptionIngredient))
        viewModel.requestSupervisorOverride("TAG-001", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-001", "SUP-001")
        advanceUntilIdle()
        assertTrue(viewModel.scannedIngredients.value.isNotEmpty())

        viewModel.cancelJob()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
        assertTrue(viewModel.scannedIngredients.value.isEmpty())
        assertEquals("", viewModel.hopperCode.value)
    }
}

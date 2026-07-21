package com.ppnam.station2aa.ui.mixing.board

import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.ActiveCycle
import com.ppnam.station2aa.domain.model.ActiveRun
import com.ppnam.station2aa.domain.model.AreaOverview
import com.ppnam.station2aa.domain.model.Equipment
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.domain.model.ReadyCollection
import com.ppnam.station2aa.domain.model.ReadyMix
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class MixingBoardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingBoardUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var connectionState: MutableStateFlow<MqttConnectionState>
    private lateinit var viewModel: MixingBoardViewModel

    // Test fixtures shared with Task 5's tests.
    private fun equipment(
        code: String, role: String = "Mixer", status: String = "Available",
        area: MixingArea? = MixingArea.Main, enabled: Boolean = true,
        currentCycleId: String? = null, currentJc: String? = null,
    ) = Equipment(
        machineCode = code, displayName = code, area = area, role = role,
        isEnabled = enabled, isAvailable = enabled && status == "Available", status = status,
        productLayer = null, currentCycleId = currentCycleId, currentJobCardNumber = currentJc,
        currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
        routeDescription = "",
    )

    private fun readyMix(id: String, jc: String = "510019068", validNext: List<String>) = ReadyMix(
        mixBatchId = id, collectionId = "COL_$id", area = MixingArea.Main, jobCardNumber = jc,
        mixerCode = "MXR-01", mixerDisplayName = "Main Mixer 1", status = "ReadyForProduction",
        validNextMachineCodes = validNext, nextStepDescription = "",
    )

    private val mainOverview = AreaOverview(
        equipment = listOf(
            equipment("MXR-01"), equipment("MXR-02", status = "InUse", currentCycleId = "CYC_9"),
            equipment("EXT-03", role = "ProductionMachine"),
            equipment("EXT-04", role = "ProductionMachine"),
        ),
        activeCycles = listOf(ActiveCycle(
            cycleId = "CYC_9", machineCode = "MXR-02", area = MixingArea.Main, role = "Mixer",
            jobCardNumber = "510019068", collectionId = "COL_9", mixBatchIds = listOf("MIX_9"),
            productionRunId = null, startedAtUtc = "2026-07-21T08:00:00Z", startedByOperatorId = "OP-001")),
        readyMixes = listOf(readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04"))),
        activeRuns = emptyList(),
    )

    private val readyCollections = listOf(ReadyCollection("COL_1", "510019068", "HD Film"))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        connectionState = MutableStateFlow(MqttConnectionState.CONNECTED)

        whenever(mockMqttRepository.connectionState).thenReturn(connectionState)
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))

        viewModel = MixingBoardViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadAreaPicker success carries the overview and the pending collection`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingBoardUiState.AreaPicker)
        assertEquals("COL_1", (state as MixingBoardUiState.AreaPicker).pendingCollectionId)
        verify(mockUseCase).fetchOverview(isNull(), anyOrNull())
    }

    @Test
    fun `loadAreaPicker failure sets Error`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(Exception("boom")))
        viewModel.loadAreaPicker(null)
        advanceUntilIdle()
        assertEquals("boom", (viewModel.uiState.value as MixingBoardUiState.Error).message)
    }

    @Test
    fun `openArea loads the filtered overview and collections and pre-selects the pending collection`() = runTest {
        // Stub BOTH shapes before any call — the eager dispatcher runs launches immediately.
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()

        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(MixingArea.Main, board.area)
        assertEquals(readyCollections, board.readyCollections)
        val sel = board.selection
        assertTrue(sel is BoardSelection.Collection)
        assertEquals("COL_1", (sel as BoardSelection.Collection).collectionId)
        // Collection selected -> available, enabled mixers highlight (MXR-01, not InUse MXR-02)
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)
    }

    @Test
    fun `reconnect triggers a refresh of the current board`() = runTest {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        connectionState.value = MqttConnectionState.DISCONNECTED
        connectionState.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        // openArea's overview fetch ran twice: once on entry, once on reconnect
        verify(mockUseCase, times(2)).fetchOverview(eq(MixingArea.Main), anyOrNull())
    }

    @Test
    fun `computeHighlightedMachines for mixes uses the validNext intersection and same-JC accumulation`() {
        val overview = mainOverview.copy(
            equipment = listOf(
                equipment("EXT-03", role = "ProductionMachine"),
                equipment("EXT-04", role = "ProductionMachine", status = "InUse", currentJc = "510019068"),
                equipment("EXT-05", role = "ProductionMachine", status = "InUse", currentJc = "510018531"),
                equipment("EXT-06", role = "ProductionMachine", status = "InUse", currentJc = "510018531"),
            ),
            readyMixes = listOf(
                readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04", "EXT-05", "EXT-06")),
                readyMix("MIX_2", validNext = listOf("EXT-03", "EXT-04", "EXT-06")),
            ),
            activeRuns = listOf(
                ActiveRun("RUN_1", "EXT-04", "510019068", listOf("MIX_0"), "2026-07-21T08:00:00Z"),
                ActiveRun("RUN_2", "EXT-05", "510018531", listOf("MIX_8"), "2026-07-21T08:00:00Z"),
                ActiveRun("RUN_3", "EXT-06", "510018531", listOf("MIX_7"), "2026-07-21T08:00:00Z"),
            ),
        )
        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mixes(listOf("MIX_1", "MIX_2"), "510019068"))
        // EXT-03 available+in intersection; EXT-04 accumulating same JC; EXT-05 other JC excluded;
        // EXT-06 IN intersection but its active run is on another JC — the true accumulation boundary
        assertEquals(setOf("EXT-03", "EXT-04"), highlights)
    }

    @Test
    fun `computeHighlightedMachines for a collection highlights only enabled available mixers`() {
        val overview = mainOverview.copy(equipment = listOf(
            equipment("MXR-01"),
            equipment("MXR-02", status = "InUse"),
            equipment("MXR-05", enabled = false, status = "Disabled"),
            equipment("EXT-03", role = "ProductionMachine"),
        ))
        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Collection("COL_1", "510019068"))
        assertEquals(setOf("MXR-01"), highlights)
    }

    @Test
    fun `the auto-nav pre-selection is one-shot — a refresh does not re-assert it`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()
        assertTrue((viewModel.uiState.value as MixingBoardUiState.Board).selection is BoardSelection.Collection)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue("a refresh must not re-assert the consumed auto-nav hint",
            (viewModel.uiState.value as MixingBoardUiState.Board).selection is BoardSelection.None)
    }
}

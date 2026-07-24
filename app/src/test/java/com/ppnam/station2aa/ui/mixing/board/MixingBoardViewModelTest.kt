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
        // 4.1: scanAllowed is decided by the server and is NOT the same as isAvailable — a mixer
        // reserved by the collection in hand is unavailable to everyone else yet is exactly what
        // this operator should scan. Defaults to the available case so existing fixtures read the
        // same; the reserved case sets it explicitly.
        scanAllowed: Boolean = enabled && status == "Available",
        mixPlanId: String? = null, reservationCollectionId: String? = null,
    ) = Equipment(
        machineCode = code, displayName = code, area = area, role = role,
        isEnabled = enabled, isAvailable = enabled && status == "Available", status = status,
        productLayer = null, currentCycleId = currentCycleId, currentJobCardNumber = currentJc,
        currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
        routeDescription = "",
        mixPlanId = mixPlanId, reservationCollectionId = reservationCollectionId,
        scanAllowed = scanAllowed,
    )

    private fun readyMix(
        id: String, jc: String = "510019068", validNext: List<String>,
        status: String = "ReadyForProduction", completionMode: String? = null,
        isAssignable: Boolean = true,
    ) = ReadyMix(
        mixBatchId = id, collectionId = "COL_$id", area = MixingArea.Main, jobCardNumber = jc,
        mixerCode = "MXR-01", mixerDisplayName = "Main Mixer 1", status = status,
        validNextMachineCodes = validNext, nextStepDescription = "",
        completionMode = completionMode, isAssignable = isAssignable,
    )

    /** A collection with a saved 4.1 mixer plan reserving [remaining]. */
    private fun plannedCollection(
        id: String = "COL_1", jc: String = "510019068", remaining: List<String> = listOf("MXR-01"),
    ) = ReadyCollection(
        collectionId = id, jobCardNumber = jc, productName = "HD Film",
        status = "MixingPlanned", mixPlanId = "MPL_1", mixPlanStatus = "Saved",
        plannedMixerCount = remaining.size, startedMixerCount = 0,
        remainingMixerCount = remaining.size,
        plannedMixerCodes = remaining, remainingMixerCodes = remaining,
        nextAction = "scan_reserved_mixer:" + remaining.joinToString(","),
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
        readyCollections = listOf(plannedCollection()),
    )

    private val readyCollections = listOf(plannedCollection())

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
    fun `computeHighlightedMachines for a planned collection highlights its remaining reserved mixers`() {
        // 4.1: the mixers belonging to this collection are RESERVED by its plan, so their status
        // is "Reserved", not "Available" — the pre-4.1 rule would have highlighted none of them.
        val overview = mainOverview.copy(
            equipment = listOf(
                equipment("MXR-01", status = "Reserved", scanAllowed = true,
                    mixPlanId = "MPL_1", reservationCollectionId = "COL_1"),
                equipment("MXR-02", status = "Reserved", scanAllowed = true,
                    mixPlanId = "MPL_1", reservationCollectionId = "COL_1"),
                // Reserved by somebody ELSE's collection: not scannable here.
                equipment("MXR-03", status = "Reserved", scanAllowed = false,
                    mixPlanId = "MPL_2", reservationCollectionId = "COL_9"),
                equipment("MXR-05", enabled = false, status = "Disabled"),
                equipment("EXT-03", role = "ProductionMachine"),
            ),
            // MXR-01 already started; only MXR-02 is left to scan.
            readyCollections = listOf(plannedCollection(remaining = listOf("MXR-02"))),
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Collection("COL_1", "510019068"))

        assertEquals(setOf("MXR-02"), highlights)
    }

    @Test
    fun `a collection with no saved plan highlights nothing`() {
        // The plan is saved in Station 2, not on the handheld. Highlighting a mixer the operator
        // cannot legally start would just earn them a `mixer_plan_required` rejection.
        val overview = mainOverview.copy(
            equipment = listOf(equipment("MXR-01"), equipment("MXR-02")),
            readyCollections = listOf(
                ReadyCollection(
                    collectionId = "COL_1", jobCardNumber = "510019068", productName = "HD Film",
                    status = "ReadyForMixing",
                    validMixerCodes = listOf("MXR-01", "MXR-02"),
                    nextAction = "save_mixer_plan_in_station_2",
                )
            ),
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Collection("COL_1", "510019068"))

        assertTrue(highlights.isEmpty())
    }

    @Test
    fun `a quarantined force-closed mix offers no destination at all`() {
        // Backend issue B2: a force-closed cycle still yielded a usable mix. 4.1 quarantines it,
        // and it stays unassignable until an audited Manager/Admin Release or Discard — which the
        // operator cannot do from the handheld.
        val overview = mainOverview.copy(
            readyMixes = listOf(
                readyMix("MIX_Q", validNext = listOf("EXT-03", "EXT-04"),
                    status = "Quarantined", completionMode = "ForceClosed", isAssignable = false)
            )
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mixes(listOf("MIX_Q"), "510019068"))

        assertTrue("a quarantined mix must not be routable to production", highlights.isEmpty())
    }

    @Test
    fun `a quarantined mix mixed into a selection cannot widen the destinations`() {
        // The intersection must be taken over assignable mixes only. If the quarantined one were
        // left in, its validNextMachineCodes would participate in the intersection and could even
        // narrow or widen the legal set based on material nobody may use.
        val overview = mainOverview.copy(
            readyMixes = listOf(
                readyMix("MIX_1", validNext = listOf("EXT-03")),
                readyMix("MIX_Q", validNext = listOf("EXT-03", "EXT-04"),
                    status = "Quarantined", completionMode = "ForceClosed", isAssignable = false),
            )
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mixes(listOf("MIX_1", "MIX_Q"), "510019068"))

        assertEquals(setOf("EXT-03"), highlights)
    }

    @Test
    fun `machineTabOf puts mixers under Collections and everything else under Mixing`() {
        val equipment = listOf(
            equipment("MXR-01"),
            equipment("EXT-03", role = "ProductionMachine"),
            equipment("TRF-01", role = "Transfer"),
            equipment("UNK-01", role = ""), // unmodelled role — still reachable, not dropped
        )
        assertEquals(
            listOf("MXR-01"),
            equipment.filter { machineTabOf(it) == MachineTab.Collections }.map { it.machineCode })
        assertEquals(
            listOf("EXT-03", "TRF-01", "UNK-01"),
            equipment.filter { machineTabOf(it) == MachineTab.Mixing }.map { it.machineCode })
    }

    @Test
    fun `every highlighted machine is visible on some tab`() {
        val overview = mainOverview.copy(activeRuns = emptyList())
        val collectionHighlights =
            computeHighlightedMachines(overview, BoardSelection.Collection("COL_1", "510019068"))
        val mixHighlights =
            computeHighlightedMachines(overview, BoardSelection.Mixes(listOf("MIX_1"), "510019068"))
        val collectionsTab = overview.equipment
            .filter { machineTabOf(it) == MachineTab.Collections }.map { it.machineCode }.toSet()
        val mixingTab = overview.equipment
            .filter { machineTabOf(it) == MachineTab.Mixing }.map { it.machineCode }.toSet()
        // A collection's highlights all sit on the Collections tab, a mix's on the Mixing tab —
        // the tab the selection auto-switches to.
        assertTrue(collectionsTab.containsAll(collectionHighlights))
        assertTrue(mixingTab.containsAll(mixHighlights))
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

    private suspend fun openMainBoard() {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
    }

    @Test
    fun `selectCollection highlights mixers and toggleMix respects the same-JC rule`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        var board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)

        viewModel.clearSelection()
        viewModel.toggleMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.Mixes)
        assertEquals(setOf("EXT-03", "EXT-04"), board.highlightedMachineCodes)

        // a second toggle removes it -> selection collapses to None
        viewModel.toggleMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
    }

    @Test
    fun `machineChosen with no selection opens the cycle sheet for a busy machine`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        val sheet = board.sheet
        assertTrue(sheet is BoardSheet.CycleSheet)
        assertEquals("CYC_9", (sheet as BoardSheet.CycleSheet).cycle.cycleId)
    }

    @Test
    fun `machineChosen with no selection on an idle machine only messages`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-01")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `machineChosen with a collection on a Rajoo mixer fetches dose rows`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials("510019068", "COL_1")).thenReturn(
            Result.success(listOf(
                com.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 550.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue(sheet is BoardSheet.StartConfirm)
        val rows = (sheet as BoardSheet.StartConfirm).doseRows
        assertEquals("MAT-1", rows!!.single().materialCode)
        assertEquals(550.0, rows.single().collectedQty, 0.0)
    }

    @Test
    fun `confirmStart for a collection on a plain mixer calls startMixer`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startMixer(any(), any(), any())).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "MXR-01", cycleId = "CYC_1",
                mixBatchId = "MIX_5", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_5"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startMixer("MXR-01", "510019068", "COL_1")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
        assertTrue(board.sheet is BoardSheet.None)
        assertFalse(board.busy)
    }

    @Test
    fun `confirmStart for mixes calls startDownstream with the selected ids`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startDownstream(any(), any(), any())).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "EXT-03", cycleId = "RUN_1",
                mixBatchId = null, productionRunId = "RUN_1",
                affectedMixBatchIds = listOf("MIX_1"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.toggleMix("MIX_1")
        viewModel.machineChosen("EXT-03")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startDownstream("EXT-03", "510019068", listOf("MIX_1"))
    }

    @Test
    fun `a rejected start applies the embedded areaStatus and keeps the selection`() = runTest {
        openMainBoard(); advanceUntilIdle()
        val refreshed = mainOverview.copy(equipment = listOf(equipment("MXR-01", status = "InUse")))
        whenever(mockUseCase.startMixer(any(), any(), any())).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Rejected(
                errorCode = com.ppnam.station2aa.data.mqtt.ErrorCode.EQUIPMENT_IN_USE,
                reason = "Busy on another cycle.", areaStatus = refreshed))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals("InUse", board.overview.equipment.single { it.machineCode == "MXR-01" }.status)
        assertTrue("selection survives a rejection so the operator can retry elsewhere",
            board.selection is BoardSelection.Collection)
        // and highlights were recomputed against the refreshed overview
        assertTrue(board.highlightedMachineCodes.isEmpty())
    }

    @Test
    fun `a rejection without areaStatus keeps the current board picture`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startMixer(any(), any(), any())).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Rejected(
                errorCode = com.ppnam.station2aa.data.mqtt.ErrorCode.SESSION_REQUIRED,
                reason = "No valid session.", areaStatus = null))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue("an envelope-level rejection must not blank the board",
            board.overview.equipment.isNotEmpty())
        assertTrue(board.overview.equipment.any { it.machineCode == "MXR-01" })
        assertTrue("selection survives a rejection so the operator can retry elsewhere",
            board.selection is BoardSelection.Collection)
    }

    @Test
    fun `a late area load cannot overwrite a newer one`() = runTest {
        val slowGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Dolci), anyOrNull())).doSuspendableAnswer {
            slowGate.await()
            Result.success(mainOverview)
        }
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull()))
            .thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))

        viewModel.openArea(MixingArea.Dolci) // suspends on the gate
        viewModel.openArea(MixingArea.Main)  // cancels the Dolci load
        advanceUntilIdle()
        slowGate.complete(Unit)              // late Dolci result must be dead
        advanceUntilIdle()

        assertEquals(MixingArea.Main, (viewModel.uiState.value as MixingBoardUiState.Board).area)
    }

    @Test
    fun `rajoo confirm validates doses fail-closed`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials(any(), any())).thenReturn(
            Result.success(listOf(
                com.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 100.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        viewModel.updateDose("MAT-1", "150.0") // above collected
        viewModel.confirmStart()
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.StartConfirm).validationError)
        verify(mockUseCase, never()).startRajoo(any(), any(), any(), any())
    }

    @Test
    fun `a scan while a sheet is open is ignored`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()
        vm.machineChosen("MXR-02") // opens the cycle sheet

        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-01", "CODE128", java.time.Instant.now()))
        advanceUntilIdle()

        val sheet = (vm.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue("cycle sheet must survive a stray scan", sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `a scan on the board dispatches machineChosen`() = runTest {
        val events = MutableSharedFlow<com.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()

        events.emit(com.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-02", "CODE128", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue((vm.uiState.value as MixingBoardUiState.Board).sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `finishCycle sends the stored cycle id and messages alreadyFinished as success`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.finish("MXR-02", "CYC_9")).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Finished", machineCode = "MXR-02", cycleId = "CYC_9",
                mixBatchId = "MIX_9", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_9"), alreadyFinished = true,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.machineChosen("MXR-02")
        viewModel.finishCycle()
        advanceUntilIdle()

        verify(mockUseCase).finish("MXR-02", "CYC_9")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `a failed (timed out) cycle action re-syncs the board from the server instead of trusting the stale cache`() = runTest {
        // The real bug this guards: a machine_cycle request can time out client-side (flaky
        // WiFi/slow backend) while Station 2 actually accepted it. The late confirmation gets
        // dropped by MqttRepositoryImpl's correlation matching, so the ONLY way the board learns
        // the true state is a fresh overview fetch — never trusting whatever it had cached before.
        val refreshedAfterServerActuallyFinished = mainOverview.copy(
            equipment = listOf(equipment("MXR-01"), equipment("MXR-02", status = "Available")),
            activeCycles = emptyList(),
        )
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(
            Result.success(mainOverview), Result.success(refreshedAfterServerActuallyFinished))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()
        viewModel.machineChosen("MXR-02")

        whenever(mockUseCase.finish("MXR-02", "CYC_9")).thenReturn(
            com.ppnam.station2aa.domain.model.MachineCycleOutcome.Failed("Timed out waiting for Station 2"))
        viewModel.finishCycle()
        advanceUntilIdle()

        verify(mockUseCase, times(2)).fetchOverview(eq(MixingArea.Main), anyOrNull())
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals("Available", board.overview.equipment.single { it.machineCode == "MXR-02" }.status)
        assertTrue(board.overview.activeCycles.isEmpty())
        assertTrue(board.sheet is BoardSheet.None)
        assertFalse(board.busy)
    }

    @Test
    fun `submitForceClose refuses blank credentials without touching the wire`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        viewModel.openForceClose()
        viewModel.submitForceClose("", "", "")
        advanceUntilIdle()

        verify(mockUseCase, never()).forceClose(any(), any(), any(), any(), any())
        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.ForceCloseDialog).validationError)
    }

    @Test
    fun `toggleMix ignores a mix from another job card once one is selected`() = runTest {
        val overview = mainOverview.copy(readyMixes = listOf(
            readyMix("MIX_1", validNext = listOf("EXT-03")),
            readyMix("MIX_OTHER", jc = "510018531", validNext = listOf("EXT-03")),
        ))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(overview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        viewModel.toggleMix("MIX_1")
        viewModel.toggleMix("MIX_OTHER") // other JC — the same-JC mirror must ignore this tap

        val selection = (viewModel.uiState.value as MixingBoardUiState.Board).selection
        assertTrue(selection is BoardSelection.Mixes)
        assertEquals(listOf("MIX_1"), (selection as BoardSelection.Mixes).mixBatchIds)
    }
}

package com.ppnam.station2aa.ui.mixing.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.ActiveCycle
import com.ppnam.station2aa.domain.model.AreaOverview
import com.ppnam.station2aa.domain.model.Equipment
import com.ppnam.station2aa.domain.model.LayerInput
import com.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.domain.model.ReadyCollection
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import com.ppnam.station2aa.ui.components.ConnectionStatus
import com.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the operator has picked as the START source (source-first, user decision 4). */
sealed class BoardSelection {
    object None : BoardSelection()
    data class Collection(val collectionId: String, val jobCardNumber: String) : BoardSelection()
    data class Mixes(val mixBatchIds: List<String>, val jobCardNumber: String) : BoardSelection()
}

/** One Rajoo dose-entry row; [doseText] is the raw operator input, validated on confirm. */
data class DoseRow(
    val materialCode: String,
    val materialName: String,
    val collectedQty: Double,
    val doseText: String = "",
)

/** The one dialog the board may own at a time. The scan guard blocks scans while != None. */
sealed class BoardSheet {
    object None : BoardSheet()

    /** Start confirmation. [doseRows] is non-null only for a Rajoo mixer start. */
    data class StartConfirm(
        val machine: Equipment,
        val doseRows: List<DoseRow>?,
        val validationError: String? = null,
    ) : BoardSheet()

    data class CycleSheet(val machine: Equipment, val cycle: ActiveCycle) : BoardSheet()

    data class ForceCloseDialog(
        val machine: Equipment,
        val cycle: ActiveCycle,
        val validationError: String? = null,
    ) : BoardSheet()
}

sealed class MixingBoardUiState {
    object Loading : MixingBoardUiState()
    data class Error(val message: String) : MixingBoardUiState()

    /** The five-area entry screen. [pendingCollectionId] fills the "ready to mix" banner. */
    data class AreaPicker(
        val overview: AreaOverview,
        val pendingCollectionId: String?,
    ) : MixingBoardUiState()

    data class Board(
        val area: MixingArea,
        val overview: AreaOverview,
        val readyCollections: List<ReadyCollection>,
        val selection: BoardSelection = BoardSelection.None,
        val highlightedMachineCodes: Set<String> = emptySet(),
        val sheet: BoardSheet = BoardSheet.None,
        /** A cycle request or dose fetch is in flight; scans and taps are ignored. */
        val busy: Boolean = false,
    ) : MixingBoardUiState()
}

/**
 * The machine grid splits by what a machine can be started FROM: a collection starts on a
 * mixer, a finished mix moves downstream. An area carries more machines than fit one screen,
 * so the grid shows one side at a time.
 */
enum class MachineTab(val label: String) {
    Collections("Collections"),
    Mixing("Mixing"),
}

/**
 * Every machine lands in exactly one tab, so none is unreachable. Downstream is the default:
 * an unknown or blank role (§13.7 tolerates roles we don't model) still shows up somewhere.
 */
internal fun machineTabOf(machine: Equipment): MachineTab =
    if (machine.role == "Mixer") MachineTab.Collections else MachineTab.Mixing

/**
 * The pure highlight rule, unit-testable without the ViewModel. Highlights guide TAPS only —
 * a SCAN of any machine is trusted intent and goes to the server regardless (§13.7/§13.8:
 * availability and destinations render from server data; the server stays authoritative).
 */
internal fun computeHighlightedMachines(overview: AreaOverview, selection: BoardSelection): Set<String> =
    when (selection) {
        is BoardSelection.None -> emptySet()

        // 4.1: a collection's mixers are RESERVED by its saved plan, so "enabled and Available"
        // is exactly the wrong predicate — a reserved mixer's status is `Reserved`, which would
        // highlight none of the machines this operator should scan, while happily highlighting
        // mixers reserved by somebody else's collection.
        //
        // The plan's own remainingMixerCodes is the answer, intersected with the equipment the
        // server says this handheld may scan. Both come from the server; nothing is inferred.
        is BoardSelection.Collection -> {
            val plan = overview.readyCollections.firstOrNull {
                it.collectionId == selection.collectionId
            }
            val scannable = overview.equipment
                .filter { machineTabOf(it) == MachineTab.Collections && it.isEnabled && it.scanAllowed }
                .map { it.machineCode }
                .toSet()
            when {
                // Planned: highlight exactly the mixers still to be scanned.
                plan != null && plan.hasSavedPlan ->
                    plan.remainingMixerCodes.toSet() intersect scannable
                // Saved plan pending: the server tells us which mixers are legal to plan against,
                // but none may be started yet — the plan is saved in Station 2, not here.
                plan != null -> emptySet()
                // No plan row at all (a stale selection): highlight nothing rather than guess.
                else -> emptySet()
            }
        }

        is BoardSelection.Mixes -> {
            val chosen = overview.readyMixes
                .filter { it.mixBatchId in selection.mixBatchIds }
                // 4.1/B2: a force-closed mix is Quarantined and never assignable until an audited
                // Manager/Admin Release or Discard. Offering a destination for one would let the
                // operator send quarantined material to production — the exact defect B2 reported.
                .filter { it.isAssignable }
            if (chosen.isEmpty()) {
                // Every selected mix is quarantined: no destination is legal.
                emptySet()
            } else {
                val intersection = chosen
                    .map { it.validNextMachineCodes.toSet() }
                    .reduceOrNull { a, b -> a intersect b }
                    .orEmpty()
                val available = overview.equipment
                    .filter { it.machineCode in intersection && it.isEnabled && it.status == "Available" }
                    .map { it.machineCode }
                // Run accumulation (§8): a production machine busy on the SAME JC with an
                // active run accepts additional completed mixes into that run.
                val accumulating = overview.activeRuns
                    .filter { it.jobCardNumber == selection.jobCardNumber && it.machineCode in intersection }
                    .map { it.machineCode }
                (available + accumulating).toSet()
            }
        }
    }

@HiltViewModel
class MixingBoardViewModel @Inject constructor(
    private val useCase: MixingBoardUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingBoardUiState>(MixingBoardUiState.Loading)
    val uiState: StateFlow<MixingBoardUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    /** Operator-facing snackbar lines: server reasons, start/finish confirmations. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The collection that triggered auto-navigation; pre-selected when an area opens. */
    private var pendingCollectionId: String? = null

    /** Task 5: in-flight cycle-operation guard (the capture VM's approvalJob discipline). */
    private var actionJob: Job? = null

    /** Cancels a superseded area load so a late response can't overwrite a newer one. */
    private var loadJob: Job? = null

    init {
        // Reconnect refresh (§13.11): the board is stale after any transport drop.
        viewModelScope.launch {
            mqttRepository.connectionState
                .drop(1) // the value at subscribe time is not a transition
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { refresh() }
        }

        // Scan-first machine selection (user decision 2). Guarded exactly like the capture
        // screen: a scan lands only on a quiet board — never over a sheet or an in-flight request.
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                val board = _uiState.value as? MixingBoardUiState.Board ?: return@collect
                if (board.sheet != BoardSheet.None || board.busy) return@collect
                val code = when (event) {
                    is ScanEvent.RfidTag -> event.tagId
                    is ScanEvent.Barcode -> event.value
                }
                machineChosen(code)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }

    fun loadAreaPicker(pendingCollectionId: String?) {
        this.pendingCollectionId = pendingCollectionId?.takeIf { it.isNotBlank() }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = MixingBoardUiState.Loading
            useCase.fetchOverview()
                .onSuccess {
                    _uiState.value = MixingBoardUiState.AreaPicker(
                        it, this@MixingBoardViewModel.pendingCollectionId)
                }
                .onFailure {
                    _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load mixing overview")
                }
        }
    }

    fun openArea(area: MixingArea) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = MixingBoardUiState.Loading
            val overview = useCase.fetchOverview(area).getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load $area")
                return@launch
            }
            val collections = useCase.fetchReadyCollections().getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load collections")
                return@launch
            }
            // Auto-navigation context: pre-select the pending collection while it is still ready.
            val selection = pendingCollectionId
                ?.let { pending -> collections.firstOrNull { it.collectionId == pending } }
                ?.let { BoardSelection.Collection(it.collectionId, it.jobCardNumber) }
                ?: BoardSelection.None
            // One-shot: the auto-nav hint must not re-assert itself over the operator's
            // later manual choices when a reconnect refresh re-derives this board.
            pendingCollectionId = null
            _uiState.value = MixingBoardUiState.Board(
                area = area,
                overview = overview,
                readyCollections = collections,
                selection = selection,
                highlightedMachineCodes = computeHighlightedMachines(overview, selection),
            )
        }
    }

    /**
     * Re-fetches whatever is on screen. A refresh resets an in-progress selection —
     * server state has moved, so stale selections must not survive it.
     */
    fun refresh() {
        when (val state = _uiState.value) {
            is MixingBoardUiState.AreaPicker -> loadAreaPicker(pendingCollectionId)
            is MixingBoardUiState.Board -> if (!state.busy) openArea(state.area)
            else -> Unit
        }
    }

    private fun board(): MixingBoardUiState.Board? = _uiState.value as? MixingBoardUiState.Board

    private fun setBoard(board: MixingBoardUiState.Board) {
        _uiState.value = board.copy(
            highlightedMachineCodes = computeHighlightedMachines(board.overview, board.selection))
    }

    fun selectCollection(collectionId: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val collection = board.readyCollections.firstOrNull { it.collectionId == collectionId } ?: return
        setBoard(board.copy(selection = BoardSelection.Collection(collection.collectionId, collection.jobCardNumber)))
    }

    fun toggleMix(mixBatchId: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val mix = board.overview.readyMixes.firstOrNull { it.mixBatchId == mixBatchId } ?: return
        val current = board.selection as? BoardSelection.Mixes
        // Same-JC rule (client mirror of job_card_mismatch): ignore taps on other-JC mixes.
        if (current != null && current.jobCardNumber != mix.jobCardNumber) return
        val ids = when {
            current == null -> listOf(mixBatchId)
            mixBatchId in current.mixBatchIds -> current.mixBatchIds - mixBatchId
            else -> current.mixBatchIds + mixBatchId
        }
        val selection = if (ids.isEmpty()) BoardSelection.None
        else BoardSelection.Mixes(ids, mix.jobCardNumber)
        setBoard(board.copy(selection = selection))
    }

    fun clearSelection() {
        val board = board() ?: return
        if (board.busy) return
        setBoard(board.copy(selection = BoardSelection.None))
    }

    /**
     * A machine was scanned or a highlighted card tapped. With a selection this opens the
     * start-confirm sheet; without one it opens the machine's active-cycle sheet, or just
     * explains. An unknown scanned code still proceeds with a stub — trusted intent,
     * server-authoritative rejection after confirm.
     */
    fun machineChosen(machineCode: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val machine = board.overview.equipment.firstOrNull { it.machineCode == machineCode }
            ?: Equipment(
                machineCode = machineCode, displayName = machineCode, area = board.area,
                role = "", isEnabled = true, isAvailable = false, status = "",
                productLayer = null, currentCycleId = null, currentJobCardNumber = null,
                currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
                routeDescription = "",
            )
        when (val selection = board.selection) {
            is BoardSelection.None -> {
                val cycle = board.overview.activeCycles.firstOrNull { it.machineCode == machineCode }
                if (cycle != null) {
                    setBoard(board.copy(sheet = BoardSheet.CycleSheet(machine, cycle)))
                } else {
                    _messages.trySend("Select a collection or mix to start this machine.")
                }
            }
            is BoardSelection.Collection -> {
                if (machine.area == MixingArea.Rajoo && machine.role == "Mixer") {
                    // Rajoo dose rows come from the collection's collected lines.
                    viewModelScope.launch {
                        setBoard(board.copy(busy = true))
                        useCase.fetchCollectedMaterials(selection.jobCardNumber, selection.collectionId)
                            .onSuccess { materials ->
                                val rows = materials.map { DoseRow(it.materialCode, it.materialName, it.collectedQty) }
                                setBoard(board.copy(busy = false,
                                    sheet = BoardSheet.StartConfirm(machine, doseRows = rows)))
                            }
                            .onFailure {
                                setBoard(board.copy(busy = false))
                                _messages.trySend(it.message ?: "Could not load the collection's materials")
                            }
                    }
                } else {
                    setBoard(board.copy(sheet = BoardSheet.StartConfirm(machine, doseRows = null)))
                }
            }
            is BoardSelection.Mixes ->
                setBoard(board.copy(sheet = BoardSheet.StartConfirm(machine, doseRows = null)))
        }
    }

    fun updateDose(materialCode: String, text: String) {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        val rows = sheet.doseRows ?: return
        setBoard(board.copy(sheet = sheet.copy(
            doseRows = rows.map { if (it.materialCode == materialCode) it.copy(doseText = text) else it },
            validationError = null)))
    }

    fun dismissSheet() {
        val board = board() ?: return
        if (board.busy) return
        setBoard(board.copy(sheet = BoardSheet.None))
    }

    fun confirmStart() {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        val machine = sheet.machine
        actionJob = viewModelScope.launch {
            val outcome: MachineCycleOutcome = when (val selection = board.selection) {
                is BoardSelection.Collection -> {
                    if (sheet.doseRows != null) {
                        val doses = validateDoses(sheet.doseRows)
                        if (doses == null) return@launch // validationError already set
                        setBoard(board.copy(busy = true))
                        useCase.startRajoo(machine.machineCode, selection.jobCardNumber,
                            selection.collectionId, doses)
                    } else {
                        setBoard(board.copy(busy = true))
                        useCase.startMixer(machine.machineCode, selection.jobCardNumber, selection.collectionId)
                    }
                }
                is BoardSelection.Mixes -> {
                    setBoard(board.copy(busy = true))
                    useCase.startDownstream(machine.machineCode, selection.jobCardNumber, selection.mixBatchIds)
                }
                is BoardSelection.None -> return@launch
            }
            applyOutcome(outcome) { accepted ->
                val id = accepted.productionRunId ?: accepted.cycleId ?: ""
                "Started $id on ${accepted.machineCode}"
            }
        }
    }

    /** Returns null and surfaces a validation error when the rows are not sendable. */
    private fun validateDoses(rows: List<DoseRow>): List<LayerInput>? {
        val entered = rows.filter { it.doseText.isNotBlank() }
        val error = when {
            entered.isEmpty() -> "Enter at least one dose."
            entered.size > 5 -> "A Rajoo start takes at most five dose lines."
            entered.any { it.doseText.toDoubleOrNull()?.let { d -> d > 0.0 } != true } ->
                "Every dose must be a positive number."
            entered.any { it.doseText.toDouble() > it.collectedQty + 0.001 } ->
                "A dose cannot exceed the collected quantity."
            else -> null
        }
        if (error != null) {
            val board = board() ?: return null
            val sheet = board.sheet as? BoardSheet.StartConfirm ?: return null
            setBoard(board.copy(sheet = sheet.copy(validationError = error)))
            return null
        }
        return entered.map { LayerInput(it.materialCode, it.doseText.toDouble()) }
    }

    fun finishCycle() {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.CycleSheet ?: return
        actionJob = viewModelScope.launch {
            setBoard(board.copy(busy = true))
            val outcome = useCase.finish(sheet.machine.machineCode, sheet.cycle.cycleId)
            applyOutcome(outcome) { accepted ->
                if (accepted.alreadyFinished) "Cycle ${sheet.cycle.cycleId} was already finished"
                else "Cycle ${sheet.cycle.cycleId} finished"
            }
        }
    }

    fun openForceClose() {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.CycleSheet ?: return
        setBoard(board.copy(sheet = BoardSheet.ForceCloseDialog(sheet.machine, sheet.cycle)))
    }

    fun submitForceClose(managerUsername: String, managerPassword: String, auditReason: String) {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.ForceCloseDialog ?: return
        // Fail-closed: never put a blank credential or audit-trail entry on the wire.
        val validation = when {
            managerUsername.isBlank() || managerPassword.isBlank() ->
                "Manager username and password are required."
            auditReason.isBlank() -> "Audit reason is required."
            else -> null
        }
        if (validation != null) {
            setBoard(board.copy(sheet = sheet.copy(validationError = validation)))
            return
        }
        actionJob = viewModelScope.launch {
            setBoard(board.copy(busy = true))
            val outcome = useCase.forceClose(
                sheet.machine.machineCode, sheet.cycle.cycleId,
                managerUsername, managerPassword, auditReason)
            applyOutcome(outcome) { accepted ->
                "Cycle ${sheet.cycle.cycleId} force-closed" +
                    (accepted.approverDisplayName?.let { " (approved by $it)" } ?: "")
            }
        }
    }

    /**
     * Applies a machine-cycle outcome. Accepted always carries areaStatus (§8) — the board
     * refreshes from the response itself, clears the selection, and re-fetches ready
     * collections (a mixer start consumes one). Rejected carries areaStatus for business
     * rejections too, but null for envelope/session-level rejections — in that case the
     * board's current overview is kept as-is. Rejected keeps the selection so the operator
     * can retry another machine against the refreshed board.
     */
    private suspend fun applyOutcome(
        outcome: MachineCycleOutcome,
        successMessage: (MachineCycleOutcome.Accepted) -> String,
    ) {
        val board = board() ?: return
        when (outcome) {
            is MachineCycleOutcome.Accepted -> {
                val collections = useCase.fetchReadyCollections().getOrElse { board.readyCollections }
                setBoard(board.copy(
                    overview = outcome.areaStatus,
                    readyCollections = collections,
                    selection = BoardSelection.None,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(successMessage(outcome))
            }
            is MachineCycleOutcome.Rejected -> {
                setBoard(board.copy(
                    overview = outcome.areaStatus ?: board.overview,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(outcome.reason)
            }
            is MachineCycleOutcome.Failed -> {
                // No response reached us (timeout, drop, disconnect) — Station 2 may still have
                // applied the change server-side. Trusting our stale cache here is exactly the bug:
                // the board would keep showing a machine as free/occupied when it no longer is, and
                // e.g. a just-started cycle would never appear to finish against. Re-sync from the
                // server instead of guessing. Same reasoning as Accepted (§8): clear the selection
                // and re-fetch ready collections too, since a "no response" start/finish may have
                // actually landed server-side and consumed exactly what's still selected.
                val resynced = useCase.fetchOverview(board.area).getOrNull()
                val collections = useCase.fetchReadyCollections().getOrElse { board.readyCollections }
                setBoard(board.copy(
                    overview = resynced ?: board.overview,
                    readyCollections = collections,
                    selection = BoardSelection.None,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(outcome.message)
            }
        }
    }
}

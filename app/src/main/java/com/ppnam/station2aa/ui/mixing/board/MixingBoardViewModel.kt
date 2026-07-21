package com.ppnam.station2aa.ui.mixing.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.ActiveCycle
import com.ppnam.station2aa.domain.model.AreaOverview
import com.ppnam.station2aa.domain.model.Equipment
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.domain.model.ReadyCollection
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import com.ppnam.station2aa.ui.components.ConnectionStatus
import com.ppnam.station2aa.ui.components.resolveConnectionStatus
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
 * The pure highlight rule, unit-testable without the ViewModel. Highlights guide TAPS only —
 * a SCAN of any machine is trusted intent and goes to the server regardless (§13.7/§13.8:
 * availability and destinations render from server data; the server stays authoritative).
 */
internal fun computeHighlightedMachines(overview: AreaOverview, selection: BoardSelection): Set<String> =
    when (selection) {
        is BoardSelection.None -> emptySet()
        is BoardSelection.Collection -> overview.equipment
            .filter { it.role == "Mixer" && it.isEnabled && it.status == "Available" }
            .map { it.machineCode }
            .toSet()
        is BoardSelection.Mixes -> {
            val chosen = overview.readyMixes.filter { it.mixBatchId in selection.mixBatchIds }
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

    val connectionStatus: StateFlow<ConnectionStatus> = combine(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ) { state, stationOnline, skew ->
        resolveConnectionStatus(state, stationOnline, skew)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

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

    init {
        // Reconnect refresh (§13.11): the board is stale after any transport drop.
        viewModelScope.launch {
            mqttRepository.connectionState
                .drop(1) // the value at subscribe time is not a transition
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { refresh() }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
}

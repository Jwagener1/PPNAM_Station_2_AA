package com.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.domain.usecase.MixingUseCase
import com.ppnam.station2aa.ui.components.ConnectionStatus
import com.ppnam.station2aa.ui.components.resolveConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    object Cancelling : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class EnteringBagDetails(val palletTag: String) : MixingUiState()
    data class IngredientExceptionApproval(val exceptionId: String, val reason: String) : MixingUiState()
    data class PalletRecoveryPrompt(val palletTag: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}

object MixingNavDestination {
    const val JOB_LOADED = "job_loaded"
    const val HOME = "home"
}

sealed class CancelOutcome {
    object Confirmed : CancelOutcome()
    data class Failed(val reason: String) : CancelOutcome()
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    private val sessionHolder: OperatorSessionHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

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

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }

    private val _activeJobs = MutableStateFlow<List<ActiveJobCardSummary>>(emptyList())
    val activeJobs: StateFlow<List<ActiveJobCardSummary>> = _activeJobs.asStateFlow()

    private val _activeJobsError = MutableStateFlow<String?>(null)
    val activeJobsError: StateFlow<String?> = _activeJobsError.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private val _supervisorError = Channel<String>(Channel.BUFFERED)
    val supervisorError: Flow<String> = _supervisorError.receiveAsFlow()

    private val _cancelOutcome = Channel<CancelOutcome>(Channel.BUFFERED)
    val cancelOutcome: Flow<CancelOutcome> = _cancelOutcome.receiveAsFlow()

    private var scanJob: Job? = null
    private var currentOrderNo: String = ""
    private var cachedOrder: ProductionOrder? = null

    fun lookupJob(orderNo: String, collectionId: String = "") {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo, collectionId)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    _uiState.value = MixingUiState.OrderLoaded(order)
                    _navigationEvent.send(MixingNavDestination.JOB_LOADED)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun loadActiveJobs() {
        viewModelScope.launch {
            useCase.fetchActiveJobCards()
                .onSuccess { jobs ->
                    _activeJobs.value = jobs
                    _activeJobsError.value = null
                }
                .onFailure { e -> _activeJobsError.value = e.message ?: "Could not load active jobs" }
        }
    }

    private data class PendingIngredientScan(
        val palletRfidTag: String,
        val bagSizeOption: String,
        val bagCount: Double
    )

    private var pendingScan: PendingIngredientScan? = null
    private var pendingExceptionId: String = ""
    private var pendingExceptionMaterialCode: String = ""

    fun pauseScanning() {
        scanJob?.cancel()
    }

    fun startListeningForPalletScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // Barcode scans are accepted here too as a stand-in for RFID tags until
            // real RFID hardware is available on this handheld.
            scanEventBus.events.collect { event ->
                // A scan landing mid-request or over an open dialog would clobber in-flight state
                // or dismiss the dialog under the operator's hands. Ignore reads while a request is
                // in flight or a dialog owns the screen. Error is a settled state, not an in-flight
                // one — clearError() has no caller and the error card has no dismiss button, so
                // rescanning here is the operator's only way to recover; treat it like OrderLoaded.
                when (_uiState.value) {
                    is MixingUiState.OrderLoaded, is MixingUiState.Error -> {
                        val palletTag = when (event) {
                            is ScanEvent.RfidTag -> event.tagId
                            is ScanEvent.Barcode -> event.value
                        }
                        _uiState.value = MixingUiState.EnteringBagDetails(palletTag)
                    }
                    else -> return@collect
                }
            }
        }
    }

    fun cancelBagEntry() {
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            // TODO(Task 6): requestedMaterialCode is not yet tracked for an ordinary pallet scan;
            // "" is a placeholder left for Task 6, which reworks this whole call site.
            useCase.scanIngredient(order.collectionId, palletTag, bagSizeOption, bagCount, "")
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    // TODO(Task 6): v3 deletes approveManagerException and the approvalId handshake it used. The
    // replacement is an inline resubmit — useCase.scanIngredient(..., managerUsername,
    // managerPassword, auditReason) using the fields NeedsManagerApproval now carries — but wiring
    // that up (including capturing an audit reason from the operator) is Task 6's job, not Task 4's.
    // This stub only keeps the project compiling after Task 4 deleted approveManagerException; it
    // still dead-ends, same as before.
    fun submitManagerApproval(managerUsername: String, managerPassword: String) {
        _supervisorError.trySend("Manager approval is being reimplemented for schema 3.0 (Task 6)")
    }

    fun cancelManagerApproval() {
        pendingScan = null
        pendingExceptionId = ""
        pendingExceptionMaterialCode = ""
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmPalletRecovery() {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.recoverHolding(order.collectionId, scan.palletRfidTag)
                .onSuccess { retryPendingScan(order, "") }
                .onFailure { e ->
                    pendingScan = null
                    _supervisorError.trySend(e.message ?: "Recovery failed")
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
        }
    }

    fun dismissPalletRecovery() {
        pendingScan = null
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    private fun handleScanOutcome(order: ProductionOrder, outcome: IngredientScanOutcome) {
        when (outcome) {
            is IngredientScanOutcome.Accepted -> {
                val updatedOrder = order.copy(lines = outcome.updatedLines)
                cachedOrder = updatedOrder
                pendingScan = null
                _uiState.value = MixingUiState.OrderLoaded(updatedOrder)
            }
            is IngredientScanOutcome.NeedsManagerApproval -> {
                // TODO(Task 6): v3 has no exceptionId; NeedsManagerApproval now carries the whole
                // original scan instead so it can be resubmitted with credentials attached. Wiring
                // that resubmit through submitManagerApproval() is Task 6's job.
                pendingExceptionId = ""
                pendingExceptionMaterialCode = outcome.requestedMaterialCode
                _uiState.value = MixingUiState.IngredientExceptionApproval("", outcome.reason)
            }
            is IngredientScanOutcome.NeedsRecovery -> {
                _uiState.value = MixingUiState.PalletRecoveryPrompt(pendingScan?.palletRfidTag ?: "")
            }
            is IngredientScanOutcome.Rejected -> {
                pendingScan = null
                _supervisorError.trySend(outcome.reason)
                _uiState.value = MixingUiState.OrderLoaded(order)
            }
            is IngredientScanOutcome.NeedsApprovalForWaiver -> {
                // TODO(Task 6): waiveShortBags() is not yet wired into this ViewModel, so a
                // waiver can't actually reach this branch today. Minimal handling to keep the
                // `when` exhaustive: surface the reason and fall back to the loaded order — the
                // UI re-collecting credentials into a fresh waiveShortBags() call is Task 6's job.
                _supervisorError.trySend(outcome.reason)
                _uiState.value = MixingUiState.OrderLoaded(order)
            }
        }
    }

    private fun retryPendingScan(order: ProductionOrder, approvalId: String) {
        val scan = pendingScan
        if (scan == null) {
            _uiState.value = MixingUiState.OrderLoaded(order)
            return
        }
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            // TODO(Task 6): same requestedMaterialCode placeholder as confirmIngredientScan.
            useCase.scanIngredient(order.collectionId, scan.palletRfidTag, scan.bagSizeOption, scan.bagCount, "")
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun clearError() {
        if (_uiState.value is MixingUiState.Error) _uiState.value = MixingUiState.Idle
    }

    // Waits for premix_cancel_result before touching any local state — a rejected
    // cancel (e.g. the pre-mix already has scanned ingredients, or the manager
    // approval was denied) must leave the job exactly as it was, per the backend's
    // "only an untouched JC load can be closed" rule.
    fun cancelJob(managerUsername: String = "", managerPassword: String = "") {
        if (_uiState.value is MixingUiState.Cancelling) return
        // v3 authorises a privileged action solely by the manager credentials carried in the
        // request, checked against the approver's account — never by the sender's session. There is
        // no direct-cancel path, even for a Manager on their own handheld.
        if (managerUsername.isBlank() || managerPassword.isBlank()) return
        val jobCardNumber = currentOrderNo
        val collectionId = cachedOrder?.collectionId ?: ""
        if (jobCardNumber.isBlank()) return
        scanJob?.cancel()
        val orderBeforeCancel = cachedOrder
        viewModelScope.launch {
            _uiState.value = MixingUiState.Cancelling
            useCase.cancelJob(
                collectionId,
                jobCardNumber,
                "Operator cancelled — incorrect job card",
                managerUsername,
                managerPassword
            )
                .onSuccess {
                    currentOrderNo = ""
                    cachedOrder = null
                    _uiState.value = MixingUiState.Idle
                    _cancelOutcome.send(CancelOutcome.Confirmed)
                }
                .onFailure { e ->
                    _uiState.value = orderBeforeCancel?.let { MixingUiState.OrderLoaded(it) } ?: MixingUiState.Idle
                    if (orderBeforeCancel != null) startListeningForPalletScans(jobCardNumber)
                    _cancelOutcome.send(CancelOutcome.Failed(e.message ?: "Cancel failed"))
                }
        }
    }
}

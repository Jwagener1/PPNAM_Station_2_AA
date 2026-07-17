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

    /**
     * [selectedLineNumber] is the tap-line-to-arm target (SP3 Task 6): the BOM line whose
     * itemCode becomes requestedMaterialCode on the next ordinary pallet scan. Null means no line
     * is armed.
     */
    data class OrderLoaded(val order: ProductionOrder, val selectedLineNumber: Int? = null) : MixingUiState()
    data class EnteringBagDetails(val palletTag: String) : MixingUiState()

    /**
     * v3 has no exceptionId/approval token — approval is an inline resubmit of the pending scan
     * (held in the ViewModel, see [MixingViewModel.submitManagerApproval]), so this state carries
     * only the reason to show the operator. [validationError] is set when a submission was refused
     * client-side (blank credentials/audit reason) without ever reaching the wire — distinct from
     * [reason], which is why approval was needed in the first place.
     */
    data class IngredientExceptionApproval(val reason: String, val validationError: String? = null) : MixingUiState()
    data class PalletRecoveryPrompt(val palletTag: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()

    /**
     * A rejected short-bag waiver. Deliberately distinct from [IngredientExceptionApproval]: a
     * waiver has no pallet and is never resubmitted through the scan-resubmit path — the UI
     * re-collects credentials into a fresh [MixingViewModel.submitShortBagWaiver] call.
     */
    data class ShortBagWaiverNeedsApproval(
        val requestedMaterialCode: String,
        val shortBagCount: Double,
        val reason: String,
    ) : MixingUiState()
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

    /** Tap-line-to-arm target. ViewModel-only, like [pendingScan]/[pendingApproval] below — not Room. */
    private var armedLineNumber: Int? = null

    private fun orderLoadedState(order: ProductionOrder) = MixingUiState.OrderLoaded(order, armedLineNumber)

    fun lookupJob(orderNo: String, collectionId: String = "") {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo, collectionId)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    // A fresh load/resume is a different BOM (possibly a different job entirely) —
                    // a line number armed against the previous order must not silently carry over.
                    armedLineNumber = null
                    _uiState.value = orderLoadedState(order)
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
        val bagCount: Double,
        val requestedMaterialCode: String,
    )

    /** The scan pending a Holding-recovery retry. Distinct from [pendingApproval] below. */
    private var pendingScan: PendingIngredientScan? = null

    /**
     * The scan pending manager approval, stored ViewModel-only — never Room. It is short-lived (an
     * operator standing at a pallet with a manager beside them); persisting it would invite
     * resuming a stale approval hours later against a collection that has moved on. If the app
     * dies mid-approval, the recovery path is to re-scan, not to resume.
     */
    private var pendingApproval: IngredientScanOutcome.NeedsManagerApproval? = null

    /**
     * Track the in-flight privileged submission, mirroring [scanJob]'s discipline: an in-flight
     * guard (ignore re-entry — a fast double-tap on Approve/Waive must not fire two concurrent
     * credentialed requests, each minting its own messageId and each processed as a distinct
     * privileged action by Station 2) plus a cancellable handle so [cancelManagerApproval] can
     * kill a still-running submission rather than let its late response silently overwrite
     * whatever state the operator has since moved to.
     */
    private var approvalJob: Job? = null
    private var waiverJob: Job? = null

    /** Fail-closed: refuse to put a blank credential or a blank audit trail entry on the wire. */
    private fun blankCredentialsMessage(managerUsername: String, managerPassword: String, auditReason: String): String? =
        when {
            managerUsername.isBlank() || managerPassword.isBlank() -> "Manager username and password are required."
            auditReason.isBlank() -> "Audit reason is required."
            else -> null
        }

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
                // Per-state scan-guard decision (SP3 Task 6), decided per state rather than
                // inherited — fail-closed is the default, with two deliberate exceptions:
                //  - OrderLoaded: ALLOWED — the normal scanning state; this is the point of the screen.
                //  - Error: ALLOWED — a settled state, not an in-flight request or an open dialog.
                //    dismissError() is now a real exit too, but rescanning must keep working as a
                //    second recovery path — Error must never trap the operator behind a dead reader.
                //  - Loading, Cancelling: BLOCKED — a request/cancel is in flight; a scan here would
                //    race or clobber it.
                //  - Idle: BLOCKED — nothing loaded to scan against.
                //  - EnteringBagDetails, IngredientExceptionApproval, PalletRecoveryPrompt,
                //    ShortBagWaiverNeedsApproval: BLOCKED — each owns the screen with a dialog the
                //    operator is mid-interaction with; a stray scan must not clobber it.
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
        _uiState.value = orderLoadedState(order)
    }

    /**
     * Arms [lineNumber] as the scan target (tap-line-to-arm). Its itemCode becomes
     * requestedMaterialCode on the next ordinary pallet scan. Arming survives a successful scan —
     * several bags against the same line is the common case — and is cleared automatically once
     * that line is satisfied (see [handleScanOutcome]), or explicitly when the operator arms a
     * different line. Silently ignored when no order is loaded or [lineNumber] doesn't exist on it.
     */
    fun selectLine(lineNumber: Int) {
        val order = cachedOrder ?: return
        if (order.lines.none { it.lineNumber == lineNumber }) return
        armedLineNumber = lineNumber
        if (_uiState.value is MixingUiState.OrderLoaded) {
            _uiState.value = orderLoadedState(order)
        }
    }

    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        val materialCode = armedLineNumber
            ?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
            ?.itemCode
        if (materialCode == null) {
            // No line armed: never put requestedMaterialCode = "" on the wire. Surface a clear
            // prompt instead and let the operator pick a line before retrying the scan.
            _supervisorError.trySend("Select a material line before scanning a pallet.")
            _uiState.value = orderLoadedState(order)
            return
        }
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount, materialCode)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.collectionId, palletTag, bagSizeOption, bagCount, materialCode)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    /**
     * Resubmits the pending [IngredientScanOutcome.NeedsManagerApproval] scan with manager
     * credentials attached — a fresh scanIngredient() call (the transport mints a new messageId;
     * there is no approval token to retry against). No-op when nothing is pending, e.g. called
     * after the dialog was already dismissed via [cancelManagerApproval] or already resolved.
     *
     * Ignores re-entry while a submission is already in flight ([approvalJob]), and refuses
     * (fail-closed, nothing sent) blank credentials or a blank audit reason — surfaced as
     * [MixingUiState.IngredientExceptionApproval.validationError] so the dialog can show why,
     * rather than silently doing nothing.
     */
    fun submitManagerApproval(managerUsername: String, managerPassword: String, auditReason: String) {
        if (approvalJob?.isActive == true) return
        val approval = pendingApproval ?: return
        val order = cachedOrder ?: return
        val validationMessage = blankCredentialsMessage(managerUsername, managerPassword, auditReason)
        if (validationMessage != null) {
            _uiState.value = MixingUiState.IngredientExceptionApproval(approval.reason, validationMessage)
            return
        }
        approvalJob = viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(
                approval.collectionId,
                approval.palletRfidTag,
                approval.bagSizeOption ?: "",
                approval.bagCount ?: 0.0,
                approval.requestedMaterialCode,
                managerUsername,
                managerPassword,
                auditReason,
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e ->
                    pendingApproval = null
                    _uiState.value = MixingUiState.Error(e.message ?: "Approval failed")
                }
        }
    }

    /**
     * Waives short bags on [requestedMaterialCode]. Credentials travel on this first submission —
     * unlike a scan there is no preceding attempt to reject first; the operator is declaring up
     * front that a line will be short. requestedMaterialCode is passed explicitly rather than read
     * from the armed line: Task 7's per-line waiver button targets whichever line the operator
     * taps "waive" on, independent of which line (if any) is currently armed for scanning.
     *
     * Same discipline as [submitManagerApproval]: ignores re-entry while [waiverJob] is already
     * running, and refuses (fail-closed, nothing sent) blank credentials or a blank audit reason —
     * surfaced via [supervisorError] since, unlike the approval dialog, there is no pre-existing
     * waiver-dialog state to attach a validation message to on a first submission.
     */
    fun submitShortBagWaiver(
        requestedMaterialCode: String,
        shortBagCount: Double,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ) {
        if (waiverJob?.isActive == true) return
        val order = cachedOrder ?: return
        val validationMessage = blankCredentialsMessage(managerUsername, managerPassword, auditReason)
        if (validationMessage != null) {
            _supervisorError.trySend(validationMessage)
            return
        }
        waiverJob = viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.waiveShortBags(
                order.collectionId,
                requestedMaterialCode,
                shortBagCount,
                managerUsername,
                managerPassword,
                auditReason,
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Waiver failed") }
        }
    }

    fun cancelManagerApproval() {
        // Kill any in-flight resubmit too — otherwise its late response lands after the dialog is
        // gone and silently overwrites whatever state the operator has since moved to.
        approvalJob?.cancel()
        approvalJob = null
        pendingScan = null
        pendingApproval = null
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    fun confirmPalletRecovery() {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.recoverHolding(order.collectionId, scan.palletRfidTag)
                .onSuccess { retryPendingScan(order) }
                .onFailure { e ->
                    pendingScan = null
                    _supervisorError.trySend(e.message ?: "Recovery failed")
                    _uiState.value = orderLoadedState(order)
                }
        }
    }

    fun dismissPalletRecovery() {
        pendingScan = null
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    private fun handleScanOutcome(order: ProductionOrder, outcome: IngredientScanOutcome) {
        when (outcome) {
            is IngredientScanOutcome.Accepted -> {
                val updatedOrder = order.copy(lines = outcome.updatedLines)
                cachedOrder = updatedOrder
                pendingScan = null
                pendingApproval = null
                // Decision: an armed line survives a successful scan and stays armed until it is
                // fully satisfied, or the operator arms a different line — repeated bags against
                // the same material are the common case, so re-arming after every scan would be
                // needless friction.
                val armed = armedLineNumber?.let { ln -> updatedOrder.lines.firstOrNull { it.lineNumber == ln } }
                if (armed != null && armed.isSatisfied) armedLineNumber = null
                _uiState.value = orderLoadedState(updatedOrder)
            }
            is IngredientScanOutcome.NeedsManagerApproval -> {
                pendingApproval = outcome
                _uiState.value = MixingUiState.IngredientExceptionApproval(outcome.reason)
            }
            is IngredientScanOutcome.NeedsRecovery -> {
                pendingApproval = null
                _uiState.value = MixingUiState.PalletRecoveryPrompt(pendingScan?.palletRfidTag ?: "")
            }
            is IngredientScanOutcome.Rejected -> {
                pendingScan = null
                pendingApproval = null
                _supervisorError.trySend(outcome.reason)
                _uiState.value = orderLoadedState(order)
            }
            is IngredientScanOutcome.NeedsApprovalForWaiver -> {
                // Distinct from NeedsManagerApproval BY USER DECISION: a waiver is never
                // resubmitted through the scan path (it has no pallet), so this must NOT populate
                // pendingScan/pendingApproval — those exist only for the scan-resubmit flow. The UI
                // re-collects credentials into a fresh submitShortBagWaiver() call instead.
                pendingScan = null
                pendingApproval = null
                _uiState.value = MixingUiState.ShortBagWaiverNeedsApproval(
                    outcome.requestedMaterialCode, outcome.shortBagCount, outcome.reason
                )
            }
        }
    }

    private fun retryPendingScan(order: ProductionOrder) {
        val scan = pendingScan
        if (scan == null) {
            _uiState.value = orderLoadedState(order)
            return
        }
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(
                order.collectionId, scan.palletRfidTag, scan.bagSizeOption, scan.bagCount, scan.requestedMaterialCode
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    /**
     * The only exit from [MixingUiState.Error] — replaces clearError(), which had zero callers and
     * no dismiss button on screen, leaving Error a trap state (SP2's scan guard nearly shipped a
     * permanently-dead reader over exactly this; it was only saved by letting scans through in
     * Error, which remains true above). Returns to OrderLoaded, preserving the armed line, when an
     * order is loaded; otherwise Idle.
     */
    fun dismissError() {
        if (_uiState.value !is MixingUiState.Error) return
        val order = cachedOrder
        _uiState.value = if (order != null) orderLoadedState(order) else MixingUiState.Idle
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
                    armedLineNumber = null
                    _uiState.value = MixingUiState.Idle
                    _cancelOutcome.send(CancelOutcome.Confirmed)
                }
                .onFailure { e ->
                    _uiState.value = orderBeforeCancel?.let { orderLoadedState(it) } ?: MixingUiState.Idle
                    if (orderBeforeCancel != null) startListeningForPalletScans(jobCardNumber)
                    _cancelOutcome.send(CancelOutcome.Failed(e.message ?: "Cancel failed"))
                }
        }
    }
}
